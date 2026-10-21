# S5.8 cumulative migration and caller cutover

This document records the implemented S5.8 behavior: the migration chain that every
preceding schema task shipped, verified end to end against a real previous-schema
database; the invariants each version added; the failure and recovery behavior of the
chain; and the last callers that still wrote around the app's command and query
boundaries. It also records the decision and release notes needed to review the task
without relying on mixed-ownership files, and where the physical-device evidence lives.

## Scope

The sprint added five schema versions to the one local database, each with its own
migration, each shipped with its own populated-data upgrade smoke:

| Version | Migration | What it added |
| --- | --- | --- |
| 7 | `MIGRATION_6_7` | `capture_messages`: versioned source text and provenance, with the legacy descriptions of the older records backfilled as legacy-origin annotations |
| 8 | `MIGRATION_7_8` | `note_events` and `context_links`: the readings of a capture's spans and their links to a bank draft |
| 9 | `MIGRATION_8_9` | `classification_snapshots`: the stored prediction about a draft, with the dependencies it was made against |
| 10 | `MIGRATION_9_10` | `workflow_operations`: the durable record of a command, its idempotency key, and its outcome |
| 11 | `MIGRATION_10_11` | the compare-and-set `revision` of a ledger record, and the resolution of a note event into a record |

S5.8 is the cumulative proof of that chain. It does not add a version: the database is at
version 11 before and after this task. It verifies that a real version-6 database — the
schema the app itself exported for its version-6 release — migrates through the whole
chain without losing a row, a value, a total, an identity, or a link, and that a failure
inside the chain leaves the older data alone and can be retried.

The second half of the task finishes the caller cutover: the category catalog and the
saved exact-description rules were the last production writes that still went straight
to the storage layer, and they now go through the same command boundary as everything
else. The storage layer itself became `internal`, so there is no public write path left
around the boundary.

## The migration chain

### The fixture is the real historical schema

The permanent test does not create a current database and write an old version number
into it. It reads `app/schemas/io.github.triet4p.aegisf.data.LedgerDatabase/6.json` — the
file the app exported for its own version-6 release — and builds the fixture from the
statements in it: the same tables, the same columns, the same foreign keys, the same
indices, and the same identity hash, with `PRAGMA user_version = 6`.

The fixture is then filled with the shapes the older app stored: an active category, an
archived category, a category that had been deleted before the snapshot was taken (its id
is never reissued and its record keeps its values with only the link cleared), confirmed
expenses, an income, a transfer, an uncategorized record, an expense filed under the
archived category, two saved rules (one of them pointing at the archived category), and
four bank drafts: a pending one with review overrides, a confirmed one linked to its
record, a dismissed one whose payload could not be parsed, and a possible-duplicate pair
that shares one evidence digest under two deliveries.

### What the chain preserves

After the production builder opens the file, the test asserts the state of the older rows:

- **the categories** keep their ids, names, and archived flags, and a deleted category
  does not come back;
- **the records** keep their ids, types, amounts, moments, descriptions, category links,
  confirmation state, provenance, and creation and update times, and every stored record
  starts at revision zero, because a record that was never corrected has been corrected
  zero times;
- **the totals** are exact: the confirmed expenses of the fixture are 100,000 VND over
  three records, and the income and the transfer are reported apart from the spending
  rather than inside it;
- **the saved rules** keep their pattern keys and their category ids, and a rule whose
  category is archived no longer answers;
- **the drafts** keep their delivery identities, their evidence digests, their review
  states, their review revisions, their review overrides, their confirmed-draft link, and
  their possible-duplicate pairing; a pair of equal evidence under two deliveries stays
  two candidates;
- **the migrated descriptions** are truthful about their provenance: a legacy description
  keeps its text and its link to the record it describes, and its authoring instant and
  timezone stay unknown, because the older schema recorded neither and the migration
  invents neither;
- **the tables the newer versions introduced** start empty, because versions 7 to 10 held
  no reading, no link, no prediction, and no command record to carry over.

The constraints the newer versions rely on are checked as well: the foreign-key check is
clean, the unique index on the source identity still refuses a second claim of the same
identity, and the indices the newer tables need exist.

