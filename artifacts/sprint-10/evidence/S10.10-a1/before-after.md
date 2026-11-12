# S10.10 — Before/after task observations

**Evidence source:** [S10.9-a2 baseline comparison](../S10.9-a2/baseline-comparison.md), accepted in [S10.9 review round 2](../../reviews/S10.9-review-02.md) and bound to the S10.9 checkpoint. This is worker-observed interaction, not a human usability study.

## Measurement conditions

- Same physical HONOR ALT-LX2, Android 14 / API 34, 720 × 1610 px, 320 dpi.
- Baseline was the pre-redesign `c586deb` build in isolated package `io.github.triet4p.aegisf.s812evidence`; the new build was the S10.9 candidate in isolated package `io.github.triet4p.aegisf.s109evidence`.
- Both isolated packages were cleared before their walk. Each walk began with an empty local ledger and the same manually entered expense amount, **123,456 ₫**. No owner package or owner data was used.
- Tap counts include deliberate screen taps after launch. Typing and scrolling are counted separately. No task duration, participant sample, human success rate, or preference score was measured.
- Baseline and current screenshots are linked below. The new-app frames were recaptured on the final S10.9 candidate using the same tap sequence. The evidence report is authoritative for counts and outcome reconciliation.

## Same seeded tasks

| Task | Pre-redesign observation | Current native app observation | Difference and evidence |
| --- | --- | --- | --- |
| See current-month spending | Dashboard shows the total immediately: **0 taps**. It is within a seven-card stack. | Home shows the compact glance immediately: **0 taps**, above the launcher grid. | Same entry cost for the glance; current Home removes the stacked cards. [Baseline totals](../S10.9-a2/screens/baseline-A-totals.png), [current Home totals](../S10.9-a2/screens/new-A-totals.png), [current Home after route walk](../S10.9-a2/screens/new-E1-durability-home.png). |
| Record a 123,456 ₫ expense | Manual tab → record action → amount → Save → Done: **5 taps + 1 scroll**. Save was below the first viewport. | Home **Manual record** launcher → amount → **Save to ledger** → Done: **4 taps + 0 scrolls**. Save was reachable above the keyboard. | One fewer observed tap and no scroll-to-save. Both show/store the exact 123,456 ₫ magnitude; the expense is represented as a negative ledger amount. [Baseline form/saved](../S10.9-a2/screens/baseline-B1-form.png), [current form/saved](../S10.9-a2/screens/new-B1-typed.png). |
| Find the saved record | From Dashboard, the scoped records action took **1 tap**; from a cold Dashboard tab it took **2 taps**. | Analytics link → category row took **2 taps** to the scoped ledger. | Current route uses one extra tap versus the warm baseline path, and preserves the selected period/category scope when returning. [Baseline scoped records](../S10.9-a2/screens/baseline-C1-records.png), [current Analytics](../S10.9-a2/screens/new-C1-analytics.png), [current scoped ledger](../S10.9-a2/screens/new-C2-drill.png). |
| Save a local note | Chat tab → composer → Save capture: **3 taps + 2 scrolls**. The composer and save action were below hosted-agent controls. | Notes tab → composer → **Save note**: **3 taps + 0 scrolls**. | Same observed tap count, no scrolls, with a separate local-only Notes destination. [Baseline Chat/capture](../S10.9-a2/screens/baseline-D1-chat.png), [current Notes](../S10.9-a2/screens/new-D1-notes.png), [saved note](../S10.9-a2/screens/new-D3-saved.png). |
| Reopen after process death | Not run on the pre-redesign baseline. | After a real force-stop/relaunch, Home still showed the exact 123,456 ₫ entry and the saved note was present once. | This is new-app durability evidence only; there is no before comparison. [After restart: Home](../S10.9-a2/screens/new-E1-durability-home.png), [after restart: Notes](../S10.9-a2/screens/new-E2-durability-notes.png). |

## Navigation observations and limits

- No unintended destination was reached in either worker-observed walk.
- The baseline Manual save being below the initial viewport and the local-note composer being buried below hosted controls were risks noticed by the worker, not scored human mistakes.
- In the new route, Back from a scoped Analytics drill returns to Analytics before Home (two Back steps from the scoped list). This preserves scope/position and was recorded as route depth, not a navigation failure.
- No elapsed task times were recorded. Tap/scroll differences do **not** establish user speedup, preference, learnability, or a usability target. Do not restate them as human results.
- The owner walkthrough is separately specified in [owner-walkthrough.md](owner-walkthrough.md); it has not been performed as part of this documentation task.
