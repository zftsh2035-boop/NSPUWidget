"""APK navigation/rendering for captured room-218 and teacher morning cases.

Source HTML is independently covered by PublicTimetableRegressionTest. This
scenario seeds the corresponding data and verifies the actual optimized UI.
"""
import argparse
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import hashlib
import json
from pathlib import Path
import shlex
import time
import xml.etree.ElementTree as ET

from profile_android import fixture_network
from profile_android import PKG, SOURCE, adb, bounds, dump, screenshot, seed, tap, text
from verify_favorites import put_pref, set_value


def save_ui(out, name):
    screenshot(out / (name + '.png'))
    (out / (name + '.xml')).write_text(ET.tostring(dump(), encoding='unicode'))


def write_cache(path, value):
    command = f'run-as {PKG} sh -c ' + shlex.quote('cat > cache/' + path)
    adb('shell', command, payload=json.dumps(value, ensure_ascii=False).encode())


def seed_cases():
    seed()
    group_id = 1002516
    group = '4.021.1.26 Образовательный менеджмент'
    teacher_url = SOURCE + 'teacher_shedule.php?id=2049'
    room_url = SOURCE + 'room_shedule.php?id=596'
    schedule = ET.fromstring(adb('exec-out', 'run-as', PKG, 'cat', 'shared_prefs/schedule.xml'))
    set_value(schedule, 'group_id', group_id, 'int')
    set_value(schedule, 'group_name', group)
    set_value(schedule, 'selected_date', '2026-10-26')
    set_value(schedule, 'lessons', json.dumps([dict(day=0, date='2026-10-26', time='8:30-10:00',
        raw='Экономика образования, Е.В.Ельцова (л.), а. 218', changed=False)], ensure_ascii=False))
    set_value(schedule, 'schedule_links', json.dumps([
        dict(kind='TEACHER', label='Е.В.Ельцова', url=teacher_url),
        dict(kind='ROOM', label='а. 218', url=room_url)], ensure_ascii=False))
    put_pref('schedule', schedule)
    today = datetime.now(ZoneInfo('Asia/Novosibirsk')).date()
    monday = today - timedelta(days=today.weekday())

    def event(subject, clock, date, teacher='Е.В.Ельцова', section='DATED'):
        return dict(time=clock, subject=subject,
            description=subject + ', 4.021.1.26, Магистратура (л.), ' + teacher,
            kind='ЛЕКЦИЯ', day=0, date=date, section=section,
            links=[dict(label=group, url=SOURCE + 'zgroup_shedule.php?id=2516', type='group')])

    room = [event('Экономика образования', '', '2026-10-26'),
            event('Педагогическая психология', '', '2026-10-26', 'Ж.Н.Истюфеева'),
            event('Педагогическая психология', '', '2026-10-26', 'Ж.Н.Истюфеева'),
            event('Модели современных образовательных систем', '', '2026-10-26', 'О.А.Лаврентьева')]
    # Deliberately unsorted, as a cache/source table may be. Rendering must order
    # by real clock minutes, rather than relying on a padded source string.
    teacher = [event('Экономическая история', '10:10-11:40', '2026-11-09'),
               event('Экономика образования', '8:30-10:00', '2026-11-09'),
               event('Региональные рынки', '14:10-15:40', '', section='WEEKLY')]
    for url, title, events in [(room_url, '218', room), (teacher_url, 'Ельцова Елена Валерьевна', teacher)]:
        data = dict(parserVersion=4, title=title, week=8, weekStart=str(monday),
                    roomInfo='', hasPast=True, sessions=events)
        digest = hashlib.sha256((url + '&nextweek=0').encode()).hexdigest()[:24]
        write_cache('nspu-public-' + digest + '.json', data)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('fixture_apk', type=Path)
    p.add_argument('current_apk', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Disposable emulator only'
    args.output.mkdir(parents=True, exist_ok=True)
    adb('uninstall', PKG, check=False)
    adb('install', '-r', args.fixture_apk)
    fixture_network(False)
    try:
        seed_cases()
        adb('install', '-r', args.current_apk)
        time.sleep(2)
        adb('logcat', '-c')
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
        text('Экономика образования')
        save_ui(args.output, 'group-4021126')
        tap(text('218'))
        text('Расписание аудитории')
        text('Заочные занятия по датам')
        text('Экономика образования')
        text('Время не указано')
        assert not any(n.get('text', '').startswith('На сайте пока нет записей') for n in dump().iter('node'))
        assert not any('неделя · ' in n.get('text', '') for n in dump().iter('node')), 'Weekly range mislabels dated entries'
        save_ui(args.output, 'room-218-published-classes')
        tap(text('Очные'))
        text('Нет занятий')
        assert not any(n.get('text') == 'Заочные занятия по датам' for n in dump().iter('node'))
        save_ui(args.output, 'room-manual-tab-preserved')
        adb('shell', 'input', 'keyevent', '4')
        for _ in range(4):
            teacher = next((n for n in dump().iter('node') if n.get('text') == 'Е.В.Ельцова'), None)
            if teacher is not None and bounds(teacher)[3] <= 520 and bounds(teacher)[3] - bounds(teacher)[1] >= 16:
                break
            adb('shell', 'input', 'swipe', 160, 500, 160, 280, 350)
        else:
            raise AssertionError('Teacher link was not reachable by scrolling')
        tap(teacher)
        text('Расписание преподавателя')
        text('Региональные рынки')
        tap(text('Заочные'))
        morning = text('8:30-10:00')
        later = text('10:10-11:40')
        assert bounds(morning)[1] < bounds(later)[1], '08:30 must precede 10:10 on screen'
        assert not any('неделя · ' in n.get('text', '') for n in dump().iter('node'))
        text('Экономика образования')
        save_ui(args.output, 'teacher-morning-first')
        result = 'PASS: group 4.021.1.26 -> room 218; dated section auto-selection; missing-time cards; manual tab choice; teacher 8:30 before 10:10 in actual optimized APK'
        (args.output / 'result.txt').write_text(result + '\n')
        print(result, flush=True)
    except Exception:
        save_ui(args.output, 'failure')
        raise
    finally:
        (args.output / 'logcat.txt').write_text(adb('logcat', '-d'))
        fixture_network(True)


if __name__ == '__main__':
    main()
