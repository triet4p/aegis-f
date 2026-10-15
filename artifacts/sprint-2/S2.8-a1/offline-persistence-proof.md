# S2.8 offline use and persistence proof

- Task: `S2.8` / Attempt: `S2.8-a1` / Agent: `Sprint2OfflineAcceptance` / Role: Gold, light route.
- Route and gates (as assigned): focused host production-path runtime proof for the remaining offline/persistence/month-boundary contract; reuse established tests and add only meaningful missing consumer-visible coverage; scoped self-review; update existing manual-use/date-semantics docs after smoke; clean any owned temporary scaffold; cleaned APK if product/dependency changes, otherwise retain verified unchanged APK with hash; Main compact acceptance; remaining physical checks USER ONLY. No independent reviewer/checkpoint/deep-review assigned. NO staging/commit/push. NO phone communication: no adb, emulator, connected Gradle, install/launch/logs/permissions; user performs all phone actions. No PC/system date change. No Sprint3/AI/bank-intake implementation.
- Repository/worktree/Git dir: `F:/ai-ml/aegis-f` / `F:/ai-ml/aegis-f/.git`; target `main`. **No staging, commit, push, reset, or history mutation was performed**, and no `adb`/emulator/device command was run.
- Prior evidence acknowledged (pointers only; acceptance content not re-tested): `artifacts/sprint-2/S2.1-a2/storage-proof.md`, `artifacts/sprint-2/S2.5-a1/edit-delete-proof.md`, `artifacts/sprint-2/S2.7-a1/summary-proof.md`, `artifacts/sprint-2/S2.7-a2/month-resume-proof.md`; active plan `docs/sprint-plans/sprint-2.md:14-36`; continuation `artifacts/sprint-2/state/main-supervision.md`. The S2.7 phone arithmetic/edit/archive/restart/filter/visual results are ground truth and were NOT repeated.
- Quota/cost observation: unknown (not exposed to the worker).

## Result

S2.8 host work is complete; the physical gate stays pending with the user. One new permanent test closes the single uncovered behavioural gap (accepted edit/archive/delete/inclusion invariants evaluated at local month edges across a real file-backed Room close/reopen). Docs now carry the manual offline flow, the date semantics, and a short differential USER-only checklist. No production source changed, so the accepted APK is retained byte-identical rather than rebuilt.

## Acceptance map: existing vs new evidence

- Create/view/edit/delete manual transactions (amount, type, date/time, description, category): EXISTING — S2.4/S2.5 host smoke + permanent tests, accepted by user phone checks. Reused, not re-run.
- Add/rename/archive categories without cap; archived stay attached, unavailable for new assignments: EXISTING — S2.1/S2.3 host tests + accepted user checks. Reused, not re-run.
- Expense/income/transfer distinct; only confirmed expenses enter totals: EXISTING — `ExpenseSummaryTest` inclusion test + `ExpenseSummaryPersistenceTest` acceptance scenario (165,000 with income/transfer/draft/neighbouring-month rows excluded). Reused via the focused run below.
- Exact whole-dong VND; invalid/fractional/negative/out-of-range refused without corruption: EXISTING — `VndMoneyTest` + `LedgerRepositoryRejectionTest`. Reused via the focused run below.
- Lists/filters/current-month summaries reflect edits/deletions and survive restart: EXISTING for the general case — `LedgerDatabasePersistenceTest` (close/reopen, correction identity, archive attachment, deletion isolation), `ExpenseSummaryPersistenceTest` acceptance scenario with close/reopen (165,000 → 170,000 → archive → 125,000 → reopen). Reused via the focused run below. NEW for the uncovered combination: `month edges with an edit an archive and a deletion survive close and reopen` (this attempt; see below).
- Manual use needs no model file, notification access, login, network, or bank integration: STATIC host evidence only — manifest + source + packaged-APK inspection (see below). Real offline device behaviour is USER-ONLY and unproven here; no fake offline mock assertion is made.

## New coverage (the only gap found)

`ExpenseSummaryPersistenceTest.month edges with an edit an archive and a deletion survive close and reopen` — real file-backed Room via production `LedgerDatabase.build`, real `LedgerRepository` writes, summary built through the same `summarizeMonthExpenses` the panel uses, real `close()`/reopen of the same database file:

- Four expenses on the device-local (`Asia/Ho_Chi_Minh`) October 2026 edges under one category: 9,000 at `monthStart - 1ms` (out), 11,000 at `monthStart` (in), 13,000 at `monthEnd - 1ms` (in), 17,000 at `monthEnd` (out) → 2 records, 24,000 total.
- Edit moves the 11,000 record across the edge to `monthStart - 1ms` → 1 record, 13,000 total (edit changes membership, keeps amount).
- Archive the category + delete the September-edge record, then close and reopen → 1 record, 13,000 total, single bucket on the same stable category id, category still `archived=true` under its original name, remaining record still attached to it.
- No earlier test combined edit + archive + delete + inclusion at month edges with a close/reopen: the pure `ExpenseSummaryTest` month-membership tests pin edges without storage; the persistence acceptance scenario pins storage without edge records. This test pins the combination.

