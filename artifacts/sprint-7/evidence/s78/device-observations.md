# S7.8 paired review-effort evidence — observed on the phone

**Task:** S7.8 — Prove reduced review effort
**Attempt:** S7.8-impl-02 (resumed after S7.8-impl-01 paused on the secure keyguard)
**Worker agent:** S78EffortSilverResume (`silver-task`, full route)
**Review cycle / round:** S7.8-cycle-1 / round 1 — not yet assessed
**Base commit:** `12d0b550dc4fbc3ac91865de9feaadf24c6664b2` (repo `F:/ai-ml/aegis-f`, ref `refs/heads/main`)
**Device:** `AAAJBB4930914141`, Android API 34, 720×1610 (portrait locked for the run)
**Date of observations:** 2026-10-25

This file records what was actually observed on the physical phone. It is not a review verdict.
Where the harness (ADB + `uiautomator`) adds cost, that is stated; no human-response claim is made.

## 1. Builds compared

| Role | Source | Package | APK SHA-256 | Bytes |
| --- | --- | --- | --- | --- |
| Earlier per-open manual invocation | `b1c98fd` (S7.2 base, last product state before automatic intake classification) | `io.github.triet4p.aegisf.s78baseline` | `5f29f11c678f4bd56961a2a43523203adb34311e3347f9eea7d6a66b7de22b92` | 88,794,713 |
| Current automatic intake | `12d0b550…` (`HEAD`) | `io.github.triet4p.aegisf.s78current` | `05fa7f7d4bc4cea60613918759f449e013371cff40ddbed8eb3c1efa36e0d387` | 88,893,017 |

Both were built once from separate `git archive` trees with only an `applicationIdSuffix` patched in the
copies (`F:/ai-ml/s78/src-baseline`, `F:/ai-ml/s78/src-current`). The repository worktree and the user's
install (`io.github.triet4p.aegisf`), data, model and allowlist were never written. The pinned model was
streamed read-only into each isolated package and hash-verified
(`laya.onnx a874eb25…`, `laya_config.json 5049005d…`).

The baseline is the actual product commit that still contained `ui/BankDraftSuggestion.kt` and the review
screen's per-open/per-edit `requestClassification`; the current tree's S7.6 commit deleted that path. No
manual invocation was emulated.

## 2. Paired fixture actually used

Six categories were seeded into each isolated database while the app was stopped (ids 1–6: Food, Housing,
Transport, Bills, Shopping, Entertainment). Seven supported VietinBank-shaped notifications were posted
once from the allowlisted `com.android.shell`; both listeners stored the same seven deliveries. Final ids:

| Label | Draft | Amount | Memo | Stated time | Decision |
| --- | --- | --- | --- | --- | --- |
| A | 1 | −9,500,000 VND | S78 monthly rent | 07:38 | confirm Housing (model suggested Housing) |
| B | 2 | −62,000 VND | S78 coffee with team | 07:42 | confirm Food |
| C | 3 | −33,000 VND | S78 card payment | 07:47 | confirm Bills |
| D | 4 | −45,000 VND | S78 payment to friend | 07:51 | confirm Shopping |
| E | 5 | −120,000 VND | S78 lunch | 08:02 | confirm Food |
| F | 6 | −120,000 VND | S78 lunch | 08:02 | dismiss (duplicate of 5) |
| G | 7 | −87,000 VND | S78 dinner and movie night | 08:07 | correct Entertainment → Food, then confirm |

Draft 6 stores `possibleDuplicateOfDraftId = 5`; it is never merged automatically.

### 2.1 Fixture re-establishment (disclosed)

The seven notifications posted for S7.8-impl-01 were no longer held by the system after the owner unlocked
the phone, and an earlier `prep_pair` run had snoozed the still-active `s78c…g` tags, so re-posting the
same tags replayed as suppressed. To keep a valid pair I unsnoozed those keys and posted the five missing
tags; the baseline and current apps were reset once more and both received the same seven deliveries.
Recorded state per build before either walkthrough:

- baseline: 7 pending drafts, **0** classification snapshots, 0 transactions;
- current: 7 pending drafts, **7** stored suggestions (`MODEL`/`NO_FIT`), 0 transactions, produced by
  intake with no screen opened.

A clean full-pair prep run earlier in the session stored all seven current suggestions in **12.7 s**
(`prep-pair` output, 7 × `RESULT`). Both reset/re-post cycles are harness recovery, not product behavior,
and are excluded from the effort comparison. The files from the aborted two-draft pair are kept with an
`-aborted` suffix (`prep-pair-aborted.json`, `current-pre-review-snapshots-aborted.json`,
`baseline-pre-review-snapshots-aborted.json`); the authoritative tables are
`final-ledger-comparison.json` and §4 below.

## 3. Queue order observed on the current build

Captured with screenshots plus a full top-to-bottom text scan (`queue-sequence.json`):

```text
Uncertainty-first ("Needs attention"):
  Needs your decision            → 5, 6          (possible-duplicate family)
  No fit — choose a category     → 2, 3, 4
  Suggestions, least certain first → 1 (Housing 79%, sharpness 73%), 7 (Entertainment 94%, sharpness 87%)

Oldest first override:
  1, 2, 3, 4, 5, 6, 7
```

- The order matches the frozen S7.1 policy exactly (Rank 0 → Rank 1c → Rank 2 by ascending sharpness).
- The override is a real control (`Oldest first` / `Needs attention`), it reads the same drafts, and
  switching back restored the policy order.
- The screen states "This order stays as read until you refresh", so a stored score change cannot reorder
  the list under the reader. `Refresh` and `Next work` remain available; neither was needed here.
