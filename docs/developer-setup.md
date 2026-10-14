# Command-line developer setup

This is the supported development path for Aegis F: the official Android SDK command-line tools, the project's Gradle Wrapper, and `adb` to a physical phone. Android Studio and an emulator are **not** required; no project step uses them. Commands are split into **PC-side** (worker-verified) and **user-run device** commands (never run by a worker; all phone contact is user-only).

Verified state (2026-10-14): the pinned toolchain below built the debug APK from a genuinely new PowerShell process, and the user reported that the S1.5 launch checks and the S1.6 A/B update-path checks passed. Historical evidence: [S1.1 toolchain](../artifacts/sprint-1/S1.1-a1/toolchain.md), [S1.2 SDK setup](../artifacts/sprint-1/S1.2-a1/sdk-setup.md), [S1.4 build](../artifacts/sprint-1/S1.4-a1/build-proof.md), [S1.5 launch](../artifacts/sprint-1/S1.5-a2/launch-proof.md), [S1.6 A/B update](../artifacts/sprint-1/S1.6-a1/update-proof.md), [S1.6 clean APK](../artifacts/sprint-1/S1.6-a2/clean-proof.md). The fresh-process reproduction of the commands in sections 2–3 is recorded in [S1.7 docs proof](../artifacts/sprint-1/S1.7-a1/docs-proof.md).

## 1. Pinned toolchain

| Component | Pin | Notes |
| --- | --- | --- |
| Gradle | 9.3.1 | Wrapper binary distribution; archive SHA-256 pinned in `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.1.1 | `com.android.application`, declared in the root `build.gradle.kts` |
| Kotlin | 2.2.10 | AGP 9.1.1 built-in Kotlin; do **not** also apply `org.jetbrains.kotlin.android` |
| Compose compiler plugin | `org.jetbrains.kotlin.plugin.compose` 2.2.10 | Must match the Kotlin version |
| Compose libraries | Compose BOM 2026.09.00 | `androidx.compose.ui:ui`, `androidx.compose.material3:material3` |
| Activity Compose | `androidx.activity:activity-compose:1.13.0` | Supplies `ComponentActivity` / `setContent` |
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

These are capability facts, not a claim about app performance, model inference latency, or memory headroom. The device is the user's phone; no worker contacts it.

## 5. User-run install, open, and reopen

These commands are **user-only**. They are run in the user's own PowerShell session, with the phone connected over USB, USB debugging authorized, and the phone identified as the intended device.

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

Report the actual visual/runtime observations to Main. A successful build or offline inspection does not establish device acceptance.

**Evidence distinction to keep clear:** the user-reported device checks so far were made with the S1.5 package and the S1.6 A/B packages (`versionCode` 1–3). Installing the current clean `versionCode = 4` package with the command above is still a user-run step whose result has not been reported.

## 6. Update path and troubleshooting

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

- Do **not** capture or share raw notification content, SMS, or log buffers that may contain bank transaction text, account numbers, card digits, OTPs, or other identifiers. Bank/notification features are not part of this build, but the same rule applies to any future log.
- Do not paste device serial numbers, account names, or other identifiers into shared documents or artifacts. Model, Android version, API level, ABI, page size, and memory are sufficient.
- No administrator elevation and no system-wide configuration change is part of this setup or of troubleshooting.
- Workers never run `adb` against the phone; the user runs device commands and reports observations.

## 7. Why no Android Studio or emulator

The build path needs only the JDK, the SDK command-line tools (platform, build tools, platform-tools), and the Gradle Wrapper — all installed and pinned above. Android Studio is a graphical front end over the same tools and adds nothing required here. An emulator is not used because acceptance is defined against the user's physical phone: an emulator would not prove the real device's ABI, memory, page size, OEM behavior, or install/update path. Debugging is done with `adb` plus the user's manual observations.

## 8. Official references

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
