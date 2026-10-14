# S1.7 docs reproduction proof

- Task: `S1.7`
- Attempt: `S1.7-a1`
- Agent: `Sprint1DeveloperGuide` (Bronze / Light)
- Outcome: `docs/developer-setup.md` finalized as the single supported guide; every documented PC command reproduced from a genuinely new PowerShell process; local doc links validated; read-only Git/commit readiness inspected. Main acceptance pending.
- Repository / worktree / Git dir: `F:\ai-ml\aegis-f` / `F:\ai-ml\aegis-f` / `F:\ai-ml\aegis-f\.git`
- Date: 2026-10-14
- Route: Light. No evidence review, no checkpoint, no phone contact, no Git mutation, no staging/commit/push.

## Changed paths

- `docs/developer-setup.md` — rewritten in place (existing guide, not a second competing guide). Now has numbered sections: pins; execution JDK vs bytecode target; SDK root/env/licenses incl. stale-parent caveat; fresh-shell verification; Wrapper build + output/package/version/signing-key preservation; optional offline inspection; observed device facts; user-run install/open/reopen; update path + troubleshooting; why no Android Studio/emulator; official references.
- `artifacts/sprint-1/S1.7-a1/docs-proof.md` — this proof (new file).
- No source, Gradle, manifest, plan, or sprint-plan file was changed. `docs/PLAN.md` and `docs/sprint-plans/*` were read only.

## What the guide now covers (mapped to the assignment)

| Required content | Where |
| --- | --- |
| Exact pinned versions | §1 table (Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, Compose compiler 2.2.10, BOM 2026.09.00, activity-compose 1.13.0, API 37/37/24, Build Tools 36.0.0, JDK 21.0.1, bytecode 17) |
| JDK execution vs bytecode targets | §1 "Execution JDK vs. bytecode target" |
| SDK root, env, license setup | §2 (user-scoped root, 4 variables, 4 installed packages, `platforms;android-37.0` naming, license hashes, no admin) |
| Fresh PowerShell commands + stale-parent caveat | §2 "Fresh-shell rule and the stale-parent caveat" and "Fresh-shell verification" |
| Wrapper build, output path, package, version 4, debug key preservation | §3 (+ "Debug signing key") |
| Official SDK/driver references | §8 (sdkmanager/Android CLI, cmdline build/install, adb, Google + OEM USB drivers, API 37 setup, AGP/Gradle compatibility, Wrapper checksum, Compose, restricted settings) |
| Safe manual adb install/open/reopen + phone troubleshooting | §5, §6 |
| No admin default / no bypassing restricted settings / no bank-data log dumps / no uninstall or clear-data workaround | §2, §6 ("Common failure signals", "Privacy and safety when diagnosing") |
| No Android Studio/emulator needed | §1, §3, §7 |
| Outdated probe instructions only in historical proof | Active guide contains no A/B probe steps or `s1UpdateProbe*` controls; only the S1.6 record prose and links to the historical proofs |
| Non-blocking warnings recorded honestly | §3 (SDK XML version-4 warning; unstripped `libandroidx.graphics.path.so`) |
| JDK-old-patch risk, without broadening scope | §1 "Known toolchain risk" (recorded, explicitly not in scope to fix) |
| Concrete device facts, no identifiers | §4 (HONOR ALT-LX2, Android 14 / API 34, `arm64-v8a,armeabi-v7a,armeabi`, page 4096 B, MemTotal 7837732 kB, `ro.config.low_ram` empty, adb `device`) — no serial, account, or phone number |

## Executed commands and observed results (worker-run, PC only)

All runs used a genuinely new PowerShell process. Two variants were used so the guide's environment claim is not merely self-referential:

1. `pwsh -NoLogo -NoProfile` child of the current session (inherits current block).
2. `Start-Process pwsh -UseNewEnvironment` — a **new process whose environment is rebuilt from the registry**, i.e. what a genuinely fresh window sees.

### Fresh-process environment and toolchain (variant 2, registry-rebuilt env)

```powershell
Write-Output "$env:JAVA_HOME"; Write-Output "$env:ANDROID_HOME"; Write-Output "$env:ANDROID_SDK_ROOT"
java -version; javac -version; adb version; sdkmanager --version
```

Observed:

- `JAVA_HOME=C:\Program Files\Java\jdk-21`
- `ANDROID_HOME=C:\Users\admin\AppData\Local\Android\Sdk`
- `ANDROID_SDK_ROOT=C:\Users\admin\AppData\Local\Android\Sdk`
- `java version "21.0.1" 2023-10-17 LTS` / `build 21.0.1+12-LTS-29`
- `javac 21.0.1`
- `Android Debug Bridge version 1.0.41` / `Version 37.0.1-15733141` / installed from `...\Android\Sdk\platform-tools\adb.exe`
- `sdkmanager --version` → `22.0` (preceded by its deprecation notice pointing at the Android CLI; documented in the guide as non-blocking)

