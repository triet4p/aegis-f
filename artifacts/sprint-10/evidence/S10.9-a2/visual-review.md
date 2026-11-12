# S10.9 a2 — visual review of personally opened phone captures

Every image named below was opened with the image reader from the physical phone's captures in
`screens/` and `screens/chat/`; the approved renders were opened again for the comparison. The
design contract is the owner-approved refined design (specification + screen specifications +
renders), with the plan's precedence rules for stale render copy. Where the renders disagree with
the plan, the plan wins (recorded below).

## 1. Screen-by-screen comparison

| Destination | Opened frames | Comparison with the approved design |
| --- | --- | --- |
| Home | `s109-01-home-en`, `s109-20-home-after-walk-en`, `s109-21-home-empty-en`, `s109-36-home-read-error-en`, `s109-50-home-vi-15-dark`, `s109-61-home-en-dark`, `s109-64-home-vi-light`, `new-A-totals`, `new-E1-durability-home` | One compact period glance with an eye control and a reports link, eight labelled squircles directly on the surface, five-tab bar with the Review count on Review itself. No hero card, no enclosing launcher card, no recent ledger feed. Matches the approved unstacked launcher. At 1.5× the period label and *Categories & rules* label used to ellipsize; both now wrap (fixed). Empty and read-error frames stay truthful (`0 ₫`, and the stored-read error with Try again). |
| Analytics | `s109-02-analytics-en`, `s109-22-analytics-empty-en`, `s109-37-analytics-read-error-en`, `s109-41-analytics-long-list-en`, `s109-51-analytics-vi-15-dark`, `new-C1-analytics` | Scope card, spending/income/net/transfers metrics, category split and daily bars in one report task. Exact figures reconcile with the ledger. **Observed defect**: the final category row sat under the system navigation bar at 1.0× and 1.5× (`screens/before-fix/analytics-content-under-nav-vi-15.png`); the content is now inset and the row clears the system buttons. Empty and read-error states are dedicated, not a fabricated zero. |
| Ledger | `s109-11-ledger-en`, `s109-23-ledger-empty-en`, `s109-38-ledger-read-error-en`, `s109-42-ledger-long-list-en`, `s109-43-ledger-boundary-en`, `s109-52-ledger-vi-15-dark`, `new-C2-drill` | Search plus type/category/date filters, a bounded page and exact rows; the scoped header keeps the chosen scope on a drill. No stacked unrelated panels. Long descriptions wrap/truncate in the row while the exact amount stays fully readable; the detail owns the full text. At 1.5× the bottom bar labels wrap and remain above the system bar (zoom-checked). |
| Transaction detail | `s109-12-record-detail-en`, `s109-13-record-detail-after-discard-en`, `s109-41-transaction-read-error-en`, `s109-53-transaction-detail-vi-15-dark` | Receipt identity, amount, type in words, category, moment with device time, source, record id, recorded/corrected times; Edit/Delete are the only actions. **Observed defect**: the *stored on this device* line and the scroll viewport extended under the system nav bar at 1.5× (`screens/before-fix/transaction-detail-line-under-nav-vi-15.png`); the receipt viewport is now inset above the actions and nav bar. Confirmed: the abandoned edit left the record unchanged. |
| Manual record | `s109-03-manual-en`, `s109-28-manual-initial-en`, `s109-55-manual-vi-15-dark`, a1 `device-manual-valid-dark`, `device-manual-decimal-refused-dark`, `device-manual-validation-error-dark`, `new-B1-typed`, `new-B2-saved` | One hero amount, type choice, category, optional details, one sticky Save; no competing CTA. Save stays reachable above the IME and the exact amount is read back on the receipt. The hero field now carries a screen-reader label. Decimal/refused input keeps the text and explains the whole-dong rule. |
| Notes / quick capture | `s109-14-notes-composing-en`, `s109-24-notes-empty-en`, `s109-25-notes-empty-list-en`, `s109-54-notes-vi-15-dark`, a1 `device-notes-saved-dark`, `device-notes-list-dark`, `new-D1-notes`, `new-D3-saved` | A local note composer with a truthful offline/local badge and one Save; saved notes live in a child list, and the AI handoff is an explicit action in note detail. No hosted-workflow switches compete on this screen. IME stays below the composer; Save is visible with the keyboard open. |
| Note detail / saved notes | `s109-15-note-detail-en`, `new-E2-durability-notes` | Note text, time, details-from-note section and *Discuss with AI*; the saved list insets its content above the system nav bar (same fix class as Analytics). No tokens/database jargon. |
| AI Chat | `s109-16-chat-note-context-en`, `s109-19-chat-en`, `s109-26-chat-no-provider-en`, `s109-60-chat-vi-15-dark`, `s109-63-chat-en-dark`, `s109-66-chat-vi-light`, `chat/s105-510…548*.png`, `chat/s105-620…625*.png` | One streaming conversation: empty guidance, visible/removable attached-note context before the first send, waiting/streaming/stopped/error states, Stop that keeps received text, jump-to-latest, history sheet, link cards and clarification. **a1 fix confirmed**: the attached note is fully readable on entry (no scroll under the app bar). Real streaming frames show progressive text and the composer toggling Send/Stop. No synthetic demo badge is present in production; the scripted frames are additionally labelled as a synthetic provider, as they should be. |
| Review inbox | `s109-17-review-en`, `s109-25-review-empty-en`, `s109-29-review-ambiguous-en`, `s109-35-review-after-dismiss-en`, `s109-39-review-read-error-en`, `s109-56-review-vi-15-dark`, `s109-62-review-en-dark`, `s109-65-review-vi-light` | Evidence-first lead, uncertainty-first grouping with an order control, conflict badges, duplicate family, one draft per card and a truthful count. Empty inbox shows one statement and no pager (a1 fix confirmed). **Observed defect**: on a read error the count row still said *Reading the review queue…* and an empty pager was drawn beside the error; both are now suppressed (fixed and re-captured). |
| Draft detail | `s109-18-draft-detail-en`, `s109-30-draft-unreadable-en`, `s109-31-draft-correction-snackbar-en`, `s109-32-draft-ambiguous-link-en`, `s109-33-draft-preview-en`, `s109-34-draft-preview-stale-en`, `s109-34b-draft-preview-refreshed-en`, `s109-40-draft-read-error-en`, `s109-57-draft-detail-vi-15-dark` | One draft, bank evidence, parsed fields with provenance, duplicate warning, link decision, explicit preview then confirm, secondary dismiss. The **a1 snackbar fix is confirmed**: a refused confirmation is visible above the sticky action. The stale preview offers only *Check current values* and no financial confirm. |
| Categories & rules | `s109-04-categories-en`, `s109-05-rules-en`, `s109-27-categories-empty-en`, `s109-58-categories-vi-15-dark` | Two task tabs with counts, Active/Archived filter, per-row edit/delete actions, focused sheets. Rules state the exact-description matcher truthfully. Empty list and add-success snackbar are readable. |
| Settings | `s109-06-settings-en`, `s109-07-provider-en`, `s109-08-skills-history-en`, `s109-09-privacy-en`, `s109-10-bank-notifications-en`, `s109-59-settings-vi-15-dark` | A navigation hub with language/appearance on the hub and focused children; rows state the current value. Provider, skills/history, privacy and bank access are separate tasks. No debug enums or raw logs in everyday copy; endpoint disclosure and masked credential behave as designed. |

