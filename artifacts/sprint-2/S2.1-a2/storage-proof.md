# S2.1 Storage layer proof

- Task: `S2.1`
- Attempt: `S2.1-a2`
- Agent: `Sprint2StorageCompletion` (Gold, light route)
- Route: Light. Gates: real Room close/reopen on host with exact VND and category/history invariants, debug build, worker scope review, Main acceptance. No independent reviewer/checkpoint/deep review, no commit/push.
- Repository/worktree/Git dir: `F:/ai-ml/aegis-f` / `F:/ai-ml/aegis-f/.git`; baseline `154d34c6f11836a9951f8afffd885c6b004e4e57`.
- Prior attempt: `S2.1-a1` (Gold, agent `Sprint2LedgerStorage`, history `history://Sprint2LedgerStorage`), ended by runtime failure with the test result unobserved; its handoff is at [handoffs/S2.1-a1.md](../handoffs/S2.1-a1.md) and Main's supervision record at [state/S2.1-a1-runtime-failure.md](../state/S2.1-a1-runtime-failure.md). Its build PASS is context, not fresh acceptance; this attempt re-proved everything below.
- Result: S2.1 implementation reviewed and corrected; host Room tests and debug build observed passing. Main acceptance pending.

## Changed scope

Modified (owned by S2.1):

- `build.gradle.kts` — KSP plugin `apply false` (2.3.12).
- `app/build.gradle.kts` — KSP applied; `testOptions.unitTests.isIncludeAndroidResources = true`; `room.schemaLocation`; Room 2.8.5 + KSP room-compiler; test deps (JUnit 4.13.2, Robolectric 4.17, androidx.test:core 1.7.0, kotlinx-coroutines-test 1.10.2).
- `app/src/main/AndroidManifest.xml` — registers `.AegisApplication`.

Added:

- `AegisApplication.kt`, `data/AppContainer.kt`, `data/LedgerDatabase.kt`, `data/CategoryEntity.kt`, `data/TransactionEntity.kt`, `data/CategoryDao.kt`, `data/TransactionDao.kt`, `data/TransactionType.kt`, `data/ConfirmationStatus.kt`, `data/RecordSource.kt`, `data/LedgerClock.kt`, `data/LedgerValidationException.kt`, `data/LedgerRepository.kt`.
- `app/schemas/io.github.triet4p.aegisf.data.LedgerDatabase/1.json` — exported schema (migration reference).
- Permanent host tests: `app/src/test/java/io/github/triet4p/aegisf/data/LedgerDatabasePersistenceTest.kt`, `app/src/test/java/io/github/triet4p/aegisf/data/LedgerRepositoryRejectionTest.kt`.

Corrections in this attempt (`S2.1-a2`):

- Permanent-test policy enforced per Main's instruction: every assertion that pinned incidental error wording was deleted (no `contains("Food")`, no case-insensitive re-pin). Rejections are verified by their stable category (`LedgerValidationException` caught by the tests' `expectRejection`, which fails on any other outcome) plus unchanged persisted state after the refused write.
- `AegisApplicationLedgerTest.kt` — the a1 version constructed `AegisApplication()` and read `container` without `onCreate()`, which throws `UninitializedPropertyAccessException` (lateinit is set only in `onCreate`), and an unattached `Application` has no base context for Room. It was rewritten around the Robolectric-started, manifest-registered application plus a fresh `AppContainer(context)` for restarted-process semantics, run once as the real integration entry-point smoke (observed passing), and then **removed as a throwaway smoke** because its remaining coverage was manifest/container wiring, not a consumer-visible invariant: the same production builder (`LedgerDatabase.build`) and persistence semantics stay permanently covered by `LedgerDatabasePersistenceTest`. The production entry point itself (`AegisApplication` registered in the manifest, `AppContainer`, `LedgerDatabase.build`) is unchanged production code.

Untouched: `MainActivity.kt` and the Sprint 1 home screen; `docs/PLAN.md`, `docs/sprint-plans/sprint-2.md` (Main-owned modifications preserved); `gradle.properties` unchanged. No staging, commit, push, or device action.

## Final contract

### Schema (exported `1.json`, version 1, identityHash `200715e58cc7db2c41f54aded88a06d8`)

