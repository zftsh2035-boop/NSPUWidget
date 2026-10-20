# Verification for 2.11

Local checks:

- `:app:testDebugUnitTest`: 57 passed, 0 failures/errors. One live NSPU test skipped because `NSPU_LIVE` was not enabled.
- Added tests cover display start/end boundaries, calendar day and DST behavior, multiple independent reminders, delivered reminder deduplication, deletion, time moving backwards, and missing/partial launcher dimensions.
- Executed the migration's actual SQL using SQLite against a populated v1 database. Original ids, text and lesson bindings survived. Multiple identically named day notes were accepted; duplicate lesson slots remained disallowed.
- `:app:assembleDebug`: optimized APK built successfully; signature verified with `apksigner`.
- `:app:lintDebug`: 0 errors, 52 warnings. The known AGP 8.7/Kotlin 2 false positive for `produceState` is suppressed on the two affected local properties only; both explicitly assign `this.value`.
- XML parsing, Python helper compilation and `git diff --check` passed.

GitHub Actions on dev commit `a65bbd9d4c366442fc046e45b691365087dd3c3c`:

- Build, regression tests and the live NSPU parser check passed.
- API 29 launcher pinning and independent favorite/widget selection checks passed.
- Notes and planner scripts reached the editor, then failed on text injection / accessibility selectors. The corrected scripts use the observed Compose parent/child label relationship and short ADB input batches with exact text verification. A new emulator run is required; performance measurements did not run after the feature failures.
- Physical OEM launcher verification remains outstanding. Emulator tests cannot establish behavior on every phone.

Android 15 run `39470077809` on commit `c4a0288b99029f9e9a606bbe237dd288ecc7c948`:

- Planner passed: real v1 database upgrade, day note with 632-character full reader, two native date/time reminders retained after process restart, OS alarm registration without exact-alarm permission, one group chooser and next-month calendar selection.
- Other scenarios were blocked by Android's initial notification-permission dialog. The shared disposable fixture now pre-grants notifications on API 33+, matching the planner scenario's existing setup.
- Screenshot review found note actions too close to the gesture bar. Dialogs now retain the activity's navigation inset, setting tiles have matching height, and Android 15 system-icon contrast follows the theme. These changes require the new emulator run.

Android 10 rerun `39469844767` on commit `8a431be4dc0924fe230665fd1af79bb891867b63`:

- Widget introduction, actual launcher pinning, all lesson-note CRUD/persistence/rotation/widget links and independent favorite/widget selection passed.
- Planner completed migration, day-note reader and two persistent OS reminders, then its calendar selector rejected API 29's virtual day nodes (`clickable=false`). The selector now uses the observed enabled day node and still verifies the resulting selected day. Performance collection was skipped after this failure.
- After the navigation-inset change, local optimized APK build and lint passed again (0 errors, 52 warnings).

Signing limitation:

The downloaded v2.10 APK uses a different certificate from the new permanent key. The old private key is unavailable; generating a new permanent key cannot restore in-place compatibility with v2.10. The SQL migration preserves notes only when Android retains the app's data. Release notes explicitly ask users to copy their notes before removing the old application.

Local debug, release and fixture APKs were built using the new permanent key and verified to have the same SHA-256 signing certificate: `03d2e0e96610346495a76abd60dc9e3dc4f968f457898426e3326183dd9cd85b`. Signing material is outside the repository. CI accepts one protected `NSPU_SIGNING_BUNDLE` JSON secret (or the existing four-secret format); stable builds fail rather than publish with a temporary key. Four signing-configuration tests passed. GitHub secret entry and a CI-signed build are still required.