- The duplicate card says "Needs attention: possible duplicate" and keeps both candidates.
- The high-confidence card is shown with its own scope and numbers ("Relatively confident suggestion ·
  Housing 79% · unknown 0% · sharpness 73% · 6 of 6 active options evaluated") and never confirms itself.

## 4. Decisions, ledger and dashboard reconciliation

Identical user decisions were executed on both builds (order E, F, B, C, D, A, G), each through the real
review form: explicit Expense type, explicit category row, **Confirm transaction → "Confirm this record?"
→ Confirm**, and for F **Dismiss draft → "Dismiss draft #6?" → Dismiss draft**.

| Check | Baseline `b1c98fd` | Current `12d0b55` |
| --- | --- | --- |
| Draft outcomes | 6 CONFIRMED, 1 DISMISSED, 0 pending | 6 CONFIRMED, 1 DISMISSED, 0 pending |
| `transactions` | 6 × EXPENSE, `SUM = 9,847,000` VND | 6 × EXPENSE, `SUM = 9,847,000` VND |
| Income / transfers | 0 / 0 | 0 / 0 |
| Confirmation operations | 6 `CONFIRM_DRAFT APPLIED`, 1 `DISMISS_DRAFT APPLIED` | 6 / 1 (same) |
| Classification requests | 10 during review (7 opens + 3 applied notes; 1 note request failed) | 7 automatic at intake, **0** during review |
| Dashboard "CONFIRMED SPENDING" | **9,847,000 VND**, "6 confirmed expenses" | **9,847,000 VND**, "6 confirmed expenses" |
| Dashboard income / transfers / review | 0 VND / 0 VND / 0 waiting | 0 VND / 0 VND / 0 waiting |

- The dashboard equals the transactions table and the fixture sum exactly on both builds; nothing was
  assumed from the expected list.
- The override is visible in the data: draft 7 stores `suggestedCategoryId = 6` (Entertainment,
  confidence 0.8655, `MODEL`) while its confirmed transaction has `categoryId = 1` (Food).
- Draft 6 (F) has no transaction and a terminal `DISMISSED` replay identity; its duplicate flag still
  points at draft 5.
- No duplicate confirmation exists: each draft maps to exactly one transaction id, and there are no
  extra ledger rows.

Full tables: `final-ledger-comparison.json`; screenshots `dashboard-baseline.png`, `dashboard-current.png`,
`inbox-*-final.png`, `current-detail-*.png`, `baseline-detail-*.png`.

## 5. Paired effort measurement

### 5.1 Definitions

- **Description entries** = typed user entries into the review form to obtain a model suggestion.
- **Deliberate taps** = ADB `input tap` actions the user would perform (navigation, open, type, category,
  preview, dialog confirm/dismiss, note-field focus). Swipes are reported separately because the harness
  performs extra reset/search/capture scrolling that a human would not repeat.
- **Review-time model requests** = stored `REQUEST_CLASSIFICATION` operations created while the review
  screen was in use.
- **Explicit review waits** = time the harness spent polling only for a model result during review.
- **Wall time** = process wall clock, including every `uiautomator` dump; it is a harness number, not a
  human timing.

### 5.2 Observed values

| Metric | Baseline (8.07 ks wall) | Current (part 1 + part 2) |
| --- | --- | --- |
| Description entries | **4** (`lunch`, `coffee`, `electricity bill`, `clothes shopping`) | **0** (the field no longer exists) |
| Deliberate taps | 39 | 36 recorded = **35** for one uninterrupted pass (the pass was split after a harness stop; each part tapped the nav once) |
| — breakdown | nav 1, open 7, type 7, note-field 4, category 6, preview 6, dialog confirm 6, dismiss 1, dialog dismiss 1 | nav 1, open 7, type 7, category 6, preview 6, dialog confirm 6, dismiss 1, dialog dismiss 1 |
| Review-time model requests | 10 | 0 |
| Suggestions stored before opening review | 0 / 7 | 7 / 7 |
| Explicit review waits | 42.5 s (3 note results at 12.3–14.4 s; one note produced nothing in 30 s) | 0 s |
| Recorded per-action driver time | 191.1 s | 199.8 s (split pass, includes capture scrolling) |
| Swipes (harness-inclusive) | 143 | 161 |
| Wall time | 863.3 s | 1018.9 s (203.4 + 815.7) |

### 5.3 Honest conclusion

- The automatic path removed the **manual model invocation**: all seven suggestions existed before any
  review screen was opened on the current build, while the baseline stored nothing until each draft was
  opened (10 review-time requests, including three applied note requests).
- It removed **4 description entries** in this fixture and all **review-time waiting** for a model answer.
- It did **not** reduce the deliberate decision taps: type, category and the explicit confirmation dialog
  remain user actions on both builds, as designed. The measured tap difference is exactly the four
  note-field taps the current form does not have.
- Wall-clock time is **not** evidence of improvement here: ADB/uiautomator dumps, forced scroll searches
  and fixed sleeps dominate it (the current pass also needed extra capture scrolling). No human timing or
  statistical claim is made from it.
- One baseline note request ("clothes shopping" on draft 4) ended in "A suggestion is unavailable right
  now" and stored nothing within 30 s. That is an observed baseline fragility, kept in the record; the
  user still chose the category manually and confirmed.

## 6. Limitations

- One seven-draft fixture, one pinned model, one phone, one session; not a statistical or multi-device
  result.
- The fixture memos are informative English text, which is the model's documented first language; opaque
  real-bank memos are expected to do worse (S7.7 measured 1/6 recall at 206 categories).
- The four typed notes were a deliberate walkthrough choice (the baseline form offers an optional note to
  give an opaque memo meaning); they are not mandatory for confirmation.
- Timing is harness-driven. Taps and description entries are directly observed; seconds are not human
  seconds. The current pass was split into two invocations after a harness stop, so its wall time is the
  sum of both, and the tap total is corrected by the duplicated navigation tap.
- Both builds share the same isolated processes: the current build's snapshot also exists for the
  baseline after its review (its `REQUEST_CLASSIFICATION` rows), but it was not auto-classified at intake.

## 7. Manual fallback (unchanged)

- Both builds keep type, category and confirmation manual; no suggestion is required to confirm, dismiss
  or record. Missing/failed/cancelled inference stays actionable (S7.6 controls), and a stale or no-fit
  suggestion never selects, confirms or files anything.
- The current build's only removed option is the optional per-draft suggestion note. A bank memo that the
  model cannot place ends in the visible no-fit group with manual category choice, not in a dead end.

## 8. Device state and cleanup

- Captured before any change this attempt: `stay_on_while_plugged_in = 0`, `accelerometer_rotation = 1`,
  `user_rotation = 0` (`device-state-session.json`). The device had rotated to landscape during the run;
  portrait was locked temporarily.
- After the evidence was captured: both isolated packages were uninstalled (`pm list packages` no longer
  lists them); `svc power stayon false` and explicit `settings put` restored the captured values — verified
  `stay_on_while_plugged_in = 0`, `accelerometer_rotation = 1`, `user_rotation = 0`.
- The seven synthetic `s78*` notifications were already no longer held by the system at cleanup (the
  `cmd notification list` output no longer contains them); the impl-01 snooze keys were likewise already
  expired (`unsnooze` reported no matching notification). The keys are preserved in
  `snoozed-shell-keys-impl01.txt`.
- The temporary build trees (`F:/ai-ml/s78`) and the harness scratch pulls (seed/verify/tmp dirs,
  `uiautomator` dumps on `/sdcard` and `/data/local/tmp`) were removed. Durable evidence stays in this
  directory, including the two final Room pulls.
- The user's install, ledger (timestamp 2026-10-24, unchanged), model and allowlist were verified intact
  after cleanup; only the isolated packages were ever installed or removed. No credentials were touched.

### 8.1 Aborted harness runs (not counted)

Disclosed separately from the two measured passes; none contributed a decision, tap count or timing to §5,
and none touched user data:

1. baseline attempt stopped immediately: the "Review" nav lookup matched a draft card and opened draft 7;
2. baseline attempt stopped at the second draft: swipe coordinates were off the portrait screen (device had
   rotated to landscape with auto-rotate on);
3. baseline attempt stopped at the sixth draft: the note-field search used centre-x swipes that the form's
   text fields consumed;
4. baseline attempt surfaced two missing steps (the form requires an explicit Expense type, and confirmation
   goes through "Confirm this record?" → Confirm); stopped and re-run;
5. baseline attempt stopped when a typed note request answered "unavailable" and stored nothing (the harness
   now records that outcome and continues instead of aborting);
6. current pass part 1 stopped after E/F because the "Category suggestion" header was outside the viewport
   (the harness now scrolls to it non-fatally); part 2 resumed and skipped the already-terminal E/F;
7. one prep-pair cycle produced only two drafts because prep had snoozed the still-active `s78c…g` tags;
   unsnoozed and completed as described in §2.1.

## 9. Evidence inventory (this directory)

| File | What it is |
| --- | --- |
| `device-observations.md` | this report |
| `final-ledger-comparison.json` | both final DBs: snapshots, transactions, draft states, operations |
| `queue-sequence.json`, `queue-*.png/txt` | policy order and Oldest-first override |
| `baseline.json`, `current-part1.json`, `current.json` | measured action lists, waits, note outcomes |
| `inbox-*-start/final.png/txt`, `*-detail-*.png/txt` | review screens for every draft |
| `dashboard-baseline.png/txt`, `dashboard-current.png/txt` | displayed totals |
| `db-baseline-final/`, `db-current-final/` | pulled Room databases (binary; excluded from checkpoint scope, kept for audit) |
| `drive_s78.py` | the harness that produced the actions, captures and DB queries |
| `device-state-session.json` | captured/changed device settings and restore plan |
| `resume-unlocked.png` | the unlocked device at resume |
| `*-aborted.json` | artifacts of the aborted two-draft pair; superseded, see §2.1 |
