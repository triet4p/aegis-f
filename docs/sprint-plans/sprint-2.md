# Sprint 2 — VND Ledger and User-Defined Categories

## Sprint Goal

Deliver a manually usable offline expense tracker with exact VND amounts, unrestricted user-created categories, and confirmed-expense summaries.

## Status and Dependencies

- Status: Pending.
- Dependencies: Sprint 1's working device build/install path.
- Workflow: **Lightweight**. Focused behavior checks and diff review per atomic task; physical-device ledger scenario and a reviewed local commit at closure. No automatic push.
- Status legend: `[ ]` pending / `[~]` in progress / `[x]` done.

## Acceptance Criteria

- The user can create, view, edit, and delete manual transactions with amount, type, date/time, description, and category where applicable.
- The user can add, rename, and archive categories without a product-imposed count cap; archived categories remain attached to historical records but are unavailable for new assignments.
- Expenses, income, and internal transfers are distinct; only confirmed expenses enter expense totals.
- VND input/storage/display preserves exact whole dong. Invalid, fractional, negative manual amounts and out-of-range values do not produce corrupted records or wrapped totals.
- Lists, category/date filters, and current-month summaries reflect edits/deletions correctly and survive app/process restart.
- Manual use does not require a model file, notification access, login, network, or bank integration.

## Ordered Atomic Tasks

- [ ] **S2.1 — Define persistent ledger entities.** Implement Room schema and repositories for categories and transactions, including stable IDs, record type, confirmation status, source provenance, timestamps, and schema versioning. Store positive magnitude as `Long` whole VND; type determines financial direction. Persist and reload sample records through the actual database.
- [ ] **S2.2 — Implement exact VND boundaries.** Centralize accepted manual amount syntax, validation, checked range handling, and display formatting with an explicit currency marker. Reject fractional dong and arithmetic overflow rather than rounding or wrapping.
- [ ] **S2.3 — Implement category management.** Deliver add/rename/archive screens and persistence. Prevent ambiguous duplicate active names and preserve historical category references; exercise enough user-created categories to expose accidental UI/product caps.
- [ ] **S2.4 — Implement manual transaction creation.** Deliver an English form for amount, expense/income/transfer type, date/time, description, and category. A successful save creates one confirmed manual record; invalid input explains the field error without saving.
- [ ] **S2.5 — Implement transaction correction and deletion.** Deliver detail/edit actions and explicit delete confirmation. Corrections preserve record identity; cancelling an edit/delete leaves ledger state unchanged.
- [ ] **S2.6 — Implement browsing and filters.** Deliver chronological list, details, category/type/date filters, and empty states. Archived category records remain discoverable; date boundaries use a documented local-time convention rather than guessed UTC month boundaries.
- [ ] **S2.7 — Implement exact expense summaries.** Query totals and category breakdowns from confirmed expense records only, using checked money aggregation. Verify income, transfers, drafts, and deleted records do not inflate spending.
- [ ] **S2.8 — Prove persistence and manual offline use.** Exercise create/edit/archive/delete and month-boundary cases on the device with AI absent and notification permission disabled. Add deterministic regression coverage for money boundaries, ledger inclusion rules, and category-history preservation; document manual use and date semantics.

## Required Verification

On the physical phone, record expenses of 45,000 and 120,000 VND, income of 1,000,000 VND, and an internal transfer of 500,000 VND. The expense total must be 165,000 VND. Change the second expense to 125,000 VND, archive its category, restart, and observe 170,000 VND with historical attribution intact. Delete the first expense and observe 125,000 VND. Repeat relevant operations offline and verify no model/permission requirement.

## Decisions / Blockers

- Categories are database records, not a fixed model enum. Unknown categorization must not prevent manual saving.
- Rejected input and unavailable AI need user-visible states, not fake successful records.
- Parent roadmap: [Project Plan](../PLAN.md). Previous: [Sprint 1](sprint-1.md). Next: [Sprint 3](sprint-3.md).
