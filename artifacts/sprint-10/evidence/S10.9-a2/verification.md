# S10.9 attempt a2 — verification record

Candidate: the frozen uncommitted working tree described in `source-snapshot.txt` (diff hash
`cc94734eb15f4b1e04bdaa00287d9d6eff8077e5`, status hash
`89bed152c0a880f2060fcf1a6893ff1425e1230e`). No commit, staging, push or checkpoint by this
worker. Source was frozen after the final run below.

Device: HONOR ALT-LX2, Android 14 (API 34), 720 × 1610 px, 320 dpi, font scale 1.0 (device
setting untouched; app-scoped density variants used for 1.5×), night mode dark (device setting
untouched; the app theme is passed explicitly per frame). Isolated package
`io.github.triet4p.aegisf.s109evidence` and its test package; the owner package
`io.github.triet4p.aegisf` was never launched, instrumented, cleared or written.

## 1. Candidate build

```
./gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest \
  -PS108_ANDROID_TARGET_PACKAGE=io.github.triet4p.aegisf.s109evidence --console=plain
```

Result: BUILD SUCCESSFUL after the final source edit. Installed APKs (adb install -r):
`io.github.triet4p.aegisf.s109evidence` base sha256
`e1b58dcb28580f0e256eb756e5de4a452fd099c2fefffdee25036e26cb1c2ccd`; test APK sha256
`5fa9a539bc16136d561766b6d848d7112baa35609914f1744f761a34a60fa570` (see
`source-snapshot.txt`).

## 2. Cross-destination instrumented evidence (final candidate)

All runs: `adb shell am instrument -w -e class
io.github.triet4p.aegisf.ui.S109CompleteExperienceInstrumentedTest#<method>
io.github.triet4p.aegisf.s109evidence.test/androidx.test.runner.AndroidJUnitRunner`, isolated
package cleared first. Full stdout: `logs/s109-final-suite.txt`.

| Method | Result | What it proves |
| --- | --- | --- |
| `everyDestinationAndChildOpensAndReturnsToItsOwnContext` | **OK (1 test), 35.4 s** | 8 launchers + 5 tabs; every primary destination and child opens and returns; unsaved manual/detail guards write nothing; ledger filter survives tabs and detail; note save → browse → detail → Chat context visible before first send; Review open/Back; Home intact after the walk |
| `emptyStatesStayTruthfulOnEveryDestination` | **OK (1 test), 12.5 s** | Home `0 ₫`; Analytics/Ledger/Saved-notes/Review/Categories empty copy; empty Review states *Nothing to review* once with no pager; Chat no-provider boundary; untouched manual form writes nothing |
| `ambiguousStaleAndCancelledReviewStatesStayTruthful` | **OK (1 test), 15.6 s** | duplicate + undecided-link badges; unreadable draft refuses confirmation with a visible snackbar; preview turns stale after the stored revision changes, offers only *Check current values*, writes nothing; cancelled dismiss changes nothing; confirmed dismiss sets `DISMISSED`, removes the card, writes no ledger row |
| `readErrorsStayTruthfulOnEveryDataDestination` | **OK (1 test), 7.0 s** | Home, Analytics, Ledger, Review inbox, Draft detail and Transaction detail each render their own stored-read error copy over a closed store instead of crashing; the Review error no longer shows a reading line or empty pager |
| `activityRecreationKeepsRouteFiltersAndUnsavedText` | **OK (1 test), 6.0 s** | unsaved manual amount `424242` survives activity recreation; selected Notes tab survives |
| `longListsLongTextAndExactMoneyStayExact` | **OK (1 test), 8.5 s** | 67 records incl. 9,999,999,999 ₫ and 1 ₫ boundary amounts; Home/Analytics exact totals; ledger search shows the exact boundary row; long descriptions wrap |
| `englishDarkStaysLegible` | **OK (1 test), 5.6 s** | EN dark Home/Review/Chat; a11y scans on Home |
| `vietnameseLightStaysLegible` | **OK (1 test), 5.6 s** | VI light Home/Review/Chat |
| `vietnameseDarkAndOneAndAHalfTextKeepEveryDestinationUsable` | **OK (1 test), 19.2 s** | VI, dark, 1.5× text; name + 48 dp scans on Home, Analytics, Ledger, Transaction detail, Notes, Manual, Review inbox, Draft detail, Categories, Settings and Chat |

The three a1 product fixes are re-exercised: empty Review (`s109-25-review-empty-en`), the
draft-confirm snackbar (`s109-31-draft-correction-snackbar-en`) and the attached-note context
(`s109-16-chat-note-context-en`).

