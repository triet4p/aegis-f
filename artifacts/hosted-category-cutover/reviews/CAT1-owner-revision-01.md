# CAT1 Evidence Review Report — Owner Revision Final

## Identity and Review Context

- **task_id**: `CAT1`
- **reviewer attempt_id**: `CAT1-review-a1`
- **agent_id**: `HostedCategoryReview`
- **worker_attempt_id**: `CAT1-a1`
- **worker agent_id**: `HostedCategoryCutover`
- **review_cycle_id**: `CAT1-owner-revision-1`
- **review_round**: `1`
- **linked_prior_cycle**: `CAT1-cycle-1` (Round 1: `changes_requested`; Round 2: `FAIL`, `blocked: owner_decision_required`)
- **worker_artifact_path**: `artifacts/hosted-category-cutover/workers/CAT1-a1.md`
- **round2_evidence_path**: `artifacts/hosted-category-cutover/evidence/CAT1-a1/round2-verification.md`
- **incident_evidence_path**: `artifacts/hosted-category-cutover/evidence/CAT1-a1/incident-owner-data-loss.md`
- **main_permission_evidence_path**: `artifacts/hosted-category-cutover/state/notification-permissions-main.md`
- **report_path**: `artifacts/hosted-category-cutover/reviews/CAT1-owner-revision-01.md`
- **manifest_path**: `artifacts/hosted-category-cutover/reviews/CAT1-owner-revision-01.manifest.json`
- **base_commit_sha**: `80b3a69a09b028b430235e5f67806104d7ba54b3`
- **target_ref**: `refs/heads/main`
- **snapshot_sha256**: `ec4fe67280f58574c33a69f1e97723bfef711ed5d4280ad8b4d04e18fed84262`
- **verdict**: **PASS**
- **advance**: `true`
- **owner_decision_required**: `false`

---

## Executive Summary & Owner Revision Context

Task CAT1 implements the hosted category cutover for Aegis-F: removing active Laya and its English translation bridge from the application and enabling the hosted workflow model to select an existing category from current choices as part of normal draft proposal reasoning, without a separate on-device classifier or category-only tool round-trip.

### Prior Cycle `CAT1-cycle-1` History
1. **Round 1**: The candidate implementation across 124 scoped paths was assessed. Four findings were identified:
   - Device proof for Foods and Transport ran on a build prior to removing the unused ONNX Runtime dependency; the candidate APK was installed but unexercised.
   - Force-stopped retry run `run-326981730c8c6b1edb0aecb1ab062606` was left in `RUNNING` in SQLite.
   - Missing on-device/runtime proof of invalid/stale proposal rejection.
   - Candidate scope inventory discrepancy in `changed-paths.txt` (directory entry instead of concrete file `app/src/test/resources/schema/15.json`).
   Outcome: `changes_requested` (`advance: false`), consuming Round 1.
2. **Round 2**: Worker corrected the scope inventory file path (124 concrete paths) and produced on-device runtime proof of stale revision (`Conflict`) and archived category (`CATEGORY_UNAVAILABLE`) rejection (`androidTest-stale-runtime-logcat.txt`). However, during test execution, the AGP test runner uninstalled the package during cleanup, clearing the physical device app database (`incident-owner-data-loss.md`). Furthermore, entered `OPENCODE_GO_API_KEY` returned HTTP 401 (`Credential rejected (HTTP 401)`), preventing candidate APK live flow execution. Round 2 closed as `FAIL` (`blocked: owner_decision_required`).

