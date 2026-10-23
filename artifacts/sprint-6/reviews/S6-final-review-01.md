# Sprint 6 End-of-Sprint Differential Review

- **Task ID:** S6-final
- **Attempt ID:** S6-final-review-01
- **Agent ID:** S6FinalDifferential
- **Sprint:** 6 (`docs/sprint-plans/sprint-6.md`)
- **Route:** Light — mandatory evidence gates S6.1–S6.8 all PASS; **no checkpoint assigned/required**; no commits assigned. No checkpoint records were expected; none are absent-found.
- **Reviewed scope:** cross-task differential review over the completed S6.1–S6.8 set; task-level gates were NOT re-run and re-audited.
- **Verdict:** **PASS** (zero actionable findings; zero material open questions)
- **Confidence:** 0.86
- **Advance:** `true`

## 1. Inputs (established ground, read first)

- Evidence gate reports: `artifacts/sprint-6/reviews/S6.1-review-01.md` … `S6.8-review-01.md` (all PASS, all `Advance: true`), read in full.
- Worker artifacts under `artifacts/sprint-6/workers/` are cited by the gates and hashing-bound by them (per-attempt spot-checks recorded in each gate). Worker histories were not reopened: no contested strength-of-claim or provenance issue surfaced.
- Plan status (`docs/sprint-plans/sprint-6.md`): S6.1–S6.8 marked `[x]` with carry-forward closures noted (S6.7 → date ordering; S6.8 → font 1.3 labels + Manual IME). Consistent with gate reports; no drift between plan status and recorded evidence found.
- Source inspection was pulled **only for the novel cross-task questions below** — five targeted reads, no full-file sweeps, no builds/tests, no git/checkpoint operations, no phone/production mutation, no Telex changes.

## 2. Differential probes (new evidence this review produced)

### 2.1 S6.2 ordering fix survived all later shared-file edits — VERIFIED SOUND

