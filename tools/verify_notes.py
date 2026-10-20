"""Personal notes CRUD on the actual optimized APK and two real launcher widget views."""
import argparse
from datetime import datetime, timedelta
import json
import re
import shlex
import time
from pathlib import Path
import xml.etree.ElementTree as ET

from profile_android import PKG, adb, bounds, dump, screenshot, tap, text, wait_node
from verify_favorites import (HOST, PRIMARY, CODES, compact_is, favorites_button,
                              full_is, put_pref, seed_favorites, set_value, start_host)


def save_ui(out, name):
    screenshot(out / f'{name}.png')
    (out / f'{name}.xml').write_text(ET.tostring(dump(), encoding='unicode'))


def field():
    return wait_node(lambda n: n.get('class') == 'android.widget.EditText')


def type_note(value):
    existing = field()
    tap(existing)
    previous = existing.get('text', '')
    # MOVE_END is the end of a visual line in a multiline Compose field.
    # Move below every wrapped line before deleting the previous draft.
    down = previous.count('\n') + len(previous) // 10 + 5
    adb('shell', 'input', 'keyevent', '123', *(['20'] * down), '123', *(['67'] * (len(previous) + 3)))
    adb('shell', 'input', 'text', value.replace(' ', '%s'))
    wait_node(lambda n: n.get('class') == 'android.widget.EditText' and n.get('text') == value)


def hide_keyboard():
    if re.search(r'mInputShown=true', adb('shell', 'dumpsys', 'input_method')):
        adb('shell', 'input', 'keyevent', '4')


def save_note():
    # A real user must be able to save with the keyboard still open.
    tap(text('Сохранить'))
    wait_node(lambda n: n.get('content-desc') == 'Мои заметки')


def cancel_note():
    hide_keyboard()
    tap(text('Отмена'))
    # The underlying surface can be either the schedule or the notes list.
    for _ in range(12):
        if not any(n.get('class') == 'android.widget.EditText' for n in dump().iter('node')):
            return
        time.sleep(.35)
    raise AssertionError('Cancel left the note editor open')


def find_lesson_note_button(subject):
    # Switching a favorite deliberately jumps to the live lesson. The clock can
    # cross a class boundary during this test, so find the original class above it.
    for _ in range(12):
        found = next((n for n in dump().iter('node') if
                      n.get('content-desc', '').startswith('Добавить заметку: ' + subject + ', ')), None)
        if found is not None:
            return found
        adb('shell', 'input', 'swipe', 160, 240, 160, 500, 350)
    raise AssertionError('Could not reach the original lesson in the friend schedule')


def note_text(value, compact=False):
    return wait_node(lambda n: n.get('resource-id', '').endswith(
        ':id/compactTeacher' if compact else ':id/lessonNote') and n.get('text') == 'Заметка: ' + value)


