# Verification for 2.12

The application sources were verified at `41d261ddfb5263babe7958f7c658afa12260cc44`. The final release preparation changes documentation only.

| Check | Result | Evidence |
|---|---|---|
| Full Android regression tests, live public HTML, R8 debug/release builds, ZIP and signing integrity | PASS | [Build 39533447036](https://github.com/zftsh2035-boop/NSPUWidget/actions/runs/39533447036) |
| Android 15 / API 35, five complete feature scenarios | PASS | [Run 39533447021](https://github.com/zftsh2035-boop/NSPUWidget/actions/runs/39533447021) |
| Android 10 / API 29, five feature scenarios, native navigation and repeated scroll measurements | PASS | [Run 39533446996](https://github.com/zftsh2035-boop/NSPUWidget/actions/runs/39533446996) |
| Focused local parser, important-event and release-note JUnit tests | 19 passed | Includes eight new group-HTML tests and both official week-annotation styles |
| Local signing-validation unit tests | 4 passed | No private key material is printed |
| Python compilation, XML checks and whitespace checks | PASS | Local checks; XML assets also compiled by Android CI |

The feature scenarios cover widget introduction and native pin confirmation; note CRUD, long text, cold/warm widget links, process restart and draft restoration; legacy note migration, day notes, two saved reminder times and an OS alarm; independent favorite groups/dates in the app and two actual widgets; GitHub update text without the build appendix, the future-credit card and exact date, widget diagnostics/copy, offline installed-version notes and the OS-rendered icon. Screenshots and reports are attached to each run.

The dev release APK measured 1,538,912 bytes. Its certificate SHA-256 is `03d2e0e96610346495a76abd60dc9e3dc4f968f457898426e3326183dd9cd85b`, matching v2.11 and the permanent repository secret. The stable workflow independently verifies signing and APK integrity before publishing. Its final download size/checksum are recorded in the GitHub release assets.

See [the 26-page public schedule audit](PUBLIC_AUDIT_v2.12.md) for concrete missing session events, merged week columns, short-year dates, embedded start times, the sample URLs and limits. Older parsed caches are refreshed; the format migration does not claim that all old lessons were just changed by the university.

## Limits

- The Blackview BV4900 has not been tested physically and its root cause is unconfirmed. The supplied screenshot matches the static full-widget template: no date/group and the default empty text. This supports an unapplied populated view, not a conclusion about permissions. The local report records callbacks/render submissions, cache, workers and Android restrictions; `render_ok` does not prove that a launcher drew the pixels. No note or lesson text is included.
- Background refresh uses WorkManager every three hours and Android can delay it. Discovery/change notifications require the OS notification permission. A notification at a chosen future time remains a personal-note reminder.
- Exact dates and explicit assessment words are recognised; ambiguous and unfamiliar wording remains source text. The audit is a sample, not a guarantee about every university page or future HTML format. Two sampled pages contain no timetable tables.
- The performance workflow retains historical folder labels `v2.11` / `repeat-v2.11`; in this run they contain the current commit APK, compared with the actual v2.9 baseline. Emulator frame counts are observations, not physical-device, battery or universal speed claims.
