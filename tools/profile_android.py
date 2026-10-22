"""Black-box APK comparison on a disposable emulator; no instrumentation or app hooks."""
import argparse
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

PKG = 'ru.nspu.schedule'
TEACHER = 'Петров Иван Иванович'
SOURCE = 'https://schedule.nspu.ru/'
TIMES = ['8:30-10:00', '10:10-11:40', '12:10-13:40', '14:10-15:40',
         '15:50-17:20', '17:30-19:00', '19:10-20:40', '20:50-22:20']


def adb(*args, binary=False, check=True, payload=None):
    result = subprocess.run(['adb', *map(str, args)], check=check, capture_output=True, timeout=30, input=payload)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')


def dump():
    # API 29's accessibility dumper can race a window transition and crash in
    # childNafCheck. Retry the observation, never the user action being tested.
    for attempt in range(3):
        try:
            result = adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/nspu-window.xml')
            if 'dumped to' not in result:
                raise RuntimeError('UIAutomator did not finish a fresh hierarchy dump: ' + result)
            root = ET.fromstring(adb('exec-out', 'cat', '/sdcard/nspu-window.xml'))
            # Fresh Google API images can show a Pixel Launcher ANR while their
            # first-boot Google packages initialize. This is an external system
            # overlay, not the tested app. Never dismiss an NSPU app ANR/crash.
            launcher_anr = any(n.get('resource-id') == 'android:id/alertTitle' and
                               n.get('text') == "Pixel Launcher isn't responding"
                               for n in root.iter('node'))
            if launcher_anr:
                close = next((n for n in root.iter('node') if n.get('resource-id') == 'android:id/aerr_close'), None)
                if close is None or attempt == 2:
                    raise RuntimeError('Emulator Pixel Launcher ANR did not clear')
                tap(close)
                time.sleep(.5)
                continue
            return root
        except (subprocess.CalledProcessError, ET.ParseError, RuntimeError):
            if attempt == 2:
                raise
            time.sleep(.4)


def wait_node(predicate, timeout=18):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = dump()
        node = next((n for n in root.iter('node') if predicate(n.attrib)), None)
        if node is not None:
            return node
        time.sleep(.35)
    raise AssertionError('Expected UI node was not found')


def text(value):
    return wait_node(lambda n: n.get('text') == value)


def bounds(node):
    return list(map(int, re.findall(r'\d+', node.attrib['bounds'])))


