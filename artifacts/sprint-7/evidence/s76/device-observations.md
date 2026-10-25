# S7.6 device smoke — transcribed observations

Device: `AAAJBB4930914141`, Android API 34, 720×1610 dp surface. Isolated install
`io.github.triet4p.aegisf.s76evidence` (temporary `applicationIdSuffix`, reverted before the
production build; package uninstalled after the run). The user's own install
`io.github.triet4p.aegisf`, its data, allowlist and model were not modified. The pinned Laya
export was copied out read-only with `tar` through `run-as`, installed into the isolated app,
verified (`laya.onnx` SHA-256 `a874eb25…`, `laya_config.json` `5049005d…`), and removed with
the package.

## What was real and what was seeded (disclosed)

- **Real:** the review screen and its stored suggestion panel, the inbox cards and live
  job-status flow, the single-entry queue read and its policy-gated relative label, the
  durable queue's cancel/retry paths, the real command boundary, the real Room database
  (schema 12), the real pinned Laya model and its outputs, every screenshot, and the ledger
  (0 transactions throughout).
- **Seeded:** the deterministic status fixture (`seed_fixture.py`): 6 categories, 10 drafts,
  6 prediction rows, 9 durable job rows, one proposed link and one attachable saved reading.
  The values are illustrative policy shapes, not model runs. The fixture keeps only
  RESULT/FAILED/CANCELLED job states so the worker does not replace them on launch.
- **Real model outputs are labelled as such below.** The seeded “Relatively confident
  suggestion · Food 56% …” is a fixture; the Bills 98% and unknown 92% values are the real
  pinned model’s outputs on the stored descriptions.

## 1. Inbox status and provenance

With the fixture loaded (plus one live shell notification that the real listener stored as an
unsupported format, so 11 waiting), the inbox visibly showed:

| Card | Status line observed |
| --- | --- |
| proposed-link fixture | `Needs attention: note association not decided`; no confident label despite its 0.99 stored sharpness |
| failed fixture | `Suggestion unavailable — the local model is missing or the check failed. Open this draft to retry, choose a category, or check model setup.` + `Retry suggestion` |
| cancelled fixture | `Suggestion cancelled. Retry or open this draft.` + `Retry suggestion` |
| stale fixture | `Suggestion no longer current — the context changed` |
| no-fit fixture | `No fit — choose a category` |
| direct fixture | `Saved rule or category name — no model score` |
| user-resolved fixture | `Your category choice — confirm or dismiss` |
| fresh fixture | `Relatively confident suggestion · Food 56% · unknown 10% · sharpness 93% · 5 of 6 active options evaluated` + `A suggestion among the evaluated options; it is not a guarantee of accuracy and nothing is confirmed automatically.` |
| missing (no job) fixture | `Not classified yet` (the detail panel offered the explicit retry) |

`inbox-top.png` is the inbox top; the status strings above are transcribed from the
`uiautomator` dumps taken at the time.

## 2. Inspect a high-confidence candidate without selecting it

Opened DRAFT #1 (fixture) and did not tap any option. `fresh-detail.png` shows the review
fields unchanged; `fresh-suggestion.png` shows:

- `Relatively confident suggestion: Food 56% · no-fit 10% · sharpness 93% over 5 of 6 active categories.`
- `Food — 56%`, `Housing — 13%`, `Transport — 9%`, `Bills — 7%`, `Shopping — 5%`, `Unknown / no fit — 10%`.
- `Evaluated 5 of 6 active categories; 1 were not offered.`
- `Recorded 2026-12-11 21:40 · model laya-model-t · candidate set laya-candidates-test`.
- The category field remained unselected; nothing was confirmed.

## 3. Real-model retry of a failed check

Opened DRAFT #5 (seeded FAILED) and tapped **Retry check** with the real model installed.
`failed-busy.png` caught the live `Checking a suggestion now. The local model is reading the
stored description and linked context; this screen stays usable.` state. After it completed,
the panel showed `A newer stored state or a changed context is available. Refresh to read it;
the values above stay as they were until you do.` Tapping **Refresh suggestion** showed the
real output: `The model chose the unknown / no-fit option…`, options
`Bills 3%, Entertainment 1%, Food 1%, Housing 0%, Shopping 1%, Transport 1%`,
`Unknown / no fit — 92%`, `Evaluated 6 of 6 active categories.`, and
`Recorded 2026-10-25 03:15 · model 68f27dfe5a27 · candidate set laya-candidates-v1`
(`real-suggestion.png`, `real-no-fit.png`). No label is attached to a no-fit outcome.

## 4. Context improvement without leaving review

Opened DRAFT #10 (`S76 card payment`, seeded FAILED, no snapshot), tapped **Add note context**,
attached the saved `Monthly rent` reading, closed the dialog, and tapped **Refresh suggestion**
after the explicit “newer stored state” notice. The real model then produced
`Relatively confident suggestion: Bills 98% · no-fit 1% · sharpness 94% over 6 of 6 active
categories.` with the six options, `Unknown / no fit — 1%`,
`Evaluated 6 of 6 active categories.` and
`Recorded 2026-10-25 03:20 · model 68f27dfe5a27 · candidate set laya-candidates-v1 · 1 linked reading(s)`
(`context-improved.png`, `context-improved-options.png`). The note context section showed
`Attached note excerpt` and the bank amount/ledger stayed unchanged.

## 5. Manual override and no financial auto-confirmation