- `categories`: `id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`, `name TEXT NOT NULL`, `archived INTEGER NOT NULL`, `createdAtEpochMillis INTEGER NOT NULL`, `updatedAtEpochMillis INTEGER NOT NULL`.
- `transactions`: `id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`, `type TEXT NOT NULL`, `amountVnd INTEGER NOT NULL`, `occurredAtEpochMillis INTEGER NOT NULL`, `description TEXT NOT NULL`, `categoryId INTEGER` (nullable), `confirmation TEXT NOT NULL`, `source TEXT NOT NULL`, `createdAtEpochMillis INTEGER NOT NULL`, `updatedAtEpochMillis INTEGER NOT NULL`, with `FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE CASCADE ON DELETE RESTRICT` and an index on `categoryId`.

### Storage rules

- `amountVnd` is a positive whole-dong `Long` magnitude ≥ 1; direction comes from `type` (`EXPENSE`/`INCOME`/`TRANSFER`), so no sign column exists.
- `categoryId` is nullable (an unknown category must not prevent saving); a referenced category cannot be deleted (DB `RESTRICT` plus a repository guard), and archiving keeps historical links intact while removing the category from new assignments.
- Active category names are unique, compared case-insensitively (`COLLATE NOCASE`); archived names are free for reuse.
- `confirmation` (`CONFIRMED`/`DRAFT`) and `source` (`MANUAL`/`BANK_NOTIFICATION`) are data-only here; S2.7 totals drafts out, Sprint 3 uses provenance.
- Timestamps are `Long` epoch millis from an injectable `LedgerClock` (production `SystemLedgerClock`); `editTransaction` preserves `id` and `createdAtEpochMillis` and refreshes `updatedAtEpochMillis`.
- `@Database(version = 1, exportSchema = true)` with `room.schemaLocation = app/schemas`; no `fallbackToDestructiveMigration` anywhere — a version mismatch must fail loudly, never silently delete ledger history.
- Production entry point: `AegisApplication` (manifest-registered) → `AppContainer` → `LedgerRepository`. Nothing seeds records; the DB file is `aegis-ledger.db`.

### Rejections (`LedgerValidationException`, nothing written)

Amount < 1 (including 0, negative, `Long.MIN_VALUE`); any category on a `TRANSFER`; unknown `categoryId`; archived category on a new record; blank/whitespace category name; duplicate active name (case-insensitive) on add or rename; delete of a referenced category; edit/rename/archive/delete of an unknown id.

## Verification (observed)

Toolchain per `docs/developer-setup.md`: JDK 21.0.1 (`JAVA_HOME`), Android SDK at `%LOCALAPPDATA%\Android\Sdk` (`ANDROID_HOME`). All commands from `F:/ai-ml/aegis-f` with `--console=plain`; every run recompiled the test sources (`compileDebugUnitTestKotlin` executed), so each result exercised the code as it stood.

1. `./gradlew.bat :app:testDebugUnitTest` — first observed run against the a1 tests plus the a2-fixed entry-point smoke: `18 tests completed, 1 failed` — `LedgerRepositoryRejectionTest` > `renaming to another active category is refused and keeps the stored name` failed at the `contains("Food")` message assertion: the repository's duplicate-name refusal quotes the name as typed (rejecting `food` against the stored `Food`). Resolution followed Main's permanent-test policy: the assertion and all other incidental error-wording pins (`contains("transfer")`, `contains("Food")` in the delete guard, `contains("archived")` in the persistence test) were **deleted, not re-pinned**; each rejection is now proven by the stable exception category through `expectRejection` (any non-`LedgerValidationException` outcome, including success, fails the test) plus unchanged persisted state (`transactions().isEmpty()`, stored names/ids, and close/reopen recounts).
2. `./gradlew.bat :app:testDebugUnitTest` — second run with wording pins removed and the rewritten entry-point smoke present: `BUILD SUCCESSFUL in 34s`; suites `AegisApplicationLedgerTest` 1/0/0 (throwaway entry-point smoke: production container persists and reloads a real record), `LedgerDatabasePersistenceTest` 6/0/0, `LedgerRepositoryRejectionTest` 11/0/0 (tests/failures/errors), result timestamps 2026-10-14T18:30Z.
3. The throwaway smoke was then removed (manifest/container wiring is not a permanent invariant; the same production builder and persistence semantics remain covered by `LedgerDatabasePersistenceTest`), and `./gradlew.bat :app:testDebugUnitTest` was run for the final permanent state: `BUILD SUCCESSFUL in 23s`; suites `LedgerDatabasePersistenceTest` 6/0/0 and `LedgerRepositoryRejectionTest` 11/0/0 — **17 tests, 0 failures, 0 errors, 0 skipped**, result timestamps 2026-10-14T18:32Z.
4. `./gradlew.bat clean :app:assembleDebug` — `BUILD SUCCESSFUL in 1m 5s`, 38 actionable tasks: 38 executed (fresh, not incremental; an earlier intermediate incremental run of `:app:assembleDebug` alone reported `37 up-to-date`, which was not accepted as the gate). Tasks included `kspDebugKotlin`, `compileDebugKotlin`, `dexBuilderDebug`, `packageDebug`, `createDebugApkListingFileRedirect`, `assembleDebug`. Only warning: `libandroidx.graphics.path.so` could not be stripped (pre-existing, non-blocking). APK: `app/build/outputs/apk/debug/app-debug.apk`, 11,731,172 bytes. The regenerated `1.json` is byte-identical (same identity hash), proving no schema drift.
5. `./gradlew.bat :app:assembleDebug` re-run after the final test corrections — `BUILD SUCCESSFUL`, 37 tasks `UP-TO-DATE`: the corrections touched only test sources, so the packaged APK is the byte-identical product of the clean build in item 4.

