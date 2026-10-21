# S5.6 preparation commands

This document records the implemented S5.6 behavior: the one write surface the app and the
later tools write through, the commands it offers, the authority and idempotency rules it
keeps, the schema change it ships, and what it deliberately does not do. It also records
the privacy and authority boundary, the decision and release notes needed to review the
task without relying on mixed-ownership files, and where the physical-device evidence
lives.

## Scope

Before this task the app wrote its preparation data through three repositories that each
owned their own transaction: `CaptureMessageRepository` saved and revised captures,
`EventContextRepository` stored readings and link decisions, and
`ClassificationSnapshotRepository` stored the prediction of a draft. Each of them was
public application API, each of them decided its own rules, and none of them knew who was
asking. A caller could therefore write a capture, a reading, a link, or a prediction
without a scope, without an idempotency key, and without the expected revision the caller
had read — and two callers could apply the same request twice.

This task replaces that with one write surface, `WorkflowCommandService`:

- save and revise a capture;
- store or revise the reading of a span of a capture;
- propose, attach, refuse, and remove the association between an event and a bank draft;
- ask for a classification of a bank draft and record what it produced.

Every command takes the caller's scope, an idempotency key, and the revision it read, and
answers with a structured outcome. The repositories that store the data became internal:
the command boundary is their only public way in, so no caller can write around its rules.
The bank-draft review screen was migrated onto it — its own model call and its own
prediction write are gone — and the read surface gained a bounded read of the recorded
operations.

No financial confirmation, dismissal, correction, or deletion was added. Those are the
user's own decisions and belong to the user-only boundary of a later task.

## The command boundary

### The commands

| Command | What it does | What it never does |
| --- | --- | --- |
| `saveCapture` | Stores one user-authored capture: a quick note or a manual annotation, with its capture instant and local zone | It never stores bank evidence, a transient prompt, or a tool trace |
| `reviseCapture` | Replaces the text of one capture under its revision | It never rewrites the bank evidence behind a capture and never touches a confirmed ledger description |
| `updateEventInterpretation` | Stores or revises the reading of one span of one capture | It never invents an amount, a merchant, or a time, and never reads a span outside the current text |
| `proposeLink` | Proposes that one event describes one draft, with the evidence it was based on | It never moves a refused pair back to a proposal |
| `attachLink` | Attaches an event to the draft it was proposed for | It never attaches a pair the policy does not support, and never touches the draft's own review state |
| `rejectLink` | Refuses the association, durably | It never removes a refusal, and it is the user's own decision to make |
| `unlinkLink` | Removes an active link and restores the event's own context | It never deletes the bank draft, its evidence, or its review state |
| `requestClassification` | Asks for a review-only decision about a draft and records it against the state it was made in | It never changes an amount, a date, a time, a memo, a type, or a category of the draft, and it never stores the composed model input |

### The caller scope

The scope is the authority of the caller, and it is supplied by trusted application code:
the screen that owns the user's action, or the session an application adapter established
for its own caller. It is never read from a model's arguments, from a request body, or from
the stored data — a note, a bank memo, and a stored category name are data, not authority.

- `WorkflowCallerScope.User` — the user's own screens. Every preparation command is
  available to it.
- `WorkflowCallerScope.AgentSession(scopeId, captureIds, draftIds)` — a bounded session of
  a later agent adapter. It may act only on the captures and the drafts its own session was
  granted. A target outside that scope is refused before anything is read or written.

The refusals are named, not generic: `CALLER_SCOPE`, `IMMUTABLE_SOURCE`, `ALREADY_DELETED`,
`EVENT_UNAVAILABLE`, `LINK_ALREADY_REJECTED`, `USER_DECISION_REQUIRED`, `ATTACH_POLICY`,
and `MULTIPLE_ACTIVE_DRAFTS`.

### The link policy

A link decision is not one rule but three, and they belong to different callers:

- **Proposing** is preparation: any caller in scope may propose that an event describes a
  draft. The proposal carries no reason and no evidence of its own — the app records
  *its* evidence about the pair, computed from the stored reading and the stored draft and
  bound to the exact source, event, draft, and revisions it was read from. A note, a bank
  memo, a model, and a caller may all say why they think the pair belongs together; none
  of them may say what the app therefore knows.
