# S9.9-a10 — every device-affecting action taken by this attempt

Prepared in response to the owner's report that developer mode was disabled and the keyboard
changed to voice. This file lists the exact actions, the timestamps observed on the device or in the
host transcript, and separates what was directly observed from what is inferred or unobserved.
No device, settings or ADB server action was taken after the halt instruction.

## Direct device mutations (complete list)

| # | action | when | observed effect |
|---|---|---|---|
| 1 | `adb shell am instrument … S99WorkflowPhoneInstrumentedTest#workflowEvidence -e s99_stage cleanup_ids -e s99_ids capture:102` | 20:38 (device clock) | deleted exactly one owned note capture; `S99CLEANUP capture=102 outcome=Applied` (`device/02-cleanup-a9-note.log`) |
| 2 | `adb push <temp> /data/local/tmp/s99_secret.env` + `run-as … cp` into `files/s98_secret.env` + `adb shell rm -f /data/local/tmp/s99_secret.env` | 20:39 | staged the key privately; host temp deleted |
| 3 | `am instrument … S98DeviceFixtureInstrumentedTest#stage -e s98_stage add_custom_profile …` then `activate_profile` | 20:39 | stored and activated the temporary profile; helper deleted `files/s98_secret.env` (`device/05-profile-staging.log`) |
| 4 | `adb install -r -t <test apk>` (twice) | 20:45, 20:53 | isolated **test** package only (`…s812evidence.test`) |
| 5 | `adb shell am start -n io.github.triet4p.aegisf.s812evidence/io.github.triet4p.aegisf.MainActivity` (several) | 20:41, 20:53, 20:57, 21:09 | launched the isolated app |
| 6 | `adb shell input tap/swipe/text/keyevent 4` (UI driving; taps only on Chat/Manual/Review nav, prompt fields, buttons, dialog rows) | 20:41–21:22 | app UI actions only |
| 7 | `adb shell am force-stop io.github.triet4p.aegisf.s812evidence` **(once)** | ≈20:52 | isolated app only; no run in flight |
| 8 | `adb logcat -c` (twice) | 20:46, 20:57 | cleared the log buffer only |
| 9 | `adb shell ime disable com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME` | ≈20:57:30 | device printed `now disabled for user #0` |
| 10 | `adb shell run-as io.github.triet4p.aegisf.s812evidence sh -c 'echo stop > files/s99_interactive_hold.stop'` and `rm -f` the same | 20:49, 20:56, 21:23, 20:55 | app-private marker file only |
| 11 | `am instrument` for the two holds, two local replay runs, run_audit/run_export | 20:46–21:24 | app/test scope only; no settings |
| 12 | `adb reconnect`, `adb kill-server`, `adb start-server`, `adb devices`, `adb shell echo ok` | ≈21:24–21:25 | transport recovery attempts after the device disappeared; no settings changed |

Read-only commands used throughout: `adb devices -l`, `getprop`, `pm list packages`, `pm path`,
`sha256sum`, `date`, `logcat -d`, `screencap`, `uiautomator dump`, `dumpsys window`,
`run-as … cat`, `settings get secure default_input_method`, `settings get secure
enabled_input_methods`, `ime list -s`.

## Developer options / USB debugging / screen settings

- **No command of this attempt enables or disables developer options, USB debugging, ADB-over-USB,
  screen timeout, stay-awake, or any `settings put`/`svc`/`setprop`/`appops`/`pm grant` value.**
  None appears in the action list above.
- Observed before this attempt's first UI action: the focused window was
  `com.android.settings/com.android.settings.Settings$DevelopmentSettingsActivity` at
  **20:37:54** (`device/03-current.png`, captured immediately after the first read-only commands).
  That is the device's pre-existing state; it does not show who opened it.
- My UI taps never targeted the Settings app: the first tap happened at 20:41, after
  `am start` had brought the isolated app to the foreground
  (`device/07-dashboard-baseline.png`), and every later tap is in the app's screens
  (evidence screenshots/XMLs in `device/`).
- **USB disappearance:** observed only as `adb devices` returning empty at ≈21:24, with
  `adb reconnect` and an adb server restart not restoring it. No physical unplug, cable, hub or
  USB-mode change was observed or performed; I cannot and do not claim the cause.

## IME / keyboard

- The only IME mutation is action 9: **Gboard (`…inputmethod.latin.LatinIME`) was disabled** to stop
  Telex corruption of adb-typed ASCII. This is the most plausible cause of the owner's report.
- Observed immediately afterwards: `settings get secure default_input_method` returned
  `com.google.android.tts/…VoiceInputMethodService`, and the voice IME sheet is visible in
  `device/52-note-typed.png`, `device/106-answer2.png`, `device/114-answer3.png`.
- **No `ime enable` and no `ime set` command was run.** The switch of the default to the voice IME
  was a system-side consequence of disabling the only keyboard IME; the mechanism is unobserved,
  only the before/after values are observed.
- Planned restoration was `ime enable` + `ime set` back to Gboard, but the device disappeared first
  and no restoration has been attempted since the halt.

## Phase 2 — after the owner reconnected the phone (owner authority supersedes the pause)

No settings, developer-option, USB-debugging, IME-enable/disable or default-IME command was run in
this phase. Constraints acknowledged: Gboard only; the visible globe switches to English (US); never
disable Gboard or enable/use the voice or any other IME; never change keyboard/default/IME or
developer settings.

Observed:
- `adb devices -l` — phone `AAAJBB4930914141` present again, device clock 21:36:15.
- No `aegisf` process and no active instrumentation before cleanup.
- `settings get secure default_input_method` = `com.google.android.inputmethod.latin/…LatinIME`;
  `ime list -s` listed only Gboard (read-only queries).
- Visible Gboard screenshot `device/136-gboard-visible.png` shows the globe key and the Vietnamese
  spacebar label; after tapping the visible globe, `device/138-gboard-typed.png` shows the
  **English** spacebar and the literal text `Literal` (no Telex corruption).
- The unsaved test text was cleared again and the keyboard hidden; nothing was saved.

Cleanup executed only through the previously approved helper with no other instrumentation live:
- `S98DeviceFixtureInstrumentedTest#stage -e s98_stage remove_profile -e s98_profile_id
  s99-opencode-go-flash` → `removed=s99-opencode-go-flash remaining=[] active=null`
  (`device/131-profile-removed.log`).
- `shared_prefs/hosted_provider_settings.xml` → 65 B, sha256
  `3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793`
  (`device/132-prefs-after-cleanup.xml`) — the known clean baseline.
- `files/s98_secret.env` absent; `files/s99_interactive_hold.stop` removed and verified absent.
- Foreign ledger untouched: the app still shows 40,000 VND / 2 confirmed expenses.

No third external provider trial was started.

## Historical state between 21:24 and the reconnect (superseded by phase 2 above)

- At that time Gboard was disabled and the default IME was the voice IME; the owner has since
  restored Gboard and added English (US), and this attempt changed no IME setting afterwards.
- At that time the temporary profile was still stored and active and the hold marker was present;
  both were removed in phase 2 through the approved helper.
- No developer-option or USB-debugging change was made by this attempt at any point.

