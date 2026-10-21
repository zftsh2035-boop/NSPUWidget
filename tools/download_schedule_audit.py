"""Download every publicly catalogued NSPU group, with bounded concurrency.

Run with Python 3 and lxml: python tools/download_schedule_audit.py OUTPUT_DIR
The files are audit inputs, never part of the Android application's startup.
"""
import concurrent.futures
import datetime
import json
import pathlib
import re
import sys
import time
import urllib.request

from lxml import html

ROOT = "https://schedule.nspu.ru/"
OUTPUT = pathlib.Path(sys.argv[1])
OUTPUT.mkdir(parents=True, exist_ok=True)


def get(path, filename):
    target = OUTPUT / filename
    if target.exists():
        return target.read_text(encoding="utf-8")
    request = urllib.request.Request(ROOT + path, headers={"User-Agent": "NSPUWidget public timetable audit"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                text = response.read().decode("utf-8-sig")
            target.write_text(text, encoding="utf-8")
            return text
        except Exception:
            if attempt == 2:
                raise
            time.sleep(1 + attempt)


def links(text, pattern):
    document = html.fromstring(text)
    return [(a.get("href"), " ".join(a.text_content().split()))
            for a in document.xpath("//a[@href]") if re.fullmatch(pattern, a.get("href"))]


def main():
    departments = links(get("dep_index.php", "dep-index.html"), r"group_index\.php\?dep=\d+")
    print(f"Departments: {len(departments)}", flush=True)
    groups = {}
    failures = []
    for path, faculty in departments:
        faculty_id = path.split("=")[-1]
        try:
            text = get(path, f"dep-{faculty_id}.html")
        except Exception as error:
            failures.append({"url": ROOT + path, "error": str(error)})
            continue
        for url, title in links(text, r"z?group_shedule\.php\?id=\d+"):
            kind = "extramural_group" if url.startswith("zgroup") else "group"
            filename = f"{kind}-{url.split('=')[-1]}.html"
            entry = groups.setdefault(url, {"url": ROOT + url, "path": url, "title": title,
                                            "file": filename, "faculties": []})
            entry["faculties"].append(faculty)
    manifest = list(groups.values())
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUTPUT / "manifest.tsv").write_text("".join(f"{e['file']}\t{e['title']}\n" for e in manifest), encoding="utf-8")
    print(f"Distinct timetable URLs: {len(manifest)}", flush=True)

    def download(entry):
        try:
            get(entry["path"], entry["file"])
            return None
        except Exception as error:
            return {"url": entry["url"], "error": str(error)}

    # Four connections at most. Do not multiply this by the number of faculties.
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for index, failure in enumerate(pool.map(download, manifest), 1):
            if failure:
                failures.append(failure)
            if index % 25 == 0 or index == len(manifest):
                print(f"Downloaded {index}/{len(manifest)}, failures={len(failures)}", flush=True)
    summary = {"retrieved_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
               "departments": len(departments), "catalogued_pages": len(manifest), "failures": failures}
    (OUTPUT / "download-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