def tap(node):
    left, top, right, bottom = bounds(node)
    adb('shell', 'input', 'tap', (left + right) // 2, (top + bottom) // 2)


def screenshot(path):
    path.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def fixture_files(directory):
    today = datetime.now(ZoneInfo('Asia/Novosibirsk')).date()
    monday = today - timedelta(days=today.weekday())
    selected = today + timedelta(days=1) if today.weekday() == 6 else today
    lessons = [dict(day=day, time=clock,
                    raw=f'Алгебра {slot * 4 + copy + 1}, {TEACHER}, (п.з.: 1-24 нед.), а. 411', changed=False)
               for day in range(6) for slot, clock in enumerate(TIMES) for copy in range(4)]
    links = [dict(kind='TEACHER', label=TEACHER, url=SOURCE + 'teacher_shedule.php?id=452'),
             dict(kind='ROOM', label='411', url=SOURCE + 'room_shedule.php?id=31')]
    prefs = {
        'schedule': {'group_id': 3328, 'group_name': '3.008.2.26 ИСиТвО', 'subgroup': 'Все', 'parser_version': 3,
                     'fetched_at': int(time.time() * 1000),
                     'selected_date': str(selected), 'term_start': str(monday - timedelta(weeks=6)),
                     'lessons': json.dumps(lessons, ensure_ascii=False), 'notices': '[]',
                     'change_history': '[]', 'updated': 'Тестовая локальная копия',
                     'schedule_links': json.dumps(links, ensure_ascii=False)},
        'app-settings': {'theme': 'light', 'auto_scroll': False, 'animate_highlight': True,
                         'update_prompts': False, 'widget_intro_seen': True},
        'app-updates': {'last_stable': int(time.time() * 1000)},
    }
    for name, values in prefs.items():
        root = ET.Element('map')
        for key, value in values.items():
            if isinstance(value, bool):
                ET.SubElement(root, 'boolean', name=key, value=str(value).lower())
            elif isinstance(value, int):
                ET.SubElement(root, 'long' if value > 2**31 - 1 else 'int', name=key, value=str(value))
            else:
                ET.SubElement(root, 'string', name=key).text = value
        path = directory / f'{name}.xml'
        ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)
        yield path, 'shared_prefs/' + path.name
    for page in ('teacher_shedule.php?id=452', 'room_shedule.php?id=31'):
        sessions = [dict(time=clock, subject=f'Алгебра {slot + 1}',
                         description='Алгебра, 3.008.2.26, а. 411, (п.з.): 1–24 нед.',
                         kind='ПРАКТИКА', day=day, date='', section='WEEKLY',
                         links=[dict(label='3.008.2.26', url=SOURCE + 'group_shedule.php?id=3328', type='group'),
                                dict(label='411', url=SOURCE + 'room_shedule.php?id=31', type='room')])
                    for day in range(6) for slot, clock in enumerate(TIMES)]
        data = dict(parserVersion=4, title=TEACHER, week=7, weekStart=str(monday), roomInfo='Тестовая аудитория',
                    hasPast=True, sessions=sessions)
        digest = hashlib.sha256((SOURCE + page + '&nextweek=0').encode()).hexdigest()[:24]
        path = directory / f'nspu-public-{digest}.json'
        path.write_text(json.dumps(data, ensure_ascii=False))
        yield path, 'cache/' + path.name


def seed():
    adb('shell', 'am', 'force-stop', PKG)
    # The functional scenarios start with notifications enabled. Android 13+
    # otherwise places its first-run permission dialog above the tested UI.
    # Grant while the disposable fixture is installed; replacement retains it.
    if int(adb('shell', 'getprop', 'ro.build.version.sdk')) >= 33:
        adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
    adb('shell', 'run-as', PKG, 'mkdir', '-p', 'shared_prefs', 'cache')
    with tempfile.TemporaryDirectory() as temp:
        for path, destination in fixture_files(Path(temp)):
            adb('shell', 'run-as', PKG, 'rm', '-f', destination + '.bak')
            command = f'run-as {PKG} sh -c ' + shlex.quote('cat > ' + destination)
            adb('shell', command, payload=path.read_bytes())


