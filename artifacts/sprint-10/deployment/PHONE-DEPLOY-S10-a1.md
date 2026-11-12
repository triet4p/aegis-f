# PHONE-DEPLOY-S10-a1

> **Privacy redaction — 2026-11-12:** The device serial is replaced by `<DEVICE_SERIAL_REDACTED>` in this Git copy. The byte-identical original remains in the ignored local archive at `artifacts/sprint-10/private-originals/S10-CLOSE-a1/artifacts/sprint-10/deployment/PHONE-DEPLOY-S10-a1.md` (original SHA-256 `821559ace4b3a71cf5d92d8511b7183c6b2d5c8c217f34f8826c85aeda29f069`). Deployment facts and outcomes are unchanged.

- Task: `PHONE-DEPLOY-S10`; attempt: `PHONE-DEPLOY-S10-a1`; agent: `MainAppDeploy`.
- Role: Silver; route: Light; no review/checkpoint/commit assigned. No source edits, staging, commit, or push.
- Routing refs: none.
- Outcome: main app updated and launched; all seven confirmed auxiliary Aegis/test packages removed; post-cleanup inventory contains only the main Aegis package among the authorized targets.

## Source and build

- Build source: `fc1b9b22f97406a27c4436536dbb15606457e761`; `app/` matched accepted S10.9 source `d53d7fa57f63298e690bf48147b4ebe6f29bf6ec` (both source comparison and app working-tree diff exited 0). Existing user changes outside `app/` were left untouched.
- Build command: `gradlew.bat :app:assembleDebug --no-daemon` — `BUILD SUCCESSFUL`; no test suite run.
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- SHA-256: `4e06eec75bcdf208954034ebf3faa29d4fcc7ed11d7d648f73d54f3b53942fcf`
- `aapt dump badging`: application ID `io.github.triet4p.aegisf`, versionCode `4`, versionName `1.0` (MAIN package; no evidence/test suffix).
- Pre-install device APK SHA-256 was `a4335551c19552c3156fe19fc0b44df05e2641fef57dcea222dcdb9749cb750a`, matching the supplied inventory. Installed version was code `4`, name `1.0`, signing scheme v2. `apksigner` certificate SHA-256 matched before install: `891542fd7a27bdaff367af863d28ac0babeb4dfd2c394e340348534a797ff41b`.

## Installation and preservation

- Device: serial `<DEVICE_SERIAL_REDACTED>` (`ALT_LX2`).
- Command: `adb -s <DEVICE_SERIAL_REDACTED> install -r app/build/outputs/apk/debug/app-debug.apk` — `Performing Streamed Install` / `Success`.
- Re-pulled installed base APK after installation; SHA-256 matched the local build exactly (`4e06eec75bcdf208954034ebf3faa29d4fcc7ed11d7d648f73d54f3b53942fcf`). Installed APK metadata remained package `io.github.triet4p.aegisf`, versionCode `4`, versionName `1.0`, with the same signing certificate digest.
- No main-app uninstall or data-clear command was issued. Before/after `run-as ... du -sk` metadata matched: `databases` 760 KiB, `shared_prefs` 12 KiB, `files` 2708 KiB, and app data total 3493 KiB. Package appId remained `10404`; `firstInstallTime` remained `2026-11-03 00:40:33`.
- `INTERNET` and `ACCESS_NETWORK_STATE` remained granted; `BankNotificationListener` remained enabled. No device permission, radio, connectivity, font, or theme settings were changed.
- Limits: directory sizes/metadata and package state are evidence of retention, not byte-for-byte verification of mutable database/preferences or Android Keystore material. User content and key material were not read; no claim of byte-identical data is made.

## Native launch and screenshot

- `adb shell am start -n io.github.triet4p.aegisf/.MainActivity` returned `Starting: Intent { cmp=io.github.triet4p.aegisf/.MainActivity }`.
- Personally opened and inspected the screenshot. Home showed eight native action icons (Ledger, Analytics, Notes, AI Chat, Review, Manual record, Categories & rules, Settings) and five bottom tabs (Home, Ledger, Notes, AI Chat, Review).
- Privacy-safe screenshot: `artifacts/sprint-10/deployment/PHONE-DEPLOY-S10-a1-home.png` (720×1033). Crop excludes Android status/notification icons and the spending summary; badge numerals were redacted. No notification content or financial records are shown. The unredacted temporary capture was deleted after review. No records were changed and no provider request was made.

## Auxiliary package cleanup

Immediately before cleanup, rechecked the connected serial and exact installed package IDs. Each authorized `adb uninstall` returned `Success`:

- `io.github.triet4p.aegisf.s109evidence`
- `io.github.triet4p.aegisf.s109evidence.test`
- `io.github.triet4p.aegisf.s108evidence`
- `io.github.triet4p.aegisf.s108evidence.test`
- `io.github.triet4p.aegisf.s812evidence`
- `io.github.triet4p.aegisf.s812evidence.test`
- `io.github.triet4p.aegisf.test`

Post-cleanup `pm list packages -3` filtered to the Aegis target prefix returned only `io.github.triet4p.aegisf`. `pm list instrumentation` returned no Aegis instrumentation entries. Main package remained installed at versionCode `4`, appId `10404`, with the permissions and listener state above.

Non-blocking build note: Gradle emitted an SDK XML tooling warning (tool understands XML through version 3; SDK XML version 4 encountered); the build completed successfully.