## Actual commands and results (host, observed)

Environment per `docs/developer-setup.md`; commands from `F:/ai-ml/aegis-f` with `--console=plain`.

1. Baseline before changes — `./gradlew.bat :app:testDebugUnitTest --tests LedgerDatabasePersistenceTest --tests ExpenseSummaryPersistenceTest --tests ExpenseSummaryTest --tests ManualDateTimeTest` → BUILD SUCCESSFUL in 37s; 11/2/5/5 tests, 0 failures/errors/skips.
2. After adding the new test — full permanent suite `./gradlew.bat :app:testDebugUnitTest --tests LedgerDatabasePersistenceTest --tests LedgerRepositoryRejectionTest --tests VndMoneyTest --tests ExpenseSummaryTest --tests ExpenseSummaryPersistenceTest --tests ManualDateTimeTest --tests TransactionFilterTest` → BUILD SUCCESSFUL in 32s; JUnit XML: `LedgerDatabasePersistenceTest` 11, `LedgerRepositoryRejectionTest` 12, `VndMoneyTest` 6, `ExpenseSummaryTest` 5, `ExpenseSummaryPersistenceTest` 3 (was 2), `ManualDateTimeTest` 5, `TransactionFilterTest` 13 → **55 tests, 0 failures, 0 errors, 0 skipped** (was 54 before this task; the one added test is the month-edge case above). No full-suite rerun beyond this: the change is test-only, and this run already covers every affected contract.
3. Static offline/packaging inspection (packaging evidence only, NOT device behaviour):
   - `aapt dump badging`: `package: name='io.github.triet4p.aegisf' versionCode='4' versionName='1.0'`, compile/target SDK 37, min SDK 24, `application-label:'Aegis F'`.
   - `aapt dump permissions`: only `io.github.triet4p.aegisf.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — a dependency-generated, signature-protected entry; the app manifest (`app/src/main/AndroidManifest.xml`, 20 lines) declares zero `<uses-permission>` and keeps `allowBackup="false"`.
   - `app/build.gradle.kts`: no network/ML/auth dependencies — Room, Compose, Activity-Compose, coroutines, Robolectric/JUnit test-only. No `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `BIND_NOTIFICATION_LISTENER`, or boot/receiver permissions anywhere in manifest, build files, or sources (grep-verified).
   - Source grep for `Notification|NotificationListener|WorkManager|DownloadManager|WebView|HttpClient|URL(|Socket|okhttp|retrofit|tflite|onnx|Firebase|mlkit|GoogleSign|Credential|Biometric|AccountManager|SyncAdapter|Login|SignIn|OAuth|Sms|Telephony` across `app/src/main/java`: zero matches except the data-only `RecordSource.BANK_NOTIFICATION` display string (`"Bank notification"`) — a status label, not a listener. No `assets/`, `res/raw/`, or model directories exist.
   - APK listing (121 entries): `classes*.dex` + Compose `.so` files only; no `.tflite`/`.onnx`/model/asset bundle (the one `DebugProbesKt.bin` hit is a Kotlin-debug metadata file, not a model). `androidx/compose/ui/test` string count is 0 as reported by the S2.7 proofs (the `strings` binary is absent on this host, so the per-dex count could not be re-observed here; the APK hash below proves it is the same artifact those proofs scanned).
4. APK identity (retained, NOT rebuilt — no production/dependency change): `app/build/outputs/apk/debug/app-debug.apk`, **11,927,835 bytes**, mtime `2026-10-15 16:25`, SHA-256 `dac39d804b70cc34915c627f5e5da5b815481b37cbfc0330fd4babbf334c8d3b` — matches the latest accepted value exactly. `versionCode 4` / `versionName 1.0` / original debug signer preserved per the S2.7-a2 proof; docs/tests-only changes are not packaged, so a rebuild would only churn the hash.

## Changed files

- `app/src/test/java/io/github/triet4p/aegisf/ui/ExpenseSummaryPersistenceTest.kt` — one new permanent test (see above); no production, build-config, or existing-test change.
- `docs/developer-setup.md` — §3: S2.7 bullet now records 3 `ExpenseSummaryPersistenceTest` tests + the S2.8 proof link, and a new manual-offline/date-semantics bullet (no-model/no-notification/no-login/no-network/no-bank static evidence, wall-clock zone convention, half-open local month, transaction-dates-not-clock rule); §5: new differential S2.8 USER-only checklist (baseline B, NEW disposable records, Wi-Fi/mobile-data off, Force-stop relaunch, month-edge transaction dates, restore connectivity, cleanup only test records). No other doc touched.
- This proof file.

Explicitly untouched: all production sources, `app/build.gradle.kts`, Room schema/`app/schemas`, `AndroidManifest.xml`, version pins, signing config, sprint plans, supervision state.

