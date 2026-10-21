"""Run isolated feature checks independently, then fail if any scenario failed."""
import argparse
from pathlib import Path
import subprocess
import sys

p = argparse.ArgumentParser()
p.add_argument('fixture_apk', type=Path)
p.add_argument('current_apk', type=Path)
p.add_argument('host_apk', type=Path)
p.add_argument('output', type=Path)
args = p.parse_args()
scripts = Path(__file__).parent
checks = [
    ('widget-intro', 'verify_widget_intro.py', [args.fixture_apk, args.current_apk]),
    ('notes', 'verify_notes.py', [args.fixture_apk, args.current_apk, args.host_apk]),
    ('planner', 'verify_planner.py', [args.fixture_apk, args.current_apk, args.host_apk]),
    ('favorites', 'verify_favorites.py', [args.fixture_apk, args.current_apk, args.host_apk]),
    ('support', 'verify_support.py', [args.fixture_apk, args.current_apk, args.host_apk]),
]
failures = []
for name, script, inputs in checks:
    result = subprocess.run([sys.executable, str(scripts / script), *map(str, inputs), str(args.output / name)])
    if result.returncode:
        failures.append(name)
print('Feature checks: ' + (', '.join(failures) + ' FAILED' if failures else 'ALL PASSED'), flush=True)
sys.exit(bool(failures))
