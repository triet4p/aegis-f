# S7.3 device smoke — transcribed observations

Device: `AAAJBB4930914141`, Android API 34. Isolated package
`io.github.triet4p.aegisf.s73evidence` (temporary `applicationIdSuffix`, reverted before the final
production build; the package was uninstalled and its `/data/local/tmp` fixtures removed after the
run). The user's own install `io.github.triet4p.aegisf` was only read — its pinned Laya export was
copied out with `run-as` and nothing was written to it; its allowlist did not contain
`com.android.shell`, so every synthetic post was dropped there before content was read (observed
in the log as `persistOne: dropped notification from com.android.shell (not in allowlist)`).

Setup: the previous-version APK was built from commit
`1896d2d18c2d4634a400ee15eac65f4f5c75a9e7` (database version 11) and installed first, so the
upgrade below starts from a real, populated previous-version app. Notification access was granted
with `adb shell cmd notification allow_listener …`; the isolated app's allowlist file held
`com.android.shell` (`allowlist-prefs.xml`); the pinned export was copied into
`files/laya/model` from the user's install with `tar` through `run-as`, never modified. Supported
notifications were posted with `adb shell cmd notification post -S bigtext …` using the
VietinBank-shaped text (`VietinBank: 16/10/2026 07:38` / `TK: Redacted` / `GD: -N VND` /
`SDC: xxx,000 VND` / `ND: <memo>`). Databases were captured by pulling
`databases/aegis-ledger.db{,-wal,-shm}` through `adb exec-out run-as` and querying a local copy;
each directory below holds those files.

## 1. Populated previous-version upgrade (11 → 12)

Pre-upgrade `v11/`: `PRAGMA user_version = 11`; 3 categories (Food, Housing, Transport); 2 pending
drafts — draft 1 `UNSUPPORTED_FORMAT`, draft 2 `PARSED`, 63,000 VND, memo `S73 seed coffee`; 1
snapshot (draft 2, `NO_FIT`, confidence `0.3188`); 1 `REQUEST_CLASSIFICATION` row `APPLIED`; 0
transactions; no `classification_work` table. Draft 2's prediction came from the previous build's
own S7.2 path (`intake classification draft=2 outcome=APPLIED`), not from a fixture.

Then the S7.3 APK was installed over it (`adb install -r`; same package, same debug signature) and
the app was launched. `upgrade/`:

| Observation | Result |
| --- | --- |
| `PRAGMA user_version` | 12 |
| categories / drafts / snapshots / operations / transactions | 3 / 2 / 1 / 1 / 0 — every pre-upgrade row and value preserved |
| `classification_work` | created, 1 row: draft 2 `RESULT`, `attemptCount = 0`, `snapshotId = 1`, `requestedQuestion = executedQuestion = 5d9e2f43325b…`, no reason |
| new `REQUEST_CLASSIFICATION` rows | none (still 1) |
| `PRAGMA foreign_key_check` | empty |

The job row was written by the listener re-reading the still-active post after the upgrade; the
stored prediction was fresh for the same question, so the queue recorded the result without asking
the model again. Log lines for the run contained no `BankDraftClassification … scheduled` line,
which is the evidence that no redundant inference ran.

## 2. Notification burst, process kill, and recreation

Posted 5 supported notifications back to back (drafts 3–7). A live pull showed the queue had
already finished drafts 3–6 (`RESULT`, `attemptCount = 1`) and held draft 7 `RUNNING`,
`attemptCount = 1`, `executedQuestion = aa1d0164f057…` with its snapshot not yet stored. The
process was then force-stopped (`adb shell am force-stop`). `burst-live/` and `burst-killed/` are
identical for the job rows: draft 7 stayed `RUNNING` on disk, exactly what a process that dies
mid-inference leaves behind; all 7 drafts were stored.

Relaunching the app (`am start …MainActivity`, which starts the queue):

```text
I BankDraftClassification: resumed 1 interrupted classification job(s)
I BankDraftClassification: intake classification draft=7 scheduled question=aa1d0164f057
I LayaInference: inference session opened for work
I BankDraftClassification: intake classification draft=7 outcome=APPLIED
```

`burst-resumed/`: draft 7 `RESULT`, `attemptCount = 2`; every draft 2–7 has a snapshot; no
`PENDING` operation row remains (the interrupted attempt's claim was removed — the operation ids
run `1,2,3,4,5,7`, i.e. the interrupted id 6 is gone and its key is not reused); 0 transactions.

## 3. Missing model, then recovery

The model directory was moved aside (`run-as mv files/laya/model files/laya/model.off`), the app
was stopped and restarted, and 2 notifications were posted. `missing/`: drafts 11 and 12 stored
`PENDING`; their jobs `FAILED` with `statusReason = MODEL_OR_INFERENCE_FAILED`, `attemptCount = 1`;
no snapshots for either; 0 transactions.

```text
I BankDraftClassification: intake classification draft=11 scheduled question=f6128520e5dc
I BankDraftClassification: intake classification draft=11 outcome=FAILED
I BankDraftClassification: intake classification draft=12 scheduled question=f6fd1da6be9e
I BankDraftClassification: intake classification draft=12 outcome=FAILED
```

