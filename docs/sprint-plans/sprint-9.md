# Sprint 9 — Multi-Event Notes, Safe Matching and Agentic Review Preparation

## Sprint Goal

A user saves one natural-language note describing several purchases; the app interprets events, proposes or safely attaches relevant bank drafts, invokes Laya and prepares an uncertainty-first review queue. Corrections, unmatched events and delayed notifications remain first-class states.

## Status and Dependencies

- Status: Planned. Dependencies: Sprints 5–8 provenance, application API, four screens, proactive classification and real agent-capable runtime/tool evidence.
- Workflow: **Full**. Each **S9.1–S9.9** task requires independent evidence PASS and a separate Bronze-only exact-snapshot checkpoint before the next independent task; then assigned cross-feature differential review. No shared batch assumed approved. Explicit scoped role/checkpoint/commit authority required before dispatch; no push.
- Worker/reviewer ADB checks authorized; one consolidated user pass at sprint end. Evidence root: `artifacts/sprint-9/`.

## Acceptance Criteria

- One note can produce several source-backed editable event interpretations without creating expenses, inventing missing financial fields or copying every event into every draft.
- Resolve relative times against the note's capture timestamp/timezone; distinguish event time, bank transaction time and notification receipt time. Preserve ambiguity instead of selecting an arbitrary day/period. Optional-language limitations are visible; manual split/link remains available for any input.
- Candidate retrieval is bounded and deterministic against the actual app API. Missing matches, multiple plausible candidates and amount/type/account/time conflicts do not produce silent attachment.
- Automatic attachment, where enabled by explicit app policy, changes only reversible draft context and requires a unique evidence-supported match; model language certainty and Laya confidence do not authorize it. Proposed/automatic/manual links are visibly distinguished.
- Laya runs automatically on the resulting effective context, and uncertainty-ranked review uses its fresh confidence while unresolved matching remains higher priority.
- User correction, rejection, note edit/deletion, link undo and process restart cannot silently duplicate events/links/ledger entries or override previously confirmed financial facts.
- User-facing Chat reports actual saved events, applied/proposed links, unresolved questions and completed versus cancelled operations, not fabricated success or a verbose raw tool transcript.

## Ordered Atomic Tasks

- [ ] **S9.1 — Implement source-backed event extraction.** Connect the validated agent to durable captures; produce bounded events with source spans, normalized descriptions and optional time/merchant/amount evidence. Use capture time/timezone for relative anchors and retain uncertain interpretations. Acceptance: the two-purchase example yields two traceable events; unrelated instructions in note text cannot widen permissions; missing amounts remain absent and low-quality output leaves an editable saved capture.
- [ ] **S9.2 — Implement candidate retrieval and match evidence.** Query eligible pending drafts using supported time intervals and available account/amount/merchant evidence; handle delayed/midnight notifications and explicit user corrections. Define missing/unique/ambiguous/conflicting outcomes and bounded ranking rationale independent of category confidence. Acceptance: known fixtures produce the intended candidate set, exclude confirmed/dismissed/incompatible drafts and do not treat receipt time alone as proof of purchase identity.
- [ ] **S9.3 — Implement reversible link policy.** Enforce unique evidence-supported auto-attachment conditions, proposed-link confirmation for ambiguity, link revision/cardinality and rejected-link memory. A match can be suggested without being accepted; user can choose a different candidate. Acceptance: two same-amount nearby purchases remain ambiguous, manual rejection is not recreated by retry, incorrect attachment can be undone, and no source/amount/ledger entry is rewritten.
- [ ] **S9.4 — Reconcile arrival order and resumable work.** Trigger bounded reconciliation for affected unmatched events when notes or notifications arrive, with durable operation identities, coalescing and expected revisions. Preserve user-edited events and stable identity through re-extraction; model regeneration cannot simply append duplicates. Acceptance: note-first and notification-first converge to the same allowed links; retries, listener replay, crash/restart and late results do not resurrect rejected/dismissed work or duplicate captures/events.
- [ ] **S9.5 — Orchestrate link-to-Laya-to-queue.** Apply accepted context through the shared command boundary, invalidate relevant predictions, request Laya and expose fresh queue state. Coordinate model admission/release without blocking intake/manual UI. Acceptance: fuel and snack events supply the right draft context; uncertain links stay high priority even when a category score is high; a relink changes only affected predictions and never overwrites a user category selection.
- [ ] **S9.6 — Deliver actionable Chat results and clarification.** Show event cards with source excerpt/time interpretation, candidate links, actual operation state and explicit actions to clarify/edit/accept/reject/retry/cancel. Follow-up references must resolve within visible scope; ambiguous 'that one' asks rather than editing an arbitrary draft. Acceptance: a user can repair an extraction or link without navigating through each transaction, inspect what changed and resume saved unresolved captures after restart.
- [ ] **S9.7 — Complete correction, undo and confirmed-record boundaries.** Implement amendment/deletion of saved captures and event/link changes with provenance/retention semantics and affected-classification invalidation. Keep finalized financial records unchanged until an explicit user correction flow. Acceptance: deleting or revising a linked note cannot silently erase ledger description/history, break references or leave stale high-confidence ranking; undo reports conflicts instead of overwriting newer user work.
- [ ] **S9.8 — Validate optional language/profile boundaries.** Run the implemented workflow with the offered model profiles and supported language modes, including the user's Vietnamese fuel/Circle K example when a profile claims that capability. Clearly surface unsupported interpretation and immediate manual fallback. Acceptance: weaker/instruct-only selections cannot invoke unsupported automation, no language eligibility gate blocks saving notes, and optional multilingual capability is advertised only where actual extraction/matching/tool results support it.
- [ ] **S9.9 — Prove the complete workflow and product benefit.** On the phone, use a seeded multi-event note with matching, ambiguous and missing bank drafts; replay both arrival orders, confirm reviewed items, and reconcile dashboard totals exactly. Exercise transfers, duplicate notifications, cancellation, stale changes and unsafe text. Compare time/taps/description entry and observed wrong-link/override rates against manual capture/review on the same fixture. Update user guidance, authority/capability limits and reviewed evidence; no benchmark-only value claim.
  Acceptance: correctly supported matches and category context reach review in both arrival orders, ambiguous/unmatched events remain editable, retries create no duplicates, and confirmed exact totals match the fixture with raw-backed workflow measurements.

## Required Verification

Use actual selected model generation and Laya on isolated phone data. Baseline scenario: fuel at 15:00 and snack purchase at 15:15 on the captured day, two eligible bank drafts and a misleading nearby candidate. Prove source spans, normalized time, independent links and correct review context; ambiguous evidence must not auto-attach. Also cover a note with no notification, delayed receipt after midnight, missing amount, two candidates with equal amounts, prior user rejection and edits during inference. Recreate process at capture/extraction/link/classification boundaries, then confirm once and verify exact dashboard drill-down.

## Decisions / Boundaries

- No autonomous ledger confirmation. Agent financial/category proposals remain reviewable; Laya confidence ranks work, not permission.
- Already manually recorded note events are excluded from automatic attachment. A later matching bank draft is a possible duplicate for user review, not a reason to re-record, silently merge or remove the prior ledger entry.
- A single bank notification representing several purchases, split-payment allocation or multiple drafts jointly settling one note event requires explicit manual reconciliation. Do not invent automatic split/group support or silently lose unmatched residual events in this delivery.
- Conversational ledger Q&A and generic device automation are not prerequisites. Chat exists to reduce capture/review work.
- Parent: [Project Plan](../PLAN.md). Previous: [Sprint 8](sprint-8.md). Next: [Sprint 10](sprint-10.md).
