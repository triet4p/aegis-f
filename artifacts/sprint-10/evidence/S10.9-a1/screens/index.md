# S10.9 attempt a1 — capture index (partial, owner-stopped)

All images were captured from the physical HONOR ALT-LX2 (720 × 1610, 320 dpi) with the
isolated package `io.github.triet4p.aegisf.s109evidence` unless marked `baseline-*`. Every file
below was personally opened during the attempt. `prefix-*` files are pre-fix observations kept
only to document the defects; they are not final evidence.

The automated `s109-01..s109-20` gallery written by the first passing instrumented test was
erased from the app's external files directory by the later `pm clear` before the remaining
tests. Only `s109-debug-search.png` survived from that directory; the instrumented gallery must
be recaptured on resumption. No owner file is in this directory, and the two captures that
briefly contained a personal heads-up notification were deleted at capture time.

## Real production shell, seeded corpus (dark; EN; 1.0×)

| File | Observation |
| --- | --- |
| `device-home-launch-dark.png` | Home launch, empty isolated app: 8 labelled launchers, 5 tabs, `0 ₫` glance |
| `device-home-seeded-dark.png` | Seeded Home: spending **1,324,612 ₫**, Review badge 6 |
| `device-ledger-seeded-dark.png` | Seeded ledger, filters, pager, exact row amounts |
| `device-analytics-seeded-dark.png` | Spending 11 records, Income 1, net cash flow, category split |
| `device-categories-seeded-dark.png` | Three seeded categories, Active filter |
| `device-settings-dark.png` | Settings hub, dark |
| `device-settings-light.png` | Settings hub, light |
| `device-appearance-sheet-dark.png` | Appearance sheet from the hub (dark) |
| `device-provider-entry-light.png` | Provider & model child, untouched entry (light) |
| `device-provider-light.png` | Provider form with protocol/preset/endpoint/model/output |
| `device-skills-light.png` | Skills & history child |
| `device-privacy-light.png` | Privacy & retention child, initial scroll |
| `device-privacy-light-scrolled.png` | Same child scrolled to the end of its content |
| `device-review-seeded-dark.png` | Review inbox, 6 waiting, conflict-first sections |
| `device-review-inbox-light.png` | Review inbox rendered by the light in-memory cross-flow fixture (5 waiting); captured from the phone while the instrumented test was showing it |
| `device-draft-detail-unreadable-dark.png` | Unsupported-format draft detail; enabled sticky preview action |
| `device-categories-dark.png` | Categories empty-state (fresh install) |
| `device-analytics-empty-dark.png` | Analytics empty period |
| `device-ledger-empty-dark.png` | Ledger empty state |
| `device-notes-empty-dark.png` | Notes empty composer |
| `device-chat-empty-dark.png` | AI Chat root, no provider configured |
| `device-manual-empty-dark.png` | Manual record initial form |

## Flow captures (dark; EN; 1.0×)

| File | Observation |
| --- | --- |
| `device-manual-amount-dark.png` | Amount typed: `1234567890`, written-out helper |
| `device-manual-before-save-dark.png` | Amount field ready to save |
| `device-manual-valid-dark.png` | Valid whole-dong amount, Save reachable above IME |
| `device-after-save2-dark.png` | Saved exact record: **-123,467,890 ₫** |
| `device-home-after-save2-dark.png` | Home shows the same exact **123,467,890 ₫** |
| `device-manual-decimal-refused-dark.png` | Decimal entered: inline whole-dong refusal, edits kept |
| `device-manual-validation-error-dark.png` | Save pressed with a decimal: error visible, nothing written |
| `device-ledger-one-dark.png` | Ledger with the exact record |
| `device-detail-dark.png` | Transaction receipt, Record ID, Edit/Delete |
| `device-edit-dark.png` | Edit form prefilled, Save changes |
| `device-detail-after-edit-back-dark.png` | Unchanged record after Back (no write) |
| `device-notes-root-dark.png` | Notes composer, empty draft |
| `device-notes-typed-dark.png` | Multi-line note with IME open; Save reachable |
| `device-notes-filled-dark.png` | Same note with IME still open (inset proof) |
| `device-notes-saved-dark.png` | Saved-on-this-device confirmation |
| `device-notes-list-dark.png` | Saved notes browse |
| `device-note-detail-dark.png` | Saved note detail and Discuss with AI handoff |
| `prefix-chat-context-clipped-defect.png` | **Pre-fix defect**: attached note header and first line scrolled under the app bar |
| `prefix-chat-context-scrolled.png` | Same screen after a manual scroll; full context visible |
| `prefix-review-empty-duplicate-defect.png` | **Pre-fix defect**: “Nothing to review” repeated and `Page 1` pager over an empty inbox |
| `s109-debug-search.png` | Ledger search with IME open; the row was behind the keyboard, kept as the reason the test hides the IME |

## Baseline (pre-redesign commit `c586deb`, isolated `.s812evidence`)

| File | Observation |
| --- | --- |
| `baseline-01-launch.png` | Old **Dashboard** launch: stacked DATE RANGE / CATEGORY / CONFIRMED SPENDING / INCOME / TRANSFERS / REVIEW / SPENDING BY CATEGORY cards, four tabs Dashboard/Chat/Review/Manual, Settings in the top bar |

The baseline tap walk was interrupted before the remaining tasks; only the launch surface was
recorded. No tap-count claim should be made from this attempt.