The model directory was restored and the app restarted. The listener re-read the active posts and
re-reported the drafts; `recovered/`: drafts 11 and 12 `RESULT` (`attemptCount = 1`), fresh
snapshots (`NO_FIT`, confidence `0.3204` / `0.3697`), no `PENDING` operation rows, 0 transactions.
The log shows the two same questions retried, and no inference for the other 6 re-reported drafts
whose stored predictions were still fresh:

```text
I BankNotificationListener: reconcileCurrentActive: active=16, permitted candidates=8
I BankDraftClassification: intake classification draft=12 scheduled question=f6fd1da6be9e
I LayaInference: inference session opened for work
I BankDraftClassification: intake classification draft=12 outcome=APPLIED
I BankDraftClassification: intake classification draft=11 scheduled question=f6128520e5dc
I BankDraftClassification: intake classification draft=11 outcome=APPLIED
```

## 4. Inference session is not held

With a notification posted and answered, the new lifecycle diagnostics show the session's life:

```text
I LayaInference: inference session opened for work          23:39:25.435
I BankDraftClassification: intake classification draft=10 outcome=APPLIED
I LayaInference: inference session released after idle      23:39:56.421
```

The session is closed about 31 s after the last item, i.e. the idle window, and the queue never
keeps one open between items. The notification listener itself only writes the draft and reports
its id; it does not construct or call the model.

## 5. User dismissal cancels work

(a) Dismissing a draft that had already been answered. Draft 24's form was opened, `Dismiss
draft` confirmed. `cancel-late/`: draft 24 `DISMISSED`, job `CANCELLED` with
`statusReason = DRAFT_REVIEWED`, `attemptCount = 1`; the snapshot is retained as evidence; 0
transactions.

```text
I BankDraftClassification: classification cancelled: draft=24 reason=DRAFT_REVIEWED
```

(b) Dismissing a draft that was still waiting. A 14-notification burst was posted and the newest
draft (#50) was opened and dismissed within the burst. `cancel-queued/`: draft 50 `DISMISSED`, job
`CANCELLED` with `statusReason = DRAFT_REVIEWED`, `attemptCount = 0`, `executedQuestion = NULL` —
the queue never asked it — and no queue scheduling line for draft 50. A snapshot for draft 50
exists because the review form's own S4.8 suggestion request ran while the form was open; that is
the review path, not the queue. Draft 49 (`RESULT`, `attemptCount = 1`) and the rest of the burst
completed; job states after the run: 46 `RESULT`, 3 `CANCELLED`; no `PENDING` operation rows; 0
transactions.

```text
I BankDraftClassification: intake classification draft=49 scheduled question=8e764577bae7
I BankDraftClassification: classification cancelled: draft=50 reason=DRAFT_REVIEWED
I BankDraftClassification: intake classification draft=49 outcome=APPLIED
```

## 6. Rapid accepted edits coalesce into the latest question

A cold 8-notification burst was posted and the newest draft (#58) was opened and edited twice while
its job was still queued: `A` was appended to the description and `Save corrections` pressed, then
`B` was appended and saved again (the IME also inserted ` Vy Vy Vy` during the scripted swipes;
that is a real accepted text change and does not affect the observation). The final stored
description is `S73 edit 7A Vy Vy VyB`, `reviewRevision = 2`.

`rapid-edits/`: one `classification_work` row for draft 58 — `RESULT`, `attemptCount = 1`,
`requestedQuestion = executedQuestion = 12d92c3a1c8a…`. The log shows one scheduling line for the
pre-edit question (`09106b7827cd`, asked before the first save because the queue reached the draft
then) and exactly one more for the final question, with no scheduling line for the intermediate
revision:

```text
I BankDraftClassification: intake classification draft=58 scheduled question=09106b7827cd
I BankDraftClassification: intake classification draft=58 outcome=APPLIED
I BankDraftClassification: intake classification draft=58 scheduled question=12d92c3a1c8a
I LayaInference: inference session opened for work
I BankDraftClassification: intake classification draft=58 outcome=APPLIED
```

0 transactions. The deterministic version of this scenario — including the middle wording never
reaching the model — is the host regression
`ClassificationWorkLifecycleTest."rapid edits keep the newest question and ask the model once per
revision"`.

## 7. Cleanup

The isolated package was uninstalled, its listener permission removed, `/data/local/tmp` fixtures
(allowlist, seed database, model tarball) deleted, and the temporary build copy removed. No
production package was installed or modified, and the user's install and data were untouched.

## Evidence files

| Path | Content |
| --- | --- |
| `v11/` | populated previous-version database before the upgrade |
| `upgrade/` | the same database after the 11 → 12 upgrade and the launch |
| `burst-live/`, `burst-killed/`, `burst-resumed/` | burst before, during, and after the force-stop |
| `missing/`, `recovered/` | missing-model jobs and their recovery |
| `cancel-late/`, `cancel-queued/` | dismissal of an answered and a waiting job |
| `rapid-edits/` | the two-edit job and its latest-question result |
| `logcat-filtered.txt` | the remaining filtered log buffer (older lines transcribed above) |
| `ui-*.xml` | UI hierarchy dumps used to drive the review form |
| `allowlist-prefs.xml` | the isolated app's allowlist fixture |