## Cleanup

No temporary scaffold was created (no smoke file, no temporary dependency). Nothing to remove. No test records exist on any device (no device access); phone-side cleanup is part of the user checklist (delete the five remaining `S2.8` test records; `S2.8 A` is already deleted in step 3 — the archived `S2.8 check` category may be kept for its history).

## Honest host limits (UNPROVEN here; user-only or not exercised)

- Real offline device behaviour (Wi-Fi/mobile data off, Force-stop relaunch, month-edge dates, archive attribution on screen) is proven ONLY by the numbered phone checklist below. Host Room close/reopen is the process-death equivalent for stored rows, not a radio/network proof; static manifest/source/APK inspection proves packaging, not on-device behaviour. No mock stands in for the radio.
- Robolectric proves stored-row semantics and derived figures, not pixels: typography, dark theme, font scale, touch feel remain the user's gate (already accepted for S2.3–S2.7; only the S2.8 differential steps are requested now).
- Robolectric wall-clock is uncontrollable (established in S2.7-a2; not reprobed). The new test therefore pins month edges as stored instants around `monthStartEpochMillis`/`nextMonthStartEpochMillis` and drives the membership change through a real edit — it does not move the host clock.
- A resume racing a real midnight, a runtime zone change in the foreground, and deep-sleep timer delays are out of scope (S2.7-a2 limits, unchanged).
- No staging/commit/push performed; Sprint 2 work stays uncommitted by assignment.

## USER-only phone checklist — S2.8 differential gates ONLY (no worker device access)

Install the retained build only if the phone is not already running the exact accepted S2.7-a2 APK below (a bare `versionCode = 4` is not artifact identity — several earlier builds share it); the user already accepted that APK, so no reinstall is needed unless unsure: `adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'`. Artifact identity is the 11,927,835-byte file with SHA-256 `dac39d804b70cc34915c627f5e5da5b815481b37cbfc0330fd4babbf334c8d3b`. Baseline B = the THIS MONTH card's current figure. Use NEW disposable records only — six test records in total: expenses `S2.8 A` = 45000 and `S2.8 B` = 120000, income `S2.8 income` = 1000000, transfer `S2.8 move` = 500000, month-edge expenses `S2.8 prior edge` = 11000 and `S2.8 next edge` = 17000. NEVER clear storage, uninstall, or delete/alter existing records. Offline = turn Wi-Fi/mobile data off (no airplane-mode requirement); restore connectivity afterwards. If notification access is already off, merely observe it; never grant unrelated access. Report actual observations to Main.

1. Offline + persistence: turn connectivity off; record today's expenses `S2.8 A` = 45000 and `S2.8 B` = 120000 under the new `S2.8 check` category, income `S2.8 income` = 1000000, transfer `S2.8 move` = 500000 — card reads B + 165,000 VND, no model/permission/login/network prompt appears. **Settings > Apps > Aegis F > Force stop**, relaunch, still offline: records and B + 165,000 VND unchanged.
2. Offline edit/archive/restart: still offline, correct `S2.8 B` to 125000 (card B + 170,000 VND); archive `S2.8 check` (row `S2.8 check (archived)`, total unchanged). Force-stop and relaunch: detail still shows 125,000 VND, Confirmed/Manual, a Last corrected line, the archived attribution, and B + 170,000 VND.
3. Offline delete + month edges (transaction dates only — NEVER change the system clock): still offline, delete `S2.8 A` (card B + 125,000 VND; five test records remain); record `S2.8 prior edge` = 11000 dated the previous month's last day (e.g. `2026-09-30`, `23:59`) and `S2.8 next edge` = 17000 dated the next month's first day (e.g. `2026-11-01`, `00:00`) — neither moves the card. Correct `S2.8 next edge`'s date to today (card reads B + 142,000 VND), correct it back to `2026-11-01` (card returns to B + 125,000 VND). Delete the remaining five test records (`S2.8 B`, `S2.8 income`, `S2.8 move`, `S2.8 prior edge`, `S2.8 next edge`; `S2.8 A` is already gone — card back to **B**); force-stop and relaunch once more; restore connectivity; confirm B and all pre-existing records untouched. The archived `S2.8 check` category may be kept for its history.

## Decisions

- One new permanent test, not a smoke: the gap is a stored-row invariant (edges + edit + archive + delete + close/reopen), fully expressible through the production repository without Compose UI-test dependencies; a throwaway launcher smoke would add harness without new signal.
- No rebuild: docs/tests-only changes are not packaged, so rebuilding would only churn the APK hash for zero product change; the verified byte-identical APK is retained.
- No full-suite justification beyond the focused run: the product tree is unchanged, and the focused run already executes all 55 permanent tests across every affected contract.

## Unresolved findings

- The three numbered phone checks above are UNPROVEN on this host; no PASS is claimed for them. S2.8 stays pending user observation; sprint completion is NOT claimed.
