# Command-line developer setup

This is the supported development path for Aegis F: the official Android SDK command-line tools, the project's Gradle Wrapper, and `adb` to a physical phone. Android Studio and an emulator are **not** required; no project step uses them. Device access is user-operated by default. A worker may operate only Aegis F and only when the active task explicitly authorizes a narrow, app-scoped check; that permission does not extend to other apps, bank activity, notification content, ledger/model mutation, or system/security settings. Other device procedures remain user-run.

Verified state (2026-10-15, Sprint 2): the pinned toolchain below built the debug APK from a genuinely new process; the Room persistence and ledger unit tests (`:app:testDebugUnitTest`, Robolectric against the real database file) passed on the host with 31 tests and no failures; and the manual transaction creation form was exercised on the real launcher Activity against the real Room database (see the S2.4 creation proof). Earlier verification (2026-10-14): the user reported that the S1.5 launch checks and the S1.6 A/B update-path checks passed. Historical evidence: [S1.1 toolchain](../artifacts/sprint-1/S1.1-a1/toolchain.md), [S1.2 SDK setup](../artifacts/sprint-1/S1.2-a1/sdk-setup.md), [S1.4 build](../artifacts/sprint-1/S1.4-a1/build-proof.md), [S1.5 launch](../artifacts/sprint-1/S1.5-a2/launch-proof.md), [S1.6 A/B update](../artifacts/sprint-1/S1.6-a1/update-proof.md), [S1.6 clean APK](../artifacts/sprint-1/S1.6-a2/clean-proof.md), [S2.1 storage](../artifacts/sprint-2/S2.1-a2/storage-proof.md). The fresh-process reproduction of the commands in sections 2–3 is recorded in [S1.7 docs proof](../artifacts/sprint-1/S1.7-a1/docs-proof.md).

## 1. Pinned toolchain

| Component | Pin | Notes |
| --- | --- | --- |
| Gradle | 9.3.1 | Wrapper binary distribution; archive SHA-256 pinned in `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.1.1 | `com.android.application`, declared in the root `build.gradle.kts` |
| Kotlin | 2.2.10 | AGP 9.1.1 built-in Kotlin; do **not** also apply `org.jetbrains.kotlin.android` |
| Compose compiler plugin | `org.jetbrains.kotlin.plugin.compose` 2.2.10 | Must match the Kotlin version |
| Compose libraries | Compose BOM 2026.09.00 | `androidx.compose.ui:ui`, `androidx.compose.material3:material3` |
| Activity Compose | `androidx.activity:activity-compose:1.13.0` | Supplies `ComponentActivity` / `setContent` |
| Room runtime | `androidx.room:room-runtime` 2.8.5 | The app's only local database (ledger categories and transactions) |
| Room schema processing | `com.google.devtools.ksp` 2.3.12 with `androidx.room:room-compiler` 2.8.5 | KSP is the only supported annotation processor for this build; see the compatibility note below |
| Robolectric | 4.17 | Runs the Room host-side unit tests on the JVM against real SQLite; no emulator or device |
| Unit test libraries | JUnit 4.13.2, `androidx.test:core` 1.7.0, `org.jetbrains.kotlinx:kotlinx-coroutines-test` 1.10.2 | Robolectric runner, `ApplicationProvider`, and deterministic `runTest` for suspend repositories |
| Compile / target SDK | API 37 (Android 17) | `compileSdk = 37`, `targetSdk = 37` |
| Minimum SDK | API 24 (Android 7.0) | Broad compatibility floor; not derived from the test phone |
| SDK Build Tools | 36.0.0 | AGP 9.1.1 default |
| JDK running Gradle | Oracle JDK 21.0.1 at `C:\Program Files\Java\jdk-21` | `JAVA_HOME`; existing install, not newly installed |
| Java/Kotlin app bytecode target | 17 | Configured separately from the JDK that runs Gradle |

### Execution JDK vs. bytecode target

These are two different settings and must not be confused:

- **Execution JDK** — the JVM that runs the Gradle daemon and the build. Here it is the installed Oracle JDK 21.0.1 (`JAVA_HOME=C:\Program Files\Java\jdk-21`). Gradle 9.3.1 supports running on Java 21.
- **Bytecode target** — the Java/Kotlin language level compiled into the app: Java 17 (`sourceCompatibility`/`targetCompatibility = VERSION_17`) and Kotlin `jvmTarget = JVM_17`. AGP 9.1.1 requires JDK 17 or newer to run, and 17 is the app-level target regardless of the 21 used to execute the build.

A build succeeding on JDK 21 says nothing about the app's runtime bytecode level, and changing the bytecode target would not remove the need for a JDK 17+ build host.

### KSP version and AGP built-in Kotlin

Room's annotation processing runs through KSP, and the KSP version is tied to this project's Kotlin setup in a non-obvious way:

- **Do not pin KSP 2.2.10-2.0.2 with this build.** It fails at configuration time with `Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin`, because KSP writes its generated sources into the Kotlin source sets and AGP 9.1.1's built-in Kotlin forbids that.
- **Use KSP 2.3.x** (pinned: 2.3.12). The KSP 2.3 line is decoupled from the Kotlin release line and is the first line that supports AGP 9 built-in Kotlin. It does not need to match the Kotlin version, unlike `org.jetbrains.kotlin.plugin.compose`, which must stay equal to Kotlin.
- Rejected workarounds: `kapt` (incompatible with AGP 9 built-in Kotlin) and `android.disallowKotlinSourceSets=false` in `gradle.properties` (suppresses the guard instead of using a supported configuration).

Room writes one exported schema JSON per database version to `app/schemas/` (configured through the `room.schemaLocation` KSP argument). Those files are the reference for every future migration and must be kept with the schema change that produced them.

**Known toolchain risk (recorded, not in scope to fix):** the installed JDK 21.0.1 is dated 2023-10-17, an old patch level. Current builds and the user's device launch passed on it, but release tooling security and later JDK/runtime compatibility remain a separate, later consideration. Do not silently upgrade or replace the JDK as part of a feature change.

## 2. SDK root, environment, and licenses

The SDK is installed **user-scoped** at `%LOCALAPPDATA%\Android\Sdk`. No administrator elevation is used or required, and no Machine-scope environment variables are set. No Android Studio, emulator/system image, NDK, or CMake is installed.

| Variable | Value (user-persistent) |
| --- | --- |
| `JAVA_HOME` | `C:\Program Files\Java\jdk-21` |
| `ANDROID_HOME` | `%LOCALAPPDATA%\Android\Sdk` |
| `ANDROID_SDK_ROOT` | `%LOCALAPPDATA%\Android\Sdk` |
| `PATH` additions | `%ANDROID_HOME%\platform-tools`, `%ANDROID_HOME%\cmdline-tools\latest\bin` |

Existing `PATH` entries were not removed; these two entries were appended.

Installed packages (all from Google's official SDK repository, licenses accepted through `sdkmanager --licenses`):

| Package | Version | Location |
| --- | --- | --- |
| SDK Command-Line Tools | build 15859902 (`commandlinetools-win-15859902_latest.zip`), reported version 22.0 | `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest` |
| Platform Tools (`adb`) | 37.0.1 | `%LOCALAPPDATA%\Android\Sdk\platform-tools` |
| SDK Platform | `platforms;android-37.0` (revision 2, API level 37.0) | `%LOCALAPPDATA%\Android\Sdk\platforms\android-37.0` |
| Build Tools | `build-tools;36.0.0` | `%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0` |

**Package naming note:** the repository package is `platforms;android-37.0` — with the `.0` suffix — not `platforms;android-37`. Use the exact name when installing or scripting. All applicable license hash files are present under `%LOCALAPPDATA%\Android\Sdk\licenses\`; nothing bypasses or suppresses license prompts.

### Fresh-shell rule and the stale-parent caveat

The values above are user-persistent (registry `HKCU\Environment`) and are inherited by **newly started** processes only. A shell that was already open when the variables were set keeps its old environment block, and — importantly — a new process started *from* such an old parent (an already-open terminal window, an IDE, or a launcher that inherited the old block) inherits that stale block too.

- Start a **new PowerShell window from the Start menu / taskbar**, not from inside an older terminal or IDE, when environment values matter.
- If a new window still shows stale values, the parent process was itself started before the change; close it and start the window from a source that did not inherit the old block, or sign out and back in.
- A session-only fallback is to set the variables in the current session, but prefer the fresh window so the documented path is actually what is being tested.

### Fresh-shell verification (PC-side)

In the new PowerShell process, from any directory:

```powershell
java -version
javac -version
adb version
sdkmanager --version
sdkmanager --list_installed
```

Observed results with this installation:

- `java -version` → `java version "21.0.1" 2023-10-17 LTS`, `build 21.0.1+12-LTS-29`.
- `javac -version` → `javac 21.0.1`.
- `adb version` → `Android Debug Bridge version 1.0.41`, `Version 37.0.1-15733141`, installed from the SDK `platform-tools` directory.
- `sdkmanager --version` → `22.0`.
- `sdkmanager --list_installed` → exactly the three packages listed above (`build-tools;36.0.0`, `platform-tools`, `platforms;android-37.0`).

Non-blocking tooling note: current `sdkmanager` prints a deprecation warning that the Android CLI (`android sdk`) replaces it. The documented commands still work; this is a tooling message, not a build failure.

## 3. Build the debug APK with the Wrapper

From a fresh PowerShell process:

```powershell
Set-Location F:\ai-ml\aegis-f
.\gradlew.bat --version
.\gradlew.bat --no-daemon :app:assembleDebug
```

- `.\gradlew.bat --version` must report Gradle 9.3.1. On the very first run the Wrapper downloads the pinned `gradle-9.3.1-bin.zip`; the archive checksum in `gradle/wrapper/gradle-wrapper.properties` is verified against Gradle's published checksum, so a tampered or truncated distribution fails rather than building.
- `:app:assembleDebug` is the standard build command. Observed on the current source: `BUILD SUCCESSFUL`, exit code 0 (36 actionable tasks; `up-to-date` on a repeat run, minutes on a first/cold run). A clean rebuild uses `.\gradlew.bat --no-daemon clean :app:assembleDebug`.

Output:

- APK: `F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk`
- Expected identity: package `io.github.triet4p.aegisf`, `versionCode = 4`, `versionName = "1.0"`, compile SDK 37 / target SDK 37 / min SDK 24, exported launcher `io.github.triet4p.aegisf.MainActivity` labelled **Aegis F**. The APK SHA-256 changes on every rebuild; compare package/version/launcher/signer, not the hash, unless you are checking one specific build.

Known non-blocking build warnings; neither fails the build:

- SDK processing, printed on every build: "this version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered." This reflects command-line tooling older than the installed platform's XML revision and is informational.
- Packaging, printed when the packaging tasks actually execute (first build or after `clean`): `libandroidx.graphics.path.so` cannot be stripped and is packaged unstripped. It comes from a Compose dependency and only affects APK size. An up-to-date incremental run does not re-emit it.

No tests, lint, or formatter run is part of this build. No Android Studio, emulator, or device connection is needed for any command in this section.

### Debug signing key

`assembleDebug` uses AGP's normal debug signing with the standard per-user key at `%USERPROFILE%\.android\debug.keystore` (outside the repository). Certificate: `C=US, O=Android, CN=Android Debug`, SHA-256 `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b` — the same certificate used by every package the user has installed so far.

**Preserve that key file.** An in-place update (`adb install -r`) is accepted only when the new APK is signed by the same certificate as the installed one. Deleting, regenerating, or replacing `debug.keystore` produces a different certificate and the phone will reject the update. No release signing configuration or private key exists in the project, and no signing material belongs in the repository. `local.properties`, SDK paths, build output, caches, IDE state, signing credentials, and APK/AAB files are Git-ignored; the Wrapper files (including `gradle-wrapper.jar`) stay trackable.

### Optional offline APK inspection (PC-side)

Static checks only; no device or network access:

```powershell
$apk = 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
$aapt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt.exe"
$apksigner = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat"
& $aapt dump badging $apk
& $aapt dump permissions $apk
& $apksigner verify --verbose --print-certs $apk
Get-FileHash -LiteralPath $apk -Algorithm SHA256
```

The only declared permission is the dependency-generated, signature-protected `io.github.triet4p.aegisf.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; the app declares no runtime permission and keeps `allowBackup="false"`. Offline inspection proves packaging, not on-device behavior.