### The upgraded database still obeys the newer rules

The upgrade is not only a readable file. The test then uses the migrated database through
the production command boundary:

- a correction made against the revision the caller read applies, and a second correction
  against the old revision is a conflict that writes nothing;
- a repeated request under one idempotency key is recorded once and adds no second
  record;
- a note that was recorded by hand is resolved against the record it produced, and the
  same note cannot be recorded a second time.

### The failure and the recovery

The failure is injected inside the chain rather than before it: the fixture also holds a
`note_events` table that already carries the column the last migration adds, so the last
step fails on a duplicate column while the upgrade transaction is open.

The test then asserts that the upgrade rolled back **whole**:

- the file is still at version 6, with its identity hash unchanged;
- its categories, records, drafts, rules, and totals are exactly what they were;
- none of the tables the newer versions introduced is left behind, and the column the
  failed step added is gone again — the successful statement of the same step was rolled
  back with it;
- the foreign-key check is clean.

Nothing was deleted: the database file is still there with its rows, which is the point of
a failure that is recoverable rather than one that wipes the user's history. After the
injected defect is removed, the **same file** opens again and the whole chain runs to the
end with every row preserved.

The builder has no `fallbackToDestructiveMigration`, and that is the reason the failure
is recoverable: a version the app cannot upgrade to is a loud failure, not a deleted
database.

## The caller cutover

### What was still writing around the boundary

Every remaining production caller was inspected against the shared bounded query surface
and the typed command boundary. The last writes that went straight to the storage layer
were:

| Caller | The write it made | Now |
| --- | --- | --- |
| `CategoryManagerScreen` | add, rename, and archive a category; read the impact of a removal; remove a category | the six new commands, and the impact through the query surface |
| `TransactionEditorScreen` | save and remove the saved exact-description rule of one description | the two new rule commands, and the rule lookup through the query surface |
| `MainActivity` | handed the storage layer to both screens | hands the command and query services only |

The notification intake was inspected and kept: it is the app's own path for a permitted
bank notification, it writes only pending drafts and the immutable bank-evidence
reference beside them, and it is not exposed to any other caller. The classifier was
inspected and kept: the review screen already asks the boundary for a classification and
the editor's own suggestion path reads and infers without writing.

### The new commands

The category and rule commands are the user's own decisions, so they are available to the
user's scope alone, and every one of them is refused for another caller before anything
is read or written.

| Command | What it does | The refusal it gives |
| --- | --- | --- |
| `addCategory` | adds a category and returns its stable id | a conflict when another active category carries the name; a clarification when the name is empty |
| `renameCategory` | renames a category, keeping its id and the records filed under it | a conflict on a duplicate active name; `NotFound` for an unknown category |
| `archiveCategory` | takes a category out of the assignable pool and keeps the records filed under it | `NotFound` for an unknown category; a no-change answer for an already archived one |
| `deleteCategory` | removes a category for good, keeping every record and its totals, and reports the counts it applied | `NotFound` for an unknown category |
| `rememberCategoryRule` | saves the user's rule for one exact description and one active category | a clarification for an empty description; `NotFound` for an unknown category; a refusal for an archived category; a no-change answer when a rule already stands |
| `removeCategoryRule` | removes that one rule and nothing else | a clarification for an empty description; a no-change answer when there is no rule |

Every command carries the idempotency key of the request, so the same request under the
same key writes one change. The screens mint a key per user action — the category dialog
holds one for as long as it is open, and the editor mints one per rule action — so a
retried action cannot write twice, while a later, genuinely new action with the same
content is a new request and not a replay.

The changes are recorded in the same durable operation table as every other command, and
the encoding of the change is extended so a replayed answer names the same rows.

### The reads

Two reads that the screens used to take from the storage layer moved to the query
surface: the counts that say what removing a category would change, and the saved rule
of one exact description. Both are reads, and the query surface is where reads live.

### The storage layer is closed

`LedgerRepository` is now `internal` to the app module, and the container keeps its one
instance private. The public API of the app is therefore the command boundary for writes
and the query boundary for reads; there is no alias, no deprecated overload, and no
second convention left behind.