With the real Bills 98% suggestion on screen, selected the **Housing** category explicitly and
tapped **Save corrections**. The screen showed `Corrections saved.` and the suggestion panel
disappeared as the user’s choice took precedence (`manual-saved.png`); **Confirm transaction**
remained a separate action and was not tapped. The pulled database records
`draft 10: reviewCategoryResolved=1, reviewCategoryId=2 (Housing), reviewRevision=1`, the
stored prediction stale as `USER_CATEGORY_CHOSEN`, job `CANCELLED/DRAFT_RESOLVED`, and
`transactions=0`.

## 6. Missing model is actionable

Moved the isolated app’s `files/laya/model` aside, opened DRAFT #7 (missing), and tapped
**Retry check**. The queue recorded a real failed attempt; the panel showed
`No suggestion was stored: the local model is missing or the check failed. Retry, choose a
category manually, or check model setup.` with **Retry check** and **Open model setup**
(`missing-model-failed.png`). The pulled database for that state:
`job 7 = FAILED / MODEL_OR_INFERENCE_FAILED`, `attemptCount=1`, no snapshot,
`transactions=0`, `user_version=12`. The model was restored afterwards; a real retry recovery
is separately shown by the DRAFT #5 run above (seeded FAILED → real NO_FIT after Retry).

## 7. Cancel while inference is running — actual phone evidence (round 2)

The detail screen was already open on DRAFT #5 (seeded FAILED, no snapshot); no navigation was
needed after starting the job. `cancel-before.png` and `cancel-before-ui.txt` record the
before state (`No suggestion was stored…`, **Retry check**). Tapping **Retry check** started the
real queue job; `cancel-after.png` and `cancel-after-ui.txt` caught the live
`Checking a suggestion now. The local model is reading the stored description and linked
context; this screen stays usable.` with the **Cancel check** control. The Cancel control was
tapped while that state was on screen.

`cancel-observed.png` and `cancel-observed-ui.txt` record the after state:
`You cancelled this check. Retry to ask again, or add note context below and refresh. Nothing
is confirmed or saved.` and `Suggestion check cancelled. A late answer is discarded; nothing is
confirmed or saved.` The queue log (`cancel-logcat.txt`) shows the real ordering:

```text
04:01:09.732 intake classification draft=5 scheduled question=be8afc933487
04:01:10.160 classification cancelled by user: draft=5
04:01:10.761 intake classification draft=5 scheduled question=be8afc933487
04:01:21.841 classification cancelled by user: draft=5
```

The rapid tap sequence also hit the recomposed Retry once, which started the second real
attempt; that attempt was likewise cancelled while in flight. The persisted disposition
(`cancel-final.db`) is a single job row:

```text
draftId=5 state=CANCELLED statusReason=USER_CANCELLED attemptCount=1
snapshotId=NULL ownerToken=NULL updatedAtEpochMillis=1792875681806
```

`classification_snapshots` for draft 5 is empty, `transactions=0`, and
`workflow_operations` is empty: the cancelled attempts released their claims, no late result
was recorded, and no financial record was created.

**Explicit retry succeeds.** On the same open screen, **Retry check** was tapped again. After
the real model completed, the panel showed the explicit `A newer stored state or a changed
context is available…` notice; **Refresh suggestion** showed the real no-fit result
(`cancel-retry-result.png`, `cancel-retry-ui.txt`). The pulled database
(`cancel-retry.db`) records `draftId=5 state=RESULT attemptCount=1 snapshotId=7
statusReason=NULL` and a stored `NO_FIT` snapshot (confidence `0.7845`, `staleReason=NULL`);
`transactions=0`. No Confirm was tapped.

## 8. Schema and data

No schema change: the isolated app and every pulled database report `user_version=12`; the
production schema remains 12. The seeded fixture was created from a database the candidate app
itself created. No migration is required. The user’s install was never opened for writing; the
isolated package was uninstalled and the model tar and `/data/local/tmp` files were removed.

## Evidence files

| Path | Content |
| --- | --- |
| `seed_fixture.py` | deterministic status fixture builder |
| `drive.py` | small ADB UI driver (inbox/card/detail screenshots) |
| `seed.db` | the fixture database |
| `status-evidence.db` | the device database after the state/retry/context/manual runs |
| `missing-model.db` | the device database after the real missing-model failure |
| `inbox-top.png` | inbox top with the first needs-decision card |
| `fresh-detail.png`, `fresh-suggestion.png` | high-confidence candidate inspected without selecting |
| `failed-busy.png` | live “Checking a suggestion now” state |
| `real-suggestion.png`, `real-no-fit.png` | real model no-fit output and provenance |
| `context-improved.png`, `context-improved-options.png` | context-driven real suggestion |
| `manual-saved.png` | manual category saved, not confirmed |
| `missing-model-failed.png` | real missing-model failure with Retry/Open model setup |
| `cancel-before.png`, `cancel-before-ui.txt` | failed job before the in-flight cancel action |
| `cancel-after.png`, `cancel-after-ui.txt` | live “Checking a suggestion now” + Cancel check |
| `cancel-observed.png`, `cancel-observed-ui.txt` | post-cancel UI feedback (late answer discarded) |
| `cancel-logcat.txt` | scheduled/cancelled-by-user trace with timestamps |
| `cancel-final.db` | persisted `CANCELLED/USER_CANCELLED`, no snapshot, 0 transactions |
| `cancel-retry-result.png`, `cancel-retry-ui.txt` | explicit retry’s stored no-fit result |
| `cancel-retry.db` | persisted `RESULT` + stored snapshot, 0 transactions |
| `logcat-filtered.txt` | queue-scheduling lines from the real model runs |