### Host-side unit tests — real Room persistence (PC-side)

The Room database and repositories are tested on the PC through Robolectric, against the real database file — no fake DAO, no mock, no device. This is the supported way to prove persistence behavior before any phone step:

```powershell
Set-Location F:\ai-ml\aegis-f
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

- The tests open the real database file through the same production builder the app uses (`LedgerDatabase.build`); closing and reopening an instance is what the process does when Android kills and restarts it. No fake DAO and no mock are involved.
- Results land in `app/build/test-results/testDebugUnitTest/` (JUnit XML per class) and `app/build/reports/tests/testDebugUnitTest/index.html`.
- The tests cover persistence across close/reopen, exact whole-dong amounts, stable ids, category/history preservation, repository rejection boundaries (rejections are proven by exception type and unchanged stored state, not by message wording), the live category stream (`observeCategories` re-emits on add/rename/archive without a re-query), the live transaction stream (`observeTransactions` re-emits newest-first with the stored-id tie-breaker on create/correction/deletion without a re-query), the absence of any product cap on category count, rename keeping stored transactions attached, the device-local date/time entry contract for manual records (`ManualDateTimeTest`: a typed wall-clock moment in a fixed UTC+7 zone stores that exact instant and is never reinterpreted as UTC), and the browsing-filter contract (`TransactionFilterTest`: combined type/category/date dimensions narrow as AND, clearing every dimension returns the full list in stored order, archived categories stay selectable by stable id, `Uncategorized` never conflates with `All`, and inclusive From/Through calendar days form the half-open epoch range `[startOf(From), startOf(dayAfter(Through)))` in the device zone with 23/25-hour daylight-saving days contributing their actual length). The [S2.1 storage proof](../artifacts/sprint-2/S2.1-a2/storage-proof.md) records those invariants; the [S2.2 exact VND boundary proof](../artifacts/sprint-2/S2.2-a1/vnd-proof.md) records manual syntax, formatting, and checked-total coverage; the [S2.3 category management proof](../artifacts/sprint-2/S2.3-a1/category-proof.md) records the category screen, its launcher entry point, and the host limitation that leaves live-list refresh verification to the device checks; the [S2.4 creation proof](../artifacts/sprint-2/S2.4-a1/creation-proof.md) records the manual transaction creation form, the host smoke run, and the confirmation-panel rendering observation that stays user-only; the [S2.5 edit/delete proof](../artifacts/sprint-2/S2.5-a1/edit-delete-proof.md) records the correction and deletion contracts — a correction keeps the same row's id, creation time, confirmation, and provenance while stamping its revision time, keeps an already-attached archived category while still refusing a newly assigned one, and a deletion removes only the confirmed record — plus the host smoke run and the user-only checklist; the [S2.6 browse/filter proof](../artifacts/sprint-2/S2.6-a1/browse-filter-proof.md) records the browsing contract below, the host runtime evidence, and the exact surfaces the host could not prove.

- Type-selector sizing (S2.4 correction): the three type segments share one content-driven height (row `height(IntrinsicSize.Min)` plus per-segment `fillMaxHeight()`), proven equal top/bottom/width at 320dp and 411dp under device and explicit 1.5x composition font scales with full untruncated labels; the throwaway Compose smoke and its temporary test dependencies were removed, leaving `app/build.gradle.kts` unchanged. Evidence: [S2.4 type-selector geometry proof](../artifacts/sprint-2/S2.4-a3/type-selector-proof.md).
- Correction and deletion (S2.5): `ui/TransactionEditorScreen.kt` is one form for creation and correction (`transactionId == null` vs. a stored id), `ui/TransactionListScreen.kt` is the smallest surface that reaches records saved in any earlier session, and `ui/TransactionDetailScreen.kt` shows the stored row with **Edit** and a **Delete** confirmation that names the record. A correction writes only the user-editable fields: `LedgerRepository.editTransaction` takes a `TransactionCorrection`, which cannot carry confirmation or provenance, so correcting a draft cannot promote it into the confirmed records. The launch destinations carry stored ids, so a screen always re-reads the row it shows. The throwaway Compose smoke (real launcher `MainActivity` over a real file-backed database seeded before the launcher read it) and its temporary test dependencies were removed, leaving `app/build.gradle.kts` unchanged; it could not prove on-device pixels, so the visual gate stays user-run.
- Collapsible filters (S2.6 correction): the filter controls are collapsed by default behind a one-line **Show filters** / **Hide filters** toggle carrying the active-dimension count, which keeps the results nearly the whole screen height until the controls are asked for. The panel's open state is hoisted beside the filter values as a saveable flag, so a record round-trip and a configuration change leave the list as it was left. The throwaway host smoke on the real launcher (real file-backed database, phone-sized 411x891dp Robolectric viewport) and its temporary test dependencies were removed, leaving `app/build.gradle.kts` unchanged. Evidence: [S2.6 compact-filter proof](../artifacts/sprint-2/S2.6-a3/compact-filter-proof.md).
- Current-month expense summary (S2.7): the home screen opens with a compact **THIS MONTH** card that names the device-local calendar month (for example `October 2026`) and shows that month's confirmed spending in exact whole dong, with one breakdown row per category that has spending — including `No category` for unfiled spending and `(archived)` for a category archived after its records were filed. It reads the same live Room streams as the rest of the app, so a save, a correction, or a deletion changes the total on its own; the `Saved transactions` filters are pure browsing state and cannot narrow it. Money is summed with `VndMoney.checkedAddToTotal`, and a total past `Long.MAX_VALUE` is shown as a labeled lower bound instead of wrapping. The month it names is the one the device is in *now*: the clock and the device zone are read together when the panel first appears, whenever the home screen resumes after the app was in the background (so a phone that crossed a month boundary, or moved to another zone, while the app was away reports the month it is actually in, not the one it computed before the pause), and again at the first midnight of the next month for a screen left open across it. The throwaway host smoke on the real launcher (real file-backed database, 411x891dp) observed the sprint's exact figures: **165,000 VND** with income, an internal transfer, a draft, and neighbouring-month records stored; **170,000 VND** after correcting 120,000 to 125,000 with the archived category still attributed; **125,000 VND** after deleting the 45,000 record; and then the honest empty-month state. Permanent coverage: `ui/ExpenseSummaryTest` (5 tests) and `ui/ExpenseSummaryPersistenceTest` (3 tests, including the S2.8 month-edge close/reopen case: records on the last millisecond of September, the first instant of October, the last millisecond of October, and the first instant of November, with an edit across the edge, an archive, and a deletion, all surviving a real file-backed Room close/reopen). Evidence: [S2.7 summary proof](../artifacts/sprint-2/S2.7-a1/summary-proof.md), [S2.7 resume proof](../artifacts/sprint-2/S2.7-a2/month-resume-proof.md), and [S2.8 offline/persistence proof](../artifacts/sprint-2/S2.8-a1/offline-persistence-proof.md).
- Manual offline use and date semantics (S2.8): manual ledger use needs no model file, notification access, login, network, or bank integration — the manifest declares no runtime permission and the only permission entry is the dependency-generated, signature-protected `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; the packaged APK carries no model/asset bundle and the sources make no network/notification/login call (static packaging/source evidence only — real offline device behaviour stays a user-only check). A typed date/time is the wall-clock moment in the device's current default zone (`ManualDateTime`, `YYYY-MM-DD` + `HH:mm`, no whitespace, impossible dates refused rather than rolled over), and the month card counts the half-open device-local month `[local midnight on the 1st, local midnight on the 1st of the next month)`. Current-month checks therefore set **transaction dates** around the month start/end; they never change the phone's system clock for membership, except the one resume check that moves the phone one day past a month boundary (then sets the date back).
- Laya artifact reference (S4.1, host-side): `tools/laya/reference_runner.py` reproduces the exact checkpoint sequence/marker/temperature contract against the real pinned ONNX bundle (machine-local ambient download, never committed) and decides with an explicit `unknown` no-fit option; `tools/laya/expense_examples.json` holds the labeled English examples and `tools/laya/PROVENANCE.md` the pinned revisions, hashes, license, graph operators, and parity evidence. Runtime needs `onnxruntime`, `tokenizers`, and `numpy` in the ambient Python. This proves host reference behavior only — Android parity, latency, and memory are later Sprint 4 concerns.
- Laya Android runtime (S4.2, host-side): the app's own tokenizer/sequence/calibration port is proven against the real pinned weights without ORT Java natives — `LayaTokenizerTest` (52 golden strings incl. added-token splits, `[MASK]` lstrip, whitespace runs, NFC variants), `LayaSequenceTest` (9/9 exact `input_ids`/marker positions incl. mask-injection and a 131-option refusal), `LayaCalibrationTest` (9/9 real-logit decisions incl. the two honest unknown misses), `LayaLiveParityTest` (the app's exact input bytes through the real weights via `tools/laya/run_tensors.py`, asserting the S4.1 reference choices), and `LayaRuntimeStatesTest` (missing/corrupt bundle states report without a session). Host JVMs here cannot load ORT Java natives (measured: even the proven 1.21.1 DLL fails under `java.exe` while loading under `python.exe`), so the ARM64 session open stays a user-run device step (§6 below); the desktop ORT artifact is deliberately not a dependency.
- S4.6 manual-entry suggestion gate: `ManualCategorySuggestionMachineTest` checks explicit accept, manual-choice/ignore suppression, expense-only eligibility (Income stays manual), description/type/form identity, category-snapshot staleness, unknown/unavailable outcomes, and late/out-of-order results. This is host state-machine evidence only: it does not render the Compose editor or execute Android-native inference. The actual editor surface remains a user-only phone check below.