## Privacy, authority, and the user's own overrides

Nothing in this task changes what the app stores or who may change it:

- the bank evidence of a permitted notification stays immutable and separate from
  user-authored text, and a legacy description keeps its unknown authoring instant and
  timezone rather than a fabricated one;
- the caller's scope is still supplied by trusted application code and is never read from
  a request, a model, or the stored data; the category and rule commands refuse another
  caller before anything is read;
- a category that was archived while a form was open is still refused for a new
  assignment, and the record that already carried it keeps it;
- a removal still keeps every record, its amount, its type, its moment, its description,
  and the totals computed from them, and it still reports what it cleared;
- a saved rule is still never learned automatically, never silently replaced, and never
  applied to a stored row.

## Decision and release note

**S5.8 decision:** the migration chain is verified as one chain, against the schema the
app exported for its own older release, with the failure behavior proven inside the
upgrade transaction rather than in front of it; and the last direct write paths are
closed by making the storage layer internal, so the app's public surface is the command
boundary for writes and the query boundary for reads. The legacy descriptions of the
older records keep their unknown authoring instant and timezone: the honest provenance of
a record whose origin the old schema did not record is "unknown", and a migration that
invented one would be a lie the user could not detect.

**S5.8 release note:** The local database now migrates from the previous app release
through every intermediate version without losing a record, a total, a category, a saved
rule, or a bank draft, and an interrupted or failing upgrade leaves the existing data
untouched and can be retried. Category management and the saved category rules are now
handled by the same single application boundary as the rest of the app's writes, with
the same authority checks, the same protection against repeated requests, and the same
explicit refusal messages.

## Verification boundary

The host evidence is the permanent test suite: 43 classes and 355 tests, of which the new
`MigrationChainTest` (three cases) and `CategoryCommandServiceTest` (seven cases) belong
to this task.

### The physical device, on an isolated package

The device evidence lives in
`artifacts/sprint-5/workflow-replan/workers/S5.8-replan-a1.md` (the migration chain and
its failure and recovery) and
`artifacts/sprint-5/workflow-replan/workers/S5.8-replan-a2.md` (the app's own intake
path and the three real screens). On an isolated package on an ALT-LX2 / API 34 phone
with synthetic data only, the version-6 fixture migrated through the whole chain with
every row, the exact 100,000 VND total, the draft-to-record link, and the truthful legacy
provenance preserved and a clean foreign-key check; the injected failure in the last
migration rolled the chain back whole, left the version, the rows, and the totals
untouched, and the same file migrated successfully once the defect was removed. The pinned
Laya model was copied read-only out of the user's own package into the isolated package on
the device, and the copies are byte-identical (the same SHA-256 values the S5.6 round
recorded); the user's own package and model files were not modified.

The second round of the same device work proved the boundary itself on the phone:

- **the app's own intake path** — the production repository the notification listener
  calls — turned one permitted payload into one pending draft with exactly one
  bank-evidence source row that carries the memo alone, the bank's own post instant, and
  no timezone; a replay of the same delivery stored nothing at all; the same evidence
  delivered again under a different post time became its own draft, flagged as a
  possible duplicate of the first rather than dropped; the confirmed total did not move;
  and the allowlist gate permitted the allowlisted package only, refusing another package
  and a revoked grant.
- **the Categories screen** — adding, renaming, archiving, reading the impact of a
  removal, and removing a category, all through the command boundary. The impact of
  removing a category with records named what the removal changes and what it does not:
  the records stay with the same amount, date, and description and become uncategorized,
  one remembered rule goes with the category, two pending drafts lose their pre-set
  category, and the monthly totals do not change.
- **the manual entry screen with the real pinned Laya runtime** — the real ONNX Runtime
  session loaded the pinned model and produced a ranked distribution over the current
  active categories, the ranked category was selected at the user's request, and the
  saved record landed as one manual confirmed expense.
- **the bank-draft review screen** — the pending draft was opened, the preview dialog
  bound the exact values that would be stored, and confirming produced exactly one
  ledger record; the possible-duplicate candidate stayed pending and was not confirmed
  with it.

The month summary on the home screen was the exact check on all of it: 100,000 VND over
three confirmed expenses after the migration, and 157,000 VND over five confirmed
expenses after the manual entry and the draft confirmation, with the category buckets
adding up to the same figure.

One limit is stated rather than papered over: the **system-bound** notification callback
is not exercised. The notification-access consent on the phone is held by the user's own
package, not by the disposable package the round used, and no consent was changed to
manufacture one. The claim is therefore about the app's own service path — the path the
listener calls — and not about the operating system's delivery of a notification.

### The cleanup

Everything the device round created was removed again: the isolated package was
uninstalled and is gone from the package list, the throwaway driver, the debug manifest,
and the debug assets were deleted from the repository, the temporary application-id
suffix was removed from the build file, the database copies taken off the phone for
inspection were deleted from the device, and the rebuilt debug APK reports the normal
application id `io.github.triet4p.aegisf` with no driver activity in its merged
manifest. The user's own package was never uninstalled, cleared, or reset, and its model
files still read with the same sizes, the same SHA-256 values, and the same dates after
the round as before it.

## S5.9 connected migration and workflow proof

Attempt `S5.9-replan-a1` repeated the failure/recovery exercise on the same isolated
ALT-LX2/API-34 fixture that carried the connected workflow; it did not rely only on the
prior S5.8 record. The seed used the exported v6 schema and identity hash
`ff97e68ec56591d684c3e5fd371fe854`, with two categories, five ledger rows, four drafts,
two rules, 100,000 VND confirmed expenses, and 1,125,000 VND across all record types.
A deliberately conflicting `note_events.resolvedTransactionId` column caused the real
Room migration chain to fail. After a new process reopened the same file, the version-6
user version/hash, all five ledger rows, all four drafts, 100,000 VND expense total, and
absence of new migration tables/transaction revisions were verified unchanged. Removing
only that injected fault table let the production builder upgrade the same file to v11:
five records, four drafts, eight migrated source captures, two categories, two rules,
100,000 VND confirmed expenses, 1,125,000 VND across all types, and zero foreign-key
violations.

The one-file v11 fixture then carried the S5.9 intake, two-reading/link/classification
workflow, unlink and source-edit race, user category choice, authorization denials, and
one explicit bank confirmation end to end. The final ledger had six rows, 145,000 VND
confirmed expenses, 1,000,000 VND income, 25,000 VND transfer, and 1,170,000 VND across
all types. Same-key confirmation replay after process recreation did not add a row.
Actual MainActivity manual-entry/Laya selection and bank-review preview/cancel screens
also ran on this fixture; the temporary manual row was deleted through the user command
boundary, restoring the final totals.

**S5.9 release note:** The existing schema-v11 commands were exercised as one durable
workflow across real process recreation: retries and denied callers made no ledger
changes, a stale native prediction could not replace edited context, and one explicit
user confirmation produced one bank record. No S5.9 schema change was required. The
system-bound notification listener callback remained unexercised; no notification
access consent changed. Exact phase PIDs, model hashes, UI observations, and cleanup are
in `artifacts/sprint-5/workflow-replan/workers/S5.9-replan-a1.md`.

Implementation references: `app/src/main/java/io/github/triet4p/aegisf/data/LedgerDatabase.kt`,
`WorkflowCommandService.kt`, `WorkflowCommandTypes.kt`, `WorkflowOperationEntity.kt`,
`WorkflowQueryService.kt`, `LedgerRepository.kt`, `AppContainer.kt`,
`app/src/main/java/io/github/triet4p/aegisf/ui/CategoryManagerScreen.kt`,
`TransactionEditorScreen.kt`, `app/src/main/java/io/github/triet4p/aegisf/MainActivity.kt`,
`app/src/test/java/io/github/triet4p/aegisf/data/MigrationChainTest.kt`, and
`app/src/test/java/io/github/triet4p/aegisf/data/CategoryCommandServiceTest.kt`. The
frozen source, privacy, and authority contract is `docs/sprint-5-workflow-contract.md`;
the command contracts are `docs/sprint-5-preparation-commands.md` and
`docs/sprint-5-financial-commands.md`; the read contracts are
`docs/sprint-5-shared-queries.md`.