def swipe(width, height, up):
    upper, lower = height * 32 // 100, height * 75 // 100
    adb('shell', 'input', 'swipe', width // 2, lower if up else upper,
        width // 2, upper if up else lower, 180)
    time.sleep(.12)


def top(width, height):
    for _ in range(20):
        if any(n.get('text') == 'ТВОЯ ГРУППА' or n.get('content-desc') == 'Выбрать дату в календаре'
               for n in dump().iter('node')):
            swipe(width, height, False)
            return
        swipe(width, height, False)
    raise AssertionError('Could not return to home header')


def measure(out, screen, width, height):
    for up in [True] * 4 + [False] * 4:
        swipe(width, height, up)
    adb('shell', 'dumpsys', 'gfxinfo', PKG, 'reset')
    for up in [True] * 6 + [False] * 6:
        swipe(width, height, up)
    time.sleep(.4)
    frames = adb('shell', 'dumpsys', 'gfxinfo', PKG, 'framestats')
    assert re.search(r'Total frames rendered:\s*[1-9]', frames), 'No frames were recorded'
    (out / f'{screen}-frames.txt').write_text(frames)
    (out / f'{screen}-memory.txt').write_text(adb('shell', 'dumpsys', 'meminfo', PKG))


def run(apk, out, verify):
    out.mkdir(parents=True, exist_ok=True)
    adb('install', '-r', '-d', apk)
    # Compare steady-state UI with equal ART compilation rather than different
    # first-install JIT decisions. Cold/install performance is a separate metric.
    compiled = adb('shell', 'cmd', 'package', 'compile', '-f', '-m', 'speed', PKG)
    assert 'Success' in compiled, compiled
    (out / 'compilation.txt').write_text(compiled)
    adb('logcat', '-c')
    try:
        launch = adb('shell', 'am', 'start', '-S', '-W', '-f', '0x10008000',
                     '-n', PKG + '/.MainActivity')
        (out / 'startup.txt').write_text(launch)
        wait_node(lambda n: n.get('text') in ('ТВОЯ ГРУППА', 'Расписание НГПУ'))
        size = re.search(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))
        width, height = map(int, size.groups())
        screenshot(out / 'home.png')
        (out / 'home.xml').write_text(ET.tostring(dump(), encoding='unicode'))
        if verify:
            assert not any('↗' in n.get('text', '') for n in dump().iter('node')), 'Arrow remains in lesson card'
            root = dump()
            badge = next(n for n in root.iter('node') if n.get('content-desc') == 'Аудитория 411')
            left, upper, right, bottom = bounds(badge)
            assert abs((right - left) - (bottom - upper)) <= 3, f'Badge must be square: {badge.attrib}'
            assert (left + right) / 2 > width * .65, 'Badge must be on the right'
            # Material Surface exposes the label beneath its clickable accessibility node.
            parents = {child: parent for parent in root.iter() for child in parent}
            target = badge
            while target.get('clickable') != 'true' and target in parents:
                target = parents[target]
            assert target.get('clickable') == 'true', 'Badge has no clickable target'
            tap(badge)
            text('Расписание аудитории')
            text('Алгебра 1')
            screenshot(out / 'room.png')
            adb('shell', 'input', 'keyevent', '4')
            wait_node(lambda n: n.get('text') in ('ТВОЯ ГРУППА', 'Расписание НГПУ'))
        measure(out, 'home', width, height)
        top(width, height)
        tap(wait_node(lambda n: n.get('text', '').startswith(TEACHER)))
        text('Расписание преподавателя')
        text('Алгебра 1')
        screenshot(out / 'teacher.png')
        measure(out, 'teacher', width, height)
        if verify:
            tap(text('Заочные'))
            text('Заочные занятия по датам')
            tap(text('Сессия'))
            text('Сессия · консультации и экзамены')
            adb('shell', 'input', 'keyevent', '4')
            text('Расписание НГПУ')
        (out / 'result.txt').write_text('PASS: loaded fixture, scroll, navigation and requested UI checks\n')
        print(out.name + ': PASS', flush=True)
    except Exception:
        screenshot(out / 'failure.png')
        (out / 'failure.xml').write_text(ET.tostring(dump(), encoding='unicode'))
        raise
    finally:
        (out / 'logcat.txt').write_text(adb('logcat', '-d'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('fixture_apk', type=Path)
    parser.add_argument('baseline_apk', type=Path)
    parser.add_argument('current_apk', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Use a disposable Android emulator'
    # Each trial below uninstalls the app before installing the version-1
    # seeder. Replacing the optimized APK here would be a forbidden downgrade.
    # Park network-constrained WorkManager sync; all public pages are seeded in the cache.
    adb('shell', 'svc', 'wifi', 'disable')
    adb('shell', 'svc', 'data', 'disable')
    try:
        # ABBA order exposes host/emulator drift instead of attributing one noisy
        # pair of measurements to an app change. Each trial starts from identical data.
        trials = [('v2.9', args.baseline_apk, False), ('v2.11', args.current_apk, True),
                  ('repeat-v2.11', args.current_apk, False), ('repeat-v2.9', args.baseline_apk, False)]
        for label, apk, verify in trials:
            adb('uninstall', PKG, check=False)
            adb('install', '-r', args.fixture_apk)
            seed()
            print('Measuring ' + label, flush=True)
            run(apk, args.output / label, verify)
    finally:
        adb('shell', 'svc', 'wifi', 'enable', check=False)
        adb('shell', 'svc', 'data', 'enable', check=False)


if __name__ == '__main__':
    main()
