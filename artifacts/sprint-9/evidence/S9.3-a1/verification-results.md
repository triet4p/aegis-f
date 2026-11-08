# S9.3-a1 verification outcomes

## Isolated phone behavior

The final actual Room/command-boundary scenario and its observed `OK (1 test)` result are recorded in [phone-room-instrumented.txt](phone-room-instrumented.txt). The pre-fix defect is separately preserved in [baseline-incomplete-auto-attach.txt](baseline-incomplete-auto-attach.txt).

## Build, test compilation and lint

Command:

`./gradlew.bat :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence`

Observed: `BUILD SUCCESSFUL` (79 actionable tasks; 16 executed, 63 up-to-date). Main and isolated Android-test APKs assembled; `compileDebugAndroidTestKotlin` and `lintDebug` completed successfully.

After adding the final no-existing-link cardinality assertion, this command also completed successfully:

`./gradlew.bat :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence` → `BUILD SUCCESSFUL` (53 actionable tasks; 5 executed, 48 up-to-date). The rebuilt test APK was installed with `adb install -r` only to the isolated synthetic test package, and the final phone run passed.

## JVM unit suite

Command:

`./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1 -PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence`

Observed: **551 tests, 14 failures, 0 errors, 3 skipped**. The failures were `SQLiteCantOpenDatabaseException: unable to open database file (code 14 SQLITE_CANTOPEN)` during Room database opening in existing tests from `FinancialCommandServiceTest` (2), `MigrationChainTest` (2), `PendingBankDraftDeduplicationTest` (6), `PendingBankDraftMigrationTest` (3), and `CaptureMessagePersistenceTest` (1). No business assertion failed in those 14 test cases. The agent/runtime suite passed **36/36** and `WorkflowCommandServiceTest` passed **26/26**, including the existing unique-attachment, stale-evidence, revision, refusal and unlink cases. `AgentToolRuntimeTest` also passed its assertions that unknown candidate dimensions remain `account` and `transaction_type`. `--max-workers=1` did not remove the DB-open failures; their cause remains undetermined and they are not reported as a passing full suite.

The project volumes had ample free space (C: about 75 GB, F: about 59 GB) when checked; this does not establish the SQLite failure cause. No repair to unrelated migration/dedup/capture tests was made.

## External inference

No provider/network call was made. The changed behavior was exercised through production Room and command/query services on the isolated phone package; the known provider credential failure was not retried.
