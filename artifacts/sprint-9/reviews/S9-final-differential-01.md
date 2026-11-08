# Sprint 9 Final Differential Review — Cross-Task Integration (S9.1–S9.9 / CAT1)

**Task:** `Sprint9-final` — end-of-sprint cross-task differential deep review (not a per-task evidence gate).
**Attempt:** `Sprint9-final-r1`; **Runtime agent:** `S9FinalDifferential`.
**Reviewed commit:** `1b585b8772e4e578283b32a25ed41f9332ab9784` = current `HEAD` = `refs/heads/main` (verified). Repo `F:/ai-ml/aegis-f/.git`, worktree `F:/ai-ml/aegis-f`. Unstaged Main-owned plan tracking (`docs/PLAN.md`, `docs/sprint-plans/sprint-9.md`), untracked `MANIFEST.sha256` and `artifacts/sprint-9/` observed preserved, not reviewed as product.
**Established ground (not reopened):** per-task evidence-gate PASSes `artifacts/sprint-9/reviews/S9.1..S9.8-review-01.md` with Bronze checkpoints `9d44632 b395038 e0e6bbc e660b63 c74d0c6 820eac9 b453f95 1cd84dd`; S9.9 gate PASS `artifacts/sprint-9/reviews/S9.9-review-01.md` (cycle `S9.9-cycle-1` round 1, snapshot `650e07fd…`) and executor-owned checkpoint record `artifacts/sprint-9/checkpoints/S9.9-checkpoint-01.md` (33 owned paths, base `1cd84dd…`, parent-verified, index clean, foreign state excluded).
**Evidence consulted:** S9.9 gate report + manifest + checkpoint; worker `artifacts/sprint-9/workers/S9.9-a11.md`; `docs/sprint-plans/sprint-9.md` (incl. acceptance + boundaries + current status); `docs/PLAN.md` Sprint 9 section; `README.md`, `CHANGELOG.md`, `docs/sprint-8-agent-contract.md`, `docs/sprint-8-agent-interface-redesign.md` at the reviewed commit.
**Verdict:** **PASS** — zero actionable findings, no material open question. `advance: true`.
**Confidence:** 0.83. Sample-based differential review of passed gates per assignment; no device/procedure re-execution (no phone, no ADB, no trials; 3/5 live-trial allowance remains unspent and unclaimed).

## Cross-boundary probes performed (source-read at 1b585b8)

