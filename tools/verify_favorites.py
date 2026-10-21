"""Black-box checks of the distributed APK with four groups and two actual AppWidgetHostViews."""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import argparse
import json
from pathlib import Path
import shlex
import tempfile
import time
import xml.etree.ElementTree as ET

from profile_android import (PKG, adb, bounds, dump, fixture_files, measure,
                            screenshot, seed, tap, text, wait_node)

HOST = 'ru.nspu.widgettesthost'
PRIMARY = '3.008.2.26'
CODES = [f'4.00{i}.2.26' for i in range(1, 4)]


def put_pref(name, root):
    destination = f'shared_prefs/{name}.xml'
    command = f'run-as {PKG} sh -c ' + shlex.quote('cat > ' + destination)
    adb('shell', command, payload=ET.tostring(root, encoding='utf-8', xml_declaration=True))


def set_value(root, name, value, kind='string'):
    for node in list(root):
        if node.get('name') == name:
            root.remove(node)
    node = ET.SubElement(root, kind, name=name)
    if kind == 'string':
        node.text = value
    else:
        node.set('value', str(value))


def seed_favorites():
    seed()
    with tempfile.TemporaryDirectory() as temp:
        files = list(fixture_files(Path(temp)))
        path = next(path for path, dest in files if dest == 'shared_prefs/schedule.xml')
        original = ET.parse(path).getroot()
        favorites = [dict(id=3328, title=PRIMARY + ' ИСиТвО', name='')]
        for i, code in enumerate(CODES, 1):
            favorites.append(dict(id=9000 + i, title=code + ' Тестовое направление', name=''))
            friend = ET.fromstring(ET.tostring(original))
            set_value(friend, 'group_name', code + ' Тестовое направление')
            lessons = next(n.text for n in friend if n.get('name') == 'lessons')
            set_value(friend, 'lessons', lessons.replace('Алгебра', f'Предмет друга {i}'))
            set_value(friend, 'fetched_at', int(time.time() * 1000), 'long')
            put_pref(f'schedule-group-{9000 + i}', friend)
        set_value(original, 'favorites', json.dumps(favorites, ensure_ascii=False))
        set_value(original, 'primary_group_id', 3328, 'int')
        put_pref('schedule', original)


def start_host():
    adb('shell', 'am', 'start', '-W', '-n', HOST + '/.WidgetHostActivity')
    wait_node(lambda n: n.get('text', '').startswith('Widget 2:'))


def compact_is(group):
    return wait_node(lambda n: n.get('resource-id', '').endswith(':id/compactHeader') and n.get('text') == group)


def full_is(group):
    return wait_node(lambda n: n.get('resource-id', '').endswith(':id/subtitle') and
                     n.get('text', '').startswith(group + ' ·'))


def favorites_button(first=False):
    root = dump()
    buttons = [n for n in root.iter('node') if n.get('content-desc') == 'Избранные группы']
    assert buttons, 'Favorites control is missing'
    return min(buttons, key=lambda n: bounds(n)[1]) if first else buttons[0]


def save_ui(out, name):
    screenshot(out / f'{name}.png')
    (out / f'{name}.xml').write_text(ET.tostring(dump(), encoding='unicode'))


