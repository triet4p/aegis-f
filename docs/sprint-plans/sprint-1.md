# Sprint 1 — Command-Line Build and Physical Device

## Sprint Goal

Build, install, and launch an English Android app on the user's actual phone using command-line tools only.

## Status and Dependencies

- Status: Complete — all S1.1–S1.7 gates passed; reviewed delivery committed and pushed to origin/main.
- Dependencies: User-run update preservation and targeted test-value removal passed. Temporary probe code/build controls were removed; clean versionCode 4 APK build and offline package/launcher/same-certificate checks passed. Installation of the final clean APK remains user-only and has not been reported.
- Workflow: **Lightweight**. Completed tasks use focused checks and Main acceptance; S1.5 includes passed user-run visual/runtime proof. S1.6 requires PC builds and matching signature/package checks, user-run A→B installation with preserved saved state, then removal of the temporary probe and a clean build. S1.7 requires reproducible PC commands and finalized documentation grounded in those results. No independent evidence reviewer, full-route checkpoint, or deep review is assigned. Workers must not execute ADB or any other phone communication. After these gates pass, the user authorizes a reviewed local commit and normal push to origin/main: repository/worktree `F:/ai-ml/aegis-f`, Git directory `F:/ai-ml/aegis-f/.git`, target `main`; stage only verified project/docs/plans/evidence scope, preserve unrelated/staged user changes, no force-push, branch switch, history rewrite, or hook/signing bypass. Unexpected ownership/ref/remote/authentication failures remain explicit blockers.
- Status legend: `[ ]` pending / `[~]` in progress / `[x]` done.

## Acceptance Criteria

- A fresh shell can build using the documented pinned toolchain and Gradle Wrapper without Android Studio or an emulator.
- adb identifies the authorized physical device; its model, Android/API, ABI, RAM, page size, and relevant OEM settings are recorded without device/account identifiers.
- The installed app launches an English screen with an explicit VND currency label; this is a smoke surface, not a completed ledger.
- Rebuilding and updating the same package retains its test app data and launches successfully.
- Developer setup, device connection, build/install, and safe diagnostic commands are reproducible.

## Ordered Atomic Tasks

- [x] **S1.1 — Inspect the local toolchain.** Resolved installed JDK and removed-IDE SDK paths; selected a compatible stable toolchain in [developer setup](../developer-setup.md). Java/javac 21.0.1 ran successfully; configured/standard SDK roots were absent. Selection evidence: [S1.1](../../artifacts/sprint-1/S1.1-a1/toolchain.md). Build compatibility was subsequently exercised in S1.4.
- [x] **S1.2 — Establish the minimal SDK installation.** Installed official command-line tools (build 15859902), platform-tools 37.0.1, `platforms;android-37.0`, and Build Tools 36.0.0 at `%LOCALAPPDATA%\Android\Sdk`; accepted SDK licenses and configured user-scoped environment without removing existing PATH entries. New-process smoke using persisted environment verified java/javac, adb, package discovery, and license status. No emulator/system images or NDK/CMake installed. Evidence: [S1.2](../../artifacts/sprint-1/S1.2-a1/sdk-setup.md).
- [x] **S1.3 — Authorize and inspect the physical device.** User-run PowerShell 7.6.6 / ADB 37.0.1 reported the USB phone in `device` state. Observed: HONOR ALT-LX2, Android 14 / API 34, ABI `arm64-v8a,armeabi-v7a,armeabi`, page size 4096 bytes, `MemTotal: 7837732 kB`. `ro.config.low_ram` returned empty (not declared; not assumed false). Accepted from the user's supplied command output without rerunning; no device/account identifiers recorded. OEM installation-specific settings remain to be observed during S1.5.
- [x] **S1.4 — Create the Android build project.** Added authentic Gradle 9.3.1 Wrapper and pinned Kotlin/Compose configuration with application ID `io.github.triet4p.aegisf`, min SDK 24 / compile and target SDK 37. A fresh PowerShell `:app:assembleDebug` build succeeded; offline APK metadata and debug signature verification passed. SDK-local configuration, outputs, secrets, and model binaries are ignored; Wrapper JAR remains trackable. No phone communication or launcher activity; S1.5 owns the screen and runtime launch. Evidence: [S1.4 build proof](../../artifacts/sprint-1/S1.4-a1/build-proof.md).
- [x] **S1.5 — Deliver the launchable English screen.** Implemented exported launcher `io.github.triet4p.aegisf.MainActivity` and the English Aegis F / Vietnamese dong / VND screen. Main accepted the successful Wrapper build and offline launcher/metadata/same-debug-signature checks from [S1.5 PC proof](../../artifacts/sprint-1/S1.5-a2/launch-proof.md). The user reported all five manual checks passed, including install/open, layout/readability, no crash, close/reopen, and theme/font checks; supplied screenshots confirm the English/VND screen and visible dark-theme system-bar spacing. No dangerous/runtime permissions were found; AndroidX adds a signature-only receiver permission. No worker phone communication occurred.
- [x] **S1.6 — Prove the update path.** A/B builds/offline checks passed; user confirmed A save/reopen, B update/value preservation, and targeted clear/reopen to `none`. Accepted without rerunning phone operations. Removed probe UI/storage/resources/Gradle controls and obsolete generated outputs; clean versionCode 4 build and same-certificate/launcher checks passed. Evidence: [A/B proof](../../artifacts/sprint-1/S1.6-a1/update-proof.md), [clean APK proof](../../artifacts/sprint-1/S1.6-a2/clean-proof.md). No worker phone communication occurred.
- [x] **S1.7 — Document the supported development path.** Finalized the single [developer setup guide](../developer-setup.md) with observed pinned tools, SDK/environment/licensing, Wrapper build/output/signing, safe user-only device commands, troubleshooting, and device facts. A fresh registry-environment PowerShell build and offline APK checks passed; local documentation links resolved. Evidence: [S1.7 docs proof](../../artifacts/sprint-1/S1.7-a1/docs-proof.md). User phone observations are identified separately; final clean APK installation is not claimed.

## Required Verification

Build from a fresh shell → inspect adb device → install APK → launch and inspect the screen → close/reopen → update APK → observe preserved smoke state. Use the existing installed JDK if compatible; do not confuse Gradle's execution JDK with app bytecode targets.

## Decisions / Blockers

- English-first behavior and VND are delivery scope, not temporary demo defaults.
- All seven tasks passed assigned acceptance. Reviewed delivery commit `57d7095ed8daf368a0b401955ded9aaf61523d3d` was pushed normally to origin/main; reported HEAD and remote tracking ref matched, with a clean worktree. No force-push or phone communication occurred. Final clean versionCode 4 APK installation remains optional and user-run; it is not claimed as exercised.
- Existing JDK 21.0.1 has an old patch level; release tooling security remains a later consideration. Current build and user-tested launch passed, but later feature/runtime compatibility still requires its own verification.
- Android/API, ABI, RAM, and page size are known from the user's device inspection. Actual model quality, inference latency, and memory use still require later device measurements; these capability facts do not prove either AI model runs acceptably.
- Parent roadmap: [Project Plan](../PLAN.md). Next: [Sprint 2](sprint-2.md).