1. **a10 `make_time_anchor` identity propagation (AgentToolRuntime.kt:475–565, 2247–2278, 2295–2330).** The anchor carries the single distinct event id of its components (`boundEventId`, l.540) plus the exact source-span key (`occurrenceKey`, l.541–545) and the capture binding (l.491–496). `record_event` re-binds with `bindingCompatible(source, anchor)` (l.731–737) and `bindingStillValid` (l.2225). Traced consequences: an anchor built from event X's clarification cannot bind to event Y's source (id or key mismatch → `REFERENCE_KIND_MISMATCH / anchor_from_selected_occurrence`), cannot bind to a yet-unwritten span via another event's clarification (effectiveKey mismatch), and cannot survive event deletion/re-revision (`bindingStillValid` → `STALE_OBSERVATION`). Arbitrary cross-occurrence refs are structurally refused; the write-side hole a10 closed is not re-opened anywhere: `ANCHOR` kind is issued only at l.549 and l.2311 and consumed only at l.731. Mixed clarification + occurrence components resolve to the single occurrence key and the shared event id, exactly what the a11 phone flow exercised (event #49 revision 1).
2. **Multi-event identity / time / provenance.** `captureReferenceYear`/`captureReferenceDate` resolve only from the capture's own stored instant and zone (`AgentToolRuntime.kt:2643–2666`); relative-day offsets travel with the anchor and resolve again at the write; a year-less date without a truthful capture instant is clarified, not guessed. Receipt time never enters purchase identity (S9.2 gate); arrival reconciliation matches `draft.amountVnd` + `draft.statedDate == event.anchorDate` only (ArrivalReconciliationCoordinator.kt:243).
3. **Edit/delete/re-extract + arrival reconcile + category invalidation.** `invalidateDerivedReadings` (EventContextRepository.kt:488–499) on capture save/delete and `reopenHostedReviews` on link propose/accept/reject/unlink (l.378, 418, 446, 471) clear only `origin=HOSTED` pending choices and advance `reviewRevision` in the same statement (BankDraftReviewDao.kt:218–232); `USER` choices and terminal drafts survive (enum contract PendingBankDraftEntity.kt:158–191; legacy rows default NONE, LedgerDatabase.kt MIGRATION_17_18). Catalog invalidation is real: `CategoryDao.deletePermanently` detaches pending reviews, resets origin, bumps revision (CategoryDao.kt:161–187), while confirmed transactions keep the category (TransactionEntity FK RESTRICT) — the CHANGELOG claim "changed note/link/draft/category catalog invalidates an older choice" is backed by code, not just tests.
4. **Unique complete match / cardinality.** `countWithAmountAndDate` counts only still-candidate drafts (PendingBankDraftDao.kt:159–176) so a dismissed or confirmed draft no longer poisons later uniqueness; `LinkEvidencePolicy.candidateMatches` uses the same row set. Rejected pairs are not resurrected: `proposeLink` returns `Rejected` for prior refusal (EventContextRepository.kt:350–352), the arrival loop simply continues, and `findUnmatchedForReconciliation` never hides an event whose link was refused (NoteEventDao.kt:230–252). Under-counting cannot happen (count ≥ retrieval rows) so detection failure is fail-safe toward ambiguity, i.e. review, never silent attach.
5. **Bounded scope 500/32 + competing arrivals / restart / replay / rejection.** Arrival ops are revision-identity keyed (`event:$id:$rev`, `draft:$id`), inserted in the same transaction as the trigger, page-bound (32 events/page, cursor committed with each page, ArrivalReconciliationCoordinator.kt:186–236, 280–285), and stale-check before every batch; >500 pending drafts or an incomplete first-32 session grant refuses automatic attachment (`QueryPageRequest.MAX_PAGE_SIZE`; QuickCaptureScreen.kt:264–275 `limit=32, draftScopeComplete`), matching the plan's "either incomplete scope refuses". Restart recovery is a plain `resumePending()` re-scan bounded by the same page caps.
6. **Hosted zone/revision checks (WorkflowCommandService.kt:769–810).** Agent-session category saves re-check context fingerprint AND catalog digest in the same transaction, refuse an unassignable category, and store origin HOSTED only for agent scope; the user path stores USER. S9.9's "origin becomes USER at explicit confirm" is the same mechanism on the confirm form.
7. **Chat/result truth.** Durable run state, outcome cards, and cancellations are Room-backed; the two known UI wording defects (stale "Generating with…", generic cancel text) are recorded separately below. Follow-up handles for unissued refs restate only this run's issued handles, one newest per subject, never the refused string, bounded 8/kind (`AgentReferenceRegistry.issuedFor`/resolve, AgentInterfaceContract.kt:475–595) — no scope widening, no spelling mapping.
8. **Provider budget / masking / protocols / settings migration.** `HostedGenerationBudget.maxOutputTokens` optional, null = field omitted (`HostedGeneration.kt`); three adapters serialize the limit only when non-null and in the protocol's own field (`max_completion_tokens` / `max_output_tokens` / Gemini); `MIN_EXPLICIT_OUTPUT_TOKENS = 32_768` gate at validatedConfig, and legacy 8192/4096 profiles read as model default via `storedOutputTokens()` with stored document left untouched (HostedProviderSettingsStore.kt:22, 40–45, 247–256). Cache identity is value-free and budget-frozen (HostedCacheIdentity.kt). `TIME_BUDGET` exhaustion is terminal, not retried (AgentRunFailure.kt:123–131) — matches the a10/a11 disclosed limitation that the 12-turn budget is shared across clarification attempts within one run.
9. **Permanent tests / harness isolation.** All three device-facing permanent suites hard-assert the isolated package before any data access (`S99WorkflowPhoneInstrumentedTest.kt:70`, `S93ReversibleLinkPhoneInstrumentedTest.kt:21`, `S98DeviceFixtureInstrumentedTest.kt:37`), stage-gate live work behind `-e s99_live yes`, allocate fresh per-run Room files and delete them (`s99_<tag>_<runId>`), so the documented incident path (bare `am instrument`) fails closed on any wrong package and cannot touch owner rows; the incident ledger `INCIDENT-2026-11-08.md` + fail-closed helper `tools/s99i.sh` rejection proof close the repeat vector.
10. **Cross-checks that worsened nothing elsewhere:** arrival auto-link re-opening a HOSTED category on the newly linked draft (fail-safe, user choice preserved), incomplete arrival op outcomes (no attach), `issuedFor` eviction answering unknown rather than re-issuing (`AgentReferenceRegistry`), schema/`ProviderJson` ref-depth bound 32.

## Differential findings

**Actionable findings: none.** No cross-task contract break, regression, privacy, authority, measurement or maintainability defect was evidenced at the reviewed commit.

### Probed and dismissed (with reasons)

- Anchor identity widening after the a10 fix — refusals are structural at both issue and consume sites (see probe 1). Dismissed.
- Rejected-pair resurrection across arrival replay/restart — refused at propose + filtered at page query (probe 4). Dismissed.
- Cardinality counting rejected-link drafts — conservative over-count only, pushing the pair to review rather than attaching; cannot silently attach. Minor UX note, not a defect. Dismissed (noted below).
- `processDraftPage` cursor arithmetic off-by-one — cursor is `page[MAX_EVENT_PAGE-1].id` on a 33-row fetch, i.e. exactly the last processed row. Dismissed.
- Undated/amountless event auto-match — `continue` on `amountVnd`/`anchorDate` mismatch covers both nullities. Dismissed.
- `countWithAmountAndDate` vs. `LinkEvidencePolicy` row-set divergence — same PENDING/unconfirmed filter as the policy. Dismissed.

