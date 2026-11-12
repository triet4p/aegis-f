# S10.9 attempt a3 — round-2 F1 correction verification

- **task_id:** S10.9
- **attempt_id:** S10.9-implementation-a3 (continuation of a1/a2; both handoffs preserved)
- **runtime agent_id:** S109Finish
- **assigned role:** `gold-task`, full route; no commit/staging/push/checkpoint
- **review cycle:** `S10.9-evidence`, round 2 (round 1 = changes_requested/F1, 1 assessed round
  consumed; round 2 is a correction of F1 only and is **not assessed yet**; no round 3 authorized)
- **criterion reference:** `docs/sprint-plans/sprint-10.md:75-78`, with the design pointers used in
  round 1 (`artifacts/sprint-10/design/current-to-proposed-audit.md`,
  `docs/design/sprint-10-mobile-experience-specification.md` §2.1/§3,
  `artifacts/sprint-10/design/screen-specifications.md` §12)

This attempt makes **no source edit**. It verifies the corrected candidate a2 produced (the only
product delta from the round-1 assessed candidate is the `AiChatScreen.kt` masking-note line-cap
removal) and refreshes the affected native evidence. It does not claim task PASS.

## 1. Candidate identity (frozen)

- Repository `F:/ai-ml/aegis-f/.git`, worktree `F:/ai-ml/aegis-f`, `git -C F:/ai-ml/aegis-f`,
  ref `refs/heads/main`, HEAD `6813d71e3c7ed0053207fb79e9e45ad1ca9ef689`, base
  `6813d71e3c7ed0053207fb79e9e45ad1ca9ef689`.
- Snapshot hashes: diff `ee4ba941aeba8f766f68a7c0683e434e866fef374884953e73aecaa8190609f7`,
  status `036bb22075a3e23c854e833500605dbf49799ebd54fb2d6cecf6f38c5f6d0961`
  (method in `source-snapshot.txt`); round-1 reviewer manifest snapshot
  `be5ff8960af1b54dc12f1b6039ff51c6a57f2608ab853c83435b508c491bddac` unchanged.
- Candidate APK `7304307fd7ab47e54c019b9eac8ebf9bf58b7dbcc990e3667ca1b6e891be691b`, test APK
  `5fa9a539bc16136d561766b6d848d7112baa35609914f1744f761a34a60fa570`. Both hashes match the
  round-2 hashes recorded by a2, so the verified artifact is the same corrected build.
- Installed on the isolated `io.github.triet4p.aegisf.s109evidence` package; on-device sha256 of
  both installed APKs equals the local hashes above. No app source/test file is newer than the
  APK, so the installed artifact corresponds to the frozen source.
- The F1 source state was re-read in the current diff: the masking-note `Text` no longer carries
  `maxLines = 2` / `TextOverflow.Ellipsis`.

## 2. Round-2 affected verification actually run

Device: HONOR ALT-LX2, Android 14 (API 34), 720 × 1610 px, 320 dpi. Isolated package and its test
package only; owner package `io.github.triet4p.aegisf` never launched, instrumented or cleared.

### 2.1 Full scripted Chat suite — 13/13 PASS

```
adb shell am instrument -w -e class \
  'io.github.triet4p.aegisf.ui.S105ChatInstrumentedTest#progressiveStreamingStopKeepsPartialTextAndAllowsTheNextMessage,...13 selectors...' \
  io.github.triet4p.aegisf.s109evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (13 tests), 74.8 s** (`logs/s105-scripted-final-run.txt`). Covered: progressive
streaming → Stop keeps partial text → next message; stored-history load once; scroll-follow and
jump-to-latest; provider failure/retry limit; durable history read without duplication; link
attach/reject/unlink; stale revision refusal; bilingual outcome copy; clarification continuation;
stale attached context blocks send; no-provider guidance; offline warning; composer/send usable at
1.5×.

An earlier identical run also passed 13/13 (70.9 s) but every frame captured the open quick
settings shade, so its frames were discarded and the run is preserved as evidence of the
environment problem, not of screens: `logs/s105-scripted-run-shade-invalid.txt`. The shade was
collapsed and the package cleared before the authoritative rerun above; the refreshed frames show
the app.

### 2.2 Affected S109 cross-flow and 1.5× checks — 2/2 PASS

```
adb shell am instrument -w -e class \
  'io.github.triet4p.aegisf.ui.S109CompleteExperienceInstrumentedTest#everyDestinationAndChildOpensAndReturnsToItsOwnContext,io.github.triet4p.aegisf.ui.S109CompleteExperienceInstrumentedTest#vietnameseDarkAndOneAndAHalfTextKeepEveryDestinationUsable' \
  io.github.triet4p.aegisf.s109evidence.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (2 tests), 49.7 s** (`logs/s109-affected-crossflow-15x.txt`). The cross-flow test
again walked all 11 primary destinations and children, exercised the unsaved-edit guards, ledger
filter retention across tabs/detail, note save → browse → detail → Chat with the attached note
visible before first send, and Review open/Back. The VI/dark/1.5× test again ran the unlabelled-
clickable and minimum-touch-target scans on every destination plus Chat. No assertions failed.

### 2.3 F1 evidence — refreshed 1.5× masking caveat

`screens/chat/s105-518-chat-large-text-en.png` (720 × 1610) was personally opened at full
resolution. The caveat is fully rendered across three lines:

```
Only locally masked context is sent.
Masking reduces direct personal values; it is not anonymity.
```

No ellipsis, no clipped line. The multi-line composer shows the typed second line and the **Send**
action; both remain fully inside the composer band above the system navigation bar. This is the
corrected counterpart of the round-1 failing frame `S10.9-a2/screens/chat/s105-518-chat-large-text-en.png`
(preserved unchanged as F1 evidence).

Also personally opened from the refreshed set:

- `screens/s109/s109-60-chat-vi-15-dark.png` — VI, dark, 1.5×, Chat empty/no-provider: heading,
  guidance card, composer placeholder and disabled Send are fully visible; bottom-bar labels wrap
  without clipping; 48 dp/name scans passed in the same test.
- `screens/s109/s109-16-chat-note-context-en.png` — cross-flow attached note fully readable above
  the placeholder before first send; composer and Send fully visible.

## 3. Unchanged accepted criteria — carried with provenance (not rerun)

The candidate differs from the round-1 assessed candidate only in the F1 line-cap removal
(`AiChatScreen.kt`), plus records. The round-1 review already accepted every other S10.9 row on
that candidate; those results remain valid and are cited, not re-executed:

- 11 destinations/children, navigation/back/return, unsaved guards, process recreation and
  force-stop durability, state families, long lists/text/exact money, accessibility and 48 dp,
  IME/insets: accepted in `artifacts/sprint-10/reviews/S10.9-review-01.md` §4, bound to the
  opened a2 frames and the 9/9 S109 suite (`S10.9-a2/logs/s109-final-suite.txt`).
- Chat lifecycle scripted 13/13 and the real authorized DeepSeek streaming/Stop/next/re-entry/
  cleanup proof: accepted in the same report; raw records in
  `S10.9-a2/chat-verification.md` and `S10.9-a2/logs/s105-{scripted-final,realdurable,reentry,
  provision,cleanup}-run.txt`. The real provider was **not** re-run in a3; no credential was
  read, staged or printed, and no `.env` value is present in a3 evidence.
- Baseline comparison (worker observation): `S10.9-a2/baseline-comparison.md`, accepted in R1 §4.
- The a3 rerun of the scripted Chat suite and the two affected S109 tests is consistent with the
  accepted results on the unchanged surfaces.

## 4. Scope, safety and device state

- Only the F1-affected Chat/1.5× surface and the two affected S109 tests were re-run; the full
  S109 9-test suite, baseline walk and real provider route were not repeated because their
  evidence is accepted and unaffected.
- Read-only device settings after the runs: font_scale **1.0**, ui_night_mode **2**, heads-up
  notifications **1**, airplane_mode **0**. No system/radio/display/permission setting was
  written. EN/VI, light/dark and 1.5× remain app-scoped variants.
- No owner package or owner data was launched, instrumented, cleared or written. Both isolated
  packages were force-stopped and the isolated data was cleared before the authoritative runs.
  No secret exists in a3 evidence.
- Undisclosed limitation: a3 adds no new real-provider call; the real streaming proof remains the
  R1-accepted a2 run on the same non-Chat-delta candidate.

## 5. Records

- Worker record: `artifacts/sprint-10/workers/S10.9-implementation-a3.md`
- Handoff: `artifacts/sprint-10/handoffs/S10.9-implementation-a3.md`
- Source/snapshot and owned-path hashes: `source-snapshot.txt`
- Visual review: `visual-review.md`; capture/log index: `index.md`
- Predecessors: `S10.9-a1/`, `S10.9-a2/`, handoffs a1/a2, review 01 + manifest, `history://S109Implementation`,
  `history://S109Resume`, reviewer `history://S109Evidence` (completed; not revived).