def full_note(value):
    for _ in range(14):
        found = next((n for n in dump().iter('node') if
                      n.get('resource-id', '').endswith(':id/lessonNote') and
                      n.get('text') == 'Заметка: ' + value), None)
        if found is not None:
            return found
        area = wait_node(lambda n: n.get('resource-id', '').endswith(':id/lessonList'))
        left, upper, right, lower = bounds(area)
        adb('shell', 'input', 'swipe', (left + right) // 2, lower - 8,
            (left + right) // 2, upper + 8, 350)
    raise AssertionError('Full widget did not show the saved note')


def seed_notes():
    seed_favorites()
    # One subject per slot makes the real widget scroll reproducible; the separate
    # performance trial still uses the 32-lessons/day fixture.
    for pref in ['schedule'] + [f'schedule-group-{9000+i}' for i in range(1, 4)]:
        root = ET.fromstring(adb('exec-out', 'run-as', PKG, 'cat', f'shared_prefs/{pref}.xml'))
        raw = next(n.text for n in root if n.get('name') == 'lessons')
        rows, seen = [], set()
        for lesson in json.loads(raw):
            slot = (lesson['day'], lesson['time'])
            if slot in seen:
                continue
            seen.add(slot)
            for i in range(1, 4):
                lesson['raw'] = lesson['raw'].replace(f'Предмет друга {i}', 'Алгебра')
            rows.append(lesson)
        set_value(root, 'lessons', json.dumps(rows, ensure_ascii=False))
        put_pref(pref, root)


def verify(out):
    start_host()
    full_is(PRIMARY)
    compact_is(PRIMARY)
    subject = wait_node(lambda n: n.get('resource-id', '').endswith(':id/compactSubject')).get('text')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity', '--ei', 'open_group_id', 3328)
    add = wait_node(lambda n: n.get('content-desc', '').startswith('Добавить заметку: ' + subject + ', '))
    tap(add)
    text('Заметка к паре')
    assert field().get('text', '') == '', 'New note must be empty'
    type_note('Bring bandages')
    save_note()
    wait_node(lambda n: n.get('text') == 'Заметка: Bring bandages')
    save_ui(out, 'saved-app-note')

    start_host()
    note_text('Bring bandages', compact=True)
    full_note('Bring bandages')
    save_ui(out, 'note-in-both-widgets')
    print('Notes: saved and visible in full/tiny widgets', flush=True)
    # Collection fill-in intent: open the exact dated class, not the app's live focus.
    adb('shell', 'am', 'force-stop', PKG)
    tap(full_note('Bring bandages'))
    text('Личная заметка')
    assert field().get('text') == 'Bring bandages', 'Cold widget link lost its note'
    type_note('Bring bandages and notebook')
    save_note()
    start_host()
    full_note('Bring bandages and notebook')
    tap(note_text('Bring bandages and notebook', compact=True))
    text('Личная заметка')
    assert field().get('text') == 'Bring bandages and notebook', 'Warm compact link opened another note'

    long_note = 'Bring bandages and notebook ' + 'extra reminder ' * 22
    type_note(long_note.rstrip())
    save_ui(out, 'long-note-editor-keyboard')
    save_note()
    save_ui(out, 'long-note-saved-app')
    start_host()
    tiny_note = note_text(long_note.rstrip(), compact=True)
    assert bounds(tiny_note)[3] - bounds(tiny_note)[1] < 50, 'Tiny note grew beyond a single line'
    full_note(long_note.rstrip())
    save_ui(out, 'long-note-preview')
    root = wait_node(lambda n: n.get('resource-id', '').endswith(':id/compactRoot'))
    tiny_height = bounds(root)[3] - bounds(root)[1]
    # am start can merely bring an existing standard-mode task to the front,
    # leaving extras untouched. Restart the host explicitly for this resize.
    adb('shell', 'am', 'start', '-S', '-W', '-n', HOST + '/.WidgetHostActivity', '--ei', 'compact_height', 180)
    wait_node(lambda n: n.get('resource-id', '').endswith(':id/compactRoot') and
              int(re.findall(r'\d+', n['bounds'])[3]) - int(re.findall(r'\d+', n['bounds'])[1]) > tiny_height * 1.3)
    wait_node(lambda n: n.get('resource-id', '').endswith(':id/compactRoom') and '411' in n.get('text', ''))
    normal_note = note_text(long_note.rstrip(), compact=True)
    save_ui(out, 'normal-compact-long-note')
    tap(normal_note)
    assert field().get('text') == long_note.rstrip(), 'Truncated preview lost the full note'
    type_note('Bring bandages and notebook')
    save_note()
    adb('shell', 'am', 'start', '-S', '-W', '-n', HOST + '/.WidgetHostActivity')
    tap(note_text('Bring bandages and notebook', compact=True))

    # Draft text and its target survive Activity recreation.
    type_note('Unsaved rotation draft')
    adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
    wait_node(lambda n: n.get('class') == 'android.widget.EditText' and n.get('text') == 'Unsaved rotation draft')
    adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    wait_node(lambda n: n.get('class') == 'android.widget.EditText' and n.get('text') == 'Unsaved rotation draft')
    cancel_note()
    print('Notes: exact widget links, long previews and draft rotation checked', flush=True)
    # Cancel is not save. The original text is still in storage after process restart.
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
    tap(wait_node(lambda n: n.get('content-desc') == 'Мои заметки'))
    tap(text('Bring bandages and notebook'))
    assert field().get('text') == 'Bring bandages and notebook'
    cancel_note()
    tap(text('Закрыть'))
    print('Notes: saved text survived process restart; cancelled draft was discarded', flush=True)

    # Same weekday and subject next week must not repeat this personal reminder.
    # This is the same documented Activity intent that launcher views send.
    from zoneinfo import ZoneInfo
    future = datetime.now(ZoneInfo('Asia/Novosibirsk')).date() + timedelta(days=7)
    adb('shell', 'am', 'start', '-W', '-f', '0x24000000', '-n', PKG + '/.MainActivity',
        '--ei', 'open_group_id', 3328, '--ez', 'open_note', 'true',
        '--es', 'note_date', str(future), '--es', 'note_subject', shlex.quote(subject),
        '--es', 'note_time', '8:30-10:00', '--es', 'note_kind', 'ПРАКТИКА', '--es', 'note_subgroup', 'all')
    text('Заметка к паре')
    assert field().get('text', '') == '', 'A note repeated on another date'
    cancel_note()

    # Equal subjects in a different group still get independent personal notes.
    tap(favorites_button())
    tap(text(CODES[0]))
    tap(find_lesson_note_button(subject))
    text(CODES[0])
    assert field().get('text', '') == '', 'Note leaked into another group'
    type_note('Friend notebook')
    save_note()
    tap(wait_node(lambda n: n.get('content-desc') == 'Мои заметки'))
    text('Friend notebook')
    tap(text('Bring bandages and notebook'))
    tap(text('Удалить заметку'))
    text('Friend notebook')
    assert not any(n.get('text') == 'Bring bandages and notebook' for n in dump().iter('node'))
    save_ui(out, 'deleted-note-other-group-kept')
    tap(text('Закрыть'))
    start_host()
    full_is(PRIMARY)
    compact_is(PRIMARY)
    teacher = wait_node(lambda n: n.get('resource-id', '').endswith(':id/compactTeacher'))
    assert not teacher.get('text', '').startswith('Заметка:'), 'Deleted note stayed in compact widget'
    assert not any(n.get('resource-id', '').endswith(':id/lessonNote') and n.get('text')
                   for n in dump().iter('node')), 'Deleted note stayed in full widget'
    save_ui(out, 'deleted-widget-note')
    (out / 'result.txt').write_text('PASS: personal note CRUD; cold/warm widget intents; both widget sizes; '
        'process restart; draft restoration; cancel; group/date isolation; deletion refresh\n')
    print('Personal notes, persistence and real widgets: PASS', flush=True)


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
        seed_notes()
        adb('install', '-r', args.current_apk)
        adb('install', '-r', args.host_apk)
        adb('shell', 'appwidget', 'grantbind', '--package', HOST, '--user', '0')
        time.sleep(6)
        adb('logcat', '-c')
        verify(args.output)
    except Exception:
        save_ui(args.output, 'failure')
        raise
    finally:
        (args.output / 'logcat.txt').write_text(adb('logcat', '-d'))
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '0', check=False)
        adb('shell', 'svc', 'wifi', 'enable', check=False)
        adb('shell', 'svc', 'data', 'enable', check=False)


if __name__ == '__main__':
    main()
