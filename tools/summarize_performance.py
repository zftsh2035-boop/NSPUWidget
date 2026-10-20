"""Summarize device measurements without claiming they predict a user's phone."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'performance-results')
lines = ['# Android 29: v2.9 / v2.11', '',
         'Same emulator, seeded offline data, four warm-up gestures in each direction, then 12 measured swipes per screen. '
         'The home fixture has 32 lessons for stress testing; the teacher fixture has 48 lessons in the week. '
         'Both APKs use ART speed compilation; ABBA order provides repeat trials. '
         'These are steady-state emulator observations, not cold-install, physical-phone or battery measurements.', '',
         '| Version | Screen | Frames | Janky frames | P95 |',
         '|---|---|---:|---:|---:|']
for version in ('v2.9', 'v2.11', 'repeat-v2.11', 'repeat-v2.9'):
    for screen in ('home', 'teacher'):
        path = root / version / f'{screen}-frames.txt'
        if not path.exists():
            lines.append(f'| {version} | {screen} | unavailable | — | — |')
            continue
        text = path.read_text()
        def match(pattern):
            value = re.search(pattern, text)
            return value.group(1) if value else 'unavailable'
        frames = match(r'Total frames rendered:\s*(\d+)')
        janky = match(r'Janky frames:\s*(\d+\s*\([^)]+\))')
        p95 = match(r'95th percentile:\s*(\d+ms)')
        lines.append(f'| {version} | {screen} | {frames} | {janky} | {p95} |')
lines += ['', 'v2.11 also asserts a square room badge on the right, native room/teacher navigation, '
          'absence of arrow glyphs in lesson cards, the teacher tabs and Back navigation.', '']
favorite_result = root / 'favorites' / 'result.txt'
if favorite_result.exists():
    lines += ['## Favorites and widgets', '', favorite_result.read_text().strip(), '']
notes_result = root / 'notes' / 'result.txt'
if notes_result.exists():
    lines += ['## Personal notes and widgets', '', notes_result.read_text().strip(), '']
intro_result = root / 'widget-intro' / 'result.txt'
if intro_result.exists():
    lines += ['## Widget onboarding', '', intro_result.read_text().strip(), '']
summary = '\n'.join(lines)
(root / 'summary.md').write_text(summary)
print(summary)
