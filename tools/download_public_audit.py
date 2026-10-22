"""Capture every teacher/room page linked by an official group timetable."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urljoin, urlsplit
from urllib.request import Request, urlopen


class Links(HTMLParser):
    def __init__(self, base):
        super().__init__()
        self.base = base
        self.pages = set()

    def handle_starttag(self, tag, attrs):
        if tag != 'a':
            return
        uri = urlsplit(urljoin(self.base, dict(attrs).get('href', '')))
        if uri.scheme != 'https' or uri.hostname != 'schedule.nspu.ru':
            return
        if uri.path not in ('/teacher_shedule.php', '/room_shedule.php'):
            return
        identifier = parse_qs(uri.query).get('id', [''])[0]
        if identifier.isdigit():
            self.pages.add('https://schedule.nspu.ru' + uri.path + '?id=' + identifier)


def download(url):
    for attempt in range(2):
        try:
            with urlopen(Request(url, headers={'User-Agent': 'Mozilla/5.0 NSPUWidget-audit'}), timeout=25) as response:
                return response.read().decode('utf-8-sig')
        except Exception:
            if attempt:
                raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('group_url')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    group = download(args.group_url)
    (args.output / 'source-group.html').write_text(group)
    links = Links(args.group_url)
    links.feed(group)

    def capture(url):
        uri = urlsplit(url)
        kind = 'teacher' if 'teacher_' in uri.path else 'room'
        filename = kind + '-' + parse_qs(uri.query)['id'][0] + '.html'
        (args.output / filename).write_text(download(url))
        print(filename, flush=True)
        return filename + '\t' + url

    with ThreadPoolExecutor(max_workers=4) as executor:
        manifest = list(executor.map(capture, sorted(links.pages)))
    (args.output / 'manifest.tsv').write_text('\n'.join(manifest) + '\n')
    print('Captured', len(manifest), 'teacher/room pages', flush=True)


if __name__ == '__main__':
    main()
