"""Summarize device measurements without claiming they predict a user's phone."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'performance-results')
lines = ['# Android 29: v2.7 / v2.8', '',
         'Same emulator, seeded offline data, two warm-up gestures in each direction, then 12 measured swipes per screen. '
         'The home fixture has 32 lessons for stress testing; the teacher fixture has 48 lessons in the week. '
         'These are emulator observations, not a physical-phone or battery benchmark.', '',
         '| Version | Screen | Frames | Janky frames | P95 |',
         '|---|---|---:|---:|---:|']
for version in ('v2.7', 'v2.8'):
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
lines += ['', 'v2.8 also asserts a square room badge on the right, native room/teacher navigation, '
          'absence of arrow glyphs in lesson cards, the teacher tabs and Back navigation.', '']
summary = '\n'.join(lines)
(root / 'summary.md').write_text(summary)
print(summary)
