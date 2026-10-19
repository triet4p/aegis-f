# S5.3 event interpretation and context-link persistence

This document records the implemented S5.3 persistence behavior, its privacy and authority
boundaries, the v7-to-v8 migration, and the decision and release notes needed to review the
task without relying on mixed-ownership files. It describes the code as implemented and links
the physical-device evidence in `artifacts/sprint-5/workflow-replan/workers/S5.3-replan-a1.md`;
independent evidence review and the separate Bronze checkpoint remain gates.

## Scope and stored model

Room database `aegis-ledger.db` is now schema version 8. Two tables were added; nothing that
version 7 held was rewritten.

`note_events` stores one interpretation of one occurrence inside a capture's text. Its columns
are `id`, `captureId`, `eventIdentity`, `spanStart`, `spanEnd`, `normalizedText`, `amountVnd`,
`merchant`, `anchorDate`, `anchorTime`, `anchorTimezone`, `intervalEndDate`, `intervalEndTime`,
`extractionVersion`, `unresolvedReason`, `status`, `revision`, `sourceRevision`, and
`createdAtEpochMillis`. A capture may own several events, so one note can describe several
real-world occurrences.

`context_links` stores the association between one event and one pending bank draft. Its
columns are `id`, `eventId`, `draftId`, `state`, `evidence`, `revision`, `userOverride`,
`activeEventId`, and `createdAtEpochMillis`.

### Identity, span, and anti-duplication

`eventIdentity` is a SHA-256 digest over the capture's own source kind, source identity, and
the source span, following the length-prefixed canonical form the delivery and evidence
identities already use. It is derived from the capture's stable source identity and the span,
never from the row id and never from the reading's text, so it survives a restart, a
re-extraction, and the removal of the row it was first stored under.

One span of one capture therefore holds one reading. The unique index on
`(captureId, eventIdentity)` is what keeps a repeated extraction of the same span from
appending a blind second copy. Re-stating the same reading writes nothing and reports no
change; stating a different reading for the same span is an explicit revision under
compare-and-set on the event's own revision.

### Extracted fields, time anchor, and ambiguity

`amountVnd`, `merchant`, and the time anchor or interval are optional: each is null when the
source did not state it. A missing amount is never a zero, a missing time is never "now", and
a missing merchant is never invented. A reading that states no amount must say why in
`unresolvedReason`; the stored `status` is then `UNRESOLVED` and the reason travels with the
reading. The three states are `INTERPRETED` (the reading is current and complete enough to
describe one occurrence), `UNRESOLVED` (required information is missing or ambiguous), and
`NO_MATCH` (the reading is complete but no stored draft matches it). Link state is stored
separately, so a draft's pending/confirmed/dismissed lifecycle is never collapsed into the
reading's status.

A time anchor is either a point (`anchorDate`, `anchorTime`, `anchorTimezone`) or an interval
that starts at the anchor and ends at `intervalEndDate`/`intervalEndTime`. The stated grammar
is the app's existing `YYYY-MM-DD` and `HH:mm` grammar, and a stated zone must be a zone this
device knows. A time without its date, an interval without its start, an interval that ends
before it starts, and an unknown zone are all refused.

### Provenance and revision

`extractionVersion` records which extraction produced the reading and `sourceRevision` records
the capture revision the reading was taken from. `revision` is the compare-and-set revision
for explicit changes to the reading. A reading is only accepted against the capture revision
the caller names: when the capture has moved on, the reading is refused as stale instead of
being written against text the caller never saw.

## Link states, cardinality, and anti-duplication

`state` is one of `PROPOSED`, `ACCEPTED`, and `REJECTED`, and it is stored rather than inferred
from what else exists.

- A refused link stays refused. It survives restarts, retries, and re-processing, and no
  statement in the storage layer moves a link out of the refused state: proposing the same
  pair again reports the refusal, accepting it reports the refusal, and removing it reports
  the refusal, because removing a refusal would recreate the very link the user refused.
- A refusal is not the same as "no link". Several independent events may describe the same
  draft, and one event may be proposed for a different draft after its previous proposal was
  refused.
- The pair `(eventId, draftId)` is unique, so one event never holds two rows for the same
  draft, and the pair is the link's identity across retries and restarts.
