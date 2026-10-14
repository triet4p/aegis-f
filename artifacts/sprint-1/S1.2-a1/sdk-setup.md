# S1.2 SDK setup proof

- Task: S1.2
- Attempt: S1.2-a1
- Agent: Sprint1SdkSetup (Bronze)
- Date: 2026-10-14
- Repository: `F:\ai-ml\aegis-f`

## Environment scope

- OS: Windows 10.0.26200
- User-scoped installation; no admin elevation, no system-level (Machine) environment changes.
- No Android Studio, emulator/system image, NDK, CMake, or global Gradle installed.
- Existing JDK 21.0.1 reused (no new JDK).

## SDK root

`C:\Users\admin\AppData\Local\Android\Sdk` (= `%LOCALAPPDATA%\Android\Sdk`)

## Installed packages (observed versions)

| Package | Version | Source |
| --- | --- | --- |
| SDK Command-Line Tools | commandlinetools-win-15859902_latest.zip (build 15859902) | dl.google.com/android/repository |
| platform-tools (adb) | 37.0.1-15733141 | Google SDK repository |
| platforms;android-37.0 | API 37 (Android 17) | Google SDK repository |
| build-tools;36.0.0 | 36.0.0 | Google SDK repository |

**Package naming note:** The SDK repository uses `platforms;android-37.0` (not `platforms;android-37`). The S1.1 planning reference used the shorthand; the actual install path requires the `.0` suffix.

## Integrity verification

- Command-line tools ZIP SHA-256 verified against official expected hash:
  - Expected: `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a`
  - Observed: `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a`
  - Result: MATCH
- Package downloads performed by `sdkmanager` from Google's official repository.

## Environment mutations (user-persistent, via registry)

| Variable | Value |
| --- | --- |
| `JAVA_HOME` | `C:\Program Files\Java\jdk-21` |
| `ANDROID_HOME` | `C:\Users\admin\AppData\Local\Android\Sdk` |
| `ANDROID_SDK_ROOT` | `C:\Users\admin\AppData\Local\Android\Sdk` |
| PATH (user) added entries | `%ANDROID_HOME%\platform-tools`, `%ANDROID_HOME%\cmdline-tools\latest\bin` |

No Machine-scope environment variables were modified. No existing PATH entries were removed.

## License status

All applicable SDK licenses accepted during installation via `sdkmanager --licenses` (non-interactive). License hash files stored under `%LOCALAPPDATA%\Android\Sdk\licenses\`. No license terms were bypassed.

## Fresh-shell verification (new process, user-persistent env inherited)

The following were executed in a subprocess environment configured to match what a fresh interactive shell would inherit from the user registry:

| Command | Observed result |
| --- | --- |
| `java -version` | `java version "21.0.1" 2023-10-17 LTS` / `Java(TM) SE Runtime Environment (build 21.0.1+12-LTS-29)` / `Java HotSpot(TM) 64-Bit Server VM (build 21.0.1+12-LTS-29, mixed mode, sharing)` |
| `javac -version` | `javac 21.0.1` |
| `adb version` | `Android Debug Bridge version 1.0.41` / `Version 37.0.1-15733141` / `Installed as C:\Users\admin\AppData\Local\Android\Sdk\platform-tools\adb.exe` / `Running on Windows 10.0.26200` |
| `sdkmanager --list` (Installed) | `platform-tools`, `platforms;android-37.0`, `build-tools;36.0.0` |
| `sdkmanager --licenses` | No pending prompts (all accepted) |

## Shell restart note

Already-open shells (including any used during S1.2 installation) do not automatically receive the new user-persistent environment variables. A new shell must be opened, or session variables set manually, before SDK commands resolve correctly.

## Scope limitation

This is pre-phone SDK setup (S1.2 only). No physical device was attached or inspected. No APK build, app launch, or device debugging was performed. S1.3 (device connection) and S1.4 (Gradle project) remain ahead.

## Changed paths

- `docs/developer-setup.md` — updated with observed S1.2 installation versions and fresh-shell instructions
- `artifacts/sprint-1/S1.2-a1/sdk-setup.md` — this artifact

No plan, sprint-plan, app source, project scaffold, staging, commit, or push was changed.