## 4. Physical device (observed facts)

Recorded from the user's own `adb` observation, without serial numbers or account identifiers:

| Fact | Observed value |
| --- | --- |
| Model | HONOR ALT-LX2 |
| Android / API | Android 14 / API 34 |
| ABI | `arm64-v8a,armeabi-v7a,armeabi` (64-bit primary) |
| Page size | 4096 bytes |
| Memory | `MemTotal: 7837732 kB` |
| `ro.config.low_ram` | empty (not declared; do **not** assume `false`) |
| adb state | `device` (authorized) |

These are capability facts, not a claim about app performance, model inference latency, or memory headroom. The phone is user-owned; this documentation grants no general worker device access.

## 5. User-run install, open, and reopen

These commands are normally user-run from the user's own PowerShell session, with the intended phone connected over USB and USB debugging authorized. Worker use of `adb` requires explicit authorization in the active Aegis task and is limited to the assigned app-scoped operations; it does not authorize other apps, bank activity, notification access, or system/security changes.

```powershell
adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
adb -d shell am start -n io.github.triet4p.aegisf/.MainActivity
```

The user may also tap **Aegis F** in the phone's app launcher instead of the second command.

Manual checks after installing and opening:

- The screen shows **Aegis F**, **Vietnamese dong**, **VND**, and the `₫` symbol; it presents no fictitious accounts, balances, transactions, or actions.
- In portrait the content stays readable and clear of the status and navigation bars; scrolling works when the available height is reduced.
- Text stays legible after increasing the system font size, and the screen stays readable in both light and dark system themes.
- Close the app from the recent-apps view, reopen it from the launcher (or the component command), and confirm the same screen appears with no crash.