- **Attaching** resolves a conflict, so it is bounded. The user may attach any proposed
  link: resolving an ambiguity is the user's own decision and needs no proof. Another
  caller may attach only a link the app's own policy accepts:
  - the link's evidence is evidence the app computed, not a reason somebody stated;
  - that evidence still proves one association — the reading and the draft state the
    same whole-dong amount and the same calendar day, the draft is the only stored draft
    that states that amount on that day, the draft is not one of an uncertain duplicate
    pair, and no field the comparison needs was missing or unreadable;
  - and that evidence is still the evidence the proposal was made on, so a reading, a
    draft, or a revision that moved on since the proposal refuses the attach as a
    `Conflict`.

  A link the app cannot prove that way stays proposed and is left to the user: the attach
  is refused with `ATTACH_POLICY`, and nothing is written. The policy is a comparison of
  facts, never a score: it does not weigh a merchant against a bank memo, and it never
  turns a similarity into an identity.
- **Refusing** is the user's own decision, and a refusal is not removable — removing it
  would recreate the very link the user refused. Another caller is refused before anything
  is read or written.

Removing a link is preparation again: it restores the event's own context and touches
nothing else.

### The caller's authority comes first

The caller's scope is settled **before** anything protected is read: before the target row
is looked up, before the idempotency record is looked up, and before any write.

- A request the caller may not make is refused with `CALLER_SCOPE`, and the refusal is the
  same whether or not the row it names exists. A caller outside its own scope therefore
  cannot tell a row that does not exist from a row it may not touch.
- A request the caller may not make is refused the same way whether or not its key was
  ever used, so the use of another principal's key is not observable either.
- The link commands resolve the reading the request names to the capture it belongs to
  before they decide, and a reading that does not exist is refused exactly like a reading
  outside the caller's scope.

### Idempotency

Every command carries an idempotency key, and the key is recorded durably with the
outcome. The rules are:

- **The same key, the same caller, the same request** is answered from that record: the
  state is not changed a second time, whatever the number of attempts, the number of
  processes, or the number of callers that race for it. The answer is `NoChange` and it
  carries the change that was made the first time, so a caller that repeats a request
  still learns which row it is about.
- **The same key with a different request** is a `Conflict`. The key names one request, and
  a key that was used for something else is not this request's key.
- **The same key from a different caller** is a `Conflict`. The key is unique in the whole
  table, not per caller, so one key cannot be used by two callers to make two requests.
  The two conflicts are answered with the same words, so the answer never says whose
  record holds the key: it names neither the other caller nor what that caller changed,
  and not even whether that record was applied.
- **The answer of a key does not depend on whether its record exists yet.** A request
  that runs the model takes its key in the process before the durable record of it is
  written, and the reservation of that key says which caller holds it and which request
  it is running. A competitor that arrives in that window is therefore answered the way
  the record itself would answer it: the same request is running, and another request or
  another caller is a conflict.
- **A refused request records nothing.** A denied, conflicting, or unanswerable request
  leaves the key free, so a corrected request may use it.

The record of a command and the change the command made are written in the same
transaction, so a command is either fully applied and recorded or not applied at all. Two
callers that race for one key are settled by the unique index on the key: the loser rolls
its whole transaction back and is answered from the winner's record.

### The claim on a key

A request that runs the model cannot be recorded only after it finished: two attempts
under one key would both reach the model. The key is therefore **claimed durably before
the expensive work starts**. The claim is a row in the same table, with the attempt's own
token and the moment the claim ends, and it is held by a second fact: the in-process
guard, which names the attempt that is still alive to do the work.

The two facts fail independently, and a request is safe only while both hold:

- the **row** says what may be done once. It is written and committed before the model is
  asked, so it is visible to every command-service instance and to every process, and no
  transaction is held open across the model call. It also fences the writes: only the
  attempt whose token the row carries may finish it.
- the **guard** says which attempt is still alive. It lives in the process, so a database
  that cannot be written does not lose it, and it dies with the process, so a request
  whose attempt was interrupted can still be taken over after a restart. The app runs in
  one process, so the guard covers every attempt that can compete for a key.

The two are taken **together, and never apart**:

- the key is taken in the process **before** the durable row is written, and a takeover
  takes the key **before** it updates the row. There is therefore no moment in which the
  row names an attempt that the guard does not name — a competitor that found such a row
  would take it over while its owner was still alive, and the owner would then ask the
  model a second time for one request.
