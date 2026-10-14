# S1.4 Android build proof

- Task: `S1.4`
- Attempt: `S1.4-a1`
- Agent: `Sprint1AndroidProject` (Silver)
- Route: Light
- Repository: `F:\ai-ml\aegis-f`
- Result: project build and offline APK checks passed; Main acceptance remains pending.

## Selected and resolved configuration

| Component | Resolved value |
| --- | --- |
| Gradle Wrapper | Gradle 9.3.1 binary distribution |
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.2.10 through AGP built-in Kotlin; no `org.jetbrains.kotlin.android` plugin |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` 2.2.10 |
| Compose libraries | Compose BOM 2026.09.00; Compose UI and Material 3 |
| SDK | compile 37, target 37, minimum 24; Build Tools 36.0.0 |
| Build JDK | Oracle Java 21.0.1 (`C:\Program Files\Java\jdk-21`) |
| Java/Kotlin bytecode target | 17 |
| Namespace / application ID | `io.github.triet4p.aegisf` |

The package ID follows the observed repository origin owner `triet4p` and app name. Compatibility pins were not changed: the selected AGP/Gradle/API 37/Build Tools set completed the build.

## Wrapper authenticity and build

The official `gradle-9.3.1-bin.zip` was downloaded to a temporary directory, verified against the official Gradle release-checksum listing, and removed with the temporary bootstrap directory after Wrapper generation. Expected and observed archive SHA-256: `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06` (match). The generated Wrapper JAR SHA-256 was `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`, matching Gradle's published 9.3.1 Wrapper JAR checksum. `gradle/wrapper/gradle-wrapper.properties` pins both `gradle-9.3.1-bin.zip` and its `distributionSha256Sum`.

Official references:

- [Gradle release checksums](https://gradle.org/release-checksums/)
- [Gradle 9.3.1 Wrapper documentation](https://docs.gradle.org/9.3.1/userguide/gradle_wrapper.html)
- [AGP 9.1 compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
- [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Compose compiler and BOM setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)

A fresh `pwsh -NoProfile` process inherited the documented user environment (`JAVA_HOME=C:\Program Files\Java\jdk-21`, `ANDROID_HOME` and `ANDROID_SDK_ROOT` both `C:\Users\admin\AppData\Local\Android\Sdk`). `java -version` reported Java 21.0.1. `gradlew.bat --version` downloaded the pinned Wrapper distribution and reported Gradle 9.3.1. The same fresh process then ran:

```powershell
Set-Location F:\ai-ml\aegis-f
.\gradlew.bat --no-daemon :app:assembleDebug
```

Observed result: exit code 0, `BUILD SUCCESSFUL in 5m 15s`, 34 tasks executed. `:app:compileDebugKotlin` reported `NO-SOURCE`, intentionally: S1.4 configures the Kotlin/Compose build but adds no screen, activity, or Kotlin implementation. No Android Studio or emulator was used.

Build warnings (non-blocking): SDK processing reported that the installed command-line tooling understands SDK XML through version 3 but encountered version 4; packaging also reported `libandroidx.graphics.path.so` could not be stripped and was included unstripped. The build completed successfully; no pin change was needed.

## APK metadata and signature (offline)

Static inspection used the installed Build Tools 36.0.0 `aapt.exe` and `apksigner.bat`; no network or device command was involved.

- APK: `F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk`
- Size: 11,349,391 bytes (10.82 MiB)
- SHA-256: `ef72c2bb9bb53b0afaa53df86524f58478572b3932424f902305195376126fcf`
- `aapt dump badging`: package `io.github.triet4p.aegisf`, versionCode `1`, versionName `1.0`, compile SDK `37`, minimum SDK `24`, target SDK `37`.
- `apksigner verify --verbose --print-certs`: `Verifies`; one signer; APK Signature Scheme v2 verified. Signer certificate DN: `C=US, O=Android, CN=Android Debug`.
- Debug certificate SHA-256 fingerprint: `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b`.
- The standard per-user debug keystore was absent before the build and now exists at `C:\Users\admin\.android\debug.keystore`, outside the repository. Android Gradle Plugin generated/used this normal debug key; no private key was copied into the repository or artifact. Preserve this same local file for same-certificate debug updates.
- `aapt dump badging` showed no `launchable-activity`, and the merged manifest has no app activity or `MAIN`/`LAUNCHER` intent filter. The APK has no launcher activity. S1.5 owns the entry activity, screen, and runtime launch; no UI or phone-runtime success is claimed.

The APK is intentionally retained at the output path above for the user and is ignored by Git.

## Ignore behavior and scope

Read-only `git check-ignore --no-index -v` confirmed that `local.properties`, `.gradle/`, `app/build/` APK output, `.idea` state, signing property files, and future model directories/`.onnx`/`.gguf`/`.bin` examples match `.gitignore`. The same check returned no ignored rule for `gradle/wrapper/gradle-wrapper.jar`, as required. No files were staged.

Changed project/documentation files:

- `.gitignore`
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`
- `docs/developer-setup.md`
- This proof artifact

No screen/navigation, ledger, AI, notification, persistence, test, or device feature was added. No `adb`, install/connected task, device discovery, phone communication, or UI-runtime action was performed. No commit or push was made.

## User commands

In a fresh PowerShell process with the documented user-persistent environment:

```powershell
Set-Location F:\ai-ml\aegis-f
.\gradlew.bat --version
.\gradlew.bat --no-daemon :app:assembleDebug
$apk = 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
$aapt = 'C:\Users\admin\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt.exe'
$apksigner = 'C:\Users\admin\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat'
& $aapt dump badging $apk
& $aapt dump xmltree $apk AndroidManifest.xml
& $apksigner verify --verbose --print-certs $apk
Get-FileHash -LiteralPath $apk -Algorithm SHA256
(Get-Item -LiteralPath $apk).Length
```

These commands build and inspect the APK on the PC only. Device installation, launch, and UI-runtime checks remain outside S1.4 and belong to user-run later tasks.
