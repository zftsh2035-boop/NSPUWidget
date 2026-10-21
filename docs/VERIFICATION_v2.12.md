# Verification for 2.12

- XML parsing, Python helper compilation and `git diff --check` passed locally.
- The first signed CI build on `e8130bb706f15a9b80424bedf73fc4cb8d28509b` passed. It contains widget diagnostics, update notes and the new adaptive/themed icon.
- Follow-up changes add general important-event extraction, full assessment labels/contrast, dated-lesson priority and notification deduplication. Their regression and emulator checks are pending.
- Added pure tests use the actual mathematics credit example for 28 November 2026, mixed-case/parenthesized abbreviations, bold extramural table cells, changed dates/rooms, ambiguous dates and previously unknown annotations.
- The UI scenario covers cached GitHub update text, hidden build metadata, the future-credit card and dated lesson, diagnostics for two real widgets, offline installed-version notes and the system-rendered app icon.
- The Blackview BV4900 failure has not been diagnosed on the physical phone. The supplied screenshot matches the static full-widget template (missing date/group, default star colour and default empty text). This is evidence that populated content was not applied, not proof of a missing permission. The new local report records callback/render phases, cache state, worker state and Android restrictions without collecting note or lesson text.
- No claim is made that arbitrary natural-language notices can always be interpreted. Exact dates and explicit event words are recognised; ambiguous/new wording remains visible as source text. Background checks use WorkManager and may be delayed by Android.
