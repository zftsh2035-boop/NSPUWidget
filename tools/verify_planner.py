"""Planner UI and real v1 database upgrade on an optimized APK in a disposable emulator."""
import argparse
from datetime import datetime, timedelta
from pathlib import Path
import shlex
import sqlite3
import tempfile
import time
from zoneinfo import ZoneInfo

from profile_android import PKG, adb, bounds, dump, tap, text, wait_node
from verify_favorites import HOST
from verify_notes import seed_notes, save_ui


def seed_old_database():
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'personal-notes.db'
        db = sqlite3.connect(path)
        db.executescript('''CREATE TABLE notes (
            id TEXT PRIMARY KEY NOT NULL, group_id INTEGER NOT NULL, lesson_date TEXT NOT NULL,
            subject TEXT NOT NULL, subject_key TEXT NOT NULL, lesson_time TEXT NOT NULL,
            kind TEXT NOT NULL, subgroup TEXT NOT NULL, unique_on_day INTEGER NOT NULL,
            text TEXT NOT NULL, updated_at INTEGER NOT NULL,
            UNIQUE(group_id,lesson_date,subject_key,lesson_time,kind,subgroup));
            CREATE INDEX notes_by_day ON notes(group_id,lesson_date);
            PRAGMA user_version=1;''')
        db.execute('INSERT INTO notes VALUES (?,?,?,?,?,?,?,?,?,?,?)',
                   ('legacy-id', 3328, '2026-10-20', 'Legacy homework', 'legacy homework',
                    '8:30-10:00', 'ПРАКТИКА', 'all', 1, 'Do not lose this old note', 1))
        db.commit()
        db.close()
        adb('shell', 'run-as', PKG, 'mkdir', '-p', 'databases')
        command = f'run-as {PKG} sh -c ' + shlex.quote('cat > databases/personal-notes.db')
        adb('shell', command, payload=path.read_bytes())


def write_field(description, value):
    node = wait_node(lambda n: n.get('class') == 'android.widget.EditText' and
                     n.get('content-desc') == description)
    tap(node)
    old = node.get('text', '')
    adb('shell', 'input', 'keyevent', '123', *(['20'] * (len(old) // 10 + 4)), '123',
        *(['67'] * (len(old) + 3)))
    adb('shell', 'input', 'text', value.replace(' ', '%s'))
    wait_node(lambda n: n.get('class') == 'android.widget.EditText' and n.get('text') == value)


def native_ok():
    tap(wait_node(lambda n: n.get('resource-id') == 'android:id/button1'))


def hide_keyboard():
    if 'mInputShown=true' in adb('shell', 'dumpsys', 'input_method'):
        adb('shell', 'input', 'keyevent', '4')


def find_text_with_scroll(value):
    for _ in range(8):
        node = next((n for n in dump().iter('node') if n.get('text') == value), None)
        if node is not None:
            return node
        adb('shell', 'input', 'swipe', 170, 510, 170, 250, 300)
    raise AssertionError('Cannot reach ' + value)


def verify(out):
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
    tap(wait_node(lambda n: n.get('content-desc') == 'Мои заметки'))
    tap(text('Legacy homework'))
    text('Do not lose this old note')
    assert not any(n.get('class') == 'android.widget.EditText' for n in dump().iter('node'))
    save_ui(out, 'migrated-note-reader')
    tap(text('Закрыть'))
    tap(text('+ На день'))
    write_field('Название заметки', 'Training')
    long_text = 'Homework details ' + 'Read the next chapter and bring a notebook. ' * 14
    write_field('Текст заметки', long_text.strip())
    tap(text('Сохранить'))
    tap(text('Training'))
    text(long_text.strip())
    assert not any(n.get('class') == 'android.widget.EditText' for n in dump().iter('node'))
    save_ui(out, 'full-day-note-reader')
    tap(text('Изменить'))
    hide_keyboard()
    tap(find_text_with_scroll('Напомнить'))
    tap(find_text_with_scroll('+ Дата и время'))
    # Accept the first native date/time as a real future alarm.
    native_ok()
    native_ok()
    tap(find_text_with_scroll('+ Дата и время'))
    native_ok()
    tap(wait_node(lambda n: n.get('resource-id') == 'android:id/toggle_mode'))
    zone = ZoneInfo(adb('shell', 'getprop', 'persist.sys.timezone').strip() or 'UTC')
    target = datetime.now(zone) + timedelta(hours=1, minutes=2)
    for resource, value in [('input_hour', target.hour), ('input_minute', target.minute)]:
        field = wait_node(lambda n: n.get('resource-id') == 'android:id/' + resource)
        tap(field)
        adb('shell', 'input', 'keyevent', '123', '67', '67', '67')
        adb('shell', 'input', 'text', str(value))
    native_ok()
    tap(text('Сохранить'))
    alarm_state = adb('shell', 'dumpsys', 'alarm')
    assert 'ru.nspu.schedule.NOTE_TICK' in alarm_state, 'No OS reminder alarm was registered'
    (out / 'alarms.txt').write_text(alarm_state)
    # Process restart must keep the record and restore the next alarm after force-stop.
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
    tap(wait_node(lambda n: n.get('content-desc') == 'Мои заметки'))
    tap(text('Training'))
    text(long_text.strip())
    tap(text('Изменить'))
    tap(find_text_with_scroll('Напомнить'))
    text('Выбрано: 2')
    save_ui(out, 'two-persistent-reminders')
    tap(text('Отмена'))
    tap(text('Закрыть'))
    # The group chooser must remain unique in the pinned top bar.
    root = dump()
    assert sum(n.get('content-desc') == 'Избранные группы' for n in root.iter('node')) == 1
    assert not any(n.get('text') == 'Избранное' for n in root.iter('node'))
    calendar = wait_node(lambda n: n.get('content-desc') == 'Выбрать дату в календаре')
    before = calendar.get('text')
    tap(calendar)
    tap(wait_node(lambda n: n.get('resource-id') == 'android:id/next'))
    tap(wait_node(lambda n: n.get('text') == '15' and n.get('clickable') == 'true'))
    native_ok()
    after = wait_node(lambda n: n.get('content-desc') == 'Выбрать дату в календаре').get('text')
    assert before != after, 'Calendar selection remained on the old date'
    save_ui(out, 'next-month-selection')
    (out / 'result.txt').write_text('PASS: real v1 migration, full note reader, day note, two persisted reminder times, OS alarm, single group chooser, next-month calendar\n')
    print('Planner upgrade and UI: PASS', flush=True)


def main():
    p = argparse.ArgumentParser()
    for name in ('fixture_apk', 'current_apk', 'host_apk', 'output'):
        p.add_argument(name, type=Path)
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
        seed_old_database()
        adb('install', '-r', args.current_apk)
        adb('install', '-r', args.host_apk)
        if int(adb('shell', 'getprop', 'ro.build.version.sdk')) >= 33:
            adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
        time.sleep(6)
        adb('logcat', '-c')
        verify(args.output)
    except Exception:
        save_ui(args.output, 'failure')
        raise
    finally:
        (args.output / 'logcat.txt').write_text(adb('logcat', '-d'))
        adb('shell', 'svc', 'wifi', 'enable', check=False)
        adb('shell', 'svc', 'data', 'enable', check=False)


if __name__ == '__main__':
    main()
