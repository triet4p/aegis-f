# S7.4 device smoke — transcribed observations

Device: `AAAJBB4930914141`, Android API 34. Isolated package
`io.github.triet4p.aegisf.s74evidence` (temporary `applicationIdSuffix`, reverted before the
final production build; uninstalled and its `/data/local/tmp` fixtures removed after the run).
The user's own install `io.github.triet4p.aegisf` was not written: only its pinned Laya export
was copied out read-only (`files/laya/model`). The isolated app's allowlist fixture held only
`com.android.shell`; synthetic VietinBank-shaped notifications were posted with
`cmd notification post -S bigtext` and parsed by the app's real intake.

Seeded fixture (disclosed): the isolated database was created by the candidate app, then two
categories (`Food`, `Transport`) were inserted while the app was stopped. For the unlink case a
capture (`Fuel 50,000 at 15:00`), one note event, and one accepted `context_links` row were
inserted the same way, so the app's own **Unlink excerpt** action could be used; nothing else was
seeded. The model, intake, classification queue, command boundary, snapshot store, and UI were
the candidate code.

## 1. Real model snapshots are persisted with candidate metadata

The listener re-read still-active `com.android.shell` notifications and classified three drafts
with the real pinned export. The stored rows:

| draft | decision | candidateIds | probabilities | unknownShare | confidence | staleReason |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | NO_FIT | `1,2` | `0.1254,0.1176` | `0.757` | `0.3421` | null |
| 2 | NO_FIT | `1,2` | `0.1379,0.1345` | `0.7276` | `0.2951` | null |
| 3 | NO_FIT | `1,2` | `0.1374,0.1318` | `0.7308` | `0.3` | null |

`classification_work` rows were `RESULT` with `attemptCount = 1`; `transactions` stayed at 0.
The inference session opened once (`inference session opened for work`) and no ledger row was
created anywhere in this run.

## 2. A user correction after a stored prediction preserves the prediction

Draft 5 got a stored NO_FIT prediction (`candidateIds='1,2'`, shares `0.1757,0.1494`, unknown
`0.6749`, confidence `0.2218`). The user then chose **Food** in the review form and saved
(`SAVE_DRAFT_REVIEW APPLIED`):

| Observation | Result |
| --- | --- |
| draft 5 | `reviewCategoryId=1`, `reviewCategoryResolved=1`, revision 1 |
| stored prediction | unchanged values and `predictionTime`; `staleReason=USER_CATEGORY_CHOSEN` |
| work row | `CANCELLED`, `statusReason=DRAFT_RESOLVED` |
| ledger | 0 transactions |

The same was repeated for draft 9 with **Transport** (`reviewCategoryId=2`, resolved 1): its
stored prediction kept its values and became `USER_CATEGORY_CHOSEN`.

## 3. A delayed inference crossing a user correction is refused

Draft 10 was posted after a force-stop, so its classification paid the cold session open:

```text
01:16... (this run, draft 10, earlier log)
00:59:34.791 BankDraftClassification: intake classification draft=10 scheduled question=cb393fef063f
00:59:44.260 BankDraftClassification: classification cancelled: draft=10 reason=DRAFT_RESOLVED
00:59:45.766 LayaInference: inference session opened for work
00:59:46.701 BankDraftClassification: intake classification draft=10 outcome=NO_CHANGE
```

The user's category choice (Food) was saved at 00:59:44, while the model request was already
scheduled; the eventual result was answered as **NO_CHANGE** and no snapshot was written over the
choice. The database confirms: draft 10 `reviewCategoryId=1`, resolved 1, revision 1; **no**
`classification_snapshots` row for draft 10; `classification_work` `CANCELLED` /
`DRAFT_RESOLVED`; `REQUEST_CLASSIFICATION` operation outcome `NO_CHANGE`; 0 transactions.

## 4. Catalog archive and delete

Deleting **Transport** through the real category UI (Manual → Manage categories → Delete):

| Observation | Result |
| --- | --- |
| categories | only `Food` remains |
| draft 9 (had chosen Transport) | `reviewCategoryId=NULL`, `reviewCategoryResolved=0`, revision 1→2 |
| stored predictions with no earlier reason | all became `CATEGORY_CHANGED` in the same command |
| predictions already `USER_CATEGORY_CHOSEN` | kept that first reason |
| remembered rules / ledger | 0 / 0 |

Archiving **Food** through the same UI set `archived=1`; the pipeline reclassified the drafts
and the confirmed ledger stayed at 0. (The archive's immediate `CATEGORY_CHANGED` marker was
replaced by the newer predictions the queue stored, as intended.)

## 5. Source unlink (accepted excerpt) and the fresh post-unlink prediction

A seeded accepted excerpt for draft 9 (`Saved note #10 · event #1`, reading
`Fuel 50,000 at 15:00`, 50,000 VND · 2026-10-16 · Petrol) was shown by the real review screen.
Before unlinking, the stored prediction carried `eventIds='1'` and was fresh. The user tapped
**Unlink excerpt**:

| Observation | Result |
| --- | --- |
| `context_links` | empty; the accepted excerpt is gone |
| the prediction stored with the reading | immediately `CONTEXT_CHANGED`, values and `predictionTime` unchanged (not overwritten) |
| queue re-admission | new request (the first candidate build replayed an old key here; see below) |
| after the fixed build re-ran | fresh prediction with `eventIds=''`, `staleReason=null`, new `predictionTime`; work `RESULT`; request `APPLIED` |
| ledger | 0 transactions |

The first run exposed a real defect: the classification request key was derived from
(draft, question, attempt). When the question moved away (reading linked) and back (reading
unlinked), the post-unlink attempt reused the earlier key, so the command answered `NO_CHANGE`
and the stale prediction was never replaced. The candidate now includes the attempt's own owner
token in the key; after that fix the device run stored the fresh no-reading prediction (above).
The host regression `a question restored by an unlink is asked and stored again` covers it.

## 6. Model revision boundary

The host regression `a category, a rule, and a model change each make a stored prediction stale`
covers `MODEL_CHANGED`. On the device an old-`modelRevision` row was seeded for draft 9, but the
review screen's own real classification replaced it before any per-draft read surface could
isolate the reason (S7.4 has no stored-snapshot read UI). No runtime `MODEL_CHANGED` claim is
made; the device run observed only that the reclassification pipeline did not reuse a stale row.

## 7. Cleanup

The isolated package was uninstalled, its listener permission removed, `/data/local/tmp` fixtures
(the notification script, DB copies, model tar) deleted, and the temporary model tar and build
scratch files removed. The production package was rebuilt without the suffix; it was not installed
over the user's app. The user's install, allowlist, model, and data were not modified.

## Evidence files

| Path | Content |
| --- | --- |
| `real-model/a.db` | drafts 1–3 and their real NO_FIT snapshots |
| `correction/a.db` | draft 5's stored prediction and `USER_CATEGORY_CHOSEN` choice |
| `race/a.db` | draft 10 after the delayed result was refused |
| `delete/a.db` | category delete: cleared choice, `CATEGORY_CHANGED` markers |
| `archive/a.db` | category archive and the reclassified predictions |
| `unlink/a-before-unlink.db` | stored prediction with `eventIds='1'` |
| `unlink/a-after-unlink.db` | link gone, old prediction `CONTEXT_CHANGED` |
| `unlink/a-after-reclassification.db` | fixed build: fresh prediction with `eventIds=''` |
| `device-logcat-filtered.txt` | filtered listener/queue/lifecycle log from the fixed-build run |
