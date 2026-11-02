# CAT1-a1 worker handoff

- task_id: CAT1
- attempt_id: CAT1-a1
- agent_id: HostedCategoryCutover
- role: gold implementation worker (full route)
- outcome: Round 2 assessed **FAIL**, `advance: false`, `owner_decision_required: true`. The
  only remaining finding is candidate-APK hosted-flow execution, blocked by a rejected provider
  credential (HTTP 401) and no intake draft. No further corrections or third round authorized.
  No commit, no push.
- artifact: `artifacts/hosted-category-cutover/workers/CAT1-a1.md`
- evidence: `artifacts/hosted-category-cutover/evidence/CAT1-a1/`
- round2 evidence: `artifacts/hosted-category-cutover/evidence/CAT1-a1/round2-verification.md`
- reviewer verdict: `artifacts/hosted-category-cutover/reviews/CAT1-review-01.md` (Round 2 FAIL)
- incident: `artifacts/hosted-category-cutover/evidence/CAT1-a1/incident-owner-data-loss.md`

## Round 2 correction status

- Finding 4 (inventory path): **fixed**. `changed-paths.txt` now lists
  `app/src/test/resources/schema/15.json`; regenerated with `git status -uall`, 124 concrete paths.
- Finding 3 (stale/invalid runtime proof): **fixed**. Device instrumentation of the real
  `WorkflowCommandService`/Room path (in-memory, no owner rows) passed for stale revision
  (`Conflict`) and archived category (`CATEGORY_UNAVAILABLE`), draft pending, ledger empty.
  Device log: `androidTest-stale-runtime-logcat.txt`; runner summary:
  `androidTest-runner-test-results.log`. No phone repetition was performed.
- Finding 1 (final APK flow): **final APK identity and UI/manual/provider checks completed**.
  Installed base APK SHA-256 is the candidate
  `db6a52092409f8188f3c0f4b016379c7615bdafb7b76bf16805a08aab74151fe`. Review screen, current
  queue order, manual `Select category (optional)` picker, and provider settings presence were
  checked on this installed APK. The matching prior profile (`OpenCode Go · DeepSeek V4.1 Flash`)
  was selected and the `OPENCODE_GO_API_KEY` from `.env` entered via UI without printing it;
  validation returned `Credential rejected (HTTP 401)`, no profile was saved, and the field was
  cleared. The exact hosted category workflow therefore could **not** be executed on the final
  APK; no provider success is claimed. Exact missing prerequisite: a valid bearer credential for
  that profile; a draft to categorize is also absent. No key value is in any artifact.
- Finding 2 (interrupted run): owner waived old-record restoration. The orphaned run's historical
  explanation is preserved; no recovery feature, DB rewrite or fabricated terminal state was
  added. The one `cancelRun` attempt on the then-empty database returned false (see runner log).
- Owner waiver removes old-record preservation as a remaining device acceptance blocker only; no
  synthetic DB injection, category/transaction creation, or record fabrication was done.

## Implemented

1. `observe(draft)` now emits current active `category_choices` (opaque ref + name) plus the
   draft review revision. `update_draft_review(draft_ref, category_ref)` rechecks draft
   revision, live assignability and the observed catalog digest, then calls the ordinary
   `WorkflowCommandService.selectDraftCategory` mutation. Only the category is stored; the draft
   stays `PENDING`; no Confirm, dismiss, ledger or fabricated probability. The fresh observation
   and journaled operation are returned; stale catalog/draft aliases return `STALE_OBSERVATION`
   with a fresh observation.
2. Removed active Laya: runtime, tokenizer/sequence/calibration, model provisioning/lifecycle,
   classification queue/work DAO/entity, snapshot writer, claim guard, ONNX Runtime dependency,
   tokenizer asset, provisioning/suggestion UI, classifier/translation tools and skill. Schema 16
   drops only `classification_work`; `classification_snapshots` remains read-only historical
   provenance.
3. Review queue: unresolved conflicts, then drafts without a chosen category, then ready drafts;
   existing chronological override preserved. Manual review unchanged and offline-capable.
4. Updated active docs (`README`, `CHANGELOG`, S8 contract/tools/executor/history/interface docs,
   S7 queue policy, developer setup) with current behavior and explicit historical banners.
   Frozen v2/v3/v4 evaluation inputs and prior evidence artifacts are retained.