What the host tests prove against the real Room/SQLite database (Robolectric SDK 34, production `LedgerDatabase.build`, no fake DAO, no mock, no device): close/reopen survival of expense/income/transfer/draft-bank-notification records with stable ids and exact amounts (45,000 / 120,000 / 1,000,000 / 500,000 / 320,000 VND); archived category still attached after reopen and unavailable for new records; rejected writes leave stored rows unchanged across reopen; corrections keep identity + creation time and update the revision time; deletion removes only the target row; `PRAGMA user_version` equals `LedgerDatabase.VERSION`; 1-dong exact storage; `Long.MAX_VALUE` stored without wrapping; and every repository rejection listed above.

Not run (out of scope): device/`adb` actions (S2.8 owns the device scenario), lint/format tasks, offline APK badging/signing inspection, commit/push.

## Decisions

- No sign column; positive `Long` magnitude with direction from `type` — matches the plan's "type determines financial direction" and prevents contradictory rows.
- Nullable `categoryId` with `RESTRICT` + repository delete guard — archived/historical links stay structurally safe while an unknown category never blocks a save.
- Uniqueness of active names enforced by query, not a DB unique index — archived names must be reusable by new categories.
- Timestamps read through an injectable `LedgerClock` — deterministic host tests without Android framework dependencies.
- Repository returns entity rows (no parallel domain model) and owns validation (DAOs stay mechanical) — no needless abstraction for this scope.
- KSP 2.3.12 with Room 2.8.5: KSP 2.2.10-2.0.2 fails AGP 9 built-in Kotlin configuration with "Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin"; KSP 2.3.x is decoupled from the Kotlin release line and is the first line that supports it. `kapt` and `android.disallowKotlinSourceSets=false` were rejected as unsupported workarounds.

## Worker scope/diff review

- Scope is the persistence layer, its tests, and build wiring only; no screen, formatter, totals, filters, or notification code (S2.2–S2.8 territory).
- No fake records or probe UI: test data lives only under `src/test`; the production database opens empty.
- `MainActivity.kt` and the Sprint 1 English/VND home screen are unchanged; `AegisApplication` adds no UI.
- `LedgerClock` is not persisted: stored timestamps follow the device wall clock. Acceptable for S2.1; S2.6 must document its local-time convention.
- `findActiveIdByName`/name compare uses SQLite `NOCASE`, which agrees with `String.equals(ignoreCase)` for ASCII; non-ASCII case folding, if ever reported, belongs to S2.3.
- Test files are permanent regression coverage of consumer-visible invariants only — real persistence, exact amounts, history preservation, and rejection boundaries verified by exception category plus unchanged persisted state. The manifest/container wiring was checked once through a throwaway entry-point smoke (observed passing) and then removed, so no permanent test asserts wiring or error wording.

## Unresolved limits

- Main acceptance of S2.1 and the plan's `[~]` → `[x]` transition are Main's to make.
- On-device persistence (`adb`/real phone) is unproven by design here; S2.8 owns it.
- Open question carried from a1 (not blocking): `app/schemas/**/1.json` is untracked; no commit was authorized, so it sits in the worktree for Main to decide.
