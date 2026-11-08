# S9.6-a3 factual ADB/evidence audit (harness frozen, no new runs)

- task/attempt/agent: S9.6 / S9.6-a3 / S96GoldTakeover. No commit/stage/push.
- Interaction mechanism: 100% scripted. No human touched the phone.
  `adb shell am start/force-stop/instrument`, `adb shell input tap 273 1468`
  (Chat tab from dumped bounds [190,1412][356,1524]), `adb shell input swipe
  360,1200→360,300`, `adb shell uiautomator dump` + `adb pull`, `adb install -r`,
  Compose `performClick/performTouchInput{swipeUp()}/performScrollTo`.
- Preserved UI snapshots (generic rows only, NOT seededID-specific):
  `artifacts/sprint-9/evidence/S9.6-a3/` holds `ui2.xml` (post-relaunch Chat top:
  ASK header + skill checklist), `ui3.xml` (post-tap Chat), `ui4.xml`,
  `ui5.xml`, `uiall2-7.xml` (post-force-stop swipe sweep: RECENT CAPTURES &
  PROVENANCE + several `Snack 25,000 VND at 15:15` / `Saved · unresolved ·
  interpretation needs review` cards), `uimid2.xml` (mid-instrumentation-run:
  Chat selected=true, ASK composed, no RECENT — LazyColumn below fold
  uncomposed), `uimid3.xml` (launcher, no compose hierarchy).
- Real-app behavior observed (scripted adb): force-stop → relaunch → tap Chat →
  swipe sweep renders saved unresolved captures. Proves destination resume in the
  real app; does NOT prove the instrumented test's seeded row (generic Snack
  rows, no unique key asserted).
- Instrumented-test results (existing outputs, not rerun):
  section fixtures PASS (`chatOutcomeShowsLiveCardsAndUnlinkPersistsAcrossReopen`
  2.6s OK; Reject path PASS per a2). Production
  `productionChatReloadsSavedUnresolvedCaptureAfterRestart` FAILS:
  `ComposeTimeoutException: Condition still not satisfied after 60000 ms` at
  `S96ActionableChatPhoneInstrumentedTest.kt:465` (setContent-close region; file
  no longer has an explicit waitUntil there — stale-APK line numbers or internal
  `performClick` idle wait), plus one `IllegalStateException: No compose
  hierarchies` when the device sat on the launcher.
- Decided navigation/composition cause: Chat tab IS selected (`selected=true` on
  the Chat nav node) and ASK composes, but RECENT never composes without a
  physical swipe; static `waitUntil(RECENT/tag)` plus `performScrollTo` on a
  nonexistent node cannot discriminate loaded-absent vs uncomposed-offscreen.
  The swipe-until-visible loop was added but never got a passing run before the
  freeze. Not a product-loader verdict.
- Missing logs/state: loader-internal `captures/offset/loading/error` for the
  seeded id; IME/keyboard state; hosted/executor/run logs (no hosted run
  claimed); seededID-specific screenshot (XML only); full untruncated failure
  text (tails in tool job outputs bg_54/55/57-62).
- Current owned code delta (uncommitted, frozen pending direction):
  only `app/src/androidTest/.../S96ActionableChatPhoneInstrumentedTest.kt`
  (production test: Chat click + ASK/RECENT/swipe-until-tag + tag scroll + body
  assert + no-error assert). No product source fix claimed. Import churn during
  repair was test-only.
- Prior refs: handoffs `S9.6-a1-ended.md`, `S9.6-a2-render-stall.md`;
  histories `history://S96ActionableChat`, `history://S96ChatDelivery`,
  `history://S96RenderAdvisor`; base `c74d0c6`.