- a key is **never taken away from the attempt that holds it**. An attempt that asks for
  a key that is held is told that the key is not its own and does no work, so an attempt
  that comes back after its claim was taken over cannot rob the attempt that took it.
- the **reservation of a held key says what that key is running**. Because the key is
  taken before the row is written, there is a window in which the row of a running
  request does not exist yet. The reservation carries the two values the row itself
  compares — the scope of the caller and the digest of the request — so a competitor
  that arrives in that window is answered the way the row would answer it: the same
  request is running, and another request or another caller is a conflict. Neither
  value names a protected row: the scope is the caller's own identity and the digest is
  a one-way digest of the request, which is the very value the row already stores.
- the durable row is looked at **once more before the model is asked**. An attempt whose
  claim was taken over while it was starting up does not ask the model at all, and it
  writes nothing.

The rules:

- The claim is kept alive while the model works: it is extended by a write of its own,
  never by a transaction held across the model call. A slow answer therefore does not end
  the claim and let a second attempt take the key over and ask the model again. An
  extension applies to the claim the attempt still holds — the row still carries its own
  token and is still pending — so a claim another attempt took over is never extended,
  and a claim whose request already finished is never extended.
- **An extension that fails, or one that finds no claim, is not ignored.** The attempt can
  no longer prove that the key is its own, so the request is failed — a `Failed` that says
  the claim could not be kept — instead of being carried out under a claim it cannot vouch
  for. The model work itself is not interrupted, because the local model is a native
  runtime whose execution cannot be interrupted; what keeps a second attempt away from
  the key is the guard, not a cancellation.
- **The model work is not cancelled with the caller.** Cancelling a caller withdraws the
  delivery of the answer; the inference itself keeps running. The claim is therefore given
  back when the work has *really* ended, and not one moment earlier, so a key is never
  free while an inference for it is still running. A cancelled request is answered at
  once, and its model work finishes in the background, where its answer is discarded.
- A second attempt under the same key finds the claim. It is answered as running — a
  `Failed` that says the request is already running — and **the model is not asked a
  second time**. That holds even when the lease of the claim has ended: an ended lease is
  not proof that the attempt is gone, and while a live attempt holds the key in this
  process the claim is not taken over. A later attempt under the same key is answered from
  the finished record.
- The claim is given back when the request is refused, when it fails, and when it is
  cancelled, so a corrected request may use the key.
- A claim whose attempt was interrupted — a process that died, a device that was switched
  off — ends when its lease ends, and another attempt may take it over, because the guard
  died with the process. The attempt that took it over owns the request from then on.
- The stored prediction and the record of the request are written in one transaction, and
  only by the attempt that still holds the claim. An attempt whose claim was taken over
  writes nothing at all, so a late result can never replace the result of the attempt that
  took it over, nor the user's own choice.

### Expected revisions

A command that names the row it read also names the revision it read:

- a capture is revised under its own revision, and a capture that moved on is a `Conflict`;
- a reading is taken from a named revision of a named capture, and a capture that moved on
  is a `Conflict`;
- a link decision is made under the link's own revision, and a link that moved on is a
  `Conflict`;
- a classification request may name the draft's review revision, and a draft that moved on
  is a `Conflict`.

A stale request never overwrites what the caller never saw, and it never writes at all.

### The outcomes

One vocabulary, used by every command:

| Outcome | What it means |
| --- | --- |
| `Applied` | The command changed the stored state; the change names the row and the revision it left behind |
| `NoChange` | The command changed nothing: the request was already satisfied, or it was already made under this key |
| `NeedsClarification` | The request is missing something only the caller can state — the field and the reason are named |
| `Conflict` | The request was made against a state that has moved on |
| `NotFound` | The named row does not exist |
| `Denied` | The request was refused by a rule; the rule is named |
| `Cancelled` | The request was cancelled before it was applied |
| `Failed` | The request could not be carried out; the reason is stated |

### The classification request

`requestClassification` is answered in this order, and the order is the rule:

1. a request outside the caller's scope is refused **before anything protected is read**;
2. a request that was already made under the same key is answered from the durable record
   of it, and **the model is not asked again**;
3. a request that is already running under the same key is answered as running, and
   **the model is not asked a second time**; the same key with another request, or from
   another caller, is a conflict in this window as well — the answer of a key does not
   depend on whether its record exists yet;
4. the key is claimed durably, and only then is the draft read, its revision checked, and
   the text checked for the safety rules — a request for a draft that does not exist, for
   a draft that moved on, or for text that may not be offered to the model, is refused
   **before the model is asked**, and it gives the key back;
