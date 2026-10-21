# S5.4 effective context and prediction invalidation

This document records the implemented S5.4 behavior: how the effective context of a bank draft
is composed, how a prediction is stored with the dependencies it was made against, and when a
stored prediction stops applying. It also records the privacy and authority boundaries, the
version 8 to 9 migration, and the decision and release notes needed to review the task without
relying on mixed-ownership files. It describes the code as implemented and links the
physical-device evidence in `artifacts/sprint-5/workflow-replan/workers/S5.4-replan-a1.md`;
independent evidence review and the separate Bronze checkpoint remain gates.

## Scope

The effective context of a bank draft review is the text one classification request is made
against. Before this task the request carried exactly one string: the review form's permitted
bank description, or the explicit note the user typed instead of it. This task adds the stored
user event context that belongs to the same draft, composes the two under the model's actual
input budget, and stores the resulting prediction together with the state it was made in, so a
later read can say that the prediction no longer applies instead of applying it.

Nothing else moved: the query surface (S5.5), the command boundary (S5.6), the financial
confirmation boundary (S5.7), and any new screen stay out of this task. The review screen and
the manual entry screen were migrated onto the new request shape; no screen was added.

## The effective context

### What belongs to a draft

A draft's context is the review's own description plus the stored readings of the events that
are linked to THAT draft.

The readings come from the `note_events` table of S5.3, through the `context_links` rows that
are proposed or accepted. A reading is the interpretation of one span of one capture, so a
note with several events contributes only the readings of the events that are actually linked
to the draft being reviewed. The whole note is never copied into every draft: a draft whose
one linked event says `Fuel 50,000` never carries the other event of the same note.

A reading with no derived text — because its source was edited or deleted — contributes
nothing, and a link that was refused contributes nothing either: a refusal is the user's
decision about that draft, not context for it.

The readings are supplied in ascending event id, so the same draft always composes the same
text, whatever order the links were made in.

### The composition, under the model's actual budget

`LayaEffectiveContextComposer` composes the request:

- the primary text comes first and is never rewritten, shortened, or reordered;
- the readings follow in the order they were supplied, each separated by a single space;
- a reading is added only while the composed text still fits the room the model leaves for its
  state; a reading that does not fit, or that the safety guard refuses, is left out and
  reported in `droppedReadings` instead of being truncated into the text;
- the room is the room every candidate set leaves: the sequence's total budget (512 tokens)
  minus the largest header the model may build (192 tokens) minus the four structural tokens
  around the state. Because the header never exceeds that bound, a composed input that fits
  the room is always carried by the model in full;
- a primary text that is larger than the room on its own cannot be offered to the model at
  all: the request is refused with `EFFECTIVE_INPUT_OVER_BUDGET` before any model work, the
  text is never shortened to make it fit, and the app says plainly that no suggestion was
  made and that the chosen category and the other fields stay unchanged. A primary text that
  exactly fills the room does fit, and is offered to the model as it is.

The composed text is measured with the pinned tokenizer, so the recorded token count is the
exact size of what the model receives, not an estimate. A request with no readings composes to
exactly the primary text, which is why the manual entry path is unchanged.

The frozen contract's enumeration of the planner's manual reasons
(`docs/sprint-5-workflow-contract.md`, section 2.5) predates this correction. The new reason
is the same kind of refusal — a `ManualReview` that never invokes inference — and the frozen
pipeline order (explicit correction → category-name metadata match → budget-exact shortlist →
manual review) is unchanged: the budget refusal sits after the two direct decisions, so a
saved correction and an exact category-name match still answer without the model.

The note and the bank memo remain strictly separate inputs, exactly as before: the explicit
note is still evaluated alone and is never concatenated with the bank memo. The stored
readings are a third source — the user's own saved text — and they are combined with the
primary text by the rule above.

### The saved rule stays outside the model

A remembered exact-description rule is looked up by the request's own description, before
inference runs, and it answers without the model. The rule is not part of the composed
context and is not passed to the model as a correction: a rule is remembered for one exact
description, so appending readings to it would make the rule unreachable, and passing it as a
correction would count the same rule twice. What the rule state changes is the *validity* of
a stored prediction, which is why the rule set is one of the recorded dependencies.

## The stored prediction

Room schema version 9 adds one table, `classification_snapshots`, with one row per draft. The
row holds:

- the decision: where it came from (`REMEMBERED_RULE`, `EXPLICIT_CORRECTION`,
  `CATEGORY_METADATA`, `MODEL`, `NO_FIT`), the category it pointed at, the categories the
  model evaluated in the order it evaluated them, their calibrated shares, the calibrated
  no-fit share, and the one Jev sharpness value of the same distribution. A direct decision
  never ran the model, so it carries no share and no sharpness;
- the dependencies: the context revision (the linked events, the links, and the draft's own
  review state), the category revision (the whole category catalog), the rule revision (the
  remembered rules), the model revision (the pinned export and its budget pins), and the
  candidate-set version (how the evaluated set was chosen). Each is a digest, so nothing the
  prediction was made from is copied into the row;
- the digest of the composed model input, and the ids of the readings it was composed from;
- when the prediction was made, and why it no longer applies, if it does not.

The composed input itself is never stored. A prompt stays ephemeral by contract, and the
digest is enough to recognise that the same input produced the same prediction.

## When a stored prediction stops applying

A prediction is stored only against the state it was actually made in.

- **A late result is refused.** When the prediction is stored, the current state is read again
  and compared with the state the request was made in. If they differ, the result is refused
  and nothing is written: a result that arrives after the context moved on describes a state
  that no longer exists and must not replace the newer one.
- **The user's own choice wins.** When the user has already resolved the draft's category, a
  prediction is not stored over it, and the stored snapshot is marked
  `USER_CATEGORY_CHOSEN`. The draft keeps the user's category.
- **A changed dependency makes the prediction stale.** The stored reasons are checked in a
  fixed order: the user's choice, the context, the category catalog, the remembered rules, the
  model revision, and the candidate-set version. The first one that moved is the reason that
  is stored, so the same change always yields the same reason.
- **A changed source says so.** When the text a reading came from was edited or deleted, the
  reason is `SOURCE_CHANGED` rather than the generic `CONTEXT_CHANGED`.
- **Editing one event invalidates only the drafts that event describes.** The context revision
  is computed per draft from that draft's own links and readings, and the invalidation that
  happens in the same transaction as the change touches exactly the drafts the edited event
  is linked to. The suggestions of every other draft stay current.
- **The stale state is stored.** A detected staleness is written back to the row, so a stale
  prediction stays visibly stale across a restart instead of looking fresh again because
  nothing happened to read it while it was stale. A context, source, or link change is marked
  in the same transaction as the change itself, because it belongs to one draft; a category,
  rule, or model change is not tied to one draft and is marked the next time the stored
  prediction is read.

## Privacy and authority boundary

- The composed model input is the user's own text: the bank description the review already
  shows, and the readings of the user's own saved note. No notification body, no OTP or
  security text, no account identifier, and no balance is added to it. The safety guard that
  keeps OTP/security-like text and bank reference codes out of the model now applies to the
  readings as well, so a second door to the model cannot be opened by the new context.
- The composed text is not stored. Only its digest is, and a digest cannot be read back as
  text.
- Storing, refusing, or marking a prediction stale changes no amount, date, time, memo, type,
  category, review revision, or review state of the draft, and writes no ledger record. The
  confirmed ledger is untouched.
- A prediction is review-only evidence. It never assigns a category, never confirms anything,
  and never reaches the ledger. The category field still changes only on an explicit user
  action, and confirmation stays an explicit user action.
- The stored prediction carries the same single confidence value the model produced: the
  sharpness of the evaluated distribution. No second confidence field exists, and a link's
  state and a category's confidence stay separate things.

## Version 8 to 9 migration

`LedgerDatabase.MIGRATION_8_9` creates `classification_snapshots` with its unique index on
the draft and its foreign key to the pending drafts, and backfills nothing: version 8 stored
no prediction. The migration does not rewrite the categories, rules, drafts, ledger records,
captures, event readings, or links that version 8 held, so their ids, revisions, and values —
and the exact confirmed totals computed from them — are unchanged by the upgrade.

The database builder registers the full migration chain and does not configure
destructive-migration fallback; a missing or failed migration is not resolved by deleting user
data. A migration failure aborts the upgrade transaction and preserves the prior schema and
data: the injected-failure test observes `user_version` still at 8, the version 8 identity
hash intact, the populated rows and the exact confirmed total unchanged, and the new index
absent.

## Decision and release note