`sdkmanager --list_installed` → exactly three packages: `build-tools;36.0.0`, `platform-tools` (37.0.1), `platforms;android-37.0` (rev 2). This matches the guide's §2 table.

Registry cross-check (`reg query HKCU\Environment`): `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` present as user-persistent values; user `Path` contains `...\platform-tools` and `...\cmdline-tools\latest\bin`; license hash files present under `%LOCALAPPDATA%\Android\Sdk\licenses\`. No Machine-scope change was made by this attempt.

### Wrapper version and standard build (as documented in §3)

```powershell
Set-Location F:\ai-ml\aegis-f
.\gradlew.bat --version
.\gradlew.bat --no-daemon :app:assembleDebug
```

Observed (registry-rebuilt process):

- `Gradle 9.3.1`, build time 2026-01-29, `Launcher JVM: 21.0.1 (Oracle Corporation 21.0.1+12-LTS-29)`, `Daemon JVM: C:\Program Files\Java\jdk-21`.
- `:app:assembleDebug` → `BUILD SUCCESSFUL in 17s`, `36 actionable tasks: 36 up-to-date`, `ASSEMBLE_EXIT=0`.
- Warning reproduced verbatim: "SDK processing. This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered." — non-blocking, recorded in §3 of the guide.
- The `libandroidx.graphics.path.so` unstripped-packaging message appears only when the packaging/strip tasks actually execute (a clean build); it is recorded from the S1.6 clean build in §3 as well.

A second run in a `pwsh -NoProfile` child process gave `BUILD SUCCESSFUL in 15s`, `36 actionable tasks: 36 up-to-date`, exit 0 — the documented command works in both new-process variants.

A third and final run in a registry-rebuilt process was made **after the last edit to the guide**, to prove the finalized documented commands rather than an earlier draft: `JAVA_HOME=C:\Program Files\Java\jdk-21`, `java version "21.0.1" 2023-10-17 LTS`, `javac 21.0.1`, `Gradle 9.3.1`, `Launcher JVM: 21.0.1`, `BUILD SUCCESSFUL in 19s`, `36 actionable tasks: 36 up-to-date`, `ASSEMBLE_EXIT=0`, with the same SDK XML version-4 warning. The post-edit APK hash was re-confirmed as unchanged by this run's up-to-date status.

### Offline APK metadata and signature (to align the guide's expected identity)

Used Build Tools 36.0.0 `aapt.exe` / `apksigner.bat`, `Get-FileHash`, `Get-Item`; no device, no network:

| Field | Observed |
|---|---|
| Package | `io.github.triet4p.aegisf` |
| Version code / name | `4` / `1.0` |
| compile / target / min SDK | `37` / `37` / `24` |
| Launcher | `io.github.triet4p.aegisf.MainActivity`, label `Aegis F` |
| Size | 11,431,931 bytes |
| SHA-256 | `4A34F8F4C4C9766BCB8AC751CA1B3C9E0AF663091C64F5DEE31AD568DE3D896D` (identical to the S1.6 clean APK — the documented build is reproducible and this run produced no source change) |
| Signature | `Verifies`; 1 signer; v2 scheme true; v1/v3/v3.1/v4 false |
| Certificate | `C=US, O=Android, CN=Android Debug`, SHA-256 `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b` |

The guide's §3 expected identity and §3 "Debug signing key" fingerprint match this observation exactly.

### Local doc-link validation

A PowerShell sweep of every `](...)` link in `docs/**/*.md` that is not `http(s)` resolved each target relative to its file: **0 broken links** (36 relative links across `developer-setup.md`, `PLAN.md`, and the six sprint plans all resolved; this includes the new S1.7 proof link added to `developer-setup.md`).

### Temporary files

The fresh-environment smoke used a throwaway script and log under ignored `app/build/`; both were deleted after the run. `app/build/` now contains only normal generated directories (`generated`, `intermediates`, `kotlin`, `outputs`).

## User-vs-worker proof distinction

- **Worker-proven here:** the documented PC path (environment inheritance, `java`/`javac`/`adb`/`sdkmanager` versions, Wrapper version, `:app:assembleDebug`, offline APK identity/signature, doc links).
- **User-reported, not rerun:** S1.5 install/open/layout/no-crash/reopen/theme/font checks and the S1.6 A/B save/reopen/update-retain/clear checks. These are recorded in the guide as user observations.
- **Not yet reported by the user:** installing and running the current clean `versionCode = 4` APK. The guide states this explicitly in §5 and does not conflate it with the earlier user checks.
- **No phone contact in this attempt:** no `adb devices`, no install, no `am start`, no logcat, no screenshot, no emulator, no connected Gradle task. `adb version` was run (a local binary version print) — it does not touch a device.

## Read-only Git / commit readiness

No mutation: no `add`, `commit`, `push`, `fetch`, `checkout`, `switch`, `stash`, or config write. Nothing is staged (`git diff --cached --name-only` → 0).

| Item | Observed |
|---|---|
| Branch | `main` (`git symbolic-ref HEAD` → `refs/heads/main`) |
| Head commit | none — the branch is **unborn**: `git rev-parse HEAD` and `git log` fail with "does not have any commits yet". This is the repository's first commit. |
| Configured origin | `https://github.com/triet4p/aegis-f.git` (fetch + push), unchanged |
| Remote `main` | `git ls-remote --exit-code origin HEAD` → exit 2, no refs. `gh api repos/triet4p/aegis-f` → `default_branch: main`, `private: false`, `size: 0`, `pushed_at 2026-10-14T04:26:42Z`. The remote repository exists and is **empty**, so there is no remote divergence and no remote content to overwrite. |
| Auth | `gh auth status` shows an active `github.com` account with `repo` scope; `git` uses the GitHub CLI credential helper. Remote read access works; write access was not exercised (no push attempted). |
| Staged foreign changes | none (nothing staged at all) |
| Ignore rules | `git check-ignore --no-index` confirms `app/build/outputs/apk/debug/app-debug.apk` (via `**/build/`) and `/local.properties` are ignored; `gradle/wrapper/gradle-wrapper.jar` is **not** ignored (must be committed). |

