# CAT1 Evidence Review Report — Round 2 Final

## Identity and Review Context

- **task_id**: `CAT1`
- **reviewer attempt_id**: `CAT1-review-a1`
- **agent_id**: `HostedCategoryReview`
- **worker_attempt_id**: `CAT1-a1`
- **worker agent_id**: `HostedCategoryCutover`
- **review_cycle_id**: `CAT1-cycle-1`
- **final review_round**: `2`
- **worker_artifact_path**: `artifacts/hosted-category-cutover/workers/CAT1-a1.md`
- **round2_evidence_path**: `artifacts/hosted-category-cutover/evidence/CAT1-a1/round2-verification.md`
- **report_path**: `artifacts/hosted-category-cutover/reviews/CAT1-review-01.md`
- **manifest_path**: `artifacts/hosted-category-cutover/reviews/CAT1-review-01.manifest.json`
- **base_commit_sha**: `80b3a69a09b028b430235e5f67806104d7ba54b3`
- **target_ref**: `refs/heads/main`
- **snapshot_sha256**: `ec4fe67280f58574c33a69f1e97723bfef711ed5d4280ad8b4d04e18fed84262`
- **verdict**: **FAIL**
- **advance**: `false`
- **owner_decision_required**: `true`

---

## Executive Summary & Progress Delta Across Rounds

Task CAT1 implements the hosted category cutover: removing active Laya and its English translation bridge from the active application and enabling the hosted workflow to select an existing category from current choices as part of normal draft proposal reasoning, without a separate on-device classifier or category-only tool round-trip.

### Round 1 Assessment
The candidate implementation across 124 source paths removed active Laya classes, provisioning UI, tokenizer asset, and ONNX Runtime dependency; delivered schema 16 migration (`LedgerDatabase.MIGRATION_15_16`); updated review queue ordering; and passed 533 unit tests. However, four actionable findings were identified:
1. **HIGH — Device Proof Executed on Pre-Final Build**: Foods and Transport runs were performed before removing the ONNX Runtime dependency; final APK candidate `db6a52092409f8188f3c0f4b016379c7615bdafb7b76bf16805a08aab74151fe` was installed via `adb install -r` but never exercised.
2. **MEDIUM — Interrupted Run Left `RUNNING` in SQLite**: Retry run `run-326981730c8c6b1edb0aecb1ab062606` was force-stopped during final APK install, leaving its durable record in `agent_runs` in active `RUNNING` state.
3. **MEDIUM — Missing Runtime Proof for Invalid/Stale Handling**: Criterion 7 requires runtime proof of invalid/stale handling under application flow; worker cited only JVM unit tests.
4. **LOW — Inventory Path Discrepancy**: Line 113 of `changed-paths.txt` listed directory `app/src/test/resources/schema/` instead of file `app/src/test/resources/schema/15.json`.

Round 1 closed as **changes_requested** (`advance: false`), consuming Round 1. Sibling manifest `artifacts/hosted-category-cutover/reviews/CAT1-review-01.manifest.json` was frozen with `snapshot_sha256: ec4fe67280f58574c33a69f1e97723bfef711ed5d4280ad8b4d04e18fed84262`.

### Round 2 Resolution & Verification
Worker `HostedCategoryCutover` addressed the findings under authorized Round 2:
1. **Finding 4 Resolved (Inventory Path)**: `changed-paths.txt` line 113 was corrected to `app/src/test/resources/schema/15.json`, matching the 124 concrete files in the frozen reviewed snapshot.
2. **Finding 3 Resolved (Runtime Proof of Stale/Invalid Refusal)**: The worker executed an on-device instrumented test running the real `WorkflowCommandService.selectDraftCategory` and Room path against an in-memory database. On-device logcat (`androidTest-stale-runtime-logcat.txt`) recorded:
   `11-03 00:38:21.654 ... I CAT1-RUNTIME: stale_revision=Conflict archived_category=CATEGORY_UNAVAILABLE draft=pending ledger=empty`.
   Stale review revision was refused with `Conflict`, archived category was refused with `CATEGORY_UNAVAILABLE`, the draft remained pending, and ledger remained empty.