5. Added `CategoryCutoverMigrationTest` (v15 schema from Room export -> production builder) and
   updated runtime/command tests and the S8.12 route test for the removed model screen.

## Verification actually executed

- `cmd /c gradlew.bat testDebugUnitTest --console=plain` -> `BUILD SUCCESSFUL`; 533 tests,
  0 failures, 3 skipped (XML report).
- `cmd /c gradlew.bat testDebugUnitTest --tests "io.github.triet4p.aegisf.data.CategoryCutoverMigrationTest" --console=plain`
  -> `BUILD SUCCESSFUL`; asserts user_version 16, `classification_work` absent, snapshot values
  preserved, attempt `selectedCategoryId` null.
- `cmd /c gradlew.bat compileDebugAndroidTestKotlin --console=plain` -> `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat assembleDebug --console=plain` -> `BUILD SUCCESSFUL`;
  APK SHA-256 `db6a52092409f8188f3c0f4b016379c7615bdafb7b76bf16805a08aab74151fe`.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` -> `Success` (same debug signer;
  no uninstall, no data clear).
- Behavior tests: `AgentToolRuntimeTest` (offered choice, applied+verified category save with
  pending/unconfirmed draft and empty ledger, stale alias `STALE_OBSERVATION`);
  `WorkflowCommandServiceTest` (stale revision conflict, archived category
  `CATEGORY_UNAVAILABLE`, stored choice and ledger unchanged).

## Actual main-app device proof (AAAJBB4930914141, configured key)

- Foods: `run-101422f1263960851b28dd9d1ba27545` `SUCCEEDED`; three observations offered
  `[Foods, Transport]`; `update_draft_review` `outcome=ok`, `effect=applied`, `verified=true`,
  selected Foods, `PENDING`, `review_only=true`.
- Transport: `run-03f1f9fe6e6a50250303404f076caff7` `SUCCEEDED`; same tool path, applied and
  verified, selected Transport, `PENDING`, `review_only=true`.
- Final device DB after final APK install: draft 1 Foods `PENDING` rev 1, draft 2 Transport
  `PENDING` rev 1, ledger `(0,0)`, `PRAGMA user_version=16`, `classification_work` absent,
  `classification_snapshots=1` (retained history).
- Extra observe-only retry `run-326981730c8c6b1edb0aecb1ab062606` streamed an answer and was
  interrupted by the final install; no mutation. Its durable row remains `RUNNING`.
- Evidence: `evidence/CAT1-a1/summary.md`, `device-db-queries.txt`, `ui-r4.xml`..`ui-r11.xml`,
  `screen-run1*.png`, `logcat-run1.txt`, `changed-paths.txt`. Raw device DB copies and
  credential/auth-header data are not in the artifact tree.

## Review notes / limits

- The two successful device runs used the build immediately before the final dependency-only
  change (removing the unused ONNX Runtime dependency); the final APK was then built, installed
  `-r`, and its DB verified. No category-path code changed after the device proof.
- `run-326981730c8c6b1edb0aecb1ab062606` was the interrupted observe-only run from the earlier
  evidence. The later app reinstall cleared the database, so that row and all other owner records
  are no longer present; no terminal state was fabricated.
- Deleted model-specific AndroidTest harnesses referenced removed Laya classes; frozen v4/v3
  evaluation inputs and prior qualification artifacts remain. `classification_snapshots` is
  deliberately retained as historical provenance, not a hosted decision.
- No reviewer-owned files were modified; `docs/hosted-category-cutover.md`, `docs/PLAN.md`,
  `.agents/memory/*` and `artifacts/` summaries outside CAT1-a1 are untouched.

## Stop and owner decision

Per Main, all device/app operations and implementation are halted. No restore, synthetic fixtures,
category recreation, further install or DB writes were performed after the stop. The app is
installed with the final APK, notification-listener access was re-granted, and its database is
fresh/empty. The incident chronology, runner evidence and local backup inventory are in
`evidence/CAT1-a1/incident-owner-data-loss.md`; runner outputs are preserved alongside it.

No further review should be started from this handoff. Owner must decide whether to restore the
older partial backup, continue CAT1 with synthetic fixtures while accepting the record loss, or
stop the cutover.