**S5.4 decision:** Compose the effective context of a request from the review's own
description plus the stored readings of the events linked to that draft, in a fixed order and
inside the room the model actually leaves for its state; measure it with the pinned tokenizer
and leave out, rather than truncate, a reading that does not fit. A request whose own text is
already larger than that room is refused before the model is asked, with the text left
exactly as the user wrote it. Record the prediction with
the digests of everything it depended on — the context, the category catalog, the remembered
rules, the model revision, and the candidate-set version — so that a later read can tell that
it no longer applies, and store that verdict so it survives a restart. A prediction is stored
only against the state it was made in, so a late result is refused; the user's own category
choice is never overwritten; and the remembered rule keeps its place before the model, where
it already was.

**S5.4 release note:** A bank draft's suggestion can now use the events read from your own
saved notes, but only the events that belong to that draft, and only as much of them as the
model can actually read. A description that is longer than the model can read is no longer
sent to the model at all: the app says that no suggestion was made and that your category
choice and the other fields stay unchanged. The app also keeps what it suggested for a draft together with what
the suggestion depended on: when the note, the categories, the saved rules, or the model
change, the stored suggestion is marked as no longer current instead of being applied. Your
own category choice always wins over a suggestion, and the bank's own amount, date, time, and
description are never changed by any of it. Existing data keeps its rows, ids, revisions, and
totals through the upgrade.

## Verification boundary

Attempt `S5.4-replan-a1` records the physical-device evidence in
`artifacts/sprint-5/workflow-replan/workers/S5.4-replan-a1.md`: the populated version 8
fixture migrated to version 9 on the phone with its rows and exact total intact, two readings
of one note stayed independent across two drafts, a re-read event invalidated only the draft
it belonged to, a late result was refused, the user's category choice was preserved, the
stored stale state survived a process restart, the injected migration failure rolled back to
version 8, and the manual entry and review screens still worked on the upgraded schema. The
device test used an isolated application package with synthetic data only; the installed app,
its data, and its models were left untouched, and the isolated package and its driver were
removed afterwards.

Attempt `S5.4-replan-a2` corrects the input-budget finding of the independent review and
records its evidence in `artifacts/sprint-5/workflow-replan/workers/S5.4-replan-a2.md`: on the
same physical phone, the real runtime with the pinned model refused a description of 317
tokens against the 316-token room with `EFFECTIVE_INPUT_OVER_BUDGET`, produced no model
evaluation at all, and returned the text unchanged, while a description of exactly 316 tokens
was offered to the model and was scored. The isolated package, its staged model copy, and the
driver were removed afterwards; the installed app, its data, and its own model files were left
untouched.

## S5.9 connected snapshot and freshness observation

In the connected schema-v11 fixture, real pinned Laya produced snapshot 1 for draft 205
with `eventIds=1` and snapshot 2 for draft 201 with `eventIds=2`; both decisions were
`NO_FIT`. The sibling event was excluded from each draft's context, and the persisted
snapshots retained the effective-input digest rather than the composed text. A restart
and same-key replay of draft 205 returned `NoChange` with snapshot 1 unchanged.

Unlinking event 1 made only draft 205's snapshot `CONTEXT_CHANGED`. Editing capture 10
while draft 201's real Laya call was blocked made the late result a `Conflict` and left
snapshot 2's id and source intact while persisting `SOURCE_CHANGED` across process
recreation. After the user explicitly chose Food for draft 205 at review revision 1, a
new pinned-Laya request returned `NoChange`; the category, revision, and bank memo stayed
unchanged. Because snapshot 1 was already stale from the unlink, its existing
`CONTEXT_CHANGED` reason remained; classification did not make stale state look fresh or
replace its stored prediction. The per-process evidence is recorded in
`artifacts/sprint-5/workflow-replan/workers/S5.9-replan-a1.md`.

Implementation references: `app/src/main/java/io/github/triet4p/aegisf/data/laya/LayaEffectiveContext.kt`,
`app/src/main/java/io/github/triet4p/aegisf/data/ClassificationSnapshotEntity.kt`,
`ClassificationSnapshotDao.kt`, `ClassificationSnapshotRepository.kt`,
`app/src/main/java/io/github/triet4p/aegisf/data/SuggestionSafety.kt`,
`app/src/main/java/io/github/triet4p/aegisf/data/EventContextRepository.kt`,
`app/src/main/java/io/github/triet4p/aegisf/data/LedgerDatabase.kt`, and
`app/src/main/java/io/github/triet4p/aegisf/ui/BankDraftReviewScreen.kt`. The frozen source,
privacy, and authority contract is `docs/sprint-5-workflow-contract.md`.