Category management checks (S2.3, consolidated for the S2.8 run; the exact step list with expected results lives in the [S2.3 category management proof](../artifacts/sprint-2/S2.3-a1/category-proof.md)):

- **Manage categories** opens the category screen; add **Food**, and the row appears **immediately** without leaving the screen; the summary reads **1 category**.
- Adding **food** (same name, different case) is refused inline (`Another active category is already called "food".`) and changes nothing.
- Rename to **Groceries** updates the row immediately; **Archive** keeps the row with an **Archived** marker and the history note.
- Add enough categories (the S2.3 proof lists 62 in total) and scroll to the bottom: every row renders — there is no product-imposed cap.
- Close the app from recents and reopen it: the list, the archived marker, and the counts survive.

Transaction creation checks (S2.4, consolidated for the S2.8 run; the exact step list with expected stored values lives in the [S2.4 creation proof](../artifacts/sprint-2/S2.4-a1/creation-proof.md)):

- The home screen now shows **Record a transaction** next to **Manage categories**; opening it shows the form: **Amount (VND)**, **Type** (Expense / Income / Internal transfer), **Date** (`YYYY-MM-DD`), **Time** (`HH:mm`, 24-hour, device time zone), **Description (optional)**, and a category button.
- Record an expense of **45000** with today's date and time, description `Lunch`, and a category → **Save transaction** → the confirmation shows **45,000 VND**, the stored moment, **Confirmed**, and **Manual**, and the form is replaced (no second record from another tap).
- Repeat with **120000** (**Record another** first), then income **1000000** (type Income), then an internal transfer **500000** — the transfer screen states that an internal transfer carries no category and offers no category button.
- Refusals keep the typed value and store nothing: amount `0` shows `Amount must be at least 1 whole dong; got 0.`; `-1` and `1.5` show the digits-only message; a 20-digit number shows the maximum-whole-dong message.
- An archived category is not offered for a new record and cannot be attached to one.
- **Cancel** (or system back) before saving stores nothing; closing the app from recents and reopening keeps the saved records.

Transaction correction and deletion checks (S2.5, consolidated for the S2.8 run; the exact step list with expected stored values lives in the [S2.5 edit/delete proof](../artifacts/sprint-2/S2.5-a1/edit-delete-proof.md)):