### Untracked inventory (all 28 entries, complete)

Application/tooling: `.gitignore`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/io/github/triet4p/aegisf/MainActivity.kt`.

Docs: `docs/PLAN.md`, `docs/developer-setup.md`, `docs/sprint-plans/sprint-1.md` … `sprint-6.md`.

Sprint evidence: `artifacts/sprint-1/S1.1-a1/toolchain.md`, `S1.2-a1/sdk-setup.md`, `S1.4-a1/build-proof.md`, `S1.5-a2/launch-proof.md`, `S1.6-a1/update-proof.md`, `S1.6-a2/clean-proof.md`, `handoffs/S1.5-a1.md`, `handoffs/S1.6-a1.md`, `state/S1.5-a1-failure.md`.

The inventory above is the snapshot taken before this proof file was written; `artifacts/sprint-1/S1.7-a1/docs-proof.md` (this file) now also exists as a new untracked path and is part of the same authorized sprint-evidence scope.

### Scope safe to stage (Main's decision, not performed)

All of the above are project-generated for Sprint 1 (source, Wrapper, docs, sprint evidence) and match the assignment's authorized scope: existing project + generated-by-us planning docs + sprint evidence. No binary, secret, or build output is in the untracked set — APKs/AABs, `local.properties`, `.gradle/`, `app/build/`, and keystores are all ignored. Because the repository has no commits and the remote is empty, the first commit would establish history on `main`; there is no merge/divergence hazard, and no foreign or user-staged change exists to exclude.

### Commit-readiness findings (report only)

1. **Non-blocking, outside this task's scope:** `.kotlin/` (Kotlin build-session directory) is **not** covered by `.gitignore`. It currently holds no files (only an empty `sessions/` directory), so it produces no untracked entry today, but a future Kotlin build could write session files that would then appear as untracked. Suggested one-line addition to `.gitignore` (`/.kotlin/` or `**/.kotlin/`) — Main decides; this attempt made no code/config change because the assignment is docs-only.
2. No blocker for an authorized commit/push. Auth is available, the remote is empty, and the working tree contains only intended project content.

## Warnings / limitations

- `sdkmanager` prints a deprecation notice for the Android CLI; commands still work and the guide records this.
- The SDK XML version-4 warning is reproduced on every build and is documented as non-blocking.
- The JDK 21.0.1 patch level (2023-10-17) is old; documented as a recorded risk, explicitly not fixed here.
- Nothing in this attempt verifies on-device behavior; no device was contacted.
- The clean `versionCode = 4` APK's install/launch result is still unreported by the user and is not claimed anywhere in the guide.

## Remaining work

S1.7 documentation, PC reproduction, and read-only commit-readiness reporting are complete for Main's acceptance. No blocker. No staging, commit, or push was performed. Main owns `docs/PLAN.md` / sprint-plan status edits and the decision on the authorized commit scope.
