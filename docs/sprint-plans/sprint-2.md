# Sprint 2 — VND Ledger and User-Defined Categories

## Sprint Goal

Deliver a manually usable offline expense tracker with exact VND amounts, unrestricted user-created categories, and confirmed-expense summaries.

## Status and Dependencies

- Status: Complete. S2.1–S2.8 accepted; user confirmed all three final offline/persistence/month-edge checks passed (“1-3 đạt”). User authorized a scoped local Sprint 2 delivery commit; push is not authorized.
- Dependencies: Sprint 1's working device build/install path is complete.
- Workflow: **Lightweight**. Each task requires focused behavior checks on PC, a worker scope/diff review, and Main acceptance of compact evidence. S2.1 must persist/reload through a real Room database; S2.2 and S2.7 must exercise money boundaries and inclusion rules. Physical-device interaction and visual/manual ledger verification are user-run only; no worker ADB/device access. No independent evidence review, full-route checkpoint, or deep review is assigned. After final acceptance, user authorized Bronze to stage and commit only verified Sprint 2 code/tests/docs/evidence on `main`, preserving unrelated changes and normal hooks/signing. No push authorization.
- Status legend: `[ ]` pending / `[~]` in progress / `[x]` done.

## Acceptance Criteria

- The user can create, view, edit, and delete manual transactions with amount, type, date/time, description, and category where applicable.
- The user can add, rename, and archive categories without a product-imposed count cap; archived categories remain attached to historical records but are unavailable for new assignments.
- Expenses, income, and internal transfers are distinct; only confirmed expenses enter expense totals.
- VND input/storage/display preserves exact whole dong. Invalid, fractional, negative manual amounts and out-of-range values do not produce corrupted records or wrapped totals.
- Lists, category/date filters, and current-month summaries reflect edits/deletions correctly and survive app/process restart.
- Manual use does not require a model file, notification access, login, network, or bank integration.

## Ordered Atomic Tasks

