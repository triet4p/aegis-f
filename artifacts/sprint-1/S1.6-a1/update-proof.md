# S1.6 A/B update proof

- Task: `S1.6`
- Attempt: `S1.6-a1`
- Agent: `Sprint1UpdateProbe` (Silver)
- Outcome: temporary persistence probe, A/B APK builds, and offline package/signature checks are complete. User-run A→B persistence/removal proof and later clean-source build remain pending; S1.6 stays open.
- Repository: `F:\ai-ml\aegis-f`

## Change and storage contract

Added a clearly labelled **TEMPORARY UPDATE TEST** panel to the existing English launch screen. It shows the A/B build marker, saved value (or explicit `none`), an input whose contents are labelled unsaved until saved, **Save test value**, and targeted **Clear test value**. No default value is seeded. Save and clear use synchronous `SharedPreferences.Editor.commit()`; the UI claims success and changes the displayed saved state only after a successful commit. Clear removes only the probe key and then displays `Saved test value: none`.

The identical A/B storage contract is app-private `SharedPreferences` file `s1_6_update_probe`, key `temporary_test_value`, in package `io.github.triet4p.aegisf`. The existing manifest has `android:allowBackup="false"`; no cloud-backup behavior is assumed. No dependency, finance data, permission, or device integration was added.

## Actual Wrapper builds

Built sequentially on the PC with the Wrapper:

```powershell
Set-Location F:\ai-ml\aegis-f
.\gradlew.bat --no-daemon -Ps1UpdateProbeLabel=A -Ps1UpdateProbeVersionCode=2 :app:assembleDebug
Copy-Item -LiteralPath 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk' -Destination 'F:\ai-ml\aegis-f\app\build\outputs\sprint1-update\aegis-f-S1.6-A.apk' -Force
.\gradlew.bat --no-daemon -Ps1UpdateProbeLabel=B -Ps1UpdateProbeVersionCode=3 :app:assembleDebug
Copy-Item -LiteralPath 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk' -Destination 'F:\ai-ml\aegis-f\app\build\outputs\sprint1-update\aegis-f-S1.6-B.apk' -Force
```

Both actual Wrapper runs completed `BUILD SUCCESSFUL` (A: 45s; B: 24s). Both reported the existing non-blocking SDK XML version-4 processing warning. The first configuration attempt failed because custom `resValue` resources were disabled; enabling `buildFeatures.resValues` fixed that concrete issue, and the successful A and B builds above were rerun afterward. APK A was copied to its retained path before building B.

## Offline APK inspection

Used Build Tools 36.0.0 only; no device or network command was run. `aapt dump badging`, `aapt dump permissions`, and `aapt dump xmltree` plus `apksigner verify --verbose --print-certs`, `Get-FileHash -Algorithm SHA256`, and `Get-Item ... .Length` were run on both retained APKs. `aapt dump --values resources` confirmed embedded panel labels `A` and `B` respectively.

| APK | Package | Version code/name | Launcher | Size (bytes) | SHA-256 |
|---|---|---:|---|---:|---|
| `F:\ai-ml\aegis-f\app\build\outputs\sprint1-update\aegis-f-S1.6-A.apk` | `io.github.triet4p.aegisf` | `2` / `1.0` | exported `io.github.triet4p.aegisf.MainActivity`, `MAIN` + `LAUNCHER`; panel marker A | 11,432,339 | `EB35ADFFEF0D05D7C971AA14346961BF1C0C24C44233E9A9B8203DEDBCDFA102` |
| `F:\ai-ml\aegis-f\app\build\outputs\sprint1-update\aegis-f-S1.6-B.apk` | `io.github.triet4p.aegisf` | `3` / `1.0` | exported `io.github.triet4p.aegisf.MainActivity`, `MAIN` + `LAUNCHER`; panel marker B | 11,432,339 | `12E82D964F8477EBB956C51FE8E21542BF3E29C3CAA9842BFBB94C85D0DB3E1F` |

Both target SDK 37 and minimum SDK 24. `apksigner` reported one signer and verified APK Signature Scheme v2; each certificate is `C=US, O=Android, CN=Android Debug`, SHA-256 `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b`. This matches the S1.5 certificate recorded in `artifacts/sprint-1/S1.5-a2/launch-proof.md` (and S1.4 baseline). The only reported uses-permission is the same library-generated signature-level `io.github.triet4p.aegisf.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` disclosed in S1.5; no probe runtime permission was added.

The APKs are retained under ignored `app/build/outputs/sprint1-update/`; no binaries were written to tracked artifacts.

## User boundary and next gates

The worker did not run `adb`, device discovery, install, connected Gradle tasks, or any phone communication. Follow the temporary user-run A→B/save/reopen/update/clear/reopen steps in `docs/developer-setup.md` and report each observed state to Main. Do not uninstall or clear app data to bypass errors.

After user proof, remove the probe panel, storage code, generated marker resource, `resValues` enablement, and all `s1UpdateProbe*` Gradle controls. Set the clean app `versionCode` to `4` (greater than B's `3`), then build and inspect the clean APK. That final source/build is intentionally not prepared or built here.

Prior evidence: `artifacts/sprint-1/S1.4-a1/build-proof.md`, `artifacts/sprint-1/S1.5-a2/launch-proof.md`. Relevant actual prior history: `history://Sprint1LaunchRecovery:152-168`. No staging, commit, or push was performed.
