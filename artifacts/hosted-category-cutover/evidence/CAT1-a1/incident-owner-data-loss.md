# CAT1 Round 2 incident — owner app data cleared by connectedDebugAndroidTest

Date/time: 2026-11-03 ~00:38 local. Device AAAJBB4930914141, package io.github.triet4p.aegisf.

## What happened

To obtain on-device runtime proof, I added a temporary instrumented test
(`app/src/androidTest/java/io/github/triet4p/aegisf/data/Cat1ThrowawayInstrumentedTest.kt`) and ran:

`cmd /c gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.triet4p.aegisf.data.Cat1ThrowawayInstrumentedTest --console=plain`

The Gradle connected task installed the app and test APKs, then its normal cleanup uninstalled
`io.github.triet4p.aegisf` (AGP leaves no APK installed after `connectedAndroidTest`). This
removed the app's private data directory. The command was not an `adb install -r` and was not
authorized to uninstall or clear data.

Immediately after discovery:
- Reinstalled the exact final APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  (same debug signer, Success).
- Re-granted the notification listener with
  `adb shell cmd notification allow_listener io.github.triet4p.aegisf/io.github.triet4p.aegisf.notification.BankNotificationListener 0`.
- Did not restore any database by direct injection or fabricate records.

## Verified post state

- Package installed at `package:/data/app/.../io.github.triet4p.aegisf-.../base.apk`.
- Notification listener setting now includes
  `io.github.triet4p.aegisf/io.github.triet4p.aegisf.notification.BankNotificationListener`.
- Fresh app database: `PRAGMA user_version=16`, `pending_bank_drafts=0`, `note_events=0`,
  `context_links=0`, `categories=[]`, `transactions=0`, `agent_runs=0`.
- Owner data that existed before the incident (6 drafts; 4 note events; accepted link event 2 to
  draft 1; categories Foods/Transport; prior runs) is no longer present.

## Possible recovery source (not used)

`/tmp/aegis-pt2/db-before/aegis-ledger.db` is an older schema-13 Aegis database from
2026-11-02 13:13 (observed: 5 drafts, 2 events, no link, ledger 0). It is older than the lost
current state, contains sensitive owner data, and was not copied into the artifact tree. Restoring
it would require explicit owner authorization and direct database injection; it would still lose
later drafts/events/link and cannot be represented as preservation.

## Decision needed

Owner/Main must decide whether to restore the older partial backup, continue with synthetic
fixtures while accepting the owner-data loss, or stop CAT1. No further device test runner that
uninstalls the app will be used. The temporary instrumented test remains in the tree only until the
decision; it is not part of the intended candidate.

## Exact chronology and runner evidence

1. First targeted runner attempt:
   `cmd /c gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.triet4p.aegisf.data.Cat1ThrowawayInstrumentedTest --console=plain`
   Gradle failed before executing tests because the Kotlin test methods did not return `void`
   (`Invalid test class ... Method ... should be void`). The connected task had installed the app
   and test APK and then performed its normal cleanup; the app package was not checked at that
   moment.
2. The test signature was corrected and the same command was run again. It executed two tests:
   `cancelOrphanedRunThroughSupportedTerminalApi` failed with
   `AssertionError: supported cancelRun refused the orphaned run`; the in-memory
   `staleRevisionAndArchivedCategoryAreRefusedOnDevice` passed. The cancel test already failed
   because the owner run row was gone, consistent with data loss during the first run's cleanup.
   Saved runner output: `androidTest-runner-test-results.log`; stale-test device log:
   `androidTest-stale-runtime-logcat.txt`.
3. After the runner, `adb shell pm path io.github.triet4p.aegisf` and
   `adb shell pm list packages` returned no package; `/data/data/io.github.triet4p.aegisf` did not
   exist. This is the observed cleanup consequence.
4. The exact final APK was then reinstalled with `adb install -r` (Success) and notification
   listener access was restored. No DB injection, restore, category recreation or synthetic
   fixture was performed.

## Local backup inventory (existing observations only)

- `/tmp/aegis-pt2/db-before/aegis-ledger.db`: schema 13, mtime 2026-11-02 13:13, observed after
  copy: categories Foods/Transport, 5 drafts, 2 note events, 0 links, 0 ledger rows, 2 runs.
  Older than the lost state; contains sensitive owner data; not copied into artifacts.
- `/tmp/pt2db/aegis-ledger.db` (+ `-shm`, `-wal`, 2026-11-02 14:15): direct SQLite open observed
  an empty schema/unreadable result; not established as a usable backup.
- `/tmp/db_now.db`: existing observation showed no `transactions` table; not usable.
- Artifact DB copies made during this attempt (`db-final.db`, `db-current.db`, etc.) were deleted
  from `artifacts/hosted-category-cutover/evidence/CAT1-a1/` before this incident; no current
  owner database copy remains there.
- Older test/evidence databases under `artifacts/sprint-*` are sprint fixtures, not the current
  owner state; they were not used or inspected as recovery sources.

## Current stop state

All device/app operations and implementation stopped per Main. App package is installed with the
final APK; notification listener is enabled; app DB is fresh schema 16 with no owner records. No
round-2 resubmission.
