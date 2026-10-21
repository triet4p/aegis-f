# Sprint 6 — Four-Screen Daily Workflow and Quick Capture

## Sprint Goal

Make the existing ledger and AI value easy to reach through Dashboard, Chat / Quick Capture, Review Notifications and Manual. Deliver useful persistent capture and review without requiring a generative model.

## Status and Dependencies

- Status: Planned. This replaces the old Sprint 6 release plan; every recovery/signing/privacy obligation moves to [Sprint 10](sprint-10.md), not out of scope.
- Dependencies: Sprint 5 migrated data and shared command/query boundaries.
- Workflow: **Light**; mandatory independent evidence-reviewer gate per **S6.1–S6.8**, then assigned end-of-sprint differential review. No automatic checkpoint or commit/push permission. Fresh role authority required.
- Worker/reviewer direct ADB checks authorized; one consolidated final user pass at sprint end. Evidence root: `artifacts/sprint-6/`.

## Acceptance Criteria

- Exactly four primary destinations: Dashboard, Chat / Quick Capture, Review Notifications, Manual. Categories/models/settings and transaction details are secondary routes; back navigation preserves draft/input/filter state.
- Dashboard shows exact confirmed-ledger cards, accessible charts and drill-downs for a chosen period. Drafts, transfers and income are not silently included in spending. Empty data and incomplete bank history are explicit.
- Users can save a note without selecting or creating a transaction. The durable capture history supports editing, deleting under the provenance policy, manually splitting interpretations and attaching/unlinking context from review drafts.
- Before agent delivery, Chat truthfully behaves as a quick-capture journal with manual actions, not a fake conversational model. Saving a note does not fabricate a ledger entry or claim automatic matching happened.
- Review shows bank text and linked user excerpts distinctly, current Laya suggestions/confidence, missing fields and proposed changes. Manual entry and category selection stay fully usable with no models or notification permission.
- Critical flows have clear progress/error/empty states, keyboard/inset support, readable money, accessible labels/touch targets and no color-only status distinctions.

## Ordered Atomic Tasks

- [ ] **S6.1 — Build the four-destination navigation shell.** First capture a reproducible seeded baseline of current manual/review taps/time before changing the UI. Reuse existing Compose design/navigation conventions; preserve detail routes, unsaved input, back behavior and process recreation. Provide clear primary labels and relevant review count. Acceptance: all four surfaces and existing category/settings/detail flows are reachable on the phone; switching/back does not silently discard input or duplicate saves.
- [ ] **S6.2 — Deliver dashboard cards and scoped filters.** Use the shared exact query API for spending/income/transfer distinctions, selected date range and category drill-down. Show confirmed/pending separation and recorded-data coverage limits. Acceptance: seeded month/day-boundary, no-data and archived-category cases display exact VND values; changing a filter consistently updates scope and linked details.
- [ ] **S6.3 — Add dashboard charts and transaction drill-down.** Choose a small useful chart set (category breakdown and spending over time), with textual totals/labels and a list alternative. Tapping a segment/time bucket opens the same query scope; no fabricated insights or division-by-zero states. Acceptance: sums reconcile to cards, selected chart scope and transaction list agree, and large labels/font scaling remain usable.
- [ ] **S6.4 — Deliver persistent quick capture.** Prominent composer accepts unrestricted text and saves a source-backed capture independently of notifications. Show saved/unresolved/linked state and explicit manual versus model availability; no auto-created financial record or fake assistant response. Acceptance: save/retry/restart produces one capture, failed save preserves input, airplane mode works and deleting/editing linked text follows the defined policy.
- [ ] **S6.5 — Deliver manual event/context controls.** Let the user split a capture into editable event interpretations, choose relevant text, browse bounded candidate drafts and attach/reject/unlink through shared commands. Offer an unmatched event as prefilled Manual input for explicit user confirmation with its provenance reference. Preserve the source and show conflicts. Acceptance: one note can supply two different drafts; wrong linking can be reversed, missing/multiple candidates remain unresolved unless explicitly resolved by the user, cash/no-notification entry does not wait forever for matching, and attachment never changes a bank amount.
- [ ] **S6.6 — Make review self-contained and transparent.** Present pending draft financial fields/type, bank source, relevant note excerpts, current probability options/confidence and user selection in one review flow. Support explicit confirm/dismiss/correct without navigating to Manual to supply a description. Acceptance: user can inspect provenance, add context, select a category and confirm once; stale preview conflicts visibly and no draft disappears on a failed command.
- [ ] **S6.7 — Simplify Manual and secondary management.** Keep expense/income/transfer entry, category selection/create/rename/archive/delete and transaction correction accessible with the new shell and shared API. Preserve validation and explicit confirmation for destructive actions. Acceptance: create/edit known VND records with and without models, switch tabs mid-entry, and verify exact history/category behavior after restart.
- [ ] **S6.8 — Verify the actual four-screen experience.** Exercise fonts/accessibility, loading/error/empty states, keyboard, navigation and restoration on the phone. Compare the same manual/review task sequence against the pre-redesign interaction path, recording taps/time without claiming statistical significance. Update user/developer instructions and fix observed UI regressions before final acceptance.
  Acceptance: all four rendered screens and failure/recreation paths are usable on the phone, critical actions remain accessible at larger font sizes, and the seeded before/after interaction record is reproducible.

## Required Verification

Seed expenses, income, transfers, archived categories, unresolved/linked captures and drafts in an isolated device dataset. Complete quick capture → manual split/link → review → confirm → dashboard drill-down with models unavailable, then repeat current Laya selection with the actual model. Restart mid-composition and after save; exercise conflict/error states. Review screenshots/actual rendered surfaces, not only Compose previews or tests.

## Decisions / Boundaries

- No new brand system, external UI framework or decorative chart library is assumed; reuse existing patterns and choose the smallest implementation that meets accessibility and interaction needs.
- Automatic note extraction/matching belongs to Sprint 9. This sprint's manual capture/context path is complete and useful on its own; it must remain available afterward.
- Confidence-ranked automation arrives in Sprint 7; current suggestions may be manually requested here. No auto-confirmation.
- Parent: [Project Plan](../PLAN.md). Previous: [Sprint 5](sprint-5.md). Next: [Sprint 7](sprint-7.md).
