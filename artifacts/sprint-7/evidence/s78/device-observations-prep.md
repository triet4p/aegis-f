# S7.8 paired review-effort evidence — preparation state (blocked before the UI walkthrough)

**Task:** S7.8 — Prove reduced review effort
**Attempt:** S7.8-impl-01
**Worker agent:** S78EffortSilver (`silver-task`)
**Review cycle / round:** S7.8-cycle-1 / round 1 — **not submitted, not assessed**
**Base commit:** `12d0b550dc4fbc3ac91865de9feaadf24c6664b2` (repo `F:/ai-ml/aegis-f`, ref `refs/heads/main`)
**Date of observations:** 2026-10-25, device `AAAJBB4930914141` (Android API 34)

This file records only what was actually observed before the physical unlock blocker. No UI
walkthrough, screenshot, tap count, timing or dashboard figure is claimed yet. Nothing here is
a review submission or a completion claim.

## 1. Exact builds compared (both isolated, same fixture)

| Role | Source | Build | Package | APK SHA-256 | Bytes |
| --- | --- | --- | --- | --- | --- |
| Earlier manual-invocation path | `b1c98fd` (the S7.2 base commit, last product state before automatic intake classification; S7.6 later removed the transient suggestion note and its per-edit model request) | `:app:assembleDebug`, temporary `applicationIdSuffix = ".s78baseline"` in a separate extracted tree | `io.github.triet4p.aegisf.s78baseline` | `5f29f11c678f4bd56961a2a43523203adb34311e3347f9eea7d6a66b7de22b92` | 88,794,713 |
| Current automatic path | `12d0b550…` (`HEAD`) | same, `.s78current` | `io.github.triet4p.aegisf.s78current` | `05fa7f7d4bc4cea60613918759f449e013371cff40ddbed8eb3c1efa36e0d387` | 88,893,017 |

- The repository worktree was not edited: both trees were extracted with `git archive`
  into `F:/ai-ml/s78/src-baseline` and `F:/ai-ml/s78/src-current`, and the suffix was patched
  only in those copies. `git status` still shows only Main-owned plan documents and untracked
  `artifacts/` evidence.
- The baseline is not a re-creation: it is the actual product commit that still contained
  `ui/BankDraftSuggestion.kt` (`draftSuggestionInput`) and the review screen's per-open/per-edit
  `requestClassification`; the current tree's S7.6 commit removed that path
  (`git diff b1c98fd..HEAD` deletes 68 lines of `BankDraftSuggestion.kt`).
- The user's install `io.github.triet4p.aegisf`, its data, model and allowlist were not written.
  The pinned model was read out of the user's private files read-only and copied into each
  isolated package through one `adb shell` pipe (both `run-as` sides), because the app UID
  cannot write `/data/local/tmp` (`tar … Permission denied`). Verified on the isolated package:
  `laya.onnx` `a874eb254b58b0fcb1e7ad56fbb188c29d64e08c9a46b689433e1f52c66dba1e`,
  `laya_config.json` `5049005dc6ae3ca5e82cc7d85c421357d5c543817300c8e8c5281ddbc69bb561`.

## 2. Fixture (identical for both builds)

Six active categories were seeded into each isolated database while the app was stopped
(`categories` ids 1–6): Food, Housing, Transport, Bills, Shopping, Entertainment. Schema version
is 11 on the baseline and 12 on the current build (the current build adds `classification_work`);
the category rows and all fixture values are identical.

Seven supported VietinBank-shaped notifications were posted once from the allowlisted
`com.android.shell`, dated `25/10/2026`; both listeners received the same seven deliveries:

| Label | Draft id | Amount | Memo | Stated time |
| --- | --- | --- | --- | --- |
| A | 1 | −9,500,000 VND | S78 monthly rent | 07:38 |
| B | 2 | −62,000 VND | S78 coffee with team | 07:42 |
| C | 3 | −33,000 VND | S78 card payment | 07:47 |
| D | 4 | −45,000 VND | S78 payment to friend | 07:51 |
| E | 5 | −120,000 VND | S78 lunch | 08:02 |
| F | 6 | −120,000 VND | S78 lunch | 08:02 |
| G | 7 | −87,000 VND | S78 dinner and movie night | 08:07 |

E/F are the same delivery evidence under different notification keys; the later draft 6 stores
`possibleDuplicateOfDraftId = 5` (never merged).

## 3. Automatic classification before any UI interaction (current build)

No screen was opened and no draft was reviewed. The current listener stored the drafts and the
durable queue classified them by itself:

```text
07:01:31.514 LayaInference: inference session opened for work
07:01:32.598 BankDraftClassification: intake classification draft=1 outcome=APPLIED
… draft=2 … draft=3 … draft=4 … draft=5 … draft=6 … draft=7 …
07:01:40.636 intake classification draft=7 outcome=APPLIED
07:02:10.628 LayaInference: inference session released after idle
```