## 2. Plan-precedence deviations from older render copy

- The renders in `artifacts/sprint-10/renders/` show Settings as the fifth tab; the plan
  supersedes that with **AI Chat** as the fifth tab and Settings as a Home launcher. The
  implementation follows the plan (`s109-01`, `s109-20`).
- The renders show a *SYNTHETIC STREAM* badge/model line on Chat; the plan forbids presenting
  synthetic streaming as production, and the implementation omits it. Scripted evidence frames
  name their synthetic provider only inside test captures.
- The review render uses two segmented tabs; the passed S10.6 design uses conflict-first sections
  with an order control, which the implementation follows.
- Home uses a compact glance rather than the render's larger hero card, per the plan's
  no-hero/anti-stacking refinement.

These are approved task-gate decisions, not new S10.9 deviations.

## 3. Accessibility and large text observations

- All clickable controls on the visited destinations expose a name (text, content description or
  editable value); the amount field's missing static label was the only real gap and is fixed.
- The a1 Review-bell "40 dp" reading was Compose's painted box. The device accessibility tree
  exposes the bell at 96 × 96 px = 48 dp; the scan now measures the expanded hit area and passed
  on every destination at 1.5×.
- Non-color cues observed: status words on chips, words on conflict badges, the selected-tab pill
  and weight, and direction words in the receipt; no captured state uses color alone.
- At 1.5× the Home period and longest launcher labels now wrap instead of ellipsizing; the bottom
  bar labels wrap and stay above the system bar (zoom-checked).

## 4. Residual visual limitations

- The ledger row truncates very long descriptions to two lines; the full text is in record detail
  by design.
- The provider endpoint field is a single-line editable field, so a long URL is horizontally
  clipped until scrolled; the full value is editable and is disclosed as the endpoint route.
- The daily-spending chart is a horizontally scrollable row of fixed-width day columns; with
  three or more days the last column can be partly off-screen until scrolled (the partial bar is
  the affordance). Chart amounts remain exact when scrolled into view.
- Loading frames are transient local Room states and were not fabricated as stills.
- Frames include the system status/navigation bars; no personal notification is present in the
  retained set.