- The home screen shows **Saved transactions** under **Record a transaction**; opening it lists every stored record, newest first, with its amount, type, description, category, and moment — including the records from earlier sessions.
- Tapping a record opens its detail: amount, type, when, description, category, **Status**, **Source**, and **Recorded at**, with **Edit** and **Delete**.
- **Edit** opens the same form prefilled from the stored record (amount, type, date, time, description, category). Change the 120,000 record to **125000** and press **Save changes**: the detail returns showing **125,000 VND** and a **Last corrected** line, and the record keeps its **Confirmed**/**Manual** status.
- Leaving an edit with **Cancel** or the system back gesture changes nothing: the detail still shows the stored values.
- A refused edit keeps the typed value and stores nothing: `0`, `1.5`, and a 20-digit number each show their field-local message.
- A record filed under a category that was archived later keeps it: the field and the detail show `<name> (archived)`, and correcting only the description does not clear it and does not revive the category.
- **Delete** opens a confirmation naming the record (amount, type, description, moment, category). **Cancel** and the system back gesture delete nothing; **Delete transaction** removes only that record — the other records and the category remain.
- Close the app from recents and reopen it: the corrected amount and the deletion are still what the ledger holds.

Browsing and filter checks (S2.6, consolidated for the S2.8 run; the exact step list with expected visible rows lives in the [S2.6 browse/filter proof](../artifacts/sprint-2/S2.6-a1/browse-filter-proof.md)):

- **Saved transactions** opens with its filters **collapsed** behind one compact line: a **Show filters** control that reports how many dimensions are set (**1 active filter**, **2 active filters**; nothing is shown for the default state). Tapping it reveals the controls — **Type** chips (**All types**, Expense, Income, Internal transfer), a **Category** button (**All categories** by default), **From**/**Through** calendar-day fields (`YYYY-MM-DD`, device time zone, either end may be empty) and **Clear all** — and **Hide filters** puts them away again, giving the height back to the list. Collapsing changes nothing about the filters or the rows; the open/closed state, every selection, and a typed date (including a refused one and its message) stay as they were, through a record round-trip and a configuration change, and are never written to disk.
- Type, category, and dates combine as AND: choosing Expense, a category, and a From/Through pair shows only the records satisfying all three; **Clear all** (and the no-match screen's **Clear filters**) returns the full list.
- The category selector lists every stored category including archived ones marked `(archived)`, plus separate **All categories** and **No category** choices: selecting an archived category shows its historical records, and **No category** shows only unfiled records.
- Dates are inclusive calendar days in the device zone: From `2026-10-15` keeps a record at `2026-10-15 00:00`, Through `2026-10-15` keeps one at `2026-10-15 23:59`, and `2026-10-16 00:00` belongs to the 16th. A malformed date or a From after Through shows its message and keeps the other dimensions narrowing — it never silently broadens the list or crashes.
- A filtered list with no rows says **No matching transactions** (not **No transactions yet**) and offers **Clear filters**; an empty ledger still says **No transactions yet**. Concrete check: with category **Food** chosen, set From to a far-future day (e.g. `2099-01-01`) with Through empty — the list shows **No matching transactions** — then tap **Clear filters** and confirm all records return.
- Opening a record and coming back keeps the filters; a correction, deletion, rename, or archive made on another screen appears in the list without restarting the app. Filters never write: they cannot create, change, or delete records or categories.
- Close the app from recents and reopen it: the ledger rows are unchanged (filters themselves are a browsing preference and are not persisted).

Current-month summary checks (S2.7; the exact host evidence and limits live in the [S2.7 summary proof](../artifacts/sprint-2/S2.7-a1/summary-proof.md)):

Open the home screen (**Aegis F**) and read the figure the **THIS MONTH** card already shows; write it down as your baseline **B**. The card counts the whole ledger, so any records you already have dated in the current month are part of **B** — **do not delete or clear them (the S2.4 records included) to make a check come out even**, and every expectation below is relative to **B**.

- The card names the current device-local month (for example **October 2026**) and shows **B** — or `No confirmed expenses this month.` when nothing is dated this month. Nothing else on the screen shows a figure, and it never shows `0 VND` while it is still reading.
- Create a category you have not used before (for example **S2.7 test**), then record **two new disposable expenses dated today** with unmistakable descriptions — `S2.7 test A` = **45000** and `S2.7 test B` = **120000** — both filed under it (or leave `S2.7 test A` unfiled if you also want to see the `No category` row). The card reads **B + 165,000 VND**, and the **S2.7 test** row reads **165,000 VND**, because no earlier record uses that category.
- Record income **1000000** and an internal transfer **500000**, both dated today: the card still reads **B + 165,000 VND**, and neither amount appears anywhere on it. A record dated outside the current month is not counted either.
- The card is not a filter result: open **Saved transactions**, set any type/category/date filters (or none), come back to the home screen — the figure is what the previous step left. The card never writes to the ledger.
- Correct `S2.7 test B` from **120000** to **125000** (**Saved transactions** → that record → **Edit** → **Save changes**): the card reads **B + 170,000 VND** and the **S2.7 test** row reads **170,000 VND**.
- Archive the **S2.7 test** category (**Manage categories** → **Archive**): its row is now `S2.7 test (archived)` and the total is still **B + 170,000 VND** — the spending keeps its category instead of turning into **No category**.
- Delete `S2.7 test A` (45000): the card reads **B + 125,000 VND** and the **S2.7 test** row reads **125,000 VND**. A category row disappears only when no confirmed expense of this month is still filed under it, so this archived row stays until `S2.7 test B` goes too; deleting that as well returns the card to **B**, with your pre-existing records untouched.
- Swipe the app away from the recent-apps view and reopen it: the card shows the same figure. (A swipe is a close/reopen check, not a guaranteed process death — Android may keep the process alive.)
- Leave the app (home screen, then go back to the phone's home), move the phone past the first midnight of a month (turn off **Set time automatically** and set the date one day into the new month), and return to **Aegis F** without swiping it away from recents: the card names the month the phone is now in and counts that month's records — it is not still showing the month it showed before you left. Setting the date back afterwards returns the card to the earlier month the same way. A plain timezone change that does not cross a midnight usually keeps the same month name; near the end of a month it can also change which records the current month counts.
- Repeat the important steps in dark theme and with a large system font: the card wraps its rows, the home screen still scrolls, and nothing is cut off by the status or navigation bars.

S2.8 differential checks — offline persistence and month boundaries only (user-only; the S2.3–S2.7 steps above are already accepted and are not repeated here):

- Read the **THIS MONTH** card first and write it down as baseline **B**; use NEW disposable records only — six test records in total: expenses `S2.8 A` = **45000** and `S2.8 B` = **120000**, income `S2.8 income` = **1000000**, transfer `S2.8 move` = **500000**, plus the two month-edge expenses below — and never delete, clear, or edit existing records. Offline here means turning Wi-Fi/mobile data off (no airplane-mode requirement); turn connectivity back on afterwards and delete only the `S2.8` test records, keeping the archived `S2.8 check` category (with its history) if desired.
- Offline + persistence: with connectivity off, record today's expenses `S2.8 A` = **45000** and `S2.8 B` = **120000** under the new `S2.8 check` category, income `S2.8 income` = **1000000**, and transfer `S2.8 move` = **500000** — the card reads **B + 165,000 VND** with no model/permission/login/network prompt. Force-stop the app (**Settings > Apps > Aegis F > Force stop**), relaunch it, still offline: the records and **B + 165,000 VND** are unchanged. If notification access is already off, merely observe it; never grant unrelated access.
- Offline edit/archive/restart: still offline, correct `S2.8 B` to **125000** (card **B + 170,000 VND**), archive the `S2.8 check` category (row `S2.8 check (archived)`, total unchanged), force-stop and relaunch: the detail still shows **125,000 VND**, **Confirmed**/**Manual**, a **Last corrected** line, the archived attribution, and **B + 170,000 VND**.
- Offline delete + month edges (transaction dates only — never change the system clock): still offline, delete `S2.8 A` (card **B + 125,000 VND**; five test records remain), then record `S2.8 prior edge` = **11000** dated the last day of the previous month (e.g. `2026-09-30`, `23:59`) and `S2.8 next edge` = **17000** dated the first day of the next month (e.g. `2026-11-01`, `00:00`): neither moves the card — it still reads **B + 125,000 VND**. Correct `S2.8 next edge`'s date to today: the card reads **B + 142,000 VND**; correct it back to `2026-11-01`: the card returns to **B + 125,000 VND**. Delete the remaining five test records (`S2.8 B`, `S2.8 income`, `S2.8 move`, `S2.8 prior edge`, `S2.8 next edge`; `S2.8 A` is already gone — card back to **B**), force-stop and relaunch once more, restore connectivity, and confirm **B** and the pre-existing records are untouched. The archived `S2.8 check` category may be kept for its history.

Bank-notification intake checks (S3.8, user-run): the supported notification format, the host-verified behavior, and what is still unverified on a phone are stated in the [README](../README.md#supported-bank-notification-formats-and-what-is-verified), and the consolidated step list with expected results lives in the [S3.8 bank-intake verification handoff](../artifacts/sprint-3/handoffs/S3.8-A1.md). The reference-related limitations in that record are corrected by the [S3.8-A2 correction handoff](../artifacts/sprint-3/handoffs/S3.8-A2.md): the bank format still states no reference field, and a reference-like `ND:` value is ordinary description text that distinguishes two statements. Nothing there is run by a worker: phone contact stays user-only, and no notification body, account identifier, balance, or OTP may be recorded or shared.
**Bank-notification lifecycle and safe diagnostics (S4.N1 update):** `BankNotificationListener` automatically reconciles currently active notifications when the app resumes, when the application process initializes, and when the user opens **Review bank drafts**. If the listener is disconnected by system process management while notification access remains granted, an automatic rebind is requested. To verify listener state safely without exposing financial details or raw notification content, inspect filtered logcat output:
```powershell
adb logcat -d -s BankNotificationListener:V NotificationAccess:V
```
This logs only privacy-safe state transitions, rebind requests, allowlist checks, candidate counts, and intake rejection reasons; no financial payload, amount, account identifier, OTP, or memo text is ever logged.

**Evidence distinction to keep clear:** the S1.5 and S1.6 device checks used the S1.5 package and S1.6 A/B packages (`versionCode` 1–3). The current debug `versionCode = 4` package was installed in place and exercised for S4.6-A2; see the [sanitized phone evidence](../artifacts/sprint-4/workers/S4.6-A2.md). That limited Aegis UI observation does not replace unrelated task-specific device checks or the final user real-experience gate.

## 6. User-run S4.2 parity probe (phone, ONNX Runtime Android)

These steps are **user-only** (workers never contact the phone): they install the
debug build, stage the pinned model bundle into app-private storage, and run the
real on-device `LayaRuntime` through the process-owned `LayaInferenceLifecycle`
used by the debug-only probe. It remains parity-verification tooling, not the
manual-entry suggestion UI; see the task-scoped S4.6 phone check below. The probe creates
no ledger records, changes no categories, and touches no notification path.

Prerequisites: the phone from §4 (HONOR ALT-LX2, Android 14/API 34, ARM64,
4096-byte pages) with USB debugging authorized (`adb devices` shows `device`),
plus the pinned bundle on the PC — the `receptron/laya-onnx` revision `68f27dfe`
snapshot (see `tools/laya/PROVENANCE.md` for sizes/hashes) and the committed
tokenizer asset. Bundle files stay out of Git. This debug helper performs a
separate user-run transfer; the app's S4.3 provisioning page can instead import
the three pinned export files from a local folder. The install below is
`install -r` (in-place update: same package, same debug certificate, existing
app data preserved) — never uninstall, never clear app data.

Run the checked-in helper from a fresh PowerShell window (from §2) at the repo
root. It verifies the four pinned files (exact size + SHA-256) before touching
the phone, installs the debug APK, stages via shell-owned `/data/local/tmp`
with narrow readable-only permissions (`755` dirs / `644` files — deliberately
never world-writable), copies the exact four files `LayaRuntime.open` requires
into app-private storage, frees the staging copy, clears any stale report
before launch (so a retrieved report can only be from this run), launches the
probe (it auto-runs; the button re-runs it), polls until the fresh report
appears (~1–2 min for the 1.6 GB cold load + 9 inferences), validates it parses
as JSON with `verdict` + `state`, and saves it:

```powershell
powershell -ExecutionPolicy Bypass -File tools\laya\Invoke-S42ParityProbe.ps1
```

The copy-ready invocation on this host is exactly that line run from
`F:\ai-ml\aegis-f`, with these verified defaults (override only with `-BundleDir`
/ `-TokenizerAsset` / `-ApkPath` / `-ReportOut`):

- Bundle snapshot (revision `68f27dfe`, SHA-256-verified):
  `F:\llms\hf\models\hub\models--receptron--laya-onnx\snapshots\68f27dfe5a27a54fb2b1fefc432f43f972e90868`
  holding `laya.onnx` (3,807,291 B), `laya.onnx.data` (1,685,258,240 B),
  `laya_config.json` (369 B).
- Tokenizer asset (committed, SHA-256-verified):
  `F:\ai-ml\aegis-f\app\src\main\assets\laya\laya_tokenizer.bin` (1,317,378 B).
- Debug APK carrying the probe (release builds have none):
  `F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk`
  (`versionCode = 4`, package `io.github.triet4p.aegisf`; probe component
  `io.github.triet4p.aegisf.layaprobe.LayaParityProbeActivity`).
- Report destination:
  `F:\ai-ml\aegis-f\artifacts\sprint-4\reviews\S42-parity-report.json`.

To preview every device command without executing (local pins are still
verified), append `-DryRun`. For a slow device, extend the poll budget, e.g.
`-TimeoutSeconds 600`. Benign `adb` progress printed on stderr (for example
`adb push` transfer lines) is treated as ordinary device output and never
aborts a step whose exit code is 0; every `adb` step still fails loudly on a
nonzero exit and prints the exact command plus device output; a `FAIL`/`NO_RUN`
report verdict is still saved and printed verbatim — return it as observed,
never tune it.

The helper performs exactly these device commands (shown here so the record is
complete; prefer running the helper over retyping them):

```powershell
adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
adb -d shell "mkdir -p /data/local/tmp/laya-model"
adb -d push 'F:\llms\hf\models\hub\models--receptron--laya-onnx\snapshots\68f27dfe5a27a54fb2b1fefc432f43f972e90868\laya.onnx' /data/local/tmp/laya-model/laya.onnx
adb -d push 'F:\llms\hf\models\hub\models--receptron--laya-onnx\snapshots\68f27dfe5a27a54fb2b1fefc432f43f972e90868\laya.onnx.data' /data/local/tmp/laya-model/laya.onnx.data
adb -d push 'F:\llms\hf\models\hub\models--receptron--laya-onnx\snapshots\68f27dfe5a27a54fb2b1fefc432f43f972e90868\laya_config.json' /data/local/tmp/laya-model/laya_config.json
adb -d push 'F:\ai-ml\aegis-f\app\src\main\assets\laya\laya_tokenizer.bin' /data/local/tmp/laya-model/laya_tokenizer.bin
adb -d shell "chmod 755 /data/local/tmp/laya-model"
adb -d shell "chmod 644 /data/local/tmp/laya-model/laya.onnx /data/local/tmp/laya-model/laya.onnx.data /data/local/tmp/laya-model/laya_config.json /data/local/tmp/laya-model/laya_tokenizer.bin"
adb -d shell "run-as io.github.triet4p.aegisf mkdir -p files/laya/model/tokenizer files/laya/parity"
adb -d shell "run-as io.github.triet4p.aegisf cp /data/local/tmp/laya-model/laya.onnx /data/local/tmp/laya-model/laya.onnx.data /data/local/tmp/laya-model/laya_config.json files/laya/model/"
adb -d shell "run-as io.github.triet4p.aegisf cp /data/local/tmp/laya-model/laya_tokenizer.bin files/laya/model/tokenizer/laya_tokenizer.bin"
adb -d shell "run-as io.github.triet4p.aegisf ls -l files/laya/model/laya.onnx files/laya/model/laya.onnx.data files/laya/model/laya_config.json files/laya/model/tokenizer/laya_tokenizer.bin"
adb -d shell "rm -rf /data/local/tmp/laya-model"
adb -d shell "run-as io.github.triet4p.aegisf rm -f files/laya/parity/S42-parity-report.json"
adb -d shell am start -n io.github.triet4p.aegisf/io.github.triet4p.aegisf.layaprobe.LayaParityProbeActivity
adb -d exec-out run-as io.github.triet4p.aegisf cat files/laya/parity/S42-parity-report.json
```

The final `cat` is polled (every 5 s, 300 s budget): earlier absence means the
run has not finished, because the stale report was cleared before launch.

Expected evidence to return (paste the report JSON, plus the on-screen verdict line):

- `device_model` HONOR ALT-LX2, `android_api` 34, `abis[0]` `arm64-v8a`, `page_size_bytes` 4096.
- `state` `Ready` (anything else names the exact missing/corrupt cause — return it verbatim instead of parity numbers).
- `verdict` `PASS`: all nine `cases[].match` true, with `android_choice` equal to the S4.1 reference outcomes (`unknown`, `Housing`, `Transport`, `Health`, `unknown`, `unknown`, `Food`, `unknown`, `unknown` in example order), and the `meaningful-rent` entry carrying `reference_match` true (Housing 0.9796, confidence 0.9336). Per-case `latency_ms` and `open_ms` are observed cold/warm timings for S4.9, not pass/fail gates here.
- A `FAIL` verdict still returns the full report: mismatched `android_choice` values are recorded as observed misses (like the S4.1 food/long-state unknowns), not tuned away.

Privacy: the report carries only the nine fixed English example strings, category names, probabilities, timings, and device facts (model, API, ABI, page size). No ledger content, notification body, account identifier, or OTP is involved.

### S4.6 manual-entry suggestion UI (task-scoped Aegis phone check)

This section records the actual **Record a transaction** surface check. A worker
may run app-scoped `adb` enumeration, `install -r`, launch, UI capture and
interaction, and read-only Aegis-app inspection only when the active task
explicitly authorizes those operations. This scope does not allow other-app or
bank activity, notification content, model/ledger changes, or system/security
settings changes. For this check, use the already-provisioned model if present;
never uninstall, clear app data, replace a model, or alter existing ledger or
category data to manufacture a test state.

1. Build the debug APK from the repository root:

   ```powershell
   .\gradlew.bat --no-daemon :app:assembleDebug --console=plain
   ```

2. With the intended phone authorized, install in place using the command from
   §5 (or tap **Aegis F** if this build is already installed):

   ```powershell
   adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
   ```

   `install -r` updates the same app and preserves its app data; never
   uninstall or use `pm clear`.

3. Open **Aegis F** and tap **Record a transaction**. Leave **Type** as
   **Expense**, enter an amount, and enter a description containing one unique
   active category name as an exact phrase. Suggestions are expense-only;
   Income and transfers remain manual. When the local lifecycle returns that
   current category, verify **Use suggestion** appears; the category field must
   not change until tapped. Tap it and verify the field changes but no record
   is saved. Choose a different category (or **No category**) manually and
   verify that choice stays selected. Change the description to ask again,
   then tap **Ignore**; verify the category and other fields remain unchanged.

4. Navigate Back or tap **Cancel** without pressing **Save transaction**. Open
   **Saved transactions** and confirm the test description was not added. If
   the model is already missing or unready, observe the unavailable message and
   confirm the category picker and normal manual form remain usable; do not
   remove or replace an installed model to test that state.

A description that does not match category names may exercise neural scoring
when the model is ready; record its result as observed, including a no-fit
outcome. The name-only shortlist's measured recall remains **1/6** on the
accepted labeled positive examples, so this check makes no suggestion-quality
claim. Category edits, pending-request races, and lifecycle cancellation are
covered by host state-machine tests, not by pretending this phone procedure
observes every race.

The task-scoped S4.6-A2 phone observation used the installed debug `versionCode 4`
build: an exact active-category phrase produced a current-category suggestion;
**Use suggestion** changed the category field only, a distinct manual choice
stayed selected across a subsequent suggestion, and **Ignore** preserved that
choice and the other form fields. A no-fit result still left manual category
selection available. Both forms were canceled without saving, and the unique
test markers were absent from **Saved transactions**. No model or persisted
category/ledger data was changed, and no notification data was read or cleared.
The unavailable-model state was not forced. The sanitized evidence is in
[`S4.6-A2`](../artifacts/sprint-4/workers/S4.6-A2.md). This is scoped evidence,
not general worker device authority or a substitute for the final user
real-experience gate.

## 7. User-run S4.3 local model provisioning

The normal model setup path is in the app, not a shell transfer or a network
downloader. It is **user-only** to inspect or operate the phone. The selected
artifact is the pinned English `receptron/laya-onnx` export listed in
[`tools/laya/PROVENANCE.md`](../tools/laya/PROVENANCE.md); keep all graph and
external-weight files outside Git.

1. If the pinned bundle is not already on the phone, acquire that exact snapshot
   on the PC and use the phone's USB file-transfer mode (or another local file
   transfer) to place `laya.onnx`, `laya.onnx.data`, and `laya_config.json`
   together in a folder visible to the Android file picker, for example
   `Downloads/Laya-model`. The app supplies its tokenizer asset internally.
   Keep enough free phone storage for the source folder and the app-private copy
   while importing.
2. Open **Aegis F** → **Model setup**. The page checks the current app-private
   model by streaming its pinned hashes and opening the real local runtime. If
   it already says **Ready — verified on this device**, stop: the existing
   installation is intact and must not be re-imported or cleared.
3. When the page reports **Model missing or incomplete**, choose **Choose model
   folder**; when it reports a corrupt/incompatible installation, choose
   **Replace with verified model** only after selecting the pinned files.
   Select the folder containing the three named export files. The page shows
   copy progress; it checks each exact size and SHA-256 and opens the candidate
   runtime before publishing it to app-private storage.
4. Continue only if the page reports **Ready — verified on this device**.
   A missing, wrong-hash, interrupted, or runtime-incompatible file reports its
   cause and never appears Ready. The previous app-private model is retained
   until a replacement has passed all checks. The import does not touch the
   ledger or notification settings, and manual transaction entry remains
   available whether the model is ready or not.
5. Leave the page, force-stop and reopen the app, then revisit **Model setup**.
   It rechecks the stored files and runtime rather than trusting an in-memory
   flag. To confirm manual operation without creating financial data, open
   **Record a transaction** and return without saving. Do not uninstall the app,
   clear app storage, or delete existing ledger data.

The app has no model download request: network may be used externally for the
initial pinned artifact acquisition, but file import, model verification, and
the existing inference runtime are local. Category suggestions appear only in
the separate manual expense form; provisioning itself does not surface or save
suggestions or ledger records.

### S4.3-A2 final APK check and cancellation (user-only)

The final debug APK is `F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk`.
With the intended phone connected and authorized, use the in-place update command
from PowerShell at the repository root:

```powershell
adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
```

This is the same package (`io.github.triet4p.aegisf`) and existing debug signing
key; `install -r` updates the app without clearing its private model or ledger
data. Never uninstall the app, clear its storage, or delete
`files/laya/model` to manufacture a Missing state. If Android reports a
signature or installation error, stop and return the exact error rather than
trying a destructive workaround.

1. Launch **Aegis F** and open **Model setup**. Record the initial status text
   or a screenshot before making any import choice.

2. If it already says **Ready — verified on this device**, do not choose a
   folder, replace the model, or clear data. Return that screenshot/text as
   proof that the existing installation stayed Ready after the APK update.
   This is **existing-readiness proof only**, not proof of a new SAF import.
   Do not proceed with the import branch below.

3. If the initial status is **Model missing or incomplete** or
   **Model corrupt or incompatible**, use the existing pinned source folder on
   the PC:
   `F:\llms\hf\models\hub\models--receptron--laya-onnx\snapshots\68f27dfe5a27a54fb2b1fefc432f43f972e90868`.
   Copy `laya.onnx`, `laya.onnx.data`, and `laya_config.json` together, with
   those exact names, to a phone folder visible in Android Files (for example
   `Downloads/Laya-model`) using local USB file transfer or another local
   transfer. The app supplies its tokenizer. Keep enough free storage for the
   source files and the app-private staged copy at the same time.

4. On **Model setup**, choose **Choose model folder** for Missing or
   **Replace with verified model** for Corrupt or incompatible; select the
   folder containing the three files. Wait for
   **Ready — verified on this device** and record the final status and
   approximate transfer time. A fresh import is established only by this
   completed folder selection and Ready result.

5. If an import is available and cancellation is being checked, tap
   **Cancel import** (or press system Back) while progress is running. The page
   must remain busy and say cancellation is requested while the active file
   read or native runtime open finishes; those operations cannot be interrupted
   immediately. Wait until **Import cancelled** appears before leaving. At that
   point the incomplete staging copy was discarded and the prior installed
   model was not replaced; **Return to ledger** is available. If the final
   verified publication already completed before the cancellation request was
   observed, the page may instead finish at Ready; report that exact result,
   not a stopped import. To establish a fresh import after a cancelled result,
   the user may select the same folder again and let that new, explicit import
   complete.

6. After a **Ready** result (either an existing installation or the completed
   import), force-stop and reopen the app, revisit **Model setup**, and record
   the rechecked status. Open **Record a transaction**, then return without
   pressing Save; no test transaction should be created. These are
   non-destructive restart/manual-entry checks and do not turn an existing
   Ready installation into fresh SAF-import proof.

Return the initial status, the final/restart status, whether the starting
installation was already Ready or a new folder import completed, any
cancellation status and approximate copy time, and the no-save manual-entry
observation. If the initial state was already Ready, the required safe choice
is to preserve it and return that existing-readiness evidence; never delete it
solely to test a fresh import.

### S4.4 Host-side category-candidate planning

The production candidate contract is `LayaRuntime.suggest(description, activeCategories,
correctionRules)`. Caller-supplied exact-description correction rules take precedence over
unique exact normalized category-name phrases; remaining requests go through model inference.
Rules are neither persisted nor learned by this task. S4.7 owns explicit remember/remove
storage. The existing category schema stores names only, so deterministic shortlist ranking
uses Unicode name-token evidence and stable ids as tie-breaks.

When all active categories fit, the planner includes them all and appends the explicit
unknown/no-fit option. For an oversized set, it retains only budget-fitting candidates
ranked by metadata evidence, always keeps unknown, and reports omitted-category/evidence
counts. With no category-name evidence, it returns manual unknown rather than a first-N
prefix. Token budgets use the pinned tokenizer and sequence construction, including the
48-token option limit. The result uses stable category ids; before display, callers must
resolve it through `LayaCandidatePlanner.resolveCurrent` against fresh category rows.
Missing, archived, renamed, and duplicate ids are rejected as stale. No model decision
writes or confirms a financial record.

Host regression and live-weight verification:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```

The live candidate smoke sends the planner's actual Kotlin token ids and marker positions
through `tools/laya/run_tensors.py` and the pinned local host ONNX bundle. It is not an
Android/SAF test; do not run device commands as a worker. Suggestions remain unconnected
to manual-entry and notification UI until later Sprint 4 work.

### S4.5 User-run Android lifecycle responsiveness check

This is a **user-only** physical-device check of the real ORT Android runtime
through the process-owned inference lifecycle. It does not create or modify
ledger records or categories, and workers must not run its `adb` commands. Use
the debug APK built from the final task source; do not uninstall or clear app
data. The existing probe helper installs in place with `install -r`, preserves
app data, stages only the pinned model files, and does not touch notification
settings.

From a fresh PowerShell window at the repository root, run the existing
[`S4.2 probe helper`](../tools/laya/Invoke-S42ParityProbe.ps1). Save its report
to a temporary path so the accepted S4.2 report is not overwritten:

```powershell
powershell -ExecutionPolicy Bypass -File tools\laya\Invoke-S42ParityProbe.ps1 -ReportOut "$env:TEMP\S45-lifecycle-parity.json"
```

While the helper's first PowerShell window polls for the report, bring
**Aegis F**'s `MainActivity` to the foreground so the process-owned worker can
continue behind it. If the probe is the current root screen, run this
user-only command from a second PowerShell window at the repository root:

```powershell
adb -d shell am start -n io.github.triet4p.aegisf/.MainActivity
```

If **Aegis F** is already below the probe in Recents, system Back is also
sufficient. Do not force-stop or swipe the app away. Open **Record a
transaction**, type in the form, navigate away and back, and confirm the fields
remain responsive. Do not press **Save**; leaving the form without saving
writes nothing. If the first probe run finishes before you switch, tap **Run
parity probe** and switch to the main activity promptly.

If notification access is already granted and a permitted bank notification
arrives naturally during the check, verify that it appears as a pending item
under **Review bank drafts**. Do not generate a bank transaction, edit or
confirm a draft, or share its body. If no notification arrives naturally, leave
this phone-only observation unclaimed; the host smoke separately exercises the
listener-to-Room persistence path during blocked inference.

Return only whether the probe reached `Ready`/`PASS`, whether typing and
navigation remained responsive without saving, and whether a naturally
received notification persisted as a pending draft (or that none arrived).
Do not send the raw probe JSON, device facts, or notification content.

### S4.5 Host lifecycle and persistence smoke

Run the host regressions and both APK builds with:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

`LayaInferenceLifecycleTest` exercises cancellation, a single replaceable
pending request, session serialization, deferred release while native work is
active, idle close, and reopen through a controlled blocking session. The
notification concurrency smoke uses the real listener and Room-backed intake
path while that worker is blocked; its editor/navigation interaction is
host-simulated, not a Compose or physical-device result. `LayaLiveParityTest`
continues to run the Kotlin decision path against the real pinned host weights
through the existing Python ONNX Runtime tool. None of these host checks proves
ARM64 Android native execution or actual device UI responsiveness; the
user-only procedure above is the physical check.


## 8. Update path and troubleshooting

**Updating in place.** Install an updated APK over the existing one with `adb -d install -r <apk>`. For the update to be accepted and to keep existing app data:

- Use the **same application ID** (`io.github.triet4p.aegisf`) and the **same signing certificate** (the preserved `debug.keystore`).
- Increase `versionCode` for each new build; the current clean build is 4.
- Do not uninstall or clear app data as a workaround. `install -r` is the update path; wiping data hides the real cause and destroys the state you are trying to preserve. If an update fails, capture the exact `adb` error text and report it instead.

**Common failure signals and their real causes:**

| Symptom | Likely cause | Correct action |
| --- | --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signature mismatch | New APK signed with a different key than the installed one | Restore the preserved `debug.keystore` and rebuild; do not uninstall the app to force it |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | `versionCode` not increased | Raise `versionCode` and rebuild |
| `adb devices` shows `unauthorized` | USB debugging prompt not accepted on the phone | Accept the prompt on the phone (a user decision), then recheck |
| `adb devices` shows `offline` / no device | Cable, USB mode, or stale adb server | Reconnect the cable, set the USB mode to file transfer, run `adb kill-server` then `adb devices` |
| `adb` reports "no devices" but the phone is connected | Missing or wrong Windows USB driver | Install the official Google USB driver or the OEM's driver for the phone model (see references) |
| Phone blocks or warns about installing the app | Android/OEM install protection for sideloaded apps | Follow the phone's own on-screen prompts and settings as the user; never attempt to bypass restricted-settings protections or disable security to force an install |

**Privacy and safety when diagnosing:**

- Do **not** capture or share raw notification content, SMS, or log buffers that may contain bank transaction text, account numbers, card digits, OTPs, or other identifiers. Bank-notification intake **is** part of this build, so this rule is load-bearing for every check below: no notification body, account identifier, balance, or OTP may be recorded, exported, or pasted anywhere, and a redacted draft screenshot with amounts and states is the most that should be kept.
- Do not paste device serial numbers, account names, or other identifiers into shared documents or artifacts. Model, Android version, API level, ABI, page size, and memory are sufficient.
- No administrator elevation and no system-wide configuration change is part of this setup or of troubleshooting.
- Worker `adb` use requires explicit task-scoped authorization for the assigned Aegis-app operation; otherwise device steps remain user-run.

## 8. Why no Android Studio or emulator

The build path needs only the JDK, the SDK command-line tools (platform, build tools, platform-tools), and the Gradle Wrapper — all installed and pinned above. Android Studio is a graphical front end over the same tools and adds nothing required here. An emulator is not used because acceptance is defined against the user's physical phone: an emulator would not prove the real device's ABI, memory, page size, OEM behavior, or install/update path. Debugging is done with `adb` plus the user's manual observations.

## 9. Official references

- [sdkmanager](https://developer.android.com/tools/sdkmanager) and [Android CLI](https://developer.android.com/tools/agents/android-cli) — package installation and the `sdkmanager` deprecation notice
- [Build and install from the command line](https://developer.android.com/build/building-cmdline) — `assembleDebug` and `adb install -r`
- [adb](https://developer.android.com/tools/adb) — device states, `adb devices`, server restart
- [Google USB driver](https://developer.android.com/studio/run/win-usb) and [OEM USB drivers](https://developer.android.com/studio/run/oem-usb) — Windows device recognition
- [Android 17 (API 37) SDK setup](https://developer.android.com/about/versions/17/setup-sdk) — platform package and build tools
- [AGP 9.1 release notes](https://developer.android.com/build/releases/agp-9-1-0-release-notes) and [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) — plugin/Gradle/JDK compatibility
- [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html) and [Gradle release checksums](https://gradle.org/release-checksums/) — running JDK and Wrapper verification
- [Gradle Wrapper checksum verification](https://docs.gradle.org/9.3.1/userguide/gradle_wrapper.html#configuring_checksum_verification) — `distributionSha256Sum`
- [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) and [Compose setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) — BOM and compiler plugin pairing
- [Restricted settings](https://support.google.com/android/answer/12623953) — why an OEM may block sideloaded installs, and why that protection is not bypassed
