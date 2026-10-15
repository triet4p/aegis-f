# S2.3 Category management proof

- Task: `S2.3` / Attempt: `S2.3-a1` / Agent: `Sprint2Categories` (Gold, light route)
- Route: Light. Gates: actual host behavior smoke and regression invariants, debug build, worker scope review and Main acceptance; physical visual confirmation user-run at S2.8. No independent reviewer/checkpoint/deep review, no commit/push.
- Repository/worktree/Git dir: `F:/ai-ml/aegis-f` / `F:/ai-ml/aegis-f/.git`; baseline `main`.
- Quota observation: unknown (not exposed to the worker).
- Result: category management implemented (add / rename / archive from the launcher, real persistence, live observed list, duplicate guard, no count cap, history preserved). Host-verified: permanent repository invariants 26/26 green, `:app:assembleDebug` successful, and the real launcher Activity was exercised on the Robolectric Android runtime through navigation, scrolling, dialog flow, text entry, real writes, and persistence across Recreation. **Unresolved:** the in-host rendering of a live list refresh after a write — see [Host limitation](#host-limitation-unresolved-on-this-host). This gate is NOT claimed as passed; Main keeps S2.3 in progress and the user performs the exact category UI checks on the built APK before S2.4.

## Changed scope

Added:

- `app/src/main/java/io/github/triet4p/aegisf/ui/CategoryManagerScreen.kt` — category management screen: `LazyColumn` over the live category stream with a count summary row, per-row Rename/Archive actions, an "Archived" marker, empty and loading states, add/rename dialogs with inline refusal messages, archive confirmation explaining that past records are kept but new assignments cannot use the category. Safe-inset `Scaffold`, `BackHandler` back navigation, light/dark Material theming from the existing `AegisTheme`, no fixed count cap (the list scrolls through everything).
- `CategoryDao.observeAll(): Flow<List<CategoryEntity>>` — the same alphabetical `NOCASE` query as `findAll`, as a live Room stream.
- `LedgerRepository.observeCategories(): Flow<List<CategoryEntity>>` — the observation entry point for screens.
- Tests in `app/src/test/java/io/github/triet4p/aegisf/data/LedgerDatabasePersistenceTest.kt`: `category writes are observed without re-querying` (one subscription sees add → rename → archive emissions in order, then a new subscriber starts from stored state), `there is no product-imposed cap on the number of categories` (60 + 1 categories all returned, assignable, and observed), `renaming a category keeps stored transactions attached to it` (link, new name, and assignability survive close/reopen).

Modified:

- `app/src/main/java/io/github/triet4p/aegisf/MainActivity.kt` — launcher home screen gains a `CATEGORIES` card with a **Manage categories** entry point; a `rememberSaveable` flag swaps in `CategoryManagerScreen` (system back returns to the home screen). `LaunchScreen` keeps its English/VND identity and scrolling.
- `app/build.gradle.kts` — `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")` for UI coroutines (version matches the pinned test dependency).
- `docs/developer-setup.md` — host-test coverage bullets and the user-run category checks in §5.
- `C:/Users/admin/.agents/memory/decisions.md` — the flow-identity decision below.

Production defect found and fixed during verification: `CategoryManagerScreen` originally built `repository.observeCategories()` inside the composable body, so `collectAsState`/`produceState` received a **new Flow identity on every recomposition** and restarted the whole collection each time. The flow is now hoisted once per screen: `val categoryFlow = remember(repository) { repository.observeCategories() }`.

## UI entry points (from the real launcher)

- Launcher `io.github.triet4p.aegisf/.MainActivity` (manifest-registered, exported) → home screen **Aegis F / Vietnamese dong / VND / ₫** with the **Manage categories** button (below the fold on small screens; the home column scrolls).
- **Manage categories** → `CategoryManagerScreen`: title **Categories**, summary row (`N categories`, plus `, M archived`), rows with **Rename** / **Archive**, extended FAB **Add category**, empty state (**No categories yet**), loading state while the first emission arrives.
- Dialogs: **New category** / **Rename category** (field "Category name", **Save** / **Cancel**, refusals shown inline under the field), **Archive "&lt;name&gt;"** confirmation (**Archive category** / **Cancel**).
- Repository semantics that the UI surfaces: blank names refused; an active name is unique case-insensitively; a refused write stores nothing and keeps the typed name; archived categories keep their historical links and leave new assignments (`assignableCategories()` enforcement still verified by the permanent tests; the transaction UI is S2.4).

## Verification (observed)

Environment per `docs/developer-setup.md`: JDK 21 (`JAVA_HOME`), Android SDK at `%LOCALAPPDATA%\Android\Sdk`; commands run from `F:/ai-ml/aegis-f` with `--console=plain`.

1. `./gradlew.bat :app:testDebugUnitTest --console=plain` — `BUILD SUCCESSFUL` in 19s; JUnit XML: `LedgerDatabasePersistenceTest` 9/0/0 (includes the 3 new S2.3 invariants), `LedgerRepositoryRejectionTest` 11/0/0, `VndMoneyTest` 6/0/0 — **26 tests, 0 failures, 0 errors, 0 skipped**.
2. `./gradlew.bat :app:assembleDebug --console=plain` — `BUILD SUCCESSFUL` in 21s, 37 actionable tasks (7 executed, 30 up-to-date; `compileDebugKotlin`/`packageDebug`/`createDebugApkListingFileRedirect` executed on the changed sources). APK: `app/build/outputs/apk/debug/app-debug.apk`. Only the known non-blocking SDK XML warning.

### Throwaway host smoke on the real Activity (removed after the run)

A temporary test launched the production `MainActivity` with Robolectric's Android runtime and drove the real UI with real `MotionEvent` injections (pointer properties, `SOURCE_TOUCHSCREEN`), real drag scrolling, and the production `SetText` semantics action for field entry; assertions read the production Compose semantics tree and the real file-backed Room database. Substeps **observed passing**:

- launcher Activity home screen content; **Manage categories** reached by real scroll drags on the default 320×470dp viewport (below the fold → scroll → visible → tap);
- category screen empty state; FAB → **New category** dialog; field focus by real tap; text written through the production `SetText` action (field readback asserted);
- **Save** performs the real write: `stored categories: [Food]` read back from the production repository;
- a parallel `Dispatchers.IO` collector on the production `observeCategories()` received `[[], [Food]]` — the Room stream, invalidation tracker, and repository write path are healthy in-process;
- dialog buttons verified to require dispatch on the owning window (dialog taps initially landed on the Activity window and did nothing — fixed in the smoke, not in production).

Substeps **not proven on this host** (see below): the immediate list refresh after the write, the post-write dialog dismissal rendering, rename/archive confirmations, the 62-row bulk rendering, scrolled deep-row access, and the post-recreate list. A control probe (same Scaffold+TopAppBar+LazyColumn composed onto the same activity via `setContent`) laid out with real bounds, and click-driven navigation re-laid out correctly — isolating the failure to layout not following a *flow-originated* recomposition in this host runtime.

## Host limitation (UNRESOLVED on this host)

On the Robolectric host, a state update originating from the Room flow recomposes the production screen (the empty-state branch is disposed, the `LazyColumn` branch is composed) but **no layout traversal follows**: at the failure point the production list node reports `boundsInWindow = Rect.fromLTRB(0, 0, 0, 0)` with `CollectionInfo(rowCount=0)` while every sibling laid out for real (`decor=320x470`, top bar `Rect(0,0,320,64)`, FAB `Rect(224,398,304,454)`). Evidence gathered, in order:

1. the parallel IO collector received `[[], [Food]]` (flow healthy);
2. driving Choreographer frames and `idleFor(20ms)` in the settle loop changed nothing (not frame starvation);
3. `scenario.recreate()` did not recover the list (a fresh composition had the same zero-bounds list);
4. a fresh `setContent` Scaffold+LazyColumn on the same activity laid out with real bounds (the host can lay out lazy lists in this window);
5. click-driven navigation re-laid out for real (main-thread-originated updates behave correctly).

This is a host runtime scheduling limitation, **not** a passed gate: live list refresh after a write, and the recreate-after-save rendering, remain **UNRESOLVED until the user runs the manual checks below on the built APK** (S2.8). No production behavior was changed to work around it; the only production fix made during the hunt (hoisting the Flow identity) stands on its own merits (it removes per-recomposition collection restarts).

## USER-only manual category checks (for the consolidated S2.8 run)

Run in the user's own PowerShell with the phone connected and USB debugging authorized (never run by a worker):

```powershell
adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
adb -d shell am start -n io.github.triet4p.aegisf/.MainActivity
```

1. Home shows **Aegis F**, **Vietnamese dong**, **VND**, `₫`, and a **Manage categories** button (scroll if needed).
2. **Manage categories** → **No categories yet** → **Add category** → `Food` → **Save** → the **Food** row appears **immediately, without leaving or reopening the screen**, and the summary reads **1 category**.
3. **Add category** → `food` → **Save** → the refusal `Another active category is already called "food".` shows under the field, the dialog stays open with the typed name, the **Food** row is unchanged → **Cancel** → still exactly **1 category**.
4. **Rename** on **Food** → `Groceries` → **Save** → the row reads **Groceries** immediately.
5. **Archive** on **Groceries** → confirm → the row stays with an **Archived** marker; summary **1 category, 1 archived**.
6. **Add category** → `Rent` → **Save**; add 60 more categories through the same dialog; scroll to the bottom — every row renders (no cap); summary reads **62 categories, 1 archived**.
7. Close the app from recents; reopen from the launcher → the same list, the **Archived** marker, and the same summary are back.
8. Repeat steps 2–5 in dark theme and with a large system font: text stays readable, the list scrolls, content stays clear of the status/navigation bars.

Report the actual observations to Main. (Transactions do not exist in the UI yet — that is S2.4 — so "history preserved" is covered here by the permanent repository tests: rename/archive keep stored transactions attached, verified on the host.)

## Decisions

- Category list observation goes through Room's `Flow` (`observeAll`) with the flow identity hoisted per screen — the screen never refreshes manually and a per-recomposition collection restart cannot blank the list. Repository-level liveness is pinned by a permanent test that drives one subscription through add/rename/archive without re-querying.
- Uniqueness of active names stays enforced by query in `LedgerRepository` (unchanged from S2.1) so archived names remain reusable; the screen surfaces the repository's refusal text inline.
- No count cap anywhere: the DAO query has no `LIMIT`, and the screen renders a `LazyColumn`.
- Archived categories stay listed (badge + rename available) because their records remain filed under them.

## Unresolved limits

- Live list refresh after a write and recreate-after-save rendering are UNRESOLVED on the host (evidence above); the user's manual checks (S2.8) are the gate. S2.3 stays in progress.
- Physical-device visuals (portrait/landscape, font scale, themes, touch targets) are user-only.
- `app/schemas/**/1.json` remains untracked, as recorded since S2.1.
- No staging, commit, or push was performed.
