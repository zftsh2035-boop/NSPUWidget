# Verification for 2.12

- XML parsing, Python helper compilation and `git diff --check` passed locally.
- The first signed CI build on `e8130bb706f15a9b80424bedf73fc4cb8d28509b` passed. It contains widget diagnostics, update notes and the new adaptive/themed icon.
- Follow-up changes add general important-event extraction, full assessment labels/contrast, dated-lesson priority and notification deduplication. Their regression and emulator checks are pending.
- Added pure tests use the actual mathematics credit example for 28 November 2026, mixed-case/parenthesized abbreviations, bold extramural table cells, changed dates/rooms, ambiguous dates and previously unknown annotations.
- The UI scenario covers cached GitHub update text, hidden build metadata, the future-credit card and dated lesson, diagnostics for two real widgets, offline installed-version notes and the system-rendered app icon.
- The Blackview BV4900 failure has not been diagnosed on the physical phone. The supplied screenshot matches the static full-widget template (missing date/group, default star colour and default empty text). This is evidence that populated content was not applied, not proof of a missing permission. The new local report records callback/render phases, cache state, worker state and Android restrictions without collecting note or lesson text.
- No claim is made that arbitrary natural-language notices can always be interpreted. Exact dates and explicit event words are recognised; ambiguous/new wording remains visible as source text. Background checks use WorkManager and may be delayed by Android.

Follow-up regression run on `e7007ee785d40d5bd3752090a01f63d7e969e99d`:

- Compiled successfully; 69 tests executed, with one skipped live check and one failure in the standalone extramural table fixture. All other new event/annotation/abbreviation tests passed.
- The failing fixture exposed a real parser assumption: the first table was always sent to the weekly-grid parser. Standalone extramural pages begin with calendar-date rows. Table selection now considers explicit calendar cells and the page type; malformed-HTML fallback preserves the same distinction. Standalone extramural views open their dated section by default.
- The bold/uppercase assertion is retained and now additionally requires exact dates and the dated section. Rerun pending.


Extended public-page audit and follow-up:

- Signed build and all schedule unit/live tests passed on `178026d39c26f1f92265580bfb9191aebec9cde4` (run 39530322224).
- Full Android 10/API 29 feature and performance workflow passed on the same revision (run 39530322217).
- Android 15 passed introduction, planner, favorites, future-credit display and diagnostic report. Two automation problems remained: flooding a multiline editor with deletion key events, and tapping Close while Android’s clipboard overlay covered that button. The saved screenshot confirms the latter opened Android’s clipboard editor; the test now waits for that temporary overlay. Short observed input batches / a supported Ctrl+A chord replace the deletion flood.
- A user-requested wider audit downloaded 26 official pages from five departments. It exposed week-column merging, first-header-table classification, short-year dates and omitted separate session tables. The actual parsers were corrected and 19 focused local JUnit tests pass. See `PUBLIC_AUDIT_v2.12.md` for the sample, concrete examples and limits.
- A new complete Android build and emulator run is required after the parser corrections; no physical Blackview result is claimed.