5. only then is the effective context composed and the decision asked for.

The decision is asked for in the frozen order: a valid saved exact-description rule
answers first, and the model is asked only when no rule answers. The prediction is stored
against the state it was made in: a context that moved on while the model was working is
refused as a `Conflict`, and a draft whose category the user already chose keeps the
user's choice (`NoChange`). The stored prediction is review-only evidence: the composed
model input is never stored, only its digest.

## The recorded operations

The durable record of the commands is a new table, `workflow_operations`. It holds the
idempotency key, the scope that made the request, the command that was run, a digest of
the request's payload, the outcome it reached, the change it made, and — while a request
is still running — the token of the attempt that holds the key and the moment its claim
ends. It holds no private trace: the payload is a digest and never the text the request
was about, and the change names rows and revisions.

The read surface exposes it as a bounded read, `workflowOperations(WorkflowOperationQuery)`,
so a reader can see which requests were already answered under which key.

## Schema

The task ships one schema change, from version 9 to version 10:

- a new table `workflow_operations` with its unique index on the idempotency key, its
  index on the command, and its index on the creation instant;
- `MIGRATION_9_10`, which creates that table and nothing else.

The new table starts empty, because version 9 recorded no command: an idempotency key, a
caller scope, and a structured outcome are what the table holds, and version 9 had nowhere
to keep them. Nothing that version 9 already held is rewritten — the captures, the event
readings, the links, the bank drafts, the categories, the rules, the ledger records, and
the stored predictions keep their rows, ids, and values, and the confirmed totals computed
from them are unchanged by the upgrade. There is no destructive fallback: a version
mismatch still fails loudly.

The `context_links` table is not changed: the column that holds the evidence of a
proposal now holds the app's own evidence instead of a stated reason, and the rows an
older version wrote keep their old value — a stated reason stays a stated reason, and it
never becomes evidence the app may act on.

## Privacy and authority boundary

- The command boundary stores no notification body, no OTP or security text, no account
  identifier, and no balance. The intake rules that keep that content out of the database
  are unchanged.
- The operation record holds a digest of a request, never its text; the composed model
  input is never stored, only its digest.
- The evidence of a proposed link is the app's own: the amount, the calendar day, and the
  ids and revisions it was read from. It holds no text a caller, a model, or a note wrote,
  and it never holds a merchant against a bank memo.
- A refused request writes nothing and leaves no record of itself behind.
- No preparation command confirms a draft, dismisses one, writes a ledger record, edits a
  confirmed one, or deletes one. The confirmed ledger and its exact totals are untouched by
  every command in this task.
- The classification is review-only evidence: it never selects a category, never changes a
  bank field, and never applies a late result over a newer context or over the user's own
  choice.
- The surface is local to the app process. There is no HTTP server, no model-facing
  adapter, and no new screen: the tool adapter and the agent-facing schema are Sprint 8's
  work.

## Decision and release note

**S5.6 decision:** One write surface owns every reversible preparation the app can make,
and every command on it states the same four things: who is asking, which revision the
caller read, which request it is, and what happened. The caller's authority is supplied by
trusted application code and is never read from a model's arguments or from the stored
data; it is settled before anything protected is read, so a caller learns nothing about a
row or a key it may not touch. A request that repeats is a no-change and a request that is
stale is a conflict; a refusal writes nothing; and the change and the record of it are
written in one transaction. The link policy is deliberately narrow: proposing is
preparation, attaching needs an association the app itself can prove from its own facts,
and refusing is the user's own decision. The financial decisions stay out of this surface
entirely — no confirmation, no dismissal, no correction, no deletion.

**S5.6 correction decision:** A link's evidence is the app's own. The proposal request no
longer carries a reason, so a caller and a model can no longer assert the evidence that
would let a link be attached; the app computes the evidence from the stored reading and the
stored draft, binds it to the exact source, event, draft, and revisions it was read from,
and lets another caller attach only while that evidence still proves one association. The
user's explicit decision is unchanged: the user may attach any proposed link. A request
outside the caller's scope is refused with the same answer whether or not the row or the
key it names exists, and a key that belongs to another principal is a conflict that names
no other principal's record. A request that runs the model claims its key durably before
the model is asked, so two attempts under one key produce one answer; a refused, failed, or
cancelled request gives the key back, and a claim whose attempt was interrupted may be
taken over, so the model is never asked twice for one request and the key is never
reserved forever.