- **Current pre-review DB** (`db-current-pre-review/aegis-ledger.db`, SHA-256
  `4d257fb53d755ed82d9d1d3bffb6be5ddb8d826ebb198ff2b66d665b14f49502`): 7 pending drafts,
  **7 stored snapshots**, 7 `classification_work` rows all `RESULT` with `attemptCount = 1`,
  7 `workflow_operations` rows `REQUEST_CLASSIFICATION APPLIED`, **0 transactions**.
- **Baseline pre-review DB** (`db-baseline-pre-review/aegis-ledger.db`, SHA-256
  `a58ec9bfc348855698b0805fc701f46dc838fa36b4848562fa074b3b19f7b14a`): 7 pending drafts,
  **0 snapshots**, 0 operation rows, 0 transactions — the manual-invocation build has not
  classified anything because nobody opened a draft yet.
- Queue inputs observed in the current build (all `staleReason = null`, same candidate set
  `4,6,1,2,5,3` = Bills, Entertainment, Food, Housing, Shopping, Transport):

| Draft | Memo | Decision | Suggested | Confidence (sharpness) | Unknown share |
| --- | --- | --- | --- | --- | --- |
| 1 | monthly rent | MODEL | Housing (0.7903) | 0.7273 | 0.0025 |
| 2 | coffee with team | NO_FIT | — | 0.4971 | 0.5412 |
| 3 | card payment | NO_FIT | — | 0.5124 | 0.7174 |
| 4 | payment to friend | NO_FIT | — | 0.6042 | 0.8107 |
| 5 | lunch | NO_FIT | — | 0.4307 | 0.5688 |
| 6 | lunch (duplicate of 5) | NO_FIT | — | 0.4307 | 0.5688 |
| 7 | dinner and movie night | MODEL | Entertainment (0.9356) | 0.8655 | 0.0600 |

Expected visible order under the frozen policy (to be confirmed on the unlocked UI):
Rank 0 duplicate family `5, 6` → Rank 1c unknown `2, 3, 4` → Rank 2 fresh comparable
`1` (0.7273) then `7` (0.8655); chronological order `1…7`.

## 4. Planned identical user decisions (not yet executed)

`E` confirm Food → `F` dismiss (duplicate) → `B` Food → `C` Bills → `D` Shopping → `A` accept
Housing → `G` correct Entertainment to Food and confirm. Expected confirmed totals: 6 records,
9,847,000 VND spending, 0 income, 0 transfers, 0 pending, no duplicate confirmation.

Description-entry model: the earlier path needs a typed suggestion note to obtain a model
suggestion for the opaque/unknown memos (B, C, D, E) — 4 entries; the current path needs none.
This will be measured, not asserted.

## 5. Physical blocker — secure PIN keyguard, no bypass

- `dumpsys window policy`: `secure=true`; `dumpsys trust`: `deviceLocked=1`; keyguard hierarchy
  shows `Nhập mã PIN` / `fixedPinEntry` (Honor PIN keypad). The earlier S7.7 round-3 record had a
  swipe-only keyguard; the device state has changed since.
- Attempted and rejected: `input swipe` from several start points, `input keyevent 82`,
  `input keyevent 224` wake cycles, `wm dismiss-keyguard`, power-key cycling. The keyguard stayed
  locked and no credential was guessed, changed, disabled or bypassed.
- Owner help is required once: unlock the phone (PIN/biometric) and leave it on the home screen
  with USB connected. `svc power stayon true` was used only during preparation and was reverted to
  `stay_on_while_plugged_in = 0` when the run was paused; it is set again for the actual UI session
  and reverted at final cleanup.

## 6. Device state changes made for the run (revert responsibility)

- `svc power stayon true` changed `settings get global stay_on_while_plugged_in` to `15`; it was
  **reverted to `0`** when the run was paused (verified `settings get global
  stay_on_while_plugged_in` → `0`). The pre-change value was not captured before the first set;
  the observed pre-change behaviour (AOD/doze within ~10 s while charging and
  `screen_off_timeout = 2147483647`) is consistent with `0`, which is also the Android default.
  Set it again only for the UI session and revert afterwards.
- Notification snooze: the six/seven still-active S7.7 synthetic `com.android.shell` posts are
  hidden so neither isolated listener re-reads them as extra drafts. Exact keys are recorded in
  `snoozed-shell-keys.txt`; `unsnooze` each at cleanup. Only synthetic shell posts are hidden;
  the user's own notifications were never touched.
- Both isolated packages and their databases remain installed with the seven pending drafts so
  the UI walkthrough can start immediately after unlock. They must be uninstalled at cleanup.

## 7. Not claimed

No tap count, wall-clock review time, description-entry result, queue-order screenshot, dashboard
figure or exact-total reconciliation exists yet. The criterion "any claimed effort reduction is
backed by the paired fixture" is therefore **not yet satisfied**; the UI walkthrough above is the
remaining required work.