### Residual risks / known limitations (disclosed, none blocks Sprint 9 acceptance)

- **R1 (LOW, unverified reachability — no normal-state producer):** `captureReferenceYear` falls back to `TimeZone.getDefault()` when a stored capture instant exists but `captureTimezone` is unusable (AgentToolRuntime.kt:2643–2656). No documented save path can produce an invalid zone id (UIs store `TimeZone.getDefault().id`), so this only bites a corrupted/legacy row near a calendar-year boundary, where a stated year-less date could resolve to the wrong year; the day-word resolver itself refuses without a usable zone (changelog-consistent). Fix if ever reachable: fail the year resolution when the zone is unusable, same rule as `captureReferenceDate`. Discriminating check: unit test `captureReferenceYear` with a valid instant + invalid zone string asserting null (clarify, don't guess).
- **R2 (pre-existing, disclosed in S9.9 gate):** HTTP 429 `Retry-After` headers not parsed into backoff (`CanonicalAgentRetryPolicy`); FX/provider semantics in `ProviderWireSupport`.
- **R3 (UI, disclosed):** stale "Generating with …" progress line after terminal run state until repaint; generic cancel-failure copy while durable state is `USER_CANCELLED`.
- **R4 (product semantics, disclosed):** single run-level 12-turn budget shared across clarification resume attempts (trial 1 used 9 turns; trial 2 used 3). Bounded behavior completed within budget; not a defect.
- **R5 (environment, disclosed, verifiable at next owner session):** three isolated provider profiles lost in attempt a3 remain lost; deliberately preserved residues on the isolated package: accepted links, confirmed endpoint records 32/33, dismissed synthetic residue #70–73, immutable captures; original foreign ledger rows 1/2 intact.
- **R6 (measurement honesty — verified):** all reported timings include worker/adb observation overhead; the 18-form manual comparator includes worker UI learning; no human-speedup factor is claimed anywhere in worker or gate records; fresh vs historical coverage is explicitly labelled (`S9.9-a11.md` fresh/historical tables); 2 of 5 authorized live trials were used and the remainder was conserved. README/CHANGELOG/agent-contract/interface-redesign docs at the reviewed commit state only actually-implemented defaults (output-budget, issued-handle inventory, anchor binding) — no overstated capability found.

### Owner-acceptance gaps vs earlier incidents — blocker determination

| Item | Status | Sprint 9 blocker? |
|---|---|---|
| RetryAfter propagation loss | Pre-existing, disclosed (S9.9 gate nonblocking #3) | No — unchanged capability, no criterion cites it |
| Stale post-success "Generating…" / cancel wording | UI-only, durable state correct | No — disclosed known limitation |
| Run budget across clarifications | Bounded, disclosed, completed within | No |
| Original profile loss (a3) | Disclosed, not falsely claimed restored | No |
| Immutable test residue (accepted links, records 32/33, dismissed residue) | Disclosed, labelled, no dashboard/queue contamination (all consumers filter PENDING/confirmed only) | No |
| Keyboard (Gboard-only) + bare-instrumentation incidents | Ledgered + fail-closed helper; owner package untouched | No — consolidated phone acceptance below is the check |

### Proposed compact consolidated owner checklist (proposal only; acceptance remains owner-only)

1. On the isolated phone only: open Chat — confirm run cards show the two linked drafts, the saved categories, and the two explicit Confirms; dashboard reads 110,000 VND / 4.
2. Confirm foreign baseline: records 1/2 (20,000 VND each) present, untouched.
3. IME check: visible English (US) globe on Gboard; no settings page visited, no system/developer/USB/network toggles mutated.
4. Provider settings: active profile reads model-default output limit (no explicit cap), no secret left on the device.
5. Manual entry, review inbox, and dashboard still work with the provider profile removed (offline path).
6. Ledger/drafts on the owner package `io.github.triet4p.aegisf` show no s812evidence residue.

## Return contract

```json
{
  "task_id": "Sprint9-final",
  "attempt_id": "Sprint9-final-r1",
  "agent_id": "S9FinalDifferential",
  "report_path": "F:/ai-ml/aegis-f/artifacts/sprint-9/reviews/S9-final-differential-01.md",
  "sprint": 9,
  "evidence_reports": [
    "artifacts/sprint-9/reviews/S9.9-review-01.md",
    "artifacts/sprint-9/reviews/S9.9-review-01.manifest.json",
    "artifacts/sprint-9/checkpoints/S9.9-checkpoint-01.md",
    "artifacts/sprint-9/workers/S9.9-a11.md",
    "docs/sprint-plans/sprint-9.md",
    "docs/PLAN.md"
  ],
  "reviewed_commit": "1b585b8772e4e578283b32a25ed41f9332ab9784",
  "verdict": "PASS",
  "advance": true,
  "confidence": 0.83,
  "actionable_findings": []
}
```

This review neither grants owner acceptance nor closes Sprint 9 or opens Sprint 10. Counts preserved: S9.9 cycle-1 round 1 PASS stands; no task counts reset or consumed.