3. **Finding 2 Resolved (Interrupted Run)**: Per Main's supervision direction, CAT1 is not expanded into a general process-death recovery system. The historical explanation of the force-stop orphan is preserved without direct DB rewriting or fabricated completion.
4. **Finding 1 Unmet (Final APK Flow Proof)**: The worker confirmed installation of the candidate APK (pulled `base.apk` SHA-256 matches candidate `db6a52092409f8188f3c0f4b016379c7615bdafb7b76bf16805a08aab74151fe`). Review screen, uncertainty queue order, manual category picker, and provider screens were verified. However, when selecting the prior matching profile (`OpenCode Go · DeepSeek V4.1 Flash`) and entering `OPENCODE_GO_API_KEY` through the UI, provider validation returned:
   `Credential rejected (HTTP 401). Replace it and retry. No profile was changed.`
   Consequently, the hosted category flow could not run on the final APK due to an invalid/expired bearer credential, and no bank drafts were present in the newly reinstalled database.
5. **Device Data-Loss Incident**: During the Round 2 test execution, the AGP connected test task uninstalled the package, clearing the device's private app data directory and deleting the owner's database (6 pending bank drafts, 4 note events, accepted context link event 2 <-> draft 1, categories Foods/Transport). The owner waived restoring old draft/event records, but the lack of an active draft and valid provider credential prevented final-APK hosted flow verification.

---

## Detailed Evaluation of Acceptance Criteria

| Criterion | Contract Requirement | Round 2 Observed Status | Assessment |
|---|---|---|---|
| **1. Category choices & source context** | Current category choices and source context available to hosted workflow; direct category selection described in declarations/skills/prompts without Laya or translation. | Met in Code | `draftObservation` emits `category_choices` with opaque refs; `draftContextObservation` includes masked accepted linked readings; `s8_agent_tools_v6.json` declares `update_draft_review`; `s8_agent_runtime_skills_v6.json` defines `draft-review` skill (v1). |
| **2. Category persistence & validation** | Persisted via ordinary workflow mutation, validated locally for identity, scope, revision. Invalid/stale proposals have no unintended effects. No ledger Confirm. | Met in Code & Runtime | `WorkflowCommandService.selectDraftCategory` validates scope, review revision, and category assignability; `BankDraftReviewDao.saveReviewCategory` updates `reviewCategoryId` and increments revision without Confirming or inserting transaction. Stale revision and archived category refusal verified on device (`CAT1-RUNTIME: stale_revision=Conflict archived_category=CATEGORY_UNAVAILABLE`). |
| **3. Laya removal & manual review** | Remove active Laya runtime, provisioning, ONNX dependency, classifier/translation tools, suggestion UI. Review queue and manual flow work offline. | Met in Code & UI | Dropped Laya runtime classes, ONNX Runtime dependency, tokenizer asset, provisioning screen, and manual suggestion machine. `ReviewQueueDao` orders by conflict -> unresolved -> ready without model confidence. Manual category editing and UI verified on device. |
| **4. Owner data & provenance preservation** | Preserve owner database records, category identities, draft/event/link provenance and ledger totals through migration 15->16 and same-signer update. Retain historical qualification results. | **Partial / Owner Waived** | Migration 15->16 code and unit tests verified. Historical qualification JSON intact. However, on-device SQLite database was wiped during AGP test cleanup (`incident-owner-data-loss.md`). The owner explicitly waived old-record restoration on this development device. |
| **5. Privacy & streaming trace** | Retain hosted masking, neutral token reminders, output guards, streaming, and local session traces without credentials. | Met in Code | Masking boundary, neutral tokens (`⟦V-scope-seq⟧`), and output guards verified in `AgentMaskedValueUsabilityTest` and `ProductionTraceTest`. |
| **6. Behavioral tests & documentation** | Update behavioral tests and docs; remove obsolete incidental tests. Exercise changed behavior without phone. | Met | 533 unit tests pass (0 failures, 3 environment skips); `compileDebugAndroidTestKotlin` succeeds; documentation updated in `README.md`, `CHANGELOG.md`, and S7/S8 docs. |
| **7. Device flow & runtime proof** | Install/update main app without clearing data; exercise actual bread-note/draft flow with configured key; observe Foods, full trace, no Confirm. Preserve every attempt. Exercise another category and invalid/stale handling with suitable runtime proof. | **UNMET (Disclosed Prerequisite Gap)** | Final APK (`db6a5209...`) verified installed on device. Stale/invalid handling runtime proof verified on device. However, hosted category flow on the final APK could not be run because the bearer credential for OpenCode Go returned HTTP 401 and no draft was present in the fresh database. Earlier Foods/Transport runs remain pre-final build historical evidence only. |

