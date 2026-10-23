"""Black-box checks of important events, retakes, change history and free-day subjects."""
import argparse
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import json
from pathlib import Path
import time
import xml.etree.ElementTree as ET

from profile_android import fixture_network
from profile_android import PKG, adb, bounds, dump, screenshot, seed, tap, text, wait_node
from verify_favorites import put_pref, set_value


def save_ui(out, name):
    screenshot(out / (name + '.png'))
    (out / (name + '.xml')).write_text(ET.tostring(dump(), encoding='unicode'))


def seed_cases():
    seed()
    today = datetime.now(ZoneInfo('Asia/Novosibirsk')).date()
    free = today + timedelta(days=1)
    psych = 'Психология, проф.Андриенко Елена Васильевна (экз.), 406 (Учебная аудитория), Комиссия - пересдача'
    course = 'Спецкурс по курсовой работе (по математике), доц.Яровая Евгения Анатольевна (к.р.), 313 (Учебная аудитория), Комиссия - пересдача'
    lessons = []
    for index, raw in enumerate([psych, course] + [f'Дисциплина {i}, Преподаватель (зач.), а. 417' for i in range(8)]):
        date = today + timedelta(days=7 + index)
        lessons.append(dict(day=date.weekday(), date=str(date), time='10:10-11:40', raw=raw))
    lessons.append(dict(day=(free.weekday() + 1) % 7, time='8:30-10:00',
        raw='Алгебра, И.И.Петров, (л.: 7-12 нед.), а. 411'))
    banner = 'Зачёт для всей группы ' + (today + timedelta(days=22)).strftime('%d.%m.%Y') + ' в 12:10'
    ordinary = 'Встреча первокурсников состоится в актовом зале'
    message = 'Алгебра: Ауд. 411 → 213'
    spans = [dict(start=message.index(value), end=message.index(value) + len(value)) for value in ['411', '213']]
    root = ET.fromstring(adb('exec-out', 'run-as', PKG, 'cat', 'shared_prefs/schedule.xml'))
    set_value(root, 'selected_date', str(free))
    set_value(root, 'lessons', json.dumps(lessons, ensure_ascii=False))
    set_value(root, 'assessment_archive', '[]')
    set_value(root, 'notices', json.dumps([dict(id='banner-credit', text=banner), dict(id='ordinary', text=ordinary)], ensure_ascii=False))
    set_value(root, 'change_history', json.dumps([dict(message=message, highlights=spans), 'Сохранённое изменение прежней версии'], ensure_ascii=False))
    put_pref('schedule', root)
    return psych, course, banner, ordinary, message


def find_scroll(value, tries=6):
    for _ in range(tries):
        found = next((n for n in dump().iter('node') if n.get('text') == value), None)
        if found is not None and bounds(found)[3] < 535 and bounds(found)[3] - bounds(found)[1] >= 16:
            return found
        adb('shell', 'input', 'swipe', 160, 500, 160, 240, 350)
    raise AssertionError('Not reachable: ' + value)


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
        psych, course, banner, ordinary, message = seed_cases()
        adb('install', '-r', args.current_apk)
        # PACKAGE_REMOVED/REPLACED can force-stop the new process after adb
        # install reports success, especially while a fresh API 29 image boots.
        time.sleep(6)
        adb('logcat', '-c')
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
        text('Открыть все события · 11')
        text('Предметы и недели')
        save_ui(args.output, 'separate-home-blocks')
        tap(text('Объявления'))
        text(ordinary)
        assert not any(n.get('text') == banner for n in dump().iter('node')), 'Assessment banner leaked into announcements'
        save_ui(args.output, 'ordinary-announcements-only')
        tap(text('Изменения'))
        text(message)
        text('Сохранённое изменение прежней версии')
        assert not any(n.get('text') == ordinary for n in dump().iter('node'))
        save_ui(args.output, 'exact-old-new-change-highlights')
        tap(text('Важное'))
        text('Важное из расписания')
        text('ПЕРЕСДАЧА · ЭКЗАМЕН')
        save_ui(args.output, 'uniform-important-cards')
        tap(text('Пересдачи · 2'))
        text('ПЕРЕСДАЧА · ЭКЗАМЕН')
        text('ПЕРЕСДАЧА · КУРСОВАЯ')
        assert not any(n.get('text', '').startswith('Дисциплина ') for n in dump().iter('node'))
        save_ui(args.output, 'retakes-tab-with-kinds')
        tap(text('Психология'))
        text(psych)
        save_ui(args.output, 'psychology-retake-full-text')
        tap(text('Закрыть'))
        tap(find_scroll('Спецкурс по курсовой работе (по математике)'))
        text(course)
        save_ui(args.output, 'coursework-retake-full-text')
        tap(text('Закрыть'))
        tap(text('Расписание'))
        tap(find_scroll('Предметы и недели'))
        text('Найти предмет')
        text('Алгебра')
        wait_node(lambda n: 'л.: 7–12 нед.' in n.get('text', ''))
        wait_node(lambda n: n.get('text', '').startswith('На следующей неделе:'))
        save_ui(args.output, 'all-subjects-on-a-free-day')
        next_class = wait_node(lambda n: n.get('text', '').startswith('Ближайшая пара:'))
        tap(next_class)
        text('Расписание')
        find_scroll('Алгебра')
        find_scroll('л.: 7–12 нед.')
        save_ui(args.output, 'jump-to-next-class-and-week-range')
        # Rich history and legacy strings must both survive a cold start.
        adb('shell', 'am', 'force-stop', PKG)
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity', '--ez', 'show_changes', 'true')
        text(message)
        text('Сохранённое изменение прежней версии')
        result = 'PASS: three separate home blocks; banner assessment routing; old/new change history; 11 important events; retake exam/coursework tabs and details; free-day subject list, exact weeks and next-class navigation; cold-start history migration'
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