- One event holds at most one active draft. A second active link for the same event is
  refused in the application layer, and the unique index on `activeEventId` — which carries
  the event id while the link is proposed or accepted and is null once it is refused — refuses
  it in the database itself.
- A user-accepted link is a user choice. A later extraction may not silently replace the
  reading behind it: the replacement is refused until the link is unlinked or the source
  changes.

## Source edit and delete semantics

Editing or deleting the text of a capture ends what was read from that text, in the same Room
transaction as the change itself:

- the derived content of every event of that capture (the normalized text, the amount, the
  merchant, and the time anchor or interval) is cleared, so no copy of changed or deleted text
  survives, and the event becomes unresolved with the reason `SOURCE_EDITED` or `SOURCE_DELETED`;
- the active links that described those readings are removed with them, because they described
  a reading that no longer exists;
- the event rows themselves stay, because their identity and the span they cover are the
  durable record of what was read from that text, and their foreign key to the capture stays
  valid because a deleted capture is a text-free tombstone rather than a removed row;
- a refused link stays, because the user's decision about that draft is not part of the text.

The confirmed ledger is untouched by all of this. Storing, reading, linking, refusing, and
unlinking never write a ledger record, and the drafts' own review state, revision, and ledger
link are not changed by a link decision.

## Privacy boundary

This task stores only what the user already saved: an interpretation of a capture the user
kept. It stores no notification body, no OTP or security text, no account identifier, no
balance, and no model prompt, tool trace, or hidden reasoning. The derived reading of a
capture is a copy of that capture's own text, and it is removed together with the text it came
from. The bank evidence reference stays immutable, and no link decision changes a bank's
stated amount, moment, memo, or direction.

## v7-to-v8 migration and rollback

`LedgerDatabase.MIGRATION_7_8` creates `note_events` and `context_links` with their indices
and foreign keys, and backfills nothing: version 7 stored no event reading and no link. The
migration does not rewrite the categories, rules, drafts, ledger records, or captures that
version 7 held, so their ids, source identities, revisions, and values — and the exact
confirmed totals computed from them — are unchanged by the upgrade.

The database builder registers the full migration chain and does not configure
destructive-migration fallback; a missing or failed migration is not resolved by deleting user
data. A migration failure aborts the upgrade transaction and preserves the prior schema and
data: the injected-failure test observes `user_version` still at 7, the version 7 identity hash
intact, the populated rows and the exact confirmed total unchanged, and neither new table
present.

## Decision and release note

**S5.3 decision:** Keep the reading of a source span separate from the link between that
reading and a draft, and make both states explicit. One span of one capture holds one
reading, identified by a digest of the capture's own source identity and the span, so retries
and re-processing address the same event instead of appending copies. A link is proposed,
accepted, or refused, and a refusal is durable: it is not removable and no later proposal
recreates it. One event holds at most one active draft, several events may describe the same
draft, and the database enforces the first of those with a unique index. Where the old schema
had no time, no amount, or no reading, the new schema keeps the uncertainty visible instead
of filling it in.

**S5.3 release note:** The local database now keeps the events read from a saved note and the
links between those events and bank drafts. A note can carry several independent readings, a
rejected match stays rejected across restarts and re-processing, and editing or deleting the
note removes what was read from it. Existing data keeps its rows, ids, revisions, and totals
through the upgrade.

## Verification boundary

Attempt `S5.3-replan-a1` records the physical-device evidence in
`artifacts/sprint-5/workflow-replan/workers/S5.3-replan-a1.md`: the populated version 7 fixture
migrated to version 8 on the phone with its rows and exact total intact, two readings of one
note attached independently, a refused link stayed refused across a process restart, an
invalid span and a stale capture revision were refused without any write, and the manual entry
and review screens still worked on the upgraded schema. The device test used an isolated
application package with synthetic data only; the installed app, its data, and its models were
left untouched, and the isolated package and its driver were removed afterwards.

Implementation references: `app/src/main/java/io/github/triet4p/aegisf/data/NoteEventEntity.kt`,
`ContextLinkEntity.kt`, `NoteEventDao.kt`, `ContextLinkDao.kt`, `EventContextRepository.kt`,
`CaptureMessageRepository.kt`, `LedgerDatabase.kt`, and `AppContainer.kt`. The frozen source,
privacy, and authority contract is `docs/sprint-5-workflow-contract.md`.