**S5.6 second correction decision:** The claim of a request that runs the model is kept
alive while the model works, so a slow answer does not end it and let a second attempt
take the key over and ask the model a second time; the extension is a write of its own,
never a transaction held across the model call, and it never resurrects a claim that
already ended. The two conflicts of the idempotency rule — a key of another caller and a
changed request of the caller's own — are answered with the same words, so an answer
never says whose record holds the key. The scope rules are unchanged: a caller outside
its scope is refused before anything protected is read, and the refusal is the same
whether or not the row it names exists and whether or not its key was ever used.

**S5.6 third correction decision:** The claim of a request that runs the model is held by
two facts, because one of them is not enough. The durable row says what may be done once
and fences the writes, but its lease is extended by a write, and a write that fails or
arrives late says nothing about whether the attempt that holds the key is still working —
so an in-process guard now says that, and it dies with the process, which keeps the
interrupted-attempt takeover working. An extension that fails, or one that finds no
claim, is no longer ignored: the request is failed, because an attempt that cannot prove
that the key is its own must not carry the request out. The model work is not cancelled
with the caller, because the local model is a native runtime whose execution cannot be
interrupted: the claim is given back when the work has really ended, so a key is never
free while an inference for it is still running, and the model is asked once for one
request whether the caller waited, went away, or lost the claim.

**S5.6 fourth correction decision:** The two facts that hold a claim are now taken
together, and never apart. The key is taken in the process *before* the durable row is
written, and a takeover takes the key *before* it updates the row, so there is no moment
in which the row names an attempt that the guard does not name: a competitor that found
such a row would take it over while its owner was still alive, and the owner would then
ask the model a second time for one request. A key is never taken away from the attempt
that holds it, so an attempt that comes back after its claim was taken over finds the key
held by the attempt that took it and does no work, and the durable row is looked at once
more before the model is asked, so an attempt whose claim was taken over while it was
starting up does not ask the model at all. The takeover of an interrupted attempt still
works: the guard dies with the process, so a claim whose attempt is gone may be taken over
when its lease ends, and the attempt that takes it over keeps the key.

**S5.6 release note:** The app now prepares a note, its readings, its links to a bank
notification, and a classification suggestion through one set of commands instead of several
independent ones. A repeated request — after a restart, after a retry, or from two places
at once — changes nothing instead of doing the same thing twice. A request made against
something that has since changed is refused instead of overwriting it, and a request from
outside the caller's own scope is refused before anything is read or written, with the same
refusal whatever it named. A link between a note and a bank notification is attached by
anything but the user only when the app itself can prove the association from what the note
and the notification state; otherwise it stays with the user, exactly as before. Unlinking
a note from a bank notification returns the note to its own context and leaves the bank's
notification exactly as it was. Nothing about your records changes: the same database file
with the same notes, categories, drafts, and confirmed records, and the same exact
whole-dong totals.

## Verification boundary

The permanent host tests are `data/WorkflowCommandServiceTest.kt` (44 tests) and
`data/WorkflowOperationMigrationTest.kt` (2 tests). They pin the boundary's observable
behavior against the real Room file, not against the statements that implement it:

- a repeated save request stores one capture, and the repeat is a no-change that names the
  same capture;
- a repeated request after a restart changes nothing;
- two callers racing for one key apply the change once;
- the same key with a different request, and the same key from another caller, are
  conflicts, and neither changes anything;
- a stale capture revision and a stale link revision are conflicts, and the row keeps the
  revision and the text it had;
- an agent is refused on a capture outside its scope, and refused on an attach the app
  cannot prove, while the link stays proposed;
- an agent may attach a link the app proves, and the proposal carries the app's own
  evidence;
- a link that carries a reason somebody stated cannot be attached by an agent;
- a proposal whose evidence moved on is a conflict, and the link stays proposed;
- the evidence of one pair does not attach another pair;
- a caller outside its scope cannot tell a missing target from a foreign one, and a key
  another caller used is refused like an unused one;
- a key another caller used is a conflict that names no record of theirs;
- a refusal of a link is the user's own, and the refused pair is never recreated;
- unlinking restores the event's own reading and status, and the bank draft keeps its
  review state, its memo, and its amount;
- a repeated classification request asks the model once, and a finished request is
  answered from the record after a restart;
