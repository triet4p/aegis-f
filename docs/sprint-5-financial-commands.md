# S5.7 financial commands

This document records the implemented S5.7 behavior: the one boundary that owns every
financial decision the user makes, the commands it offers, the preview that binds a
confirmation to the exact fields it would write, the resolution of a note the user
recorded by hand, the schema change it ships, and what it deliberately does not do. It
also records the privacy and authority boundary, the decision and release notes needed to
review the task without relying on mixed-ownership files, and where the physical-device
evidence lives.

## Scope

Before this task the app wrote its financial data through two repositories that each
owned their own transaction and each decided its own rules: `LedgerRepository` stored the
manual records, their corrections, and their removals, and `BankDraftReviewRepository`
stored the bank's review corrections, the dismissal of a draft, and the atomic
transition that turned a draft into a confirmed ledger record. Both were public
application API. Neither knew who was asking, neither carried an idempotency key, and a
caller could therefore write a ledger record, confirm a draft, or remove a record
without any authority check at all.

This task replaces both with one write surface, the financial half of the existing
`WorkflowCommandService`:

- store one manual ledger record;
- correct the user-editable fields of a stored record;
- remove a stored record;
- store the user's explicit edits on a still-pending bank draft;
- look at what a confirmation of a bank draft would write;
- confirm a bank draft as a ledger record;
- dismiss a still-pending bank draft.

Every one of them is the user's own decision, so every one of them is available to the
user's scope alone. The preparation commands of the earlier task — the captures, the
readings, the links, and the classification — stay where they were, on the same service,
with the same caller's scope, the same idempotency rules, and the same structured
outcomes. The two families share one boundary, one set of rules for the fields a
financial write stores, and one recorded-operation table.

The bank-draft review screen, the manual editor, and the record detail view were all
migrated onto the new commands, and the repositories they used are gone: `BankDraftReviewRepository`
was deleted, and the ledger's own transaction writes became internal, so the boundary is
the only public way to change the money.

## The command boundary

### The commands

| Command | What it does | What it never does |
| --- | --- | --- |
| `saveManualTransaction` | Stores one confirmed manual record, optionally against the note event the user recorded it from | It never writes a draft, never promotes a record, and never records the same note twice |
| `correctTransaction` | Replaces the amount, the moment, the description, the type, and the category of a stored record | It never rewrites the record's id, its creation time, its confirmation, or its provenance |
| `deleteTransaction` | Removes one stored record and the migrated legacy description that belonged to it | It never removes a draft, and it never removes a record the caller did not read |
| `saveDraftReview` | Stores the user's explicit edits — amount, date, time, memo, type, category — on a still-pending draft | It never writes a ledger record, and it never rewrites the bank's own evidence |
| `previewDraftConfirmation` | Reads what a confirmation would write: the exact fields, the revision they were read from, and the binding that names them | It never writes anything, and it is never answered from a stored record |
| `confirmDraft` | Turns one pending draft into exactly one confirmed ledger record | It never writes a second record for one draft, and it never writes a record the preview no longer describes |
| `dismissDraft` | Ends a pending draft without a ledger record | It never reopens the draft and never writes a record |

### The caller's authority

The financial commands are the user's own decisions, so they are the user's alone:

- the scope is supplied by trusted application code — the screen that owns the user's
  action — and is never read from a model's arguments, from a request body, or from
  anything the stored data says about itself;
- a caller that is not the user is refused with `USER_DECISION_REQUIRED`, and the refusal
  comes **before** anything protected is read: before the target row is looked up, before
  the idempotency record is looked up, and before any write. The refusal is the same
  whether or not the row it names exists and whether or not its key was ever used, so a
  caller outside its own scope cannot tell a row that does not exist from a row it may
  not touch;
- a refusal writes nothing and leaves no record of the refused request behind.

This is the rule the earlier task froze for the financial decisions: *financial
confirm/correct/delete stay user-only*. The boundary now enforces it in one place instead
of leaving it to each caller.

### Idempotency

Every financial command carries an idempotency key, and the key is recorded durably with
the outcome. The rules are the ones the preparation commands already had:

- **the same key, the same caller, the same request** is answered from that record: the
  state is not changed a second time, whatever the number of attempts, the number of
  processes, or the number of callers that race for it. The answer is a `NoChange` and it
  carries the change that was made the first time — a repeated confirmation names the
  ledger record it made;
- **the same key with a different request** is a `Conflict`. The key names one request,
  and a key that was used for something else is not this request's key;
- **the same key from a different caller** is a `Conflict`, with the same words, so the
  answer never says whose record holds the key;
- **a refused request records nothing**, so a corrected request may use the key;
- the change and the record of the change are written in the same transaction, so a
  command is either fully applied and recorded or not applied at all.

### Revisions

A financial command that names the row it read also names the revision it read:

