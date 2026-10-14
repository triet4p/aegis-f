# S1.1 toolchain inspection

- Task: S1.1
- Attempt: S1.1-a1
- Agent: Sprint1Toolchain (Silver)
- Inspected: 2026-10-14
- Repository: `F:\ai-ml\aegis-f`
- Resolved symbolic HEAD: `main` (`.git/HEAD` contains `ref: refs/heads/main`). The `refs/heads` directory is empty and `.git/packed-refs` is absent, so no local commit SHA could be resolved from the available ref metadata. No Git mutation was performed.

## Environment observed

- `JAVA_HOME` is `C:\Program Files\Java\jdk-21`. `java -version` and the absolute JDK executable both reported Oracle Java 21.0.1, runtime build `21.0.1+12-LTS-29`, dated 2023-10-17. `javac -version` and the absolute JDK compiler both reported `javac 21.0.1`. The PATH Java/Javac shims resolve to the same versions. A separate JRE 8.0_461 exists; it is not selected.
- PowerShell environment values `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `GRADLE_USER_HOME` are unset. The default `%USERPROFILE%\.gradle` directory is absent.
- `where.exe gradle`, `where.exe sdkmanager`, and `where.exe adb` found no commands. `Get-Command` also found no Android Studio command. No Android SDK root was found at `C:\Users\admin\AppData\Local\Android\Sdk`, `F:\AndroidSDK`, `C:\Users\admin\Android\Sdk`, `C:\Android\Sdk`, `C:\android-sdk`, `C:\Program Files\Android`, or `C:\Program Files (x86)\Android`. These configured and standard locations were checked; this is not a claim that every unreferenced volume path was exhaustively searched.
- Android Studio configuration remnants remain under `%APPDATA%\Google\AndroidStudio2025.2.1` and `AndroidStudio2025.2.2`. The older configuration records `%LOCALAPPDATA%\Android\Sdk`; the newer records `F:\AndroidSDK`. Both locations are absent. Their `jdk.table.xml` metadata refers to API 36, but the SDK files are not present at the recorded roots. Standard Android Studio binary locations and matching uninstall entries were not found. The user's `.android` directory contains `adbkey` and `adbkey.pub`; their contents were not read or copied.
- No Gradle Wrapper or `settings.gradle*`/`build.gradle*` project scaffold was found. No SDK packages were installed or changed.

## Selected compatible set for S1.2

| Component | Selected pin | Basis / state |
| --- | --- | --- |
| JDK execution runtime | Oracle JDK 21.0.1 at `C:\Program Files\Java\jdk-21` | Installed and `java`/`javac` smoke passed; AGP needs JDK 17 and Gradle 9.3.1 supports running on Java 21. The installed patch is old (2023-10-17); record this before using the machine for release builds. |
| Gradle | 9.3.1 | AGP 9.1.1 minimum and default; selected for the future Wrapper. No `gradle` command is available on PATH and no project wrapper exists. |
| AGP | 9.1.1 | Stable release; supports API 37 and specifies Gradle 9.3.1. Selected; no app project has resolved it. |
| Kotlin | 2.2.10 built-in Kotlin Gradle Plugin | AGP 9.1.1's documented default; do not also apply the standalone Kotlin Android plugin. No app project has resolved it. |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` 2.2.10 | Match the Kotlin compiler version per Kotlin/Compose guidance. No app project has resolved it. |
| Compose dependencies | BOM 2026.09.00 | Current stable Compose BOM; Compose 1.12 requires compile SDK 37 and AGP 9+. No app project has resolved it. |
| SDK | `platforms;android-37` (`compileSdk = 37`, `targetSdk = 37`) | Android 17 stable SDK, supported by AGP 9.1.1. Not found in the configured or standard roots checked. Target 37 intentionally opts into Android 17 behavior changes; no phone test was made. |
| Minimum SDK | API 24 | Chosen broad Android 7.0 compatibility floor; it is not device-derived. |
| Build Tools | `build-tools;36.0.0` | AGP 9.1.1's documented default version; not found in the SDK roots checked. |
| Bytecode target | Java/Kotlin 17 | Distinct from the JDK 21 used to run Gradle. |
| Future SDK root | `%LOCALAPPDATA%\Android\Sdk` | Conventional new root; current SDK environment variables are unset. |

The API 37 / Build Tools 36 pairing follows AGP 9.1.1's explicit API 37 support and Build Tools 36.0.0 default; no custom Build Tools override is needed. The minimum API 24 choice is a planning baseline, not evidence about an attached phone or future inference dependency requirements.

## Executed smoke and limits

- Executed PowerShell `java -version`: Oracle Java 21.0.1 LTS, runtime `21.0.1+12-LTS-29`.
- Executed the installed JDK's absolute `java.exe -version`: same Java 21.0.1 runtime.
- Executed PowerShell `javac -version` and the installed JDK's absolute `javac.exe -version`: `javac 21.0.1`.
- Executed `where.exe` / `Get-Command` discovery for Java, Javac, Gradle, `sdkmanager`, `adb`, and Studio. Java/Javac resolved; Gradle, SDK Manager, ADB, and Studio commands were unavailable. SDK-specific commands were therefore not run.
- No build, tests, lint, emulator, device/ADB, SDK package listing, license acceptance, or installation was performed. There is no app project or wrapper to build.

## Authoritative compatibility sources

- [AGP 9.1.1 release and compatibility table](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
- [AGP built-in Kotlin guidance](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Gradle Java compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html)
- [Android 17 API 37 SDK setup](https://developer.android.com/about/versions/17/setup-sdk)
- [Compose compiler plugin and BOM setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)
- [Compose BOM version mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping)

## Changed paths

- `docs/developer-setup.md`
- `artifacts/sprint-1/S1.1-a1/toolchain.md`

No plan, sprint-plan, app source, project scaffold, user configuration, SDK installation, staging, or commit was changed.
