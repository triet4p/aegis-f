# LT1 Evidence Review Report — Round 2 Final

## Identity and Review Context

- **task_id**: `LT1`
- **reviewer attempt_id**: `LT1-review-a1`
- **agent_id**: `LayaBridgeReview119`
- **worker_attempt_id**: `LT1-a1`
- **worker agent_id**: `LayaEnglishBridge118`
- **review_cycle_id**: `LT1-cycle-1`
- **final review_round**: `2`
- **worker_artifact_path**: `artifacts/laya-english-bridge/workers/LT1-a1.md`
- **report_path**: `artifacts/laya-english-bridge/reviews/LT1-review-01.md`
- **manifest_path**: `artifacts/laya-english-bridge/reviews/LT1-review-01.manifest.json`
- **base_commit_sha**: `8518676c3e8f7475c1a5140275224fe286cb4a92`
- **target_ref**: `refs/heads/main`
- **snapshot_sha256**: `5193fd3caf921fbfab67894383ab9c464c3d4de99aeb86d9cde64e2a9cefc348`
- **verdict**: **PASS**
- **advance**: `true`
- **owner_decision_required**: `false`

---

## Executive Summary & Progress Delta Across Rounds

Task LT1 implements a derived English classification input bridge for the locally installed English Laya model (`receptron/Laya@68f27dfe`). When an English translation is requested, the hosted model translates the complete authorized source context (draft description and all active linked readings) while preserving displayed neutral value tokens. The local runtime validates context handles and token counts, rehydrates values locally, composes an English-only primary input for Laya without duplicating raw Vietnamese text, and stores a distinct snapshot identity under database schema v15.

### Round 1 Outcome: `changes_requested` (`advance: false`)
On Round 1, candidate review identified two actionable carrier/scope findings:
1. **HIGH — Missing v5 runtime assets from candidate manifest**: `tools/slm/s8_agent_runtime_skills_v5.json` and `tools/slm/s8_agent_tools_v5.json` are critical runtime assets referenced by `app/build.gradle.kts` (`copyS8AgentRuntimeAssets`), `AgentToolRuntime.kt` (`AgentToolDeclarations`), and `WorkflowSkillCatalog.kt`. They were untracked in git and omitted from `artifacts/laya-english-bridge/workers/LT1-a1-manifest.sha256`. An exact-snapshot checkpoint from that manifest would fail Gradle compilation.
2. **MEDIUM — Erroneous inclusion of v4 assets in candidate manifest**: `tools/slm/s8_agent_runtime_skills_v4.json` and `tools/slm/s8_agent_tools_v4.json` were listed in `LT1-a1-manifest.sha256` despite containing only accidental trailing EOF newline/CRLF edits with zero semantic diff against base `8518676c3e8f7475c1a5140275224fe286cb4a92`.

### Round 2 Resolution (Current Candidate)
Under authorized Round 2, worker `LayaEnglishBridge118` applied the requested carrier corrections:
- Both v4 files (`tools/slm/s8_agent_tools_v4.json`, `tools/slm/s8_agent_runtime_skills_v4.json`) were cleanly restored to match base `8518676c3e8f7475c1a5140275224fe286cb4a92` exactly (`git diff 8518676c3e8f7475c1a5140275224fe286cb4a92 -- tools/slm/*v4*` produces 0 output).
- `artifacts/laya-english-bridge/workers/LT1-a1-manifest.sha256` was updated to include both v5 assets (`tools/slm/s8_agent_runtime_skills_v5.json` with hash `f519f1f8...`, `tools/slm/s8_agent_tools_v5.json` with hash `5cc93294...`) and to exclude the unmodified v4 assets.
- Worker handoff `artifacts/laya-english-bridge/workers/LT1-a1.md` was updated with the Round 2 submission details and freeze.
- Verification confirms all 42 entries in `LT1-a1-manifest.sha256` exist and match worktree raw bytes with zero hash mismatches.

