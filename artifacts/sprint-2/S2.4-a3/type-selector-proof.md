# S2.4 type-selector geometry proof (correction attempt S2.4-a3)

- Task: `S2.4` / Attempt: `S2.4-a3` / Agent: `Sprint2SelectorResume` / Role: Gold (user-selected), light route.
- Scope: type-selector geometry correction only. No S2.5 work. No save/state/repository/navigation/dependency-pin change. No text shrink, label rename, fixed height, or truncation.
- Predecessors acknowledged before editing: Main pause handoff `artifacts/sprint-2/state/main-pause-handoff.md`; S2.4-a2 worker handoff `artifacts/sprint-2/handoffs/S2.4-a2.md` (agent `Sprint2TypeSelectorFix`); S2.4-a1 creation proof `artifacts/sprint-2/S2.4-a1/creation-proof.md` (agent `Sprint2ManualCreation`); active plan `docs/sprint-plans/sprint-2.md`; histories `history://Sprint2TypeSelectorFix` and `history://Sprint2ManualCreation`. All listed refs were available and read directly. S2.4-a2 and S2.4-a1 workers are completed; neither was revived. User functional phone acceptance from S2.4-a1 stands; only the screenshot-reported sizing defect was in scope here.
- Production fix (kept, reviewed, unchanged in shape from S2.4-a2): `app/src/main/java/io/github/triet4p/aegisf/ui/NewTransactionScreen.kt` — internal `TransactionTypePicker` in the same file; row `Modifier.fillMaxWidth().height(IntrinsicSize.Min)` plus per-segment `Modifier.fillMaxHeight()`; labels, checkmark, shapes, order, save/validation untouched. Root cause (Material3 1.4.0 sources, confirmed by S2.4-a2): `SingleChoiceSegmentedButtonRow` is `Row(CenterVertically)` and each `SegmentedButton` is a weighted `Surface` measuring only its own label, so the two-line `Internal transfer` grew only its own surface.

## Observed verification (exact)

Environment: `JAVA_HOME=C:\Program Files\Java\jdk-21`, SDK `C:/Users/admin/AppData/Local/Android/Sdk`, from `F:/ai-ml/aegis-f` with `--console=plain`. No phone commands (user-only); no staging/commit/push.

1. Focused selector-only smoke (production `TransactionTypePicker`, no Activity/Room/flow), four configurations:
   `.\gradlew.bat :app:testDebugUnitTest --tests 'io.github.triet4p.aegisf.ui.TypeSelectorGeometrySmokeTest' --tests 'io.github.triet4p.aegisf.ui.TypeSelectorGeometryEnlargedFontSmokeTest' --console=plain` → **BUILD SUCCESSFUL** (final run; only warning is the known `createComposeRule()` v2 deprecation).
   - JUnit XML: `TEST-...TypeSelectorGeometrySmokeTest.xml` tests=2 failures=0 errors=0; `TEST-...TypeSelectorGeometryEnlargedFontSmokeTest.xml` tests=2 failures=0 errors=0.
   - `compositionFontScale` is the real composed density in every run (device classes report `1.0`; enlarged classes report `1.5` via explicit `CompositionLocalProvider(LocalDensity provides Density(density, 1.5f))` — not the reset-prone global `RuntimeEnvironment.setFontScale`).
   - Narrow ~320dp mdpi, device font: tops=`[0, 0, 0]` bottoms=`[52, 52, 52]` widths=`[108, 107, 107]` (within 1 px), height 52 px ≥ 48 px touch target, `selected=[true, false, false]`.
   - Normal ~411dp xhdpi, device font: tops=`[0, 0, 0]` bottoms=`[96, 96, 96]` widths=`[276, 275, 275]` (within 1 px), height 96 px, `selected=[true, false, false]`.
   - Narrow ~320dp, enlarged 1.5x: same `52/52/52` geometry as the narrow device run; `compositionFontScale=1.5`.
   - Normal ~411dp, enlarged 1.5x: same `96/96/96` geometry as the normal device run; `compositionFontScale=1.5`.
   - Full English labels: exact `Expense` / `Income` / `Internal transfer` text asserted on the segment semantics in all four configurations; every label `lines=1`, `didOverflowHeight=false`, `isLineEllipsized(0)=false`, one unmerged Text node each. `hasVisualOverflow` was deliberately NOT asserted: decompiled ui-text 1.12.1 shows `didOverflowWidth` compares laid-out text width against the Text composable's own tightly-wrapped slot (observed `textSize=8x36` vs `para=84.0x36.0`), so it is true for every normally laid-out label and cannot discriminate segment clipping. The height/ellipsis/label/segment-geometry combination is the discriminating fit proof (lesson recorded in the shared log).
2. Debug APK (pre-cleanup and post-cleanup): `.\gradlew.bat :app:assembleDebug --console=plain` → **BUILD SUCCESSFUL** both times; only the documented non-blocking SDK XML warning. Final cleaned APK: `app/build/outputs/apk/debug/app-debug.apk` (18,782,475 bytes), debug-signed with the preserved per-user key (no signing change).
3. No full ledger suite rerun (explicitly not required for this layout-only change). Permanent tests are untouched; the cleaned `app/build.gradle.kts` dependency block is byte-identical to the pre-attempt state.

## Cleanup / scope review

- Removed ONLY predecessor-owned throwaway state confirmed in the S2.4-a2 handoff: deleted `app/src/test/java/io/github/triet4p/aegisf/ui/TypeSelectorGeometrySmokeTest.kt`; reverted the three temporary dependency lines (`compose-bom` test platform, `ui-test-junit4`, `ui-test-manifest`) from `app/build.gradle.kts`; deleted repo-root `baseline_check/` (`classes.jar` + extracted ui-test classes). Verified: smoke file gone, scaffold dir gone, no `TEMPORARY`/`ui-test-junit4` text remains in `app/build.gradle.kts`, production fix still present.
- Changed paths (this attempt): `app/src/main/java/io/github/triet4p/aegisf/ui/NewTransactionScreen.kt` (fix carried from S2.4-a2, kept); `app/build.gradle.kts` (temporary deps added for the smoke, then reverted — net zero vs pre-attempt); `docs/developer-setup.md` (one concise S2.4-correction bullet); shared lesson log (ui-test overflow/member-location lesson); this proof file. No plan edits, no Git mutation.
- Preserved: all Sprint 2 user/prior work, all permanent tests, labels/behavior, debug signing. No new docs beyond this required proof.

## Limitations and user recheck

- Physical visual proof is user-only (workers never touch the phone). The host proves equal geometry, real 1.5x composition scale, exact labels, and no Text-slot height overflow/ellipsis; it does not prove on-device pixels.
- Optional single user recheck (only if desired): install the cleaned APK, open **Record a transaction**, and confirm the three type segments share one top/bottom at device and enlarged system fonts with full `Expense` / `Income` / `Internal transfer` labels legible. Functional creation behavior was already accepted; do not rerun the full ledger suite for this layout correction.