def verify(out):
    start_host()
    full_is(PRIMARY)
    compact_is(PRIMARY)
    save_ui(out, 'two-widgets')
    tap(favorites_button(first=True))
    text('Избранные группы')
    for code in CODES:
        text(code)
    save_ui(out, 'widget-picker-four')
    tap(text(CODES[0]))
    full_is(CODES[0])
    compact_is(PRIMARY)
    wait_node(lambda n: 'Предмет друга 1' in n.get('text', ''))
    save_ui(out, 'independent-groups')
    # A widget day change must leave both the compact widget and app selection intact.
    initial_header = wait_node(lambda n: n.get('resource-id', '').endswith(':id/header')).get('text')
    tap(wait_node(lambda n: n.get('resource-id', '').endswith(':id/next')))
    wait_node(lambda n: n.get('resource-id', '').endswith(':id/header') and n.get('text') != initial_header)
    compact_is(PRIMARY)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
    text('Расписание НГПУ')
    today = datetime.now(ZoneInfo('Asia/Novosibirsk')).date()
    selected = today + timedelta(days=1) if today.weekday() == 6 else today
    months = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']
    weekdays = ['понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота', 'воскресенье']
    text(f'{selected.day} {months[selected.month - 1]}, {weekdays[selected.weekday()]}')
    tap(favorites_button())
    tap(text(CODES[2]))
    wait_node(lambda n: 'Предмет друга 3' in n.get('text', ''))
    wait_node(lambda n: n.get('text', '').startswith(('Осталось ', 'Через ')))
    assert not any(n.get('text') == 'ИЗБРАННАЯ ГРУППА' for n in dump().iter('node')), \
        'Explicit group selection did not scroll to its live lesson with auto-scroll disabled'
    save_ui(out, 'friend-live-lesson')
    tap(favorites_button())
    tap(wait_node(lambda n: n.get('content-desc') == 'Настройки группы ' + CODES[2]))
    tap(text('Переименовать'))
    field = wait_node(lambda n: n.get('class') == 'android.widget.EditText')
    tap(field)
    adb('shell', 'input', 'keyevent', '123', *(['67'] * 80))
    adb('shell', 'input', 'text', 'Friend3')
    tap(text('Сохранить'))
    text('● Friend3')
    save_ui(out, 'renamed-favorites')
    tap(text('Закрыть'))
    tap(favorites_button())
    tap(text(PRIMARY))
    wait_node(lambda n: n.get('text', '').startswith('Алгебра '))
    # App switching and renaming must not silently switch either widget.
    start_host()
    full_is(CODES[0])
    compact_is(PRIMARY)
    tap(favorites_button(first=True))
    tap(text('Friend3'))
    full_is('Friend3')
    compact_is(PRIMARY)
    save_ui(out, 'renamed-widget')
    # Clicking a class opens that widget's group and jumps back from manual browsing to live focus.
    subject = wait_node(lambda n: 'Предмет друга 3' in n.get('text', '') and
                        n.get('resource-id', '').endswith(':id/lessonSubject'))
    tap(subject)
    wait_node(lambda n: 'Предмет друга 3' in n.get('text', ''))
    wait_node(lambda n: n.get('text', '').startswith(('Осталось ', 'Через ')))
    tap(favorites_button())
    tap(text(PRIMARY))
    wait_node(lambda n: n.get('text', '').startswith('Алгебра '))
    start_host()
    full_is('Friend3')
    compact_is(PRIMARY)
    # The compact instance has its own working picker as well.
    controls = [n for n in dump().iter('node') if n.get('content-desc') == 'Избранные группы']
    tap(max(controls, key=lambda n: bounds(n)[1]))
    text('Избранные группы')
    picker = wait_node(lambda n: n.get('resource-id', '').endswith(':id/favoriteList'))
    left, upper, right, lower = bounds(picker)
    chosen = None
    for upward in [True] * 5 + [False] * 7:
        candidates = [n for n in dump().iter('node')
                      if n.get('resource-id', '').endswith(':id/favoriteName') and n.get('text') == CODES[1]
                      and bounds(n)[1] >= upper and bounds(n)[3] <= lower]
        if candidates:
            chosen = candidates[0]
            break
        adb('shell', 'input', 'swipe', (left + right) // 2, lower - 3 if upward else upper + 3,
            (left + right) // 2, upper + 3 if upward else lower - 3, 450)
    assert chosen is not None, 'Cannot reach a friend in the compact widget favorites list'
    tap(chosen)
    compact_is(CODES[1])
    full_is('Friend3')
    compact_time = wait_node(lambda n: n.get('resource-id', '').endswith(':id/compactTime'))
    assert 'ауд. 411' in compact_time.get('text', ''), 'Tiny widget lost its classroom'
    save_ui(out, 'compact-friend-selection')
    adb('shell', 'am', 'force-stop', HOST)
    start_host()
    full_is('Friend3')
    compact_is(CODES[1])
    save_ui(out, 'persistent-widget-selections')
    (out / 'result.txt').write_text(
        'PASS: legacy cache migration; four default group names; live lesson focus with auto-scroll disabled; '
        'rename propagation; independent app/two widget groups and dates; compact picker scrolling/selection; persistence\n')
    print('Favorite groups and two actual widgets: PASS', flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('fixture_apk', type=Path)
    p.add_argument('current_apk', type=Path)
    p.add_argument('host_apk', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Disposable emulator only'
    args.output.mkdir(parents=True, exist_ok=True)
    adb('uninstall', HOST, check=False)
    adb('uninstall', PKG, check=False)
    adb('install', '-r', args.fixture_apk)
    adb('shell', 'svc', 'wifi', 'disable')
    adb('shell', 'svc', 'data', 'disable')
    try:
        seed_favorites()
        adb('install', '-r', args.current_apk)
        adb('install', '-r', args.host_apk)
        adb('shell', 'appwidget', 'grantbind', '--package', HOST, '--user', '0')
        time.sleep(6)
        adb('logcat', '-c')
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
        text('Расписание НГПУ')
        verify(args.output)
    except Exception:
        save_ui(args.output, 'failure')
        raise
    finally:
        (args.output / 'logcat.txt').write_text(adb('logcat', '-d'))
        (args.output / 'appwidgets.txt').write_text(adb('shell', 'dumpsys', 'appwidget'))
        adb('shell', 'svc', 'wifi', 'enable', check=False)
        adb('shell', 'svc', 'data', 'enable', check=False)


if __name__ == '__main__':
    main()