### Explicit Disposition on v4 vs v5 Declaration Assets
Per Main's direction regarding dead-weight and clean-cutover evaluation:
1. **Production Runtime Consumer**: Clean cutover to v5 is complete and verified. `copyS8AgentRuntimeAssets` in `app/build.gradle.kts` bundles `s8_agent_tools_v5.json` and `s8_agent_runtime_skills_v5.json` into `generated/s8-agent-runtime/assets`, which `AgentToolDeclarations` and `WorkflowSkillCatalog` load at runtime. There is zero production fallback, zero compatibility shim, and no production consumer of v4 in runtime code.
2. **Immutable Replay Consumer**: The v4 files in `tools/slm/` are retained exclusively as immutable test fixtures for historical qualification replay. Specifically, `app/build.gradle.kts` defines `copyS813QualificationAssets` which packages `s8_agent_tools_v4.json` and `s8_agent_runtime_skills_v4.json` into `generated/s813-qualification/assets` for `androidTest` execution. The instrumented suite `S813QualificationInstrumentedTest.kt` (lines 618–625) loads these exact assets and asserts their SHA-256 hashes against `tools/slm/s8_agent_v3_qualification_fixture.json` to prove qualification environment stability. Deleting them would break the qualification asset copy task and fail `S813QualificationInstrumentedTest`.
3. **Disposition**: Retaining the v4 files unmodified at base `8518676c3e8f7475c1a5140275224fe286cb4a92` and excluding them from the LT1 worker candidate manifest is strictly correct.

---

## Detailed Evaluation of Acceptance Criteria

### 1. Translation Fidelity & Dish Loanword Adjudication
- **Exact Sanitized Model Clause**: `Bought bánh mì at [merchant], on [masked source token] approximately [masked source token]-[masked source token]`
- **Semantic Assessment**: The sentence renders the core transaction action ("Bought"), counterparty prepositional phrase ("at [merchant]"), transaction date ("on [masked source token]"), and approximate temporal window ("approximately [masked source token]-[masked source token]") completely and naturally in English.
- **Dish Loanword**: The culinary term "bánh mì" is an established loanword in standard English usage (recognized by Merriam-Webster, Oxford English Dictionary, and American Heritage Dictionary) denoting the Vietnamese baguette sandwich dish, analogous to "croissant", "taco", "sushi", or "pho". Retaining this dish loanword while translating all surrounding narrative verbs, prepositions, and qualifiers is faithful to source meaning and represents natural English usage. Run 25, which mixed untranslated Vietnamese narrative phrases, was correctly rejected and counted as unfaithful. The Round 2 clause is adjudicated as a faithful English rendering.

### 2. Live Classification Quality (`NO_FIT`) vs Technical Bridge Integrity
- **Baseline (Raw Stored Source)**: Foods `0.102`, Transport `0.2226`, unknown `0.6753`, confidence `0.2423` (`NO_FIT`).
- **Run 25 (Mixed First Pass)**: Foods `0.1481`, Transport `0.2644`, unknown `0.5874`, confidence `0.1379` (`NO_FIT`).
- **Run 26 (Misrouted Chat)**: UI intent reset to `INTERPRET_EVENTS`; chat response returned without `classify_draft` tool call. Retained and counted as a misrouted request with no classification or snapshot effect.
- **Run 27 (Final Valid Scoped Correction)**: Foods `0.0889`, Transport `0.1566`, unknown `0.7545`, confidence `0.3464` (`NO_FIT`). Transport ranked highest; unknown remained dominant; Foods did not win.
- **Adjudication**: As established in `docs/laya-english-tooling-bridge.md`, technical acceptance requires a provenance-safe, source-bound translation bridge actually reaching the English Laya runtime with correct tracing and identity. Classification quality is an observed measurement, not an invented guarantee. The owner did not authorize category forcing, threshold manipulation, candidate set alterations, or synthetic overrides. The technical bridge succeeded, and the `NO_FIT` outcome is recorded truthfully.

### 3. Source-Bound Context Handles & Multi-Reading Composition
- `AgentToolRuntime.draftContextObservation` resolves draft references and issues `classification_context_ref` of kind `CLASSIFICATION_CONTEXT`, binding the composite `sourceRevision`.
- The composite digest covers the draft review revision, all active linked event IDs, event link states/revisions, and event capture revisions.
- Supports draft description alone (0 linked events) as well as 1 or multiple linked event readings.
- Tested thoroughly in `AgentToolRuntimeTest.kt` (lines 943–1179) with 2 linked readings (Bread and Coffee), validating that both readings are bound and rehydrated into the local Laya input.

