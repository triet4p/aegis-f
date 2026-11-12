# S10.8 visual review — categories/rules and settings destinations (round 2)

Approved references personally opened: `artifacts/sprint-10/renders/10_categories_rules.webp`,
`11_settings_hub.webp` (with the plan's Settings-hub/one-task refinement overriding the stacked
render), specification §§3.10–3.11 and screen specifications §§10–12.

Actual captures personally opened: every current `s108-*.png` and `device-*.png` in `screens/`,
including the round-2 blocked-delete, race, read-error, stale-rule, no-network, safe system-intent
and archived-row captures. Observations below come from opened images.

## What matches the approved direction

- **One task per tab.** Categories/Rules are separate tabs with one sticky primary action each
  (Add category / Add rule); Settings is a grouped navigation hub with no forms, credentials or
  destructive controls. Each child owns one task and one Back.
- **Catalog.** Top bar, segmented tabs, Active/Archived filter, category mark (stable colour + one
  catalog glyph), textual state, Edit/Delete actions, and a bottom Add action.
- **Archived rows.** One textual badge `Archived` plus a separate caption `History kept`; no
  doubled phrase. Archived state is never hue-only.
- **Reference-blocked delete.** A referenced category shows **This category is still in use**, the
  exact reference counts, **Archive instead**, and **no destructive button**. An archived
  referenced category shows its own blocked copy and only Cancel. An unreferenced category keeps
  the destructive `Delete permanently` confirmation. The stale-race dialog switches to the blocked
  state after the transaction refuses.
- **Rules.** Exact-description matching is labelled truthfully (existing normalized matcher; no
  prefix/wildcard backend). List/add/edit/remove are focused; a category archived while the sheet
  is open is refused, its stale choice is removed, and Save is disabled.
- **Settings hub/children.** Language/appearance on the hub; Bank notifications, Provider & model,
  Skills & history and Privacy & retention as focused children; no fake backup/release/intake.
- **Bank.** Status/diagnostics/allowlist, one primary system-access action, confirm-on-remove,
  reconnect/app-info secondary. System access and app-info pages were opened and returned; no
  permission or setting changed.
- **Provider.** Protocol/preset choose rows, HTTPS endpoint + disclosure, masked credential,
  output limit, reasoning/parallel options, one sticky test-then-save, no-network refusal, provider
  failure that keeps edits and stores nothing, discard guard.
- **Skills/history and privacy** are read-only disclosures/lists with no fake operational panels.
- **Large text/IME:** VI 1.5× tabs/filters/rows wrap, filters use `FlowRow`, actions sit on a second
  line, provider save bar clears the IME, and nav-bar padding keeps child content reachable.

## Round-2 corrections prompted by round-1 findings and re-opened captures

1. **F1/F2 archived-row wording.** Badge `Archived` + caption `History kept`; native regression
   asserts the old `Archived · history kept` phrase is absent. The stale real-shell
   `device-archive-instead-result-dark.png` was removed; current
   `device-archived-row-final-dark.png` shows the split and was opened.
2. **F5 deletion contract.** Referenced categories are no longer deletable: impact-first, exact
   counts, Archive instead, no destructive button; archived referenced categories show only Cancel.
   The destructive button is re-checked inside the database transaction, and a race dialog re-reads
   into the blocked state (`s108-04c`). Unreferenced delete still works (`s108-04b`, real Gym2).
3. **Delete title/counts.** Blocked dialog title is `This category is still in use`, and reference
   lines say “uses this category” rather than the old “becomes uncategorized” wording.
4. **Archived blocked copy.** No longer tells an already archived category to archive; it says the
   archived category is still used and references must be removed first.
5. **F3 rules mapping.** Plan line 71 and README trace the exact-description matcher; the Rules tab
   shows one matching explanation, and the sheet keeps the exact-match detail.
6. **F6 safe intents.** System notification-access and app-info pages opened/returned on the
   isolated package; no permission/setting change. Reconnect remains an explicit state-changing N/A
   pending owner approval; its no-access refusal path is tested.
7. **F7 no-network honesty.** Provider screen consumes the production connectivity signal; the
   no-network capture shows the refusal, zero profiles, and no network call.
8. **F8 read/stale.** Closed-storage read error and stale archived-category rule refusal are
   captured; the read-error Add action is disabled; stale choice is dropped and Save disabled.
9. **1.5× tab/filter/row layout** was fixed and re-opened (`s108-12`): no truncation/vertical strips.
10. **Bank status and provider choice rows** no longer squeeze labels (`s108-07`, `s108-08b`).
11. **Hub values** moved into supporting copy; bank/provider status no longer crushes titles
    (`s108-06`, real light/dark settings captures).
12. **Provider status captures** hide the IME and scroll the status/row into view
    (`s108-08`, `-08b`, `-09`, `-15`).
13. **Nav-bar viewport padding** added to settings children; real Settings hub shows About fully.
14. **Heads-up notifications** were disabled for the capture run and restored to 1 afterwards; no
    notification appears in the current evidence.

## Deliberate deviations from the illustrative render

- Per-category pictograms: the catalog stores a name and stable id, not an icon. Rows use the
  stable category colour + one shared catalog glyph + a word state. No icon capability invented.
- Prefix/wildcard rules in the render are illustrative. The stored matcher is one normalized exact
  description; the plan now records this mapping. A prefix matcher would be a new backend feature.
- Settings render is stacked; the owner/plan refinement makes it a navigation hub. Children own
  the forms and destructive actions.
- No simulated intake test, backup, restore, cache-clear or release control exists in this release;
  none is drawn as working.

## Limits

- No live provider request for S10.8; success uses the existing adapter with a deterministic
  transport. The no-network path uses the production connectivity signal, but the second real
  airplane provider attempt was stopped by the owner restriction and is not claimed.
- System reconnect was not invoked (state-changing, pending owner approval); no-access refusal is
  tested non-radio.
- 1.5× is app-scoped; system font scale is S10.9. Full Android suite is S10.9.