## 3. Product fixes verified in this attempt

1. **Read-error states are reachable, not crashes.** Home, Analytics and Ledger now fold a failed
   Room flow construction into their own error state with *Try again*; verified by
   `readErrorsStayTruthfulOnEveryDataDestination` and the four frames `s109-36`–`s109-39`, plus
   transaction/draft detail `s109-40`/`s109-41`.
2. **Review read error no longer contradicts itself.** The reading line and empty pager are
   suppressed beside the error copy (`s109-39`).
3. **Navigation-bar insets.** Analytics, transaction detail, saved notes and the note-draft
   chooser keep their last content above the system navigation bar; pre-fix crops are preserved
   in `screens/before-fix/`, post-fix frames are `s109-51`, `s109-53`, `s109-25-notes-empty-list-en`
   and the saved-notes frames.
4. **1.5× labels wrap.** The Home period label and the longest launcher label use two/three lines
   instead of ellipsizing (`s109-50`).
5. **Hero amount field has a screen-reader label** (*Amount (VND)* / *Số tiền giao dịch (VND)*);
   confirmed in the runtime accessibility tree (`new-B1-typed`/`s109-55`) and by the passing
   name scan.
6. a1 fixes remain: single empty-inbox statement/no pager, draft snackbar, attached note fully
   visible on entry.

## 4. Accessibility at 1.5× text (VI, dark)

`assertNoUnlabelledClickables` and `assertMinimumTouchTargets` run on Home, Analytics, Ledger,
Transaction detail, Notes, Manual, Review inbox, Draft detail, Categories and Settings, plus Chat.
The scan collects every violation in one pass, uses the expanded hit area (`touchBoundsInRoot`)
and treats editable text as a text field's audible content. Result: no violations on the final
run.

The a1 "40 dp" finding was a measurement artifact: `uiautomator dump` on the isolated app reports
the Home Review bell as a 96 × 96 px (48 dp) accessibility node at `[616,90][712,186]`. The scan
now reports the same expanded area.

## 5. Exact money

| Observation | Value |
| --- | --- |
| Cross-flow in-memory spending | 1,745,032 ₫ (1,234,567 + 45,000 + Σ(1..30)×1,001), 32 records (`s109-01`, `s109-02`) |
| Cross-flow income/transfers | 0 ₫, 0 records each (expense-only corpus) |
| Long-list spending total | 1,279,567 + Σ(1..30)×1,001 + Σ(1..35)×100,003 + 1 ₫ (asserted) |
| Long-list income boundary | 9,999,999,999 ₫ |
| Boundary smallest expense | 1 ₫ |
| Refused/stale/dismissed review actions | 0 ledger rows written |
| Walk record (both builds) | 123,456 ₫ stored and re-read exactly after process death |

## 6. Baseline comparison

See `baseline-comparison.md`: same seeded tasks, worker-observed taps/mistakes/outcomes, with the
baseline build `c586deb` and the final candidate. Summary: totals 0 taps in both; record 5 taps +
1 scroll (baseline) vs 4 taps + 0 scrolls (new); note 3 taps + 2 scrolls (baseline) vs 3 taps + 0
scrolls (new); no unintended destination reached in either walk.

## 7. Chat lifecycle and authorized DeepSeek streaming

See `chat-verification.md`. Scripted lifecycle suite **OK (13 tests), 77.1 s** on the final
candidate (`logs/s105-scripted-final-run.txt`). Authorized route:
`opencode.ai` / `deepseek-v4.1-flash`; provisioning and re-entry logs:
`logs/s105-provision-run.txt`, `logs/s105-realdurable-run.txt`, `logs/s105-reentry-run.txt`,
`logs/s105-cleanup-run.txt`; frames `screens/chat/s105-620`–`625`. The real-provider stages ran
on the same candidate except for the later `ReviewInboxScreen.kt` read-error-copy suppression,
which no Chat path compiles against; the scripted suite was re-run on the frozen candidate.

## 8. Limitations

- EN/VI, light/dark and 1.0×/1.5× come from in-app language/theme and app-scoped density
  variants; no device font-scale or night-mode setting was changed.
- Loading states that exist only during a local Room round-trip are observed transiently; tests
  assert the settled state and do not fabricate a frame.
- Settings' empty state is N/A (every setting has a stored default per the approved matrix).
- The physical tap counts are worker observations with adb, not human measurements.
- The owner package was not used; all writes stayed in the isolated evidence package, which was
  cleared after the runs.