### 4. Privacy Boundary & Neutral Token Preservation
- Outbound tool arguments to the hosted model contain only display-masked text with neutral value tokens of shape `⟦V-scope-seq⟧` carrying lexical hints (redacted illustrations such as `⟦AMT-1⟧` or `⟦DATE-1⟧` in evidence notes are illustrative only, not actual issued token identities).
- `AgentToolRuntime.hostedEnglishInput` counts occurrences of every issued token in `maskedSource` and `translated`. Any dropped or added token immediately raises `SCHEMA_INVALID(preserve_current_source_tokens)`.
- Rehydration (`boundary.vault.rehydrateText`) occurs locally inside the host boundary immediately before passing text to the local Laya engine. The hosted model never observes unmasked values.
- Hosted outbound request and tool-call values remain display-masked; local production database trace retains raw events under owner policy, while repository evidence (`main-ui-db-proof.md` and opaque-redacted `main-ui-redacted.png`) strictly excludes private identifiers and credentials.
### 5. Snapshot Identity, Idempotency & Raw Queue Isolation
- **Schema v15 Migration**: `MIGRATION_14_15` in `LedgerDatabase.kt` adds `classificationInputKind` (default `STORED_SOURCE`), `classificationInputIdentity` (backfilled to `effectiveInputDigest`), and nullable `translationSourceRevision`. Tested in `MigrationChainTest.kt` (`version fourteen snapshot migration preserves source baseline and backfills its legacy identity`), verifying in-place migration, legacy identity backfill, and zero data loss.
- **Distinct Snapshot Identity**: Derived translated snapshots store `HOSTED_ENGLISH_TRANSLATION` and input identity `ecdf13c8f016a2ff3d998321ef647cb303c5efa8ab4083a08a164ff7ae016c29`, differing from the baseline stored-source identity `f09936bc7020ceb6be70e0e66a1e1289df28f6274ea9d73204419698e3068b91`.
- **Raw Queue Isolation**: `BankDraftClassificationQueue.freshSnapshotId` accepts cached snapshots only if `classificationInputKind == ClassificationInputKind.STORED_SOURCE`. Tested in `ClassificationWorkLifecycleTest.kt` (`raw queue request does not reuse a fresh translated snapshot`), proving the background intake queue asks local Laya the raw-source question rather than replaying a derived translated snapshot.
- **Idempotency Digest Integrity**: In `WorkflowCommandService.kt`, raw-source classification preserves the exact 4-part dependency digest `(COMMAND_REQUEST_CLASSIFICATION, draftId, description, expectedReviewRevision)`. Translation requests append `HOSTED_ENGLISH_TRANSLATION`, `translatedText`, and `sourceRevision`. The fix resolved the two claim-recovery regressions identified during implementation (`attempt-03-full-suite-failure.log`) without altering legacy test assertions.

### 6. Race Conditions & Staleness Revalidation
- **Pre-inference**: `AgentToolRuntime.hostedEnglishInput` rechecks draft review revision, caller scope on all linked capture IDs, and source revision before accepting a translation.
- **Pre-commit**: Inside `ClassificationSnapshotRepository.savePrediction` (within a Room transaction), dependencies are recomputed. If any draft, link, event, or capture revision changed during inference, `current != expected` triggers, refusing the late write and recording `SnapshotWriteResult.Stale`.
- **Concurrent Snapshot Write Fence**: `snapshotInputIdentity` in `PredictionDependencies` ensures that a concurrent snapshot write by another caller invalidates an in-flight prediction.

### 7. Offline & Manual Operation
- Standard `classify_draft` calls omitting `english_translation` and `classification_context_ref` execute the existing stored-source path completely offline.
- No network access or hosted model is required for manual review or standard intake classification.

### 8. Physical Device & Financial State Safety
- Installed app package `io.github.triet4p.aegisf` on owner's physical device updated via same-signer `adb install -r`.
- After all three requests:
  - Draft 1 remained `PENDING` at review revision 0.
  - Event 2 link remained `ACCEPTED` at link revision 1.
  - Financial ledger `transactions` count remained exactly 0.
  - No user confirmation or category selection occurred.
- Temporary English keyboard layout was removed, Gboard was restored to Vietnamese-only, and secure selected subtype was restored to original `-1680759253`.

---

## Verification Summary Table

