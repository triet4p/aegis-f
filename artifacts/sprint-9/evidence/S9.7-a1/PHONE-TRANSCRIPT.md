# S9.7-a1 phone action transcript (scripted ADB guided by screenshot reads; not human/manual use)

Device: `AAAJBB4930914141` (ALT-LX2), 720x1610, Android user build. Isolated package
`io.github.triet4p.aegisf.s812evidence`; both APKs installed with `adb install -r` only; no
uninstall, no data clear of the owner app. IME throughout:
`com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`. Every typed value
was read back from the dialog before saving; the keyboard was hidden with Back before every
button tap. Coordinates below are original-device pixels.

## Round A — first staged seed (pre-correction fixture; see INCIDENT-DB-CLEAR.md)

Seed command (the only run that used the now-removed clear):

```
adb shell am instrument -w -e s97_stage seed -e class \
  io.github.triet4p.aegisf.data.S97DeviceSurfaceInstrumentedTest \
  io.github.triet4p.aegisf.s812evidence.test/androidx.test.runner.AndroidJUnitRunner
```
Result: `OK (1 test)`. It seeded a pending note with an accepted link and a hosted category, plus a
confirmed note with a retained link and ledger row.

1. `monkey -p io.github.triet4p.aegisf.s812evidence ... LAUNCHER` → `s971_launch.png/xml`:
   Dashboard, `20,000 VND`, `1 confirmed expense`, Review count `1`.
2. Tap `Review` [447,1468] → `s972_review_before.png/xml`: `1 waiting`, section
   `Ready to confirm`, card label `Category chosen — confirm or dismiss`.
3. Tap `Chat` [273,1468], two upward swipes to `RECENT CAPTURES & PROVENANCE` →
   `s973_chat_recent.png/xml`, `s973_chat_A.xml`: confirmed note first, then pending note
   `S97A pending note fuel 50000` with `Linked · 1 bank draft(s)`.
4. Tap `Edit` on the pending card [460,1212] → `s974_edit_A_dialog.png/xml`: `Edit saved capture`,
   field `S97A pending note fuel 50000`, and the new policy copy:
   “If this text has saved interpretations, changing or deleting it clears their derived content
   and removes links to drafts still waiting for review. Refused link decisions and links to
   confirmed records stay as provenance. The capture identity stays; a confirmed ledger record is
   not changed. A category the hosted workflow chose from this note is reopened for review; your
   own category choice is kept.”
5. Focus the field [360,600], move to end, type `X`; hide keyboard. `s975_edit_A_typed.png/xml`
   reads `S97A pending note fuel 50000X` (verified before saving).
6. Tap `Save changes` [490,1167] → `s976_edit_A_saved.png/xml`: card now
   `S97A pending note fuel 50000X / Saved · unresolved · interpretation needs review`.
7. Tap `Review` [447,1468] → `s977_review_after.png/xml`: section `Needs a category`, label
   `Category not chosen yet`, `1 waiting`. EXPECT reopened hosted category; OBSERVED yes, without a
   manual refresh.
8. Tap `Chat`, scroll to the confirmed card, `Edit` [460,748], type `X`, hide keyboard,
   `Save changes` [490,1167] → `s979_edit_B_typed.png/xml` (`S97B confirmed note coffee 20000X`),
   `s980_chat_B_after.xml`: card still `Linked · 1 bank draft(s)`.
9. Tap `Manage events` [274,748], scroll down → `s981_confirmed_provenance.png/xml`,
   `s982_confirmed_link.png/xml`: event `Unresolved · SOURCE_EDITED`, source-conflict notice,
   `Attached · bank draft #12`, `confirmed · S97 synthetic purchase`,
   `This draft is a confirmed ledger record. The note reference is kept as provenance and the
   ledger is unchanged.`, no Unlink action.
10. Tap `Dashboard` [105,1468] → `s983_dashboard_after.png/xml`: still `20,000 VND`,
    `1 confirmed expense` after both note edits.
11. Verify stage on the same seeded ids → `OK (1 test)`.

## Round B — corrected fixture (no clear, unique run-scoped rows)

After removing the clear and adding a run id, the androidTest APK was rebuilt with
`-PS812_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s812evidence :app:assembleDebugAndroidTest`,
installed with `adb install -r`, and seeded again. The new run id was
`076be17ea54d4429b463b427fb6eb96c`; captures 31/32 and drafts 13/14 were recorded only in the
fixture's own preferences. Seed stage: `OK (1 test)`.

12. Launch → `Chat` → scroll to recent → `s984_corrected_seed_chat.xml`: newest cards
    `S97B confirmed note coffee 20000 076b…96c` and `S97A pending note fuel 50000 076b…96c`, each
    `Linked · 1 bank draft(s)`; Review count `2`.
13. Tap `Edit` on the new pending card [460,1111]; type `Y`; hide keyboard; read back
    `S97A pending note fuel 50000 076be17ea54d4429b463b427fb6ebY96c`
    (`s985_corrected_edit_A_typed.png/xml` — observed Y landed before the trailing `96c`, cosmetic
    only); `Save changes` [490,1167].
14. Tap `Review` → `s986_corrected_review_after.png/xml`: `2 waiting`, section `Needs a category`
    (`2 on this page`), `Category not chosen yet`. Both pending drafts are reopened; the newly
    edited one is no longer ready.
15. `Chat` → scroll to new confirmed card → `Manage events` [274,531] → scroll →
    `s990_corrected_confirmed_link.png/xml`: `Attached · bank draft #14`,
    `confirmed · S97 synthetic purchase 076b…96c`, retained-provenance sentence, no Unlink.
    Edit of that note was `s988_corrected_edit_B_typed.png/xml` (read-back text verified).
16. Tap `Dashboard` → `s991_corrected_dashboard.png/xml`: `40,000 VND`, `2 confirmed expenses`
    (one per seed run) — the note edits added or removed no ledger row.
17. Verify stage on the corrected run ids → `OK (1 test)`.

## What the transcript does and does not prove

- Proves the changed surfaces live on the isolated phone: capture edit dialog copy, review card
  moving out of `Ready to confirm` after a note edit, confirmed-record link retention with the
  explicit “provenance/unchanged” message and no Unlink, and the Dashboard total unchanged.
- The category reopen is produced by the production `reviseCapture` path invoked by the real
  `Save changes` button, not by a direct test call in this transcript.
- Full stored-state assertions (category origin `NONE`, link/evidence retained, event
  `SOURCE_EDITED`, ledger row equal, pending status) are in the verify stage and the two
  instrumented classes; the screenshots alone are not used as state proof.
- The isolated app database now also contains the Round A rows; they were not cleaned after the
  incident and are disclosed in `INCIDENT-DB-CLEAR.md`.