- two concurrent classification requests ask the model once, and the second is answered as
  running;
- a concurrent request with another payload under the same key is a conflict;
- a claim left by an interrupted attempt is taken over by a later attempt, because the
  guard that held it died with the process;
- an attempt whose claim was taken over writes nothing, and the key stays with the
  attempt that took it over;
- a claim that cannot be renewed fails the request, and while the model work of that
  request is still running no attempt takes the claim over and no attempt asks the model
  a second time;
- a claim outlives an inference that is longer than its lease, so a slow answer does not
  let a second attempt take the key over and ask the model again;
- an attempt that is between taking the key in the process and writing the durable claim
  keeps the key from a competitor: the competitor finds the key held, is answered as
  running, writes no claim of its own, and the model is asked once;
- in that same window the same key with another request, and the same key from another
  caller, are conflicts as well: the answer of a key does not depend on whether the
  durable record of it exists yet;
- in that same window a caller that may not touch the draft is still refused by its own
  scope, and is refused the same way as under a key that was never used, so the running
  request under that key is not observable to it;
- an attempt whose durable claim was written keeps the claim when its lease ends, so a
  competitor under the same key does not take it over and does not ask the model;
- the attempt that took a claim over keeps the key from a competitor, and the model is
  asked once;
- an attempt whose claim was taken over before the model was asked does not ask the
  model at all, and it writes nothing;
- a key of another caller and a changed request of the caller's own are answered with the
  same words, so the answer never says whose record holds the key;
- a cancelled classification keeps its key while its model work is still running and gives
  it back when that work has really ended, and a failed classification gives its key
  back, so the work may be asked for again;
- a refused classification request and a safety-blocked text never reach the model;
- a saved exact-description rule answers a classification without the model being asked;
- a prediction that arrives after the context moved on is a conflict and is not stored;
- the user's own category choice is preserved over a prediction;
- the confirmed-ledger total is unchanged by every preparation command;
- the recorded operation names the request and does not hold its text;
- the populated version 9 upgrade preserves every stored row and the exact total, keeps the
  claim columns of the new table usable, and a failed upgrade rolls back with the version 9
  file and its data intact.

The physical-device evidence for this task is recorded in
`artifacts/sprint-5/workflow-replan/workers/S5.6-replan-a1.md` and in
`artifacts/sprint-5/workflow-replan/workers/S5.6-replan-a3.md`: the throwaway command
driver ran every command family on an isolated package on the physical phone
`AAAJBB4930914141`, each phase in a new process, and the current Manual, Review, and
intake screens were driven on the same fixture. The classification was asked with the real
pinned model as well: the model was copied read-only into the fixture package with the
proven on-device pipe, the real inference ran through the command boundary and through the
review screen, and the repeated request was answered from the durable record without asking
the model again — while the original package's model files kept their sizes, their dates,
and their hashes.

The later rounds of the same task are recorded in
`artifacts/sprint-5/workflow-replan/workers/S5.6-replan-a4.md` (the claim of a running
request kept alive by the stored row and the running app's own note, with the real pinned
model), `artifacts/sprint-5/workflow-replan/workers/S5.6-replan-a5.md` (the key taken
before the durable claim is written, so the row and the note never name two attempts), and
`artifacts/sprint-5/workflow-replan/workers/S5.6-replan-a6.md` (the answer of a key in the
window before the durable claim exists). Each of them ran its own throwaway driver on the
isolated package, in a new process per phase, with synthetic fixture data only; the last
two stood a counting engine in for the classifier, because the native model path was
unchanged by them and its own evidence is the a4 record's.

Implementation references: `app/src/main/java/io/github/triet4p/aegisf/data/WorkflowCommandService.kt`,
`WorkflowCommandTypes.kt`, `WorkflowOperationEntity.kt`, `WorkflowOperationDao.kt`,
`WorkflowClaimGuard.kt`, `LinkEvidencePolicy.kt`, `ClassificationEngine.kt`,
`ManualCategoryDecision.kt`,
`app/src/main/java/io/github/triet4p/aegisf/data/laya/LayaClassificationEngine.kt`, the
`MIGRATION_9_10` in `data/LedgerDatabase.kt`, and the migrated review screen in
`app/src/main/java/io/github/triet4p/aegisf/ui/BankDraftReviewScreen.kt`. The frozen
source, privacy, and authority contract is `docs/sprint-5-workflow-contract.md`.
