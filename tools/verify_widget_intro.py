"""First-launch widget offer, persistent dismissal, help and native launcher pinning."""
import argparse
from pathlib import Path
import time
import xml.etree.ElementTree as ET

from profile_android import PKG, adb, dump, screenshot, seed, tap, text, wait_node
from verify_favorites import HOST, put_pref, set_value


def save_ui(out, name):
    screenshot(out / f'{name}.png')
    (out / f'{name}.xml').write_text(ET.tostring(dump(), encoding='unicode'))


def launch():
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')


def verify(out):
    launch()
    text('Добавь виджет расписания')
    text('Добавить виджет')
    save_ui(out, 'first-launch-offer')
    tap(text('Позже'))
    text('Расписание НГПУ')
    adb('shell', 'am', 'force-stop', PKG)
    launch()
    text('Расписание НГПУ')
    assert not any(n.get('text') == 'Добавь виджет расписания' for n in dump().iter('node')), \
        'Widget offer repeated after Later'
    tap(text('Настройки'))
    tap(text('Добавить виджет'))
    text('Добавь виджет расписания')
    tap(text('Как добавить вручную'))
    text('Как добавить виджет')
    wait_node(lambda n: n.get('text', '').startswith('1. Открой главный экран'))
    save_ui(out, 'manual-widget-instructions')
    tap(text('Понятно'))
    tap(text('Добавить виджет'))
    text('Добавь виджет расписания')
    tap(text('Добавить виджет'))
    destination = wait_node(lambda n: n.get('text', '').lower() in (
        'как добавить виджет', 'add to home screen', 'добавить на главный экран'))
    if destination.get('text') == 'Как добавить виджет':
        # A launcher is allowed to decline pinning. The in-app instructions must remain usable.
        tap(text('Понятно'))
        result = 'PASS: first launch, persistent Later, settings re-entry, manual fallback (launcher declined pinning)'
    else:
        save_ui(out, 'native-pin-confirmation')
        button = wait_node(lambda n: n.get('package') != PKG and
            n.get('clickable') == 'true' and n.get('text', '').lower() in (
            'add automatically', 'добавить автоматически', 'add', 'добавить',
            'add to home screen', 'добавить на главный экран'))
        tap(button)
        deadline = time.monotonic() + 18
        while time.monotonic() < deadline:
            state = adb('shell', 'dumpsys', 'appwidget')
            widgets = state.split('Widgets:', 1)[-1].split('Hosts:', 1)[0]
            if PKG in widgets:
                break
            time.sleep(.3)
        else:
            raise AssertionError('Launcher confirmation did not bind the actual schedule widget')
        (out / 'appwidgets.txt').write_text(state)
        launch()
        text('Расписание НГПУ')
        assert not any(n.get('text') == 'Добавь виджет расписания' for n in dump().iter('node'))
        result = 'PASS: first launch, persistent Later, settings re-entry, manual instructions, native confirmation and actual widget binding'
    (out / 'result.txt').write_text(result + '\n')
    print(result, flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('fixture_apk', type=Path)
    p.add_argument('current_apk', type=Path)
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
        seed()
        settings = ET.fromstring(adb('exec-out', 'run-as', PKG, 'cat', 'shared_prefs/app-settings.xml'))
        set_value(settings, 'widget_intro_seen', 'false', 'boolean')
        put_pref('app-settings', settings)
        adb('install', '-r', args.current_apk)
        # The fixture replacement broadcasts can arrive after adb install returns
        # on a freshly booted API 29 image and kill an Activity launched immediately.
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
