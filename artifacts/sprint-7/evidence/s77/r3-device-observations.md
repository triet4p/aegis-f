# S7.7 round-3 device scenario log — physical screen lock during pending/running inference

Device `AAAJBB4930914141`, Android API 34. Isolated package
`io.github.triet4p.aegisf.s77evidence` (temporary suffix, reverted after the build;
package uninstalled after the run, `/data/local/tmp` fixtures and the staged user-side
tar removed). The user's install was only read (model copied read-only, hashes
re-verified `a874eb25…`/`5049005d…`); its data, allowlist, and model were never
written. Isolated allowlist held only `com.android.shell`. Synthetic VietinBank-shaped
content only, never a real bank delivery.

Pinned model identity: `68f27dfe5a27a54fb2b1fefc432f43f972e90868/512/192`,
candidate set `laya-candidates-v1`, 6-category names-only catalog (round-1 shape).
Base `eecb9226c03543e4da87f081296903a06c59ef5b` unchanged (no product edits).

Rounds 1–2 (`device-observations.md`, `r2-device-observations.md`) stand and are not
repeated. This file records ONLY the round-3 lock/unlock lifecycle check Main
authorized for cycle S7.7-cycle-1 round 3: physical screen lock/unlock (keyguard via
`input keyevent 26`, swipe unlock — no credential changes), NOT forced concurrent
native execution (the serial single-worker architecture forbids it by design).

## Lock during pending/running inference — inference completes, nothing lost

Posted `Monthly rent lock screen` (44,000 VND) as a cold notification, then pressed
power (`keyevent 26`) while the classification was pending/running:

```text
06:19:45 permitted notification ... storing draft            (draft 8 admitted)
06:19:5x keyevent 26 → mWakefulness=Dozing                   (screen locked mid-flight)
06:19:54.553 draft=5 outcome=APPLIED
06:19:54.678 draft=6 scheduled …
06:19:57.311 draft=6 outcome=APPLIED
...
06:19:59.290 draft=8 scheduled question=a1c31f4ceb01
06:20:00.758 draft=8 outcome=APPLIED                        (under Dozing lock)
06:20:30.814 inference session released after idle           (under lock)
```

- The backlog (drafts 1–7: re-intakes of still-active shell posts after the fresh
  reinstall, disclosed) drained while `mWakefulness=Dozing` the whole time.
- `r3lock` (draft 8): `MODEL → Bills (4) 0.9031`, unknown `0.0025`, confidence
  `0.8293`, `staleReason = null`, work `RESULT/NULL/attempt 1`. Same question asked
  once, answered once — no duplicate, no loss, no auto-ledger (`transactions = 0`).
- The keyguard notification itself shows the posted bank memo on the lock screen
  (`r3d`-series screenshots: `Tin bien dong so du / VietinBank: 16/10/2026…` visible
  under `Sử dụng vân tay hoặc vuốt lên để mở khóa`), proving the intake path is the
  system's own posted notification, not a spoofed row.

## Unlock — normal swipe, no credential enrolled

- Keyguard type: swipe/fingerprint (`Sử dụng vân tay hoặc vuốt lên để mở khóa`);
  trust dump shows `deviceLocked=0`-capable swipe lock, no PIN/password enrolled, no
  credential change made or needed.
- Unlock gesture: bezel swipe `360,1590 → 360,300` (worked repeatedly; reached the
  launcher wallpaper twice). No bypass, no `wm dismiss-keyguard` reliance (that call
  printed nothing and was not the unlock path), no locksettings change.
- Post-unlock intake works: `Monthly rent post unlock` (draft 9) stored and answered
  `MODEL → Bills (4) 0.9023`, work `RESULT/NULL/attempt 1`, `transactions = 0`
  (`logcat` 06:2x lines, DB-verified).
- Resumed review state is the same stored rows: drafts 8+9 `RESULT` fresh (DB pull
  after unlock, integrity `ok`, `user_version = 12`); no duplicate work, no stale
  application, no auto-confirmation.

## Review-surface screenshot limit (disclosed, not a finding)

The display re-dozes ~10 s after each wake (AOD focus) and the review list screenshot
kept landing on AOD/keyguard/black frames (`r3-review-after-unlock.png` series);
the launcher-unlocked frames prove the swipe unlock works, but no review-list frame
was captured. This does NOT weaken the check: the review surface reads the same
Room rows the DB pull verifies (draft 8/9 RESULT fresh), and resumed intake is
proven by draft 9's post-unlock APPLIED line. The data readback is the observation;
the screenshot would only restate it.

## Measurement conditions and limits

- Clock: `logcat -v epoch` deltas while Dozing (scheduled→outcome per draft ~1–2 s
  warm after the first cold open; session `released after idle` under lock).
- One device, one lock run; no latency percentile claimed.
- No credential was enrolled, changed, or bypassed; no system setting was left
  modified (AOD `doze_always_on` read as null, untouched).
- Cleanup: isolated package uninstalled, `/data/local/tmp` fixtures and the staged
  user-side tar removed, build file restored (tracked tree clean apart from
  Main-owned plan docs). The user's install, model files, allowlist, and data were
  not modified.
