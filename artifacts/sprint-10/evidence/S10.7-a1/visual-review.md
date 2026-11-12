# S10.7 visual review — manual record / correction

Approved reference personally opened:
`artifacts/sprint-10/renders/06_manual_record.webp` (design contract §3.5 and screen
specification §5). Actual captures personally opened: every image listed in `verification.md`
§5, plus the real-shell PNGs in this directory.

## What matches the approved direction

- **One screen, one task, one primary action.** The form has one Close/Back control and one
  full-width sticky **Save to ledger** / **Save changes**. No second Save, Confirm or Cancel
  button competes with it. The success panel has one filled **Done** and a secondary text
  **Record another**.
- **Top bar.** Close (X) on creation, Back on correction, centered title, no duplicate action.
  Bottom navigation is hidden on the route (real-shell `device-01` proves the shell behavior).
- **Segmented transaction type.** Expense / Income / Internal transfer use the same light
  container and selected white pill as the render. The selected label and check are not
  colour-only.
- **Hero amount.** A white rounded card with the uppercase “AMOUNT (VND)” label, a large
  monospaced number, and the amount written out beneath it. The exact digits are authoritative;
  the words never replace or alter them.
- **Category.** A light-surface tile with the category’s stable colour swatch, its user label and
  a visible check when selected. A full **All categories** sheet carries every active category and
  a **No category** choice; archived rows are not offered for a new assignment. The archived
  category already on a corrected record stays visible with an **Archived** word and an explicit
  warning.
- **Details and sticky CTA.** A white details card holds the date/time row and note field; the
  Save bar sits above the gesture bar and above the keyboard (`s107-03`, `s107-12`,
  `device-02`).
- **Human copy and states.** Refusals name the field and keep the typed digits; stale record and
  stale source messages say nothing was saved and the entries are still there. No database,
  token, raw ID or debug vocabulary appears. The source card names the reading, exact amount and
  local date/time.

## Concrete mismatches found by opening the actual screens, and corrections

1. **First 1.5× capture clipped the type labels** (“Chuyển khoản …”). Fixed with an adaptive
   selector: one row at normal text, two rows at ≥1.3× (Expense/Income, then full-width
   Transfer). Re-captured `s107-12`/`s107-13`: no clipped words.
2. **The amount card carried a redundant second “Saves exactly as …” line.** The approved render
   has only the words; removed it, and made the neutral hint gray while the written amount is
   primary.
3. **Transfer help was said twice** at large text (type help plus no-category note). Replaced
   with one transfer sentence that states both the movement and the absence of a category.
4. **The segmented container was too faint** after the first capture. Strengthened it to the
   theme’s `surfaceVariant`, so unselected options read as part of a control rather than plain
   labels.
5. **Source-note labels exposed ISO date and `VND`.** They now use the shared `₫` format and the
   device-local `dd/MM/yyyy` wording.
6. **The first archive/stale captures did not show the refusal** because the error sat below the
   keyboard fold. Re-captured after hiding the keyboard and scrolling the refusal into view.
7. **The first source capture only showed the card header above the sticky bar.** Re-captured
   with the whole source card and its Change/Remove actions visible.
8. **Round-2 review: the retained archived badge in the full category sheet was still English.**
   Threaded the localized `archivedLabel` through the sheet and added the VI 1.5× regression
   `s107-14-vi-archived-sheet`; it shows `Đã lưu trữ` and no `ARCHIVED`.
9. **Round-2 review: the category tile accessibility description appended English
   `", selected"`.** Removed it; the `selectable` RadioButton semantics already expose selection.
   The localized archived label on the tile was also allowed to wrap at 1.5× so it is not truncated
   (`s107-14`).

## Deliberate deviations from the illustrative render

- The render’s 4×2 **icon** grid assumes a stored category icon. The ledger stores a category
  name and identity, not an icon, so the real form uses the stable category colour swatch plus
  the user’s label in a two-column tile grid, with the full picker in a sheet. This avoids
  inventing a category-icon capability.
- The render shows **merchant** and **account/wallet** fields. The ledger contract stores a
  description/note and no counterparty or account, so the form shows **Note** and does not invent
  either backend field.
- The date/time row opens a focused sheet with exact `YYYY-MM-DD` / `HH:mm` fields, Today/Now
  actions and the device-zone explanation rather than a calendar widget. It preserves the
  existing grammars and exact conversion; it is still a focused picker, not a stacked panel.
- The approved render is in Vietnamese and light mode. The real evidence includes English,
  Vietnamese, light and system dark, because the app ships both languages and both themes.

No approved-design element was deliberately dropped: exact VND amount, type, category, date/time,
memo, source handoff, one Save, insets and keyboard behavior are all present.
