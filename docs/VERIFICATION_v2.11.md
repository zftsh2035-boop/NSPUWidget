# Verification for 2.11

Local checks:

- `:app:testDebugUnitTest`: 57 passed, 0 failures/errors. One live NSPU test skipped because `NSPU_LIVE` was not enabled.
- Added tests cover display start/end boundaries, calendar day and DST behavior, multiple independent reminders, delivered reminder deduplication, deletion, time moving backwards, and missing/partial launcher dimensions.
- Executed the migration's actual SQL using SQLite against a populated v1 database. Original ids, text and lesson bindings survived. Multiple identically named day notes were accepted; duplicate lesson slots remained disallowed.
- `:app:assembleDebug`: optimized APK built successfully; signature verified with `apksigner`.
- `:app:lintDebug`: 0 errors, 52 warnings. The known AGP 8.7/Kotlin 2 false positive for `produceState` is suppressed on the two affected local properties only; both explicitly assign `this.value`.
- XML parsing, Python helper compilation and `git diff --check` passed.

Not yet executed for this revision:

- Emulator feature/performance suite, including the updated note reader and new planner script.
- Physical OEM launcher verification of in-app widget pinning. Local tests cannot establish behavior on every phone.

Signing limitation:

The downloaded v2.10 APK certificate and this local debug APK certificate differ. The repository currently creates temporary debug signing keys unless protected production-signing secrets are configured. Treat this build as a development artifact; do not describe it as an in-place, data-preserving update over v2.10. The SQL migration preserves notes only when Android retains the app's data. Resolve signing and the existing-data upgrade path before a stable rollout.
