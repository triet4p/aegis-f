# S10.6 attempt a1 — visual review of actual native captures

All captures in `screens/` were taken from the installed isolated APK
(`io.github.triet4p.aegisf.s812evidence`, hashes in `apk-sha256.txt`) on the HONOR ALT-LX2
(API 34, 720 × 1610 px, 320 dpi) and were **opened and inspected personally** on this attempt.
The comparison inputs opened this attempt were the approved renders
`artifacts/sprint-10/renders/08_review_inbox.webp` and `09_draft_detail.webp`, the specification
§§3.8–3.9, the screen specifications §§8–9 and the one-screen/one-task rule.

Earlier capture sets were discarded and replaced: a third-party Messenger heads-up and a
Shopee banner covered the app in some frames. Heads-up notifications were suppressed only for
the final capture run and restored afterwards (`environment.txt`); no notification content is
retained in the artifact.

## Inbox

- `screens/s106-01-inbox-en.png` — focused inbox at 1×: title **Review inbox**, a single refresh
  action, the waiting count, one compact order description with the order action, page controls,
  and the **Needs your decision** group. Each card shows bank, signed exact amount, bank moment,
  memo, a warning chip (**unsupported bank format** / **bank data needs attention** / **possible
  duplicate** / **note association not decided**), the category line and one **Review this draft**
  action. Nothing is a transaction and no card offers an immediate confirm.
- `screens/s106-03-inbox-back.png` — the same list after opening a draft and system Back. The
  chosen order and the row that was open are still shown; the page/scroll state was restored.
- `screens/s106-06-inbox-after-confirm-en.png` — after the explicit confirmation: the confirmed
  draft is gone from the waiting inbox, the count is 1, and the remaining ready income card shows
  `+250,000 ₫`, its category and one review action.
- `screens/s106-11-inbox-vi-large.png` — Vietnamese at 1.5× text: **Duyệt tin**, **Làm mới**,
  **Cần xử lý trước**, the group heading and card copy all remain readable, with 48 dp-or-larger
  controls and no overlapping action.

## One-draft review

- `screens/s106-02-detail-en.png` / `s106-04-detail-ready-en.png` — one draft: back, the title,
  the single red **Dismiss** secondary action, the digital receipt (bank, signed amount, bank
  moment, neutral **Pending** word), **Applied category** with swatch and truthful provenance
  (**Chosen by you**), the ledger type selector as full-width radio rows (no truncated segment),
  and one sticky **Confirm and add to ledger** primary action.
- `screens/s106-05-preview-en.png` — explicit confirmation preview: amount `500,000 ₫`, type,
  device-time moment, description, category and the evidence boundary. Confirm and Back to review
  are the only actions.
- `screens/s106-10-stale-preview-en.png` — after a concurrent correction, **Preview is stale**,
  the old values are labelled **Values from the earlier preview (not saved)** and Confirm is
  withheld; only **Check current values** and Back are offered.
- `screens/s106-07-dismiss-confirm-en.png` — dismissal asks `Dismiss draft #2?` and explains that
  it records nothing; Keep reviewing and Dismiss draft are the only choices.
- `screens/s106-08-category-saved-en.png` — a category change saved with **Corrections saved.**
  and the primary action unchanged; the secondary Save action is present, not competing.
- `screens/s106-09-link-attached-en.png` — linked note excerpt with excerpt, reading, amount,
  provenance revisions and a full **Unlink excerpt** action.
- `screens/s106-15-confirmed-terminal-en.png` / `s106-16-dismissed-terminal-en.png` — truthful
  terminal states: a green **✓ Confirmed** with **Linked ledger record: #1**, and a slate
  **✕ Dismissed** with no record; both show the bank evidence and no confirm action.
- `screens/s106-13-missing-draft-en.png` / `s106-14-read-error-en.png` — missing and unreadable
  drafts state what happened, offer Back (and Retry for the read error), and change nothing.
- `screens/s106-12-detail-vi-ime.png` — Vietnamese at 1.5× with the numeric keyboard open: the
  focused amount field is fully visible above the keyboard and the sticky
  **Xác nhận ghi vào sổ** action stays above the keyboard.

## Design comparison and deliberate deviations

- The approved inbox render uses a segmented **Cần xử lý / Sẵn sàng** control. This
  implementation uses the plan's grouped **Needs your decision / Needs a category / Ready to
  confirm** sections with one order action. The backend queue is one bounded, ordered read with no
  group filter; a client-side segment filter would hide rows on other pages, so grouping is the
  truthful equivalent of the approved intent. The chronological override is preserved.
- The approved detail render shows a raw bank SMS text. The database intentionally does not store
  the raw body; the screen shows the parsed fields kept as evidence and says explicitly that the
  raw body is not stored. No SMS text is fabricated.
- Bank logos are not used; a neutral bank/receipt glyph and the bank name identify the source, so
  no proprietary asset is copied.
- Editable amount/date/time/description/type fields are added to the detail because the plan
  requires focused edits; they do not change the receipt or confirm anything by themselves.
- The category picker is a bottom sheet; duplicate candidates are contextual warning cards with an
  explicit open action; note link decisions are Attach/Refuse/Unlink only. These are task-serving
  children/sheets, not stacked unrelated panels. There is one filled primary CTA.
- During the keyboard-open state the sticky CTA sits above the IME and the focused field is
  scrolled into view. The amount field's text-selection handle is visible in the IME frame; that
  is the system control for the focused field, not app chrome.

## Defects found while reviewing real captures, and corrections

1. Inbox title truncated by the order control in the top bar → the order control moved into the
   content, the title shortened to **Review inbox / Duyệt tin**, Refresh kept in the top bar.
2. The unreadable-amount fallback squeezed the bank name off the card → the card now says
   **Amount not read** and keeps the bank name visible.
3. Three competing red/amber lines for one unsupported draft → one specific amber
   **unsupported bank format** chip plus **Amount not read**.
4. Category wording was inconsistent between card and detail → one **Category: …** card line.
5. A warning glyph on a merely pending receipt implied a problem → neutral **Pending** word;
   only confirmed/dismissed use the status chip.
6. The segmented type label truncated (**Internal tra…**) → full-width radio rows.
7. `VND` suffix on evidence amounts differed from the design-system `₫` → exact amounts use `₫`
   on receipt, evidence, links, preview and Chat's draft chip.
8. At 1.5× the count/order/refresh block was cramped → one count line, full-width order help and
   a separate action; Refresh is a compact top-bar action.
9. The stale preview listed old values without saying so → labelled as earlier, not-saved values.
10. At 1.5× the focused amount field was hidden by the keyboard/sticky bar in a first capture.
    The harness was freezing frames during `Thread.sleep`; after pumping the Compose clock and
    keeping the CTA above the IME, the final frame shows both. A delayed bring-into-view request
    is retained for the post-IME geometry.
11. A third-party heads-up appeared in captures (privacy) → discarded and recaptured with the
    heads-up suppressed only for capture; all final frames are clean.

## Remaining minor observations

- The order action is a text button aligned to the trailing edge below its explanation; it is
  understandable and accessible, but a sort icon could make it more discoverable in S10.9.
- The category change and note-link flows use native sheets/dialogs rather than full child routes;
  they are bounded and task-specific and do not compete with the one primary action.
- The screenshots are from the isolated synthetic fixture database; they are production UI over
  isolated data, not real bank notifications.
