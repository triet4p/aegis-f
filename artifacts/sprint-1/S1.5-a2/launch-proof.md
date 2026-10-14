# S1.5 launcher proof

- Task: `S1.5`
- Attempt: `S1.5-a2`
- Agent: `Sprint1LaunchRecovery` (Silver)
- Outcome: PC implementation, Wrapper build, and offline APK checks complete. Main acceptance and the user's actual-device visual/runtime proof remain pending; S1.5 must stay open until the device proof is received.
- Repository: `F:\ai-ml\aegis-f`
- APK: `F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk`

## Recovery and actual starting state

Read the required recovery pointers before continuing: Main's supervision report `artifacts/sprint-1/state/S1.5-a1-failure.md`; `history://Sprint1LaunchScreen`; the predecessor's handoff `artifacts/sprint-1/handoffs/S1.5-a1.md`; `docs/sprint-plans/sprint-1.md:14-34`; `docs/developer-setup.md`; and `artifacts/sprint-1/S1.4-a1/build-proof.md`. The assigned predecessor proof `artifacts/sprint-1/S1.5-a1/launch-proof.md` was absent.

The a1 handoff records that its source changes were uncompiled and unverified. Current files contained the added Activity Compose 1.13.0 dependency, exported launcher `.MainActivity`, and Compose screen, but the manifest lacked its closing `</manifest>` tag and `isSystemInDarkTheme` was imported from Material 3. No S1.5 verification had been performed. The last prior verified package remained the S1.4 APK, which built successfully but had no launcher. A2's first build exposed those two concrete defects; both were corrected here. No progress report or handoff was treated as acceptance.

## Scope completed

- Kept the existing `io.github.triet4p.aegisf` application ID, SDK/toolchain pins, and standard per-user debug signing setup. Kept the predecessor's minimal Activity Compose dependency and single-destination Compose screen; no accounts, balances, transaction data, actions, navigation routes, new app permissions, or S1.6 probe were added.
- Corrected the theme-detection import to `androidx.compose.foundation.isSystemInDarkTheme` and closed the launcher manifest XML document. The screen uses `enableEdgeToEdge()`, safe-drawing insets, scrollable content, scalable Material typography, and explicit light/dark Material 3 colors. It presents `Aegis F`, Vietnamese dong, `VND`, and `₫`.
- Updated `docs/developer-setup.md` with the S1.5 entry-point description, user-only install and optional launch commands, and the portrait/insets, font-size, light/dark, close/reopen, and no-crash checklist.

## Build verification

Ran in a fresh PowerShell process:

```powershell
Set-Location F:\ai-ml\aegis-f
.\gradlew.bat --no-daemon :app:assembleDebug
```

Observed `:app:compileDebugKotlin` execute successfully, followed by `:app:assembleDebug`; result: `BUILD SUCCESSFUL in 54s` (36 actionable tasks: 13 executed, 23 up-to-date). Gradle 9.3.1 used the configured toolchain. The successful run emitted the non-blocking SDK processing warning that the installed tooling understands SDK XML through version 3 while encountering version 4.

The initial a2 build failed before an APK could be accepted: Kotlin reported unresolved `isSystemInDarkTheme`, and manifest processing reported malformed XML. A focused `:app:processDebugMainManifest --stacktrace` identified an unclosed XML document at EOF. After correcting the import and adding `</manifest>`, the full Wrapper build above passed. These initial failures are recorded to distinguish the verified final result from a1's unverified source state.

## Offline APK inspection

Used Build Tools 36.0.0 only; no network or device commands were involved.

- `aapt dump badging`: package `io.github.triet4p.aegisf`, version code `1`, version name `1.0`, compile SDK `37`, minimum SDK `24`, target SDK `37`; launchable activity `io.github.triet4p.aegisf.MainActivity`, label `Aegis F`.
- `aapt dump xmltree <apk> AndroidManifest.xml`: `MainActivity` is `android:exported="true"` and has the exact `android.intent.action.MAIN` / `android.intent.category.LAUNCHER` filter.
- `aapt dump permissions`: one library-generated `io.github.triet4p.aegisf.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Manifest-merger blame attributes it to `androidx.core:core:1.18.0`; the library defines it with `protectionLevel="signature"`. It is not a dangerous/runtime permission and is not authored in the app manifest. No other `uses-permission` was present. This transitive signature-only permission is disclosed rather than suppressed.
- `apksigner verify --verbose --print-certs`: `Verifies`; one signer; v2 signature verified. Certificate DN `C=US, O=Android, CN=Android Debug`; SHA-256 `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b`, matching the S1.4 proof's debug certificate.
- APK size: `18,400,605` bytes.
- APK SHA-256: `36A320E1DAC6AC4B1B8607E3824098D47A53C9F2077CFCE35AFC034202699995`.
- Retained at `F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk`.

Commands used for the offline inspection:

```powershell
$apk = 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
$aapt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt.exe"
$apksigner = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat"
& $aapt dump badging $apk
& $aapt dump permissions $apk
& $aapt dump xmltree $apk AndroidManifest.xml
& $apksigner verify --verbose --print-certs $apk
Get-FileHash -LiteralPath $apk -Algorithm SHA256
(Get-Item -LiteralPath $apk).Length
```

## Remaining acceptance and user commands

No `adb`, device discovery, install, connected Gradle task, emulator, screenshot, phone launch, or runtime check was run. PC checks do not prove visual layout or runtime behavior. Main acceptance remains pending, followed by the user's actual-device evidence for portrait insets/readability, increased font size, light/dark readability, close/reopen, and no crash.

In a user-owned PowerShell session with the intended physical device authorized, the user can install and optionally launch the APK with:

```powershell
adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
adb -d shell am start -n io.github.triet4p.aegisf/.MainActivity
```

The user may instead tap **Aegis F** in the app launcher. The full manual visual/runtime checklist is in `docs/developer-setup.md` under “User-run S1.5 install and launch”. No commit or push was made.