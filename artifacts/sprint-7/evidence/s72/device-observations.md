# S7.2 device smoke — transcribed observations

Device: `AAAJBB4930914141`, Android API 34. Isolated package `io.github.triet4p.aegisf.s72evidence`
(temporary `applicationIdSuffix`, reverted before the final production build; package uninstalled
after the run). The user's own install `io.github.triet4p.aegisf` was only read (its model was
copied read-only) and its allowlist did not contain `com.android.shell`, so every synthetic post
was dropped there before content was read.

Fixture: the isolated package's own allowlist file held `com.android.shell`, notification access
was granted with `adb shell cmd notification allow_listener <component>`, and supported
notifications were posted with `adb shell cmd notification post -S bigtext -t … <tag> <text>`.
Each scenario started with `adb logcat -c`; the lines below are transcribed from those filtered
buffers (`BankNotificationListener`, `BankDraftClassification`, `IntakeRepository`). Database
observations were made by pulling `databases/aegis-ledger.db{,-wal,-shm}` through
`adb exec-out run-as` and querying a copy; those files are kept beside this note.

## 1. Missing model, supported notification (tag `s72m`, 45,000 VND, memo `S72 alpha lunch`)

```text
D BankNotificationListener: onNotificationPosted: package=com.android.shell
D BankNotificationListener: persistOne: dropped notification from com.android.shell (not in allowlist)   ← the user's install
I BankNotificationListener: persistOne: permitted notification from com.android.shell (postTime=1792835514911); storing draft
I BankDraftClassification: intake classification draft=1 scheduled against its revision
I BankDraftClassification: intake classification draft=1 outcome=FAILED
```

`m1` query: `pending_bank_drafts` = 1 row `(1, PARSED, 45000, 'S72 alpha lunch', PENDING, resolved=0)`;
`classification_snapshots` = 0; `workflow_operations` = 0 (the failed request released its key);
`transactions` = 0.

## 2. Privacy rejection and unsupported format

```text
I BankNotificationListener: persistOne: permitted notification from com.android.shell (postTime=1792835559955); storing draft
D BankNotificationListener: persistOne: rejected notification from com.android.shell (reason=SecurityContent)
D BankNotificationListener: persistOne: rejected notification from com.android.shell (reason=GroupSummary)
```

The OTP message produced no draft and no classification line. The unsupported VietinBank text
produced draft 2 and no classification line. `m2` query: drafts `(1 PARSED, 2 UNSUPPORTED_FORMAT)`,
0 snapshots, 0 operations, 0 transactions.

## 3. Duplicate delivery: re-read active posts after reconnect (missing model)

```text
I BankNotificationListener: reconcileNow: active reconciliation requested
I BankNotificationListener: reconcileCurrentActive: active=15, permitted candidates=2
I BankDraftClassification: intake classification draft=1 scheduled against its revision
I BankDraftClassification: intake classification draft=1 outcome=FAILED
```

No new draft; the unsupported post scheduled nothing.

## 4. Real pinned model available (copied from the user's install, never modified)

Tag `s72a`, 62,000 VND, memo `S72 coffee with team`:

```text
I BankNotificationListener: persistOne: permitted notification from com.android.shell (postTime=1792835778050); storing draft
I BankDraftClassification: intake classification draft=3 scheduled against its revision
I BankDraftClassification: intake classification draft=3 outcome=APPLIED
```

`m4` snapshot: `draftId=3 source=NO_FIT candidateIds=1,3,2 probabilities=0.2447,0.0422,0.0602
unknownShare=0.6529 confidence=0.3323 staleReason=null`; one `REQUEST_CLASSIFICATION` row
`APPLIED`; 0 transactions.

## 5. Duplicate delivery with the model available (reconnect re-read)

```text
I BankNotificationListener: reconcileCurrentActive: active=15, permitted candidates=3
I BankDraftClassification: intake classification draft=3 scheduled against its revision
I BankDraftClassification: intake classification draft=3 outcome=NO_CHANGE
I BankDraftClassification: intake classification draft=1 scheduled against its revision
I BankDraftClassification: intake classification draft=1 outcome=APPLIED
```

Draft 3's replay was answered from its recorded request (`NO_CHANGE`, no second operation row,
snapshot unchanged); draft 1 (whose first attempt failed while the model was missing) received its
prediction then. `m5`: exactly one `REQUEST_CLASSIFICATION` row per draft (ids 6, 7);
`draftId=1 source=NO_FIT candidateIds=1,3,2 probabilities=0.0704,0.0256,0.0621 unknownShare=0.8418
confidence=0.5684`; 0 transactions.

## 6. Busy model: two notifications in a row

Cold session released first (>30 s idle). Posted `s72b1` (38,000 VND, `S72 taxi ride`) and
`s72b2` (9,500,000 VND, `S72 monthly rent`) back to back, then pulled the database 3 s later:

```text
m6 drafts: 1,2,3,4,5 all stored (4 = taxi, 5 = rent); snapshots only for 3 and 1
m6 classification ops: 6 APPLIED, 7 APPLIED, 8 PENDING   ← draft 4's inference still running
I BankDraftClassification: intake classification draft=4 scheduled against its revision
```

Both new drafts were durable while the first inference was still running. Afterwards:

```text
I BankDraftClassification: intake classification draft=4 outcome=APPLIED
I BankDraftClassification: intake classification draft=5 scheduled against its revision
I BankDraftClassification: intake classification draft=5 outcome=APPLIED
```

`m7` snapshots: `draftId=4 NO_FIT unknownShare=0.616 confidence=0.273`;
`draftId=5 source=MODEL suggestedCategoryId=3 candidateIds=1,3,2
probabilities=0.0097,0.9054,0.0108 unknownShare=0.0742 confidence=0.7283` (category 3 = Housing).
One inference at a time; 0 transactions.

## 7. Manual category selection wins over automatic work

With the model directory temporarily moved aside (so the first attempt fails fast), posted `s72c`
(120,000 VND, `S72 grocery market`) → draft 6 stored, `outcome=FAILED`. Through the app's own
Review screen the draft was opened, `Food` was selected in the category chooser and **Save
corrections** was pressed (`SAVE_DRAFT_REVIEW` = APPLIED). The model directory was restored and
reconciliation was requested:

```text
I BankNotificationListener: reconcileCurrentActive: active=14, permitted candidates=6
I BankDraftClassification: intake classification skipped: draft=6 already carries the user's category
I BankDraftClassification: intake classification draft=5 scheduled against its revision
I BankDraftClassification: intake classification draft=5 outcome=NO_CHANGE
I BankDraftClassification: intake classification draft=4 … outcome=NO_CHANGE
I BankDraftClassification: intake classification draft=3 … outcome=NO_CHANGE
I BankDraftClassification: intake classification draft=1 … outcome=NO_CHANGE
```

Final `m8` database: draft 6 `PENDING, reviewCategoryId=1, reviewCategoryResolved=1,
reviewRevision=1`, no snapshot and no classification operation row; four snapshots and four
`REQUEST_CLASSIFICATION` rows for drafts 1, 3, 4, 5 (unchanged by the repeats); 0 transactions.