### Explicit Owner Decisions & Criteria Revisions
Per `docs/hosted-category-cutover.md` (lines 17–21), the owner explicitly reviewed the measured state and enacted the following binding decisions:
1. **Waiver of Lost Development Database Restoration**: The owner accepted that old development data on the physical device need not be restored. This waives physical restoration of lost test records on the test device, while migration correctness, future data preservation, and historical evidence integrity remain strictly enforced. Main verified notification access, listener connection, battery exemption, and restored the two previously chosen bank packages (`com.vietinbank.ipay` and `com.VCB`) through the app UI (`artifacts/hosted-category-cutover/state/notification-permissions-main.md`).
2. **Acceptance of Existing Evidence ("Chấp nhận bằng chứng hiện có")**: The owner explicitly accepted the existing pre-final build Foods/Transport hosted executions combined with final APK identity/UI verification and on-device stale/invalid rejection proof, waiving the requirement for a repeated hosted run on the final APK. The missing live bearer credential (HTTP 401) is documented as an explicit limitation rather than a blocking gate failure.
3. **Authorization of Reassessment Cycle**: Main authorized this new cycle `CAT1-owner-revision-1` round 1 to assess the unchanged candidate under the revised contract, linked to prior cycle outcomes without retroactively rewriting historical records.

---

## Evaluation of Revised Acceptance Criteria

| Criterion | Contract Requirement & Owner Revision | Observed Evidence | Verdict |
|---|---|---|---|
| **1. Category choices & source context** | Current category choices and source context available to hosted workflow; declarations, skills, prompts consistently describe direct category selection rather than Laya or translation. | `draftObservation` emits `category_choices` with opaque refs; `draftContextObservation` emits masked accepted linked readings; `s8_agent_tools_v6.json` declares `update_draft_review`; `s8_agent_runtime_skills_v6.json` defines `draft-review` skill (v1). Zero active Laya or translation references in active declarations. | **PASS** |
| **2. Category persistence & validation** | Persisted through ordinary workflow mutation, validated locally for identity, scope, revision. Invalid/stale proposals have no unintended effects. No ledger Confirm. | `WorkflowCommandService.selectDraftCategory` validates scope, review revision, and category assignability; `BankDraftReviewDao.saveReviewCategory` updates `reviewCategoryId` and increments revision without Confirming or inserting transaction. On-device test demonstrated refusal of stale review revision (`Conflict`) and archived category (`CATEGORY_UNAVAILABLE`), draft remaining pending, and ledger remaining empty (`androidTest-stale-runtime-logcat.txt`). | **PASS** |
| **3. Laya removal & manual review** | Remove active Laya runtime, provisioning, ONNX dependency, classifier/translation tools, suggestion UI. Review queue and manual flow work offline. | Dropped Laya runtime classes, ONNX Runtime dependency, tokenizer asset, provisioning screen, and manual suggestion machine. `ReviewQueueDao` orders by conflict (0) -> unresolved category (1) -> ready (2) without model confidence. Manual category editing and UI verified on device. | **PASS** |
| **4. Owner data & provenance preservation** | Preserve owner database records, category identities, draft/event/link provenance and ledger totals through migration 15->16 and same-signer update. Retain historical qualification results. *(Revised: owner waived old development data restoration on test phone; migration correctness and historical integrity enforced).* | Migration 15->16 drops `classification_work`, preserves `classification_snapshots` read-only, and adds nullable `selectedCategoryId` column; verified by `CategoryCutoverMigrationTest` against exported v15 Room schema. Historical qualification JSON intact. Main verified permissions and restored bank allowlist (`notification-permissions-main.md`). Old dev record loss waived by owner. | **PASS (under owner waiver)** |
| **5. Privacy & streaming trace** | Retain hosted masking, neutral token reminders, output guards, streaming, and local session traces without credentials. | Masking boundary, neutral tokens (`⟦V-scope-seq⟧`), and output guards verified in `AgentMaskedValueUsabilityTest` and `ProductionTraceTest`. Session traces remain local without credential exposure. | **PASS** |
| **6. Behavioral tests & documentation** | Update behavioral tests and docs; remove obsolete incidental tests. Exercise changed behavior without phone. | 533 unit tests pass (0 failures, 3 environment skips); `compileDebugAndroidTestKotlin` succeeds; documentation updated in `README.md`, `CHANGELOG.md`, and S7/S8 docs. | **PASS** |
| **7. Device flow & runtime proof** | Install/update main app without clearing data; exercise actual bread-note/draft flow with configured key; observe Foods, full trace, no Confirm. Exercise another category and invalid/stale handling with suitable runtime proof. *(Revised: owner accepts pre-final hosted Foods/Transport runs + candidate APK identity/UI + on-device stale/invalid proof; waives repeated final-APK hosted run).* | Foods (`run-101422f1263960851b28dd9d1ba27545`) and Transport (`run-03f1f9fe6e6a50250303404f076caff7`) runs verified with review category stored, pending status, empty ledger, and full local trace. Final APK candidate (`db6a5209...`) verified installed on device. Stale/invalid rejection verified on device. Interrupted run explained. Limitations recorded. | **PASS (under revised criterion 7)** |

