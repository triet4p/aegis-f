# S10.9 a2 — baseline comparison (worker observation)

Same seeded core tasks, observed by the worker on the same physical phone with adb taps. This is
**worker observation, not a human usability study**; no human timing or preference is claimed.
The baseline is the recorded pre-redesign build installed as the isolated package
`io.github.triet4p.aegisf.s812evidence` (same source commit `c586deb` used by a1); the new build is
the current candidate in `io.github.triet4p.aegisf.s109evidence`. Both packages were cleared
before their walk, so both started from an empty local ledger and one identical task amount
(123,456 ₫ expense, entered through each app's own form).

Taps count only deliberate screen taps after launch; typing digits/letters and scrolling gestures
are counted separately. Both captures are in `screens/` and were personally opened. The new-app
frames were re-captured on the frozen final candidate (after the inset/label fixes) with the same
tap sequence; the tap counts below were re-verified in that recapture.

| Task | Baseline (pre-redesign) | New candidate | Observed difference |
| --- | --- | --- | --- |
| A. See this month's spending | **0 taps** — Dashboard's CONFIRMED SPENDING card is the first card (`baseline-A-totals.png`) | **0 taps** — Home's compact glance (`new-A-totals.png`, `new-E1-durability-home.png`) | Equal at the glance; baseline puts the figure inside a 7-card stack, the new screen puts it in one glance bar above the task grid |
| B. Record a 123,456 ₫ expense | **5 taps + 1 scroll** — Manual tab → *Record a transaction* → amount field → Save transaction → Done. The Save button is below the fold on the initial form viewport and had to be scrolled to (`baseline-B1-form.png`, `baseline-B2-saved.png`) | **4 taps + 0 scrolls** — *Manual record* launcher → amount field → *Save to ledger* → Done. Save stays reachable above the IME (`new-B1-typed.png`, `new-B2-saved.png`) | One fewer tap, no scroll-to-save; both store the exact 123,456 ₫ (baseline shows `123,456 VND`, new shows `-123,456 ₫` with the same magnitude and direction) |
| C. Find that record | **1 tap from Dashboard** (*See 1 spending records*) → scoped list; **2 taps from a cold Dashboard** (Dashboard tab then the scoped action) (`baseline-C1-records.png`) | **2 taps from Home** (Analytics link → category row) → scoped ledger (`new-C1-analytics.png`, `new-C2-drill.png`) | New takes one extra tap but keeps the chosen period/category scope visible in the header; baseline's drill page restates the scope in its own header |
| D. Save a local note | **3 taps + 2 scrolls** — Chat tab → composer → *Save capture*. The note composer sits below a hosted-agent configuration panel and the Save button is below the fold (`baseline-D1-chat.png`, `baseline-D2-saved.png`) | **3 taps + 0 scrolls** — Notes tab → composer → *Save note* (`new-D1-notes.png`, `new-D3-saved.png`) | Same tap count, no scrolling, and the note composer no longer shares the screen with hosted-agent skill switches |
| E. Process death | not run on the baseline (old four-screen shell) | **Force-stop → relaunch**: Home still shows 123,456 ₫ and the saved note is listed once (`new-E1-durability-home.png`, `new-E2-durability-notes.png`) | New app's record/note survive a real process kill; no duplicate note appeared |

**Navigation mistakes observed:** none in either walk landed on an unintended destination. Two
risks were observed rather than scored as mistakes:

- On the baseline form, the primary Save action is not in the initial viewport; a user who does
  not scroll can believe the form has no save. The worker had to inspect the view hierarchy to
  find it.
- On the baseline Chat destination, the note composer is below hosted-agent configuration, so the
  note task begins off-screen. The worker needed one scroll before the composer and one before
  Save.
- On the new Analytics drill, Back returns to Analytics first and then Home (two backs from the
  scoped list). This is a depth observation, not a mistake; the destination keeps its scope and
  position on return.

**Outcome reconciliation:** both apps store and display the same exact 123,456 ₫ magnitude; both
show zero income/transfers for the single entry; the new app's Home and Analytics recompute the
same figure after the save and after process death. No cumulative or silently rounded amount was
observed in either build.
