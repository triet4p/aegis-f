# S1.6 clean-source and APK proof

- Task: `S1.6`
- Attempt: `S1.6-a2`
- Agent: `Sprint1ProbeClosure` (Silver / Light)
- Outcome: probe cleanup, fresh clean Wrapper build, scoped source/resource/DEX scans, and offline APK checks completed. Main acceptance remains pending.
- Repository/worktree: `F:\ai-ml\aegis-f`
- Recovery pointers read: `artifacts/sprint-1/handoffs/S1.6-a1.md`, `artifacts/sprint-1/S1.6-a1/update-proof.md`, `history://Sprint1UpdateProbe`, `docs/sprint-plans/sprint-1.md:14-34`, and `docs/developer-setup.md`.
- Related baseline: `artifacts/sprint-1/S1.5-a2/launch-proof.md`.

## User-run A/B result (not worker verification)

The user confirmed the complete manual checklist passed: save and reopen on A, update to B with `install -r` and retain the saved value, then clear the targeted test key and reopen to the absent/`none` state. This is user-provided device evidence, accepted without rerunning or contacting the phone. No migration, startup cleanup, data reset, or shim was added; the user had already cleared the temporary value.

## Changes and cleanup

- Removed the temporary test panel, input/save/clear actions, preference access/state, and probe preference/key identifiers from `app/src/main/java/io/github/triet4p/aegisf/MainActivity.kt`. The launcher returns to the English Aegis F / Vietnamese dong / VND surface.
- Removed `s1UpdateProbe*` Gradle properties, generated A/B marker resource, and `resValues` enablement from `app/build.gradle.kts`. Set `versionCode = 4`; retained `versionName = "1.0"`, application ID `io.github.triet4p.aegisf`, normal debug signing, and the existing Compose build feature.
- Replaced temporary A/B instructions in `docs/developer-setup.md` with a brief historical pointer; the active build path is the standard Wrapper `:app:assembleDebug` command. The guide points to the A/B record and this clean-package proof.
- The required `:app:clean` removed prior generated contents of ignored `app/build/`, including the obsolete A/B APKs and prior intermediates; the fresh build recreated the standard debug APK and current outputs. No path outside `app/build/` was deleted. The prior attempt's proof and handoff were retained. No Git operation was performed.

## Fresh clean Wrapper build

Ran from a fresh PowerShell process at the repository root:

```powershell
Set-Location F:\ai-ml\aegis-f
.\gradlew.bat --no-daemon clean :app:assembleDebug
```

Observed `BUILD SUCCESSFUL in 49s`; all 37 actionable tasks executed. The build emitted the existing non-blocking SDK XML warning (tooling understands XML through version 3 and encountered version 4) and reported that `libandroidx.graphics.path.so` could not be stripped and was packaged unstripped. No tests, lint, or formatters were run. No device, `adb`, emulator, connected Gradle task, logs, screenshots, or phone communication were used.

## Offline APK inspection

APK: `F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk`

| Field | Observed |
|---|---|
| Package / application ID | `io.github.triet4p.aegisf` |
| Version code / name | `4` / `1.0` (code 4 is greater than B's 3) |
| Compile / target / minimum SDK | 37 / 37 / 24 |
| Launcher | Exported `io.github.triet4p.aegisf.MainActivity`, `MAIN` + `LAUNCHER`; label `Aegis F` |
| Size | 11,431,931 bytes |
| SHA-256 | `4A34F8F4C4C9766BCB8AC751CA1B3C9E0AF663091C64F5DEE31AD568DE3D896D` |
| Signing | `apksigner verify` reported `Verifies`; one signer; v2 verified |
| Certificate | `C=US, O=Android, CN=Android Debug`; SHA-256 `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b` |

The certificate fingerprint matches the S1.5 baseline and both prior A/B packages. `aapt dump permissions` showed only the existing dependency-generated `io.github.triet4p.aegisf.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, declared with signature protection in the merged manifest; no new runtime permission was added. The app manifest retains `allowBackup=false`.

The offline inspection used Build Tools 36.0.0: `aapt dump badging`, `aapt dump permissions`, `aapt dump xmltree`, `apksigner verify --verbose --print-certs`, `Get-FileHash -Algorithm SHA256`, and `(Get-Item ...).Length`. Badging and manifest output confirmed package/version/SDK values, application label, exported launcher activity, and `MAIN`/`LAUNCHER` filter.

## Probe-removal evidence

- Scoped source search across `app/src` and `app/build.gradle.kts` found no probe preference/key, UI action/label, or `s1UpdateProbe*`/generated-marker references. A scoped search of active `docs/developer-setup.md` found no obsolete probe controls, APK paths, or user A/B steps.
- `aapt dump resources` scan found no probe marker resource or panel labels.
- Inspected all five APK DEX files from the APK archive; none contained the probe panel, save/clear action, saved-value UI, preference/key, or Gradle-control strings.
- `app/build/outputs/` now contains the standard debug output only; the old `sprint1-update` A/B directory is gone.

These are source/static/package checks, not a visual or runtime claim for the clean APK.

## User-only next install

The worker did not install or launch the clean APK. If the user chooses to install this clean version on the authorized phone, the exact PowerShell command is:

```powershell
adb -d install -r 'F:\ai-ml\aegis-f\app\build\outputs\apk\debug\app-debug.apk'
```

This is a user-only update install; the command was not run by the worker. The user's S1.5 launch evidence and S1.6 A/B update-path observations remain distinct from physical installation/runtime of this final clean APK.

## Remaining work

S1.6 implementation and assigned PC verification are complete for Main's acceptance. No blocker is known. S1.7 remains the separately assigned documentation task. No staging, commit, or push was performed.