- a bank draft is confirmed or dismissed under its own `reviewRevision`, and a draft
  that moved on is a `Conflict`;
- a ledger record is corrected or removed under its own `revision`, and a record that
  moved on is a `Conflict`. The revision is a real column of the record, so it moves on
  with every correction and nothing else;
- a note event is recorded under its own revision, so a note that changed under the
  caller is a `Conflict` and the occurrence is not recorded against a reading the caller
  never saw. The revision is part of the request itself — a request names a note *and* the
  revision of the reading it was written from — so there is no shape of the request that
  names a note without it, and a record can never be attached to a reading the caller
  never read. A reading that no longer holds the occurrence at all — the note's own text
  was edited or deleted — is refused outright: there is nothing there to record against.

A stale request never overwrites what the caller never saw, and it never writes at all.

### The preview and its binding

The confirmation of a bank draft is a two-step decision, and the two steps are bound
together:

1. `previewDraftConfirmation` resolves the review's wall-clock text under the device's
   own zone policy and reports the exact fields a confirmation would store: the whole-dong
   amount, the instant the record happened, the description, the type, and the category.
   It also reports a **duplication risk** when a stored record states the same money on
   the same day. The preview is a read: it changes nothing, it is not recorded, and it is
   never answered from a stored record, so the fields it shows are always the fields the
   app would write now.
2. `confirmDraft` carries the binding the preview produced. The binding names the exact
   fields, the revision of the draft, the state of the chosen category, and the source
   the draft was read against — the note event the draft is linked to, with that
   reading's own revision and its own resolution state.

A confirmation whose binding no longer matches what the app would write now is a
`Conflict`, and it writes nothing. So a preview that went stale cannot overwrite newer
data: a draft whose review moved on, a category that was archived or renamed after the
preview, and a note whose reading moved on all refuse the confirmation.

The user's screen shows the preview before it acts on it: the confirmation dialog names
the exact amount, type, moment, description, and category that will be stored, and it
names the duplication risk when there is one.

### The outcomes

The financial commands use the same vocabulary as the preparation commands:

| Outcome | What it means |
| --- | --- |
| `Applied` | The command changed the stored state; the change names the row and the revision it left behind |
| `NoChange` | The command changed nothing: the request was already satisfied, or the state was already what the request asked for |
| `NeedsClarification` | The request is missing something only the caller can state — the field and the reason are named |
| `Conflict` | The request was made against a state that has moved on |
| `NotFound` | The named row does not exist |
| `Denied` | The request was refused by a rule; the rule is named |
| `Cancelled` | The request was cancelled before it was applied |
| `Failed` | The request could not be carried out; the reason is stated |

The two new refusals are `CATEGORY_UNAVAILABLE` — the chosen category is gone or
archived, so the field cannot be written — and `EVENT_ALREADY_RECORDED` — the note was
already recorded, so it is spent.

### A draft that is already decided

A bank draft is terminal once it has been confirmed or dismissed, and a request that
arrives after that changes nothing. The answer must then describe the state that is
*stored*, not the state the request asked for:

- a **confirmed** draft answers with `DraftConfirmed`, naming the record it made;
- a **dismissed** draft answers with `DraftDismissed`;
- the corrections of a decided draft are not stored, and the answer says so;
- the ledger is untouched: a dismissal of a confirmed draft writes no record, and a
  confirmation of a dismissed draft writes no record.

This holds for every terminal path — the review, the confirmation, and the dismissal —
and it holds for a repeated request as well: the recorded answer of the first attempt is
the one that is replayed, so a retry keeps naming the state the draft is really in.

## The note the user recorded by hand

A note the user saved may describe an occurrence that no bank notification describes.
The user can then record that occurrence by hand, and the record is bound to the note:

- `saveManualTransaction` carries an optional source: either no note at all, or the note
  event **and** the revision of the reading the caller read. The two travel together, so a
  request cannot name a note without the reading it was written from;
- the note must exist, must not be resolved already, must still hold the reading the
  caller read, and must not hold an active link to a bank draft — a note whose bank
  notification is waiting is that notification's business, and a note that is already
  recorded is spent;
- a note whose reading moved on under the caller is a `Conflict`, and a note whose
  reading was cleared because its own text changed is refused with
  `EVENT_UNAVAILABLE`: in both cases the record is written without the note, or not at
  all;
- the record and the resolution of the note are written in the same transaction, so a
  record without a resolution and a resolution without a record never exist;
- the resolution is stored, so it survives a restart.

A resolved note is spent. The link commands refuse it: `proposeLink` and `attachLink`
answer `EVENT_ALREADY_RECORDED` for a resolved reading, so a bank notification that
arrives later cannot consume the same occurrence again. The refusal is the same before
and after a restart, because the resolution is in the database.

Removing the record removes the resolution with it, in the same transaction: the
occurrence is no longer recorded, so the note may be recorded again.

### The late bank evidence and the duplication risk

A bank notification that arrives after the user recorded the same money by hand is
**evidence**, and it is kept: the draft stays pending, its own evidence and its review
state are untouched, and nothing is added to the ledger without the user's explicit
confirmation.

What the app does is *surface* the risk. The preview of that draft's confirmation looks
for a stored confirmed record that states the same whole-dong amount on the same calendar
day, and reports it — with the record's own moment and description, and whether that
record was itself written from a note. The confirmation dialog shows it plainly: the
user decides whether the notification describes money that is already recorded.

The risk is a fact, never an action: the app never merges the two records, never
removes one of them, and never writes a second one on its own. There is no matching
engine here, and none is introduced: the rule that a resolved note may not be linked
again is what keeps a later arrival from consuming the same occurrence.

## The schema

The task ships one schema change, from version 10 to version 11:

- a new column `revision` on `transactions`, the compare-and-set revision of a ledger
  record;
- two new columns on `note_events`, `resolvedTransactionId` and `resolvedAtEpochMillis`,
  the resolution of a note into a ledger record;
- an index on `note_events.resolvedTransactionId`;
- `MIGRATION_10_11`, which adds exactly those columns and that index.

The columns are added in place, so no stored row is copied, no identity is re-derived, and
no reference is left dangling. The revision starts at zero for every stored record,
because a record that was never corrected has been corrected zero times. The two
resolution columns start empty, because version 10 could not record an occurrence the user
wrote down by hand. Nothing that version 10 already held is rewritten: the captures, the
event readings, the links, the bank drafts, the categories, the rules, the ledger
records, the stored predictions, and the recorded commands keep their rows, ids, and
values, and the confirmed totals computed from them are unchanged by the upgrade. There
is no destructive fallback: a version mismatch still fails loudly.

## Privacy and authority boundary

- The command boundary stores no notification body, no OTP or security text, no account
  identifier, and no balance. The intake rules that keep that content out of the database
  are unchanged.
- The operation record holds a digest of a request, never its text; the change names rows
  and revisions.
- The duplication risk names a stored record, its amount, its moment, and its
  description — the user's own data, read back to the user.
- A refused request writes nothing and leaves no record of itself behind.
- No financial command reads anything a model wrote, and no model may make one: the
  financial commands are refused for every caller that is not the user.
- The surface is local to the app process. There is no HTTP server, no model-facing
  adapter, and no new screen beyond the note picker and the confirmation dialog the
  commands needed.

## Decision and release note

**S5.7 decision:** One write surface owns every financial decision the app can make, and
the financial decisions are the user's alone. Manual records, corrections, removals, the
explicit edits of a bank review, the confirmation of a bank draft, and the dismissal of
one are now commands on the same boundary the preparation commands live on: they take the
caller's scope from trusted application code, an idempotency key, and the revision the
caller read, and they answer with the same structured outcomes. Another caller is refused
before anything is read or written, with the same refusal whatever it named. A repeated
request changes nothing; a request made against something that has since changed is
refused instead of overwriting it; and a refusal writes nothing at all.

**S5.7 second decision:** A confirmation is bound to the fields it would write. The
preview resolves the wall-clock text under the device's own zone policy and names the
exact amount, type, moment, description, and category; the confirmation carries that
binding, and a binding that no longer describes what the app would write now is a
conflict. A draft whose review moved on, a category that was archived after the preview,
and a note whose reading moved on therefore refuse the confirmation instead of writing
over newer data. The preview also reports a stored record that states the same money on
the same day: the risk is shown to the user, and the app acts on nothing.

**S5.7 third decision:** A note the user records by hand is spent once it is recorded.
The record and the resolution of the note are written in one transaction, the resolution
is stored so it survives a restart, and the link commands refuse a resolved note — so a
bank notification that arrives later cannot consume the same occurrence a second time.
The late notification itself is kept as evidence: it stays pending, and the duplication
risk it carries is surfaced in the confirmation preview rather than resolved by the app.
Removing the record releases the note again, in the same transaction.

**S5.7 fourth decision:** A record is written against the *exact* reading the user
picked. A request that names a saved note names the revision of the reading it was read
from as well, so the two cannot be separated: a note that changed under the user is
refused instead of being recorded against a reading they never saw, and a note whose text
was edited or deleted — so that the reading no longer holds the occurrence — is refused
outright. A record the user wrote down with no note behind it is stored exactly as
before.

**S5.7 fifth decision:** A bank draft that is already decided answers with the state it
is really in. A dismissal of a confirmed draft, a review of a decided draft, and a
confirmation of a dismissed draft all name the stored state — confirmed, with the record
it made, or dismissed — instead of the state the request asked for. Nothing is written on
any of them, and a repeated request keeps naming the same stored state.

**S5.7 release note:** Recording money, correcting a record, removing a record, and
deciding what to do with a bank notification now go through one set of commands instead
of several independent ones. A repeated request — after a restart, after a retry, or
from two places at once — changes nothing instead of doing the same thing twice, and
confirming one notification twice still produces one record. Before a notification
becomes a record, the app now shows exactly what will be stored and warns when a record
of the same money already exists; if anything changed in the meantime, the confirmation
is refused instead of overwriting it. A note you record by hand is remembered as
recorded, so a later notification of the same money will not be matched to it again. You
can also record money straight from one of your saved notes: the record is then written
against the exact note reading you picked, and if that note changed or was edited in the
meantime the record is refused rather than filed against the wrong reading. A bank
notification that was already decided now says so — confirmed or dismissed — instead of
one answer for both, and nothing is written on any of those answers. Nothing about your
existing records changes: the same database file with the same notes, categories, drafts,
and confirmed records, and the same exact whole-dong totals.

## Verification boundary

The permanent host tests are `data/FinancialCommandServiceTest.kt` and
`data/FinancialCommandMigrationTest.kt`. They pin the boundary's observable behavior
against the real Room file, not against the statements that implement it:

- two confirmations of one draft under different keys write one ledger record;
- a repeated confirmation under the same key changes nothing after a restart, and it
  names the record the first confirmation made;
- concurrent confirmations of one draft write one ledger record;
- a preview that went stale is refused, and a category archived after the preview is
  refused, and neither writes a record;
- an agent is refused on the confirmation, the dismissal, the review edits, the
  correction, the removal, and the manual record, and none of them writes anything;
- a manual record is written once under one key, two callers racing for one key write
  one record, and the same key with another request is a conflict;
- a correction keeps the record's identity, its confirmation, and its provenance, and a
  correction made against a row that moved on is a conflict;
- an archived category may be kept by the record that already carries it, another
  archived category is refused, and deleting a category keeps the record and the exact
  total;
- a record written from a note resolves the note, the note is spent, and a later bank
  notification may not be linked to it — before and after a restart;
- the late notification is kept, the duplication risk is surfaced, and no second record
  is written;
- a note that is already recorded is not recorded again, and removing the record releases
  the note;
- a record written from a note is bound to the reading the caller read, a note that moved
  on under the caller is refused, and a note whose reading was cleared is refused as
  well — neither writes a record and neither spends the note;
- a manual record with no note behind it is stored, names no note, and leaves the waiting
  note untouched;
- the request itself cannot name a note without the revision of its reading;
- a dismissal of a confirmed draft names the confirmed state and the record it made, a
  review of a decided draft names the state the draft is really in, a confirmation of a
  dismissed draft names the dismissal, and a repeated dismissal keeps naming the same
  stored state — and none of them writes a record;
- a dismissed draft is terminal and never becomes a record;
- the recorded operation names the request and does not hold its text;
- the populated version 10 upgrade preserves every stored row and the exact total, the
  new columns are usable in the upgraded file, and a failed upgrade rolls back with the
  version 10 file and its data intact.

Where the evidence lives:

- `artifacts/sprint-5/workflow-replan/workers/S5.7-replan-a1.md` — the first attempt: the
  commands themselves, the migration, the host suite, and the first device round;
- `artifacts/sprint-5/workflow-replan/workers/S5.7-replan-a2.md` — the correction attempt:
  the binding of a record to the exact reading it was written from, the terminal draft
  states, their host tests, and the second device round;
- `artifacts/sprint-5/workflow-replan/reviews/S5.7-replan-r1.md` — the evidence review of
  the first attempt, whose two findings this attempt answers.

Implementation references:
`app/src/main/java/io/github/triet4p/aegisf/data/WorkflowCommandService.kt` (the
financial commands), `FinancialCommandTypes.kt` (the requests, the preview, and the
outcomes), `FinancialFields.kt` (the rules for the fields a financial write stores),
`LedgerRepository.kt` (the ledger's own writes, now internal),
`BankDraftReviewDao.kt` (the atomic draft-review transitions),
`EventContextRepository.kt` (the refusal of a resolved note),
`TransactionDao.kt` and `NoteEventDao.kt` (the revision, the resolution, and the
duplication check), `WorkflowQueryService.kt` (the bounded read of the notes that wait
to be recorded), `LedgerDatabase.kt` (the `MIGRATION_10_11`), and the migrated screens
`ui/TransactionEditorScreen.kt`, `ui/TransactionDetailScreen.kt`, and
`ui/BankDraftReviewScreen.kt`. The frozen source, privacy, and authority contract is
`docs/sprint-5-workflow-contract.md`, and the preparation commands this task extends are
`docs/sprint-5-preparation-commands.md`.