| Criterion | Status | Evidence / Verification Method |
|---|---|---|
| Complete Source-Bound Context Handle | **VERIFIED** | `AgentToolRuntime.kt:draftContextObservation`, `AgentToolRuntimeTest.kt` (composite digest over draft + 2 linked events) |
| Neutral Token Preservation & Local Rehydration | **VERIFIED** | `AgentToolRuntime.kt:1171-1185` (token count equivalence check, local `rehydrateText`), `MaskedBoundaryTest` |
| English-Only Local Composition | **VERIFIED** | `ClassificationSnapshotRepository.kt:229` (`eventReadings.isEmpty()`), `ClassificationSnapshotTest.kt:189` |
| Translation Fidelity Adjudication | **VERIFIED** | Clause `Bought bánh mì at [merchant], on [masked source token] approximately [masked source token]-[masked source token]` correctly uses English purchase syntax and valid dish loanword |
| Truthful Provenance & Category Authority | **VERIFIED** | `BankDraftReviewScreen.kt` UI labels ("Machine-translated English · derived input; original source unchanged."), `ClassificationInputKind.HOSTED_ENGLISH_TRANSLATION`, no category forcing |
| Schema v15 In-Place Migration | **VERIFIED** | `LedgerDatabase.kt:MIGRATION_14_15`, `MigrationChainTest.kt` (verifies v14 -> v15 upgrade, legacy identity backfill, zero data loss) |
| Raw Intake Queue Isolation | **VERIFIED** | `BankDraftClassificationQueue.kt:freshSnapshotId`, `ClassificationWorkLifecycleTest.kt` |
| Idempotency Key & Claim Recovery Fix | **VERIFIED** | `WorkflowCommandService.kt:802-835` preserves 4-part raw digest; 684 unit tests pass in `full-tests-and-main-apk.log` |
| Staleness & Concurrency Revalidation | **VERIFIED** | Pre-inference checks in `AgentToolRuntime.kt` + pre-commit transaction checks in `ClassificationSnapshotRepository.kt` |
| Offline / Manual Path Unaffected | **VERIFIED** | Unmodified `classify_draft` executes stored-source offline path without translation arguments |
| Physical Main-App UI & DB Trace | **VERIFIED** | `main-ui-db-proof.md`, `first-main-run.md`, `main-ui-redacted.png`, 3 requests recorded, draft PENDING rev 0, link ACCEPTED rev 1, ledger 0 |
| Keyboard Restored & Privacy Preserved | **VERIFIED** | Gboard restored to Vietnamese-only (`-1680759253`); private DB trace kept on-device; redacted screenshot |
| Candidate Manifest Completeness (R2) | **VERIFIED** | `LT1-a1-manifest.sha256` verified: 42 entries, 0 missing, 0 hash mismatches; v5 assets included, v4 assets restored to base and excluded |
| Reviewer-Owned Exact-Snapshot Manifest | **VERIFIED** | `artifacts/laya-english-bridge/reviews/LT1-review-01.manifest.json` generated; RFC 8785 canonical hash `5193fd3caf921fbfab67894383ab9c464c3d4de99aeb86d9cde64e2a9cefc348` |

---

## Actionable Findings

**None remaining on Round 2 candidate.** (Round 1 findings regarding missing v5 assets and spurious v4 newline changes were fully resolved by worker `LayaEnglishBridge118`).

---

## Unverified / Non-blocking Observations

1. **Observed Classification Quality**: Local Laya evaluated this specific translated input under the current configuration to `NO_FIT` (Foods `0.0889`, Transport `0.1566`, unknown `0.7545`, confidence `0.3464`). This reflects the observed classification outcome on this source, translation, and model configuration without causal generalization beyond the evidence; the technical bridge functions as designed without forcing or tuning category outcomes.
2. **Historical Fixture Retentions**: `tools/slm/s8_agent_tools_v4.json` and `tools/slm/s8_agent_runtime_skills_v4.json` are retained solely for historical `S813QualificationInstrumentedTest` compatibility in `androidTest` assets. They are not active in production.
3. **Excluded Preexisting Files**: `docs/PLAN.md` contains Main-owned planning updates and is intentionally excluded from the worker candidate snapshot.

---

## Review Gate Verdict

**PASS** (`advance: true`)

The candidate satisfies all technical, architectural, privacy, provenance, migration, and fidelity requirements of Task LT1. The candidate source snapshot is bound in `artifacts/laya-english-bridge/reviews/LT1-review-01.manifest.json` (`snapshot_sha256: 5193fd3caf921fbfab67894383ab9c464c3d4de99aeb86d9cde64e2a9cefc348`). Checkpoint execution may be authorized by Main for an explicit fresh Bronze task.
