"""Real release-note dialogs, widget diagnostics and the OS-rendered launcher icon."""
import argparse
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET

from profile_android import PKG, adb, dump, screenshot, seed, tap, text, wait_node
from verify_favorites import HOST, PRIMARY, compact_is, full_is, put_pref, set_value, start_host


def save_ui(out, name):
    screenshot(out / (name + '.png'))
    (out / (name + '.xml')).write_text(ET.tostring(dump(), encoding='unicode'))


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
        seed()
        settings = ET.fromstring(adb('exec-out', 'run-as', PKG, 'cat', 'shared_prefs/app-settings.xml'))
        set_value(settings, 'update_prompts', 'true', 'boolean')
        put_pref('app-settings', settings)
        updates = ET.Element('map')
        now = int(time.time() * 1000)
        version = re.search(r'versionName = "([^"]+)"',
            (Path(__file__).resolve().parents[1] / 'app/build.gradle.kts').read_text()).group(1)
        for key, value in {
            'key_stable': 'stable:9.99', 'version_stable': '9.99',
            'url_stable': 'https://github.com/zftsh2035-boop/NSPUWidget/releases/tag/v9.99',
            'apk_stable': '', 'notes_stable': '# NSPUWidget 9.99\n- **Улучшена диагностика виджета.**\n\n### Измеренные характеристики APK\nSHA-256: should-not-appear',
            f'installed_notes_{version}': '• Новая иконка и описание обновлений.',
        }.items():
            set_value(updates, key, value)
        set_value(updates, 'last_stable', now, 'long')
        set_value(updates, f'installed_notes_{version}_at', now, 'long')
        put_pref('app-updates', updates)
        adb('install', '-r', args.current_apk)
        adb('install', '-r', args.host_apk)
        adb('shell', 'appwidget', 'grantbind', '--package', HOST, '--user', '0')
        time.sleep(6)
        adb('logcat', '-c')
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
        text('Доступно обновление 9.99')
        text('• Улучшена диагностика виджета.')
        text('Скачать')
        assert not any('should-not-appear' in n.get('text', '') for n in dump().iter('node'))
        save_ui(args.output, 'update-changes')
        tap(text('Позже'))
        start_host()
        full_is(PRIMARY)
        compact_is(PRIMARY)
        adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
        tap(text('Настройки'))
        tap(text('Диагностика виджетов'))
        report = wait_node(lambda n: 'Виджетов: 2' in n.get('text', '') and 'render_ok' in n.get('text', ''))
        assert 'Кэш в приложении (id/строк): 3328/' in report.get('text')
        (args.output / 'diagnostics.txt').write_text(report.get('text'))
        save_ui(args.output, 'widget-diagnostics')
        tap(text('Копировать отчёт'))
        tap(text('Закрыть'))
        for _ in range(9):
            about = next((n for n in dump().iter('node') if n.get('text') == 'О приложении'), None)
            if about is not None:
                tap(about)
                break
            adb('shell', 'input', 'swipe', 160, 500, 160, 200, 350)
        else:
            raise AssertionError('About panel was not reachable')
        tap(text('Что нового'))
        text('• Новая иконка и описание обновлений.')
        save_ui(args.output, 'installed-changes-offline')
        tap(text('Закрыть'))
        adb('shell', 'am', 'start', '-W', '-a', 'android.settings.APPLICATION_DETAILS_SETTINGS',
            '-d', 'package:' + PKG)
        text('Расписание НГПУ')
        save_ui(args.output, 'app-icon')
        (args.output / 'result.txt').write_text('PASS: cached update notes; hidden build appendix; diagnostic report for two actual widgets; copy action; offline installed-version notes; OS app icon\n')
        print('Update notes, widget diagnostics and launcher icon: PASS', flush=True)
    except Exception:
        save_ui(args.output, 'failure')
        raise
    finally:
        (args.output / 'logcat.txt').write_text(adb('logcat', '-d'))
        adb('shell', 'svc', 'wifi', 'enable', check=False)
        adb('shell', 'svc', 'data', 'enable', check=False)


if __name__ == '__main__':
    main()