---

## Actionable Findings Status

1. **HIGH (Unresolved) — Candidate APK Hosted Flow Execution**:
   - *Claim*: The hosted category cutover flow with the configured provider key was not exercised on the installed candidate APK (`db6a52092409f8188f3c0f4b016379c7615bdafb7b76bf16805a08aab74151fe`).
   - *Evidence*: `artifacts/hosted-category-cutover/evidence/CAT1-a1/round2-verification.md` (lines 41–48): entering `OPENCODE_GO_API_KEY` returned `Credential rejected (HTTP 401)`. No hosted workflow run occurred on the final APK build.
   - *Why It Matters*: Acceptance Criterion 7 requires exercising the actual flow with the configured key on the updated main app.
   - *Confidence*: HIGH (disclosed prerequisite gap).
2. **MEDIUM (Resolved by Clarification) — Interrupted Run Lifecycle**:
   - *Resolution*: Historical explanation of `run-326981730c8c6b1edb0aecb1ab062606` preserved without direct database tampering.
3. **MEDIUM (Resolved) — Stale/Invalid Runtime Proof**:
   - *Resolution*: On-device test demonstrated real `WorkflowCommandService`/Room rejection of stale review revision (`Conflict`) and archived category (`CATEGORY_UNAVAILABLE`) with draft remaining pending and empty ledger (`androidTest-stale-runtime-logcat.txt`).
4. **LOW (Resolved) — Scope Inventory Path Discrepancy**:
   - *Resolution*: `changed-paths.txt` line 113 updated to `app/src/test/resources/schema/15.json`, matching all 124 concrete files in the frozen reviewed snapshot.
5. **CRITICAL / BLOCKING (Waived by Owner) — Device Data-Loss Incident**:
   - *Resolution*: Owner waived restoring historical records on the test device; notification listener access and bank allowlist were restored by Main.

---

## Reviewed Candidate Snapshot

The candidate snapshot of 124 changed files relative to base commit `80b3a69a09b028b430235e5f67806104d7ba54b3` remains frozen and unmodified from Round 1:
- **Manifest**: `artifacts/hosted-category-cutover/reviews/CAT1-review-01.manifest.json`
- **Canonical Snapshot SHA-256**: `ec4fe67280f58574c33a69f1e97723bfef711ed5d4280ad8b4d04e18fed84262`

Scope composition (124 paths):
- Added (5): `app/schemas/io.github.triet4p.aegisf.data.LedgerDatabase/16.json`, `app/src/test/java/io/github/triet4p/aegisf/data/CategoryCutoverMigrationTest.kt`, `app/src/test/resources/schema/15.json`, `tools/slm/s8_agent_runtime_skills_v6.json`, `tools/slm/s8_agent_tools_v6.json`.
- Modified (57): build configuration, runtime tools, executor, review DAO, screens, tests, and documentation.
- Deleted (62): obsolete Laya runtime, calibration, sequence, tokenizer, evaluation tests, and v5 tools/skills.

---

## Conclusion & Gate Verdict

- **Review Cycle**: `CAT1-cycle-1`, Round 2 Final
- **Verdict**: **FAIL**
- **Advance**: `false`
- **Owner Decision Required**: `true`
- **Reviewed Snapshot**: `artifacts/hosted-category-cutover/reviews/CAT1-review-01.manifest.json` (`snapshot_sha256: ec4fe67280f58574c33a69f1e97723bfef711ed5d4280ad8b4d04e18fed84262`).
- **Missing Prerequisite**: A valid bearer API credential for OpenCode Go (or an authorized alternative profile) and an intake bank draft are required to execute the hosted category flow on the candidate APK on device.
- **Checkpoint**: No Bronze exact-snapshot checkpoint authorized. Main and the owner must decide credential provisioning or acceptance criteria revision.