The S6.2 fix (`DashboardScope.kt`) was later touched by S6.3 (hash change recorded in that gate). Direct read of the current fix body: both ends resolve to local-day epoch bounds and `fromStart >= throughExclusive` still refuses with `"The From date is after the Through date."` before `LedgerDateRange.closed` is ever called (`DashboardScope.kt:123-150`). No later task reintroduced text ordering. (S6.7's parallel fix in `TransactionFilter.kt` is separately gated and its 4-file scope was untouched by later tasks.)

### 2.2 S6.5 ↔ S6.6 seam: capture edit/deletion cannot widen a stale preview — VERIFIED SOUND

Risk: a confirm driven from a preview made before a source edit/delete could write a record whose linked note no longer says what justified it. Source: `confirmDraft`'s binding includes every accepted link's state — `link.revision` + `event.resolvedTransactionId` — in `confirmationBinding` (`WorkflowCommandService.kt:2175-2204`), and a capture edit/delete runs `invalidateDerivedReadings`, which in the same transaction deletes **active** links for the capture (`clearDerivedReading` + `linkDao.deleteActiveForCapture`; refused links properly survive; `EventContextRepository.kt:435-451`). Deletion of an accepted link changes `sourceState`, so the carried binding no longer matches and confirm returns `Conflict` writing nothing. This is what the S6.6 "a changed source is a conflict" KDoc claims, now verified binding-by-construction across the two tasks' owners.

### 2.3 Dashboard scope retained across category lifecycle (S6.1 saveable scope × S6.7 delete) — VERIFIED SOUND

Risk: `dashboardScope` (including `LedgerCategoryFilter.Specific`) survives tab switch, process death, and restart; if the selected category is later permanently deleted from Manual, returning to Dashboard could crash or show a scoped figure for a nonexistent category. Source: the label resolves via `observeCategory` with a null fallthrough to `"Unknown category"` — no force-unwrap, no crash (`DashboardScreen.kt:134-150`); the aggregate query returns rows whose stored `categoryId` still matches, and a deleted category's transactions are uncategorized by S6.7's delete contract, so the filtered figure is 0 VND/0 records — explicit, no wrong money, and consistent with the delete-impact dialog ("amounts, dates…monthly totals do not move"). Minor cosmetic case not named by the plan; no acceptance breach.

### 2.4 Idempotent confirm / no unintended ledger writes (S6.6 × S6.4 system shape) — VERIFIED SOUND (re-derived, not re-tested)

Re-derived from source: terminal-draft `NoChange` answers name the stored state for **any** new idempotency key (`confirmDraft` pre-check `:1791-1799`, `terminalDraftChange/Detail :2139-2153`), same-key replay is claimed by `runCommand`, and binding equality is the only write path (`:1810-1814`). Together with the single-record室的 double-tap phone evidence (S6.6 screenshot `28`) this holds across the shared operation ledger used by S6.4–S6.7 commands. No path outside the preview's binding touches the ledger on confirm/dismiss.

### 2.5 Dismissed probes (checked, no finding)

- **Badge/lifecycle**: pending badge reads `PENDING` count only; S6.6 phase evidence zeros it after confirm/dismiss — no cross-task staleness.
- **Shared additive query contract**: S6.3's optional-parameter additions (default = old behavior) plus S6.6's new bounded context queries fit one convention; no second persistence convention or retry shim anywhere in the interplay.
- **Formatter/label work surface ownership**: nav labels and IME are app-surface-wide fixes owned once by S6.8 (`AppShell` labelSmall/`adjustResize` + form `imePadding`); S6.2–S6.6 evidence corroborates the same behavior, so no overlapping double-fit risk found.
- **Docs truthfulness**: `README.md` claims for the four destinations, capture/journal truthfulness, provenance tombstone/clearing ("clears derived context and active links" — matches §2.2 exactly), numeric-badge spoken count, 1.3× labels, dashboard exactness/limits, and the explicit "not verified on the phone with the real bank" disclosure all align with gate-verified evidence. No unclaimed capability is asserted.
- **No automation leakage**: sprint greps found no `autoMatch`/auto-confirm additions; the Manual-handoff prefill path (S6.5) writes only on explicit Save, and S6.7/S6.8 added no matching. Cross-task consistency confirmed.

## 3. Nonblocking observations (unverified only)

- **Bounded residual (LOW, informational):** editing only a note event's *interpretation text* between preview and confirm bumps the event's own revision but not the accepted link row's CAS revision nor `resolvedTransactionId` (and leaves the capture text untouched), so `confirmationBinding` is unchanged and Confirm isnot withheld for that specific drift (`WorkflowCommandService.kt:2179-2187`). Materiality is strictly bounded: the ledger write is exactly the preview's user-confirmed values, and the capture-text edit/delete case **is** bound (§2.2); the residue is only that the provenance card's excerpt may show the newer reading at next refresh while a confirmed description was justified by the older one. S6.6's acceptance names the review/category/source cases, all of which are bound; this reading-drift case is outside every named contract. Rationale to not action: no stored value becomes factually wrong, no bank amount or ledger changes. Suggested as a one-line note for the Sprint 7 binding work, not a contract violation.
- S6.6's disclosed limits (context-card load-error/retry branch not phone-driven; PROPOSED-card pair not driven from Review UI) and S6.8's disclosed limits (no retained loading frame, no real listener delivery — synthetic shared intake, no spoken-audio proof) remain accepted gate limits, not re-raised here.
- S6.7 INFO-1/2 hygiene items (manifest transcription typo in `51-after-edit.tar` hash; stray synthetic db/tar files in the evidence dir) are still Main's optional tidy-up; no gate impact.

## 4. Reviewed-scope / limits of this review

- Differential review only: no task-level acceptance test was re-executed, no checkpoint ran (none required on the declared Light route), nothing staged/committed/pushed, product/plan/worker artifacts untouched — only this report written.
- Source evidence was read directly for the five probes above; all other conclusions rest on the eight passed, hash-cross-checked evidence gates treated as ground, per assignment.
- The consolidated user pass on the production phone remains a separate, planned step regardless of this PASS.

## Verdict

**PASS — advance: true.** Sprint-6 cross-task composition holds: every probed seam (scope retention × category lifecycle; capture revision policy × review confirm binding; shared additive query/surface fixes; idempotent user-only confirmation on the one application ledger) is either bound by construction or falls within a disclosed, bounded non-contract residual. No action blocks the sprint; the single listed residual is a low-materiality provenance-freshness note for the Sprint 7 confidence/binding work.