- [x] **S2.1 — Define persistent ledger entities.** Implemented Room schema/repositories, stable IDs, type/status/provenance, timestamps, schema v1, and positive `Long` whole VND. Actual file-backed Room close/reopen and category-history/invariant checks passed on host: 17 tests, zero failures; clean debug build passed. User-run device persistence accepted in S2.8. [Evidence](../../artifacts/sprint-2/S2.1-a2/storage-proof.md).
- [x] **S2.2 — Implement exact VND boundaries.** Centralized digits-only positive whole-VND input (no signs, whitespace, separators, or fractions), exact `Long.MAX_VALUE`, explicit VND formatting, and checked addition. Host suite passed 23 tests; debug build passed. [Evidence](../../artifacts/sprint-2/S2.2-a1/vnd-proof.md).
- [x] **S2.3 — Implement category management.** Add/rename/archive UI and live Room observation implemented; 26 host regression tests and debug build passed. Host tests exercised 61 categories with history preservation. User confirmed all six physical checks passed: immediate add/rename/archive rendering, duplicate rejection, additional categories/scrolling, and persistence after closing/reopening. This satisfies the previously unresolved live-list UI gate; no device checks were rerun by workers. [Host evidence and manual checks](../../artifacts/sprint-2/S2.3-a1/category-proof.md).
- [x] **S2.4 — Implement manual transaction creation.** Functionality is accepted by the user; prior suite passed 31 tests and creation smoke passed 5 scenarios. Shared intrinsic row height/segment fill-height fixes the reported selector height mismatch. Four focused host geometry configurations passed; temporary tests/dependencies/scaffold were removed and the cleaned debug APK built successfully. User confirmed the requested final normal/enlarged-font visual check passed (“Đã oke”), satisfying the device-only text-fit gate. [Original evidence](../../artifacts/sprint-2/S2.4-a1/creation-proof.md). [Correction evidence and host limitations](../../artifacts/sprint-2/S2.4-a3/type-selector-proof.md).
- [x] **S2.5 — Implement transaction correction and deletion.** Reachable saved-record list/details, shared create/edit form and named delete confirmation implemented. Five launcher/file-backed Room smoke scenarios and 33 permanent tests passed; cleaned debug APK built with preserved signer and temporary harness/dependencies removed. User confirmed all physical checks passed (“đã ok hết”), including edit/cancel/invalid input/archive retention/delete/reopen and readable UI. [Evidence](../../artifacts/sprint-2/S2.5-a1/edit-delete-proof.md).
- [x] **S2.6 — Implement browsing and filters.** All filtering/list functionality and the final collapsible-panel correction passed user phone checks. Filters default collapsed with Show/Hide and active count, preserving values/results/expansion across navigation. Original host suite passed 47 tests; correction smoke, 13 focused filter tests and cleaned debug build passed; temporary harness/dependencies removed. User's “Xác nhận!” satisfies the remaining physical UI gate. [Functional evidence](../../artifacts/sprint-2/S2.6-a1/browse-filter-proof.md). [Compact-filter proof](../../artifacts/sprint-2/S2.6-a3/compact-filter-proof.md).
- [x] **S2.7 — Implement exact expense summaries.** Current-month confirmed-expense total and stable category breakdown implemented with checked VND aggregation, local month bounds and explicit overflow state. Host suite passed 54 tests; production-launcher smoke proved 165,000 → 170,000 → 125,000, exclusions and archive attribution. Resume correction reproduced stale bounds before and passed three lifecycle scenarios plus 12 targeted tests after; temporary harness removed and cleaned APK built. User confirmed all six physical checks passed (“tất cả đều ổn”), including baseline-relative totals, edit/archive/delete/reopen, filter independence and readable UI. Clock-driven month rollover remains unexercised and was not required for this gate. [Summary proof](../../artifacts/sprint-2/S2.7-a1/summary-proof.md). [Resume proof](../../artifacts/sprint-2/S2.7-a2/month-resume-proof.md).
- [x] **S2.8 — Prove persistence and manual offline use.** Host evidence reuses accepted production-launcher smokes and adds one real file-backed Room regression combining month edges with edit/archive/delete/reopen. All 55 permanent tests passed. Existing manual-use/date-semantics docs updated; accepted S2.7 APK retained byte-identical, no production/build changes. User confirmed all three differential phone checks passed (“1-3 đạt”): offline create/edit/archive/delete, force-stop persistence, month-edge exclusion and edited membership, no model/login/permission requirement, safe cleanup and preserved pre-existing data. No system-date change or worker phone access. [Evidence](../../artifacts/sprint-2/S2.8-a1/offline-persistence-proof.md).

## Required Verification

User-only offline check on the existing ledger: record baseline B, then create NEW disposable expenses of 45,000 and 120,000 VND, income of 1,000,000 VND and an internal transfer of 500,000 VND. With Wi-Fi/mobile data off, spending must be B + 165,000 VND and persist after force-stop/relaunch. Edit the second expense to 125,000 VND, archive its category and force-stop/relaunch: B + 170,000 VND with historical attribution intact. Delete the first expense: B + 125,000 VND. Use transaction dates just outside the current month to verify exclusion and edit one into/out of the month to verify membership; do not change system time. Cleanup only new test records, restore connectivity and confirm B plus pre-existing data unchanged. No model/permission/login requirement.

## Decisions / Blockers

- Categories are database records, not a fixed model enum. Unknown categorization must not prevent manual saving.
- Rejected input and unavailable AI need user-visible states, not fake successful records.
- All assigned Lightweight gates passed, including final user-only device acceptance. Sprint 2 complete; Sprint 3 not started. User alone performs phone interaction. Scoped local delivery commit authorized; unrelated staged/working changes must be preserved, and push remains unauthorized.
- Parent roadmap: [Project Plan](../PLAN.md). Previous: [Sprint 1](sprint-1.md). Next: [Sprint 3](sprint-3.md).