---

## Explicit Operational Limitations & Disclosures

In accordance with Main's direction and the revised contract, the following limitations are explicitly noted:
1. **Missing Bearer Credential on Final APK (HTTP 401)**: When validating `OPENCODE_GO_API_KEY` from `.env` through the installed candidate APK UI for profile `OpenCode Go · DeepSeek V4.1 Flash`, the provider returned `Credential rejected (HTTP 401). Replace it and retry. No profile was changed.` The candidate APK itself did not execute a live hosted tool-call round-trip. Acceptance relies on the owner-authorized combined evidence (prior-build identical category-path execution + candidate APK installation/UI verification).
2. **No Intake Drafts on Fresh Device Database**: Following the AGP test uninstallation cleanup incident (`incident-owner-data-loss.md`), the device database was reinitialized empty (schema 16). The owner explicitly waived recreating or restoring old draft/event rows.
3. **Interrupted Run Lifecycle**: Run `run-326981730c8c6b1edb0aecb1ab062606` was an observe-only retry interrupted by app force-stop. Its durable row was subsequently cleared with the database during the incident; no terminal state was fabricated and no recovery feature was added.

---

## Reviewed Candidate Snapshot Verification

The candidate snapshot of 124 changed files relative to base commit `80b3a69a09b028b430235e5f67806104d7ba54b3` was independently recaptured and verified against the worktree bytes with zero mismatches:
- **Manifest**: `artifacts/hosted-category-cutover/reviews/CAT1-owner-revision-01.manifest.json`
- **Canonical Snapshot SHA-256**: `ec4fe67280f58574c33a69f1e97723bfef711ed5d4280ad8b4d04e18fed84262`

### Scope Composition (124 Concrete Paths)
- **Added (5)**:
  - `app/schemas/io.github.triet4p.aegisf.data.LedgerDatabase/16.json`
  - `app/src/test/java/io/github/triet4p/aegisf/data/CategoryCutoverMigrationTest.kt`
  - `app/src/test/resources/schema/15.json`
  - `tools/slm/s8_agent_runtime_skills_v6.json`
  - `tools/slm/s8_agent_tools_v6.json`
- **Modified (57)**: build scripts, activity, runtime tools, workflow executor, skill catalog, DAOs, entities, screens, unit/Robolectric tests, and documentation.
- **Deleted (62)**: obsolete Laya runtime, calibration, sequence, tokenizer, model provisioning, evaluation tests, and v5 tools/skills.

---

## Conclusion & Gate Verdict

- **Task ID**: `CAT1`
- **Review Cycle**: `CAT1-owner-revision-1`, Round 1
- **Linked Prior Cycle**: `CAT1-cycle-1` (Rounds 1 & 2 closed FAIL / blocked)
- **Verdict**: **PASS**
- **Advance**: `true`
- **Owner Decision Required**: `false`
- **Reviewed Snapshot**: `artifacts/hosted-category-cutover/reviews/CAT1-owner-revision-01.manifest.json` (`snapshot_sha256: ec4fe67280f58574c33a69f1e97723bfef711ed5d4280ad8b4d04e18fed84262`).
- **Checkpoint Suitability**: The reviewed snapshot is frozen, verified, and suitable for Bronze-only exact-snapshot checkpoint execution.
