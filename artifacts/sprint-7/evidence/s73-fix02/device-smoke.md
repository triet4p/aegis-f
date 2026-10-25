# S7.3 correction device smoke

**Task / attempt:** S7.3 / S7.3-fix-02  
**Scenario:** `ClassificationWorkRetargetSmokeTest.claimRetargetMarkAndQueueRunTheNewestQuestion`  
**Device:** ALT-LX2, Android 14 (API 34); connected instrumentation report timestamp `2026-10-25T04:39:20`.

The temporary `.s73fix02` application ID ran an instrumentation scenario against a real on-device in-memory Room database and the production classification queue. It inserted one synthetic draft (`newest smoke question`) and a pending work row, claimed it for `smoke-older-attempt`, retargeted the row to `newest-question`, then marked the old attempt with `older-question`. The immediate stored row retained `requestedQuestion=newest-question` and `executedQuestion=older-question`; after the attempt was requeued as `CONTEXT_CHANGED`, the production queue consumed it and the engine observed exactly `newest smoke question`. The deliberately unavailable fake engine let the real command/queue path terminate honestly as `FAILED`/`MODEL_OR_INFERENCE_FAILED`. The synthetic draft remained present and the transaction table stayed empty.

**Observed result:** Gradle connected instrumentation reported one test, zero failures/errors/skips; the test log records the production queue scheduling one question digest and outcome `FAILED`, matching the unavailable-engine fixture. This scenario exercised the real Room DAO SQL and queue consumer on Android, not a SQL-parameter mock; the real model was intentionally not invoked.

**Isolation / cleanup:** The app ID was suffixed to `io.github.triet4p.aegisf.s73fix02`; the test used only in-memory Room and synthetic values, did not launch the normal app or read/write its database, and did not write financial records. The temporary instrumentation test and suffix/dependency configuration were removed after the run. During that temporary cleanup an unrelated `targetSdk = 37` build setting was accidentally omitted; it was restored exactly to base before round 3. The final normal APK manifest reports target SDK 37, and `git diff` against base shows no `app/build.gradle.kts` change. `adb shell pm list packages` showed no remaining `.s73fix02` package after Gradle completed.

**Raw report:** `app/build/outputs/androidTest-results/connected/debug/TEST-ALT-LX2 - 14-_app-.xml` (one pass, 0 failures/errors/skips); device log `app/build/outputs/androidTest-results/connected/debug/ALT-LX2 - 14/logcat-io.github.triet4p.aegisf.data.ClassificationWorkRetargetSmokeTest-claimRetargetMarkAndQueueRunTheNewestQuestion.txt`.
