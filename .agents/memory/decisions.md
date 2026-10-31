# Project decisions

## [2026-10-17] Use metadata-ranked exact-budget category candidates

**Decision:** Keep candidate planning over current `CategoryEntity` ids/names, apply caller-supplied exact-description corrections and unique category-name phrases before inference, and use the pinned tokenizer/sequence budget plus deterministic name-evidence ranking for oversized sets; represent no-fit as a typed unknown rather than an id sentinel.

**Alternatives considered:** Selecting the first N categories regardless of evidence; assigning unknown a reserved numeric id; expanding the category schema with aliases/descriptions in S4.4; persisting correction rules before S4.7.

**Reason:** The active category schema currently contains only stable ids, names, and archive state. A count-based prefix can omit relevant categories without evidence, while exact token measurement preserves the pinned sequence contract. Keeping correction inputs caller-owned allows S4.7 to add deliberate persistence without automatic learning now.

**Consequences:** Oversized retrieval is limited to lexical category-name evidence and may return manual unknown for semantically relevant labels that do not overlap. Results must be resolved by stable id and unchanged active name against current rows; clients must surface omitted-category uncertainty and must not write or confirm a record from this result.

## [2026-10-19] Evaluate SLMs as user-selectable capability tiers

**Decision:** Evaluate and present supported SLM options as user-selectable capability tiers with candid per-task limits and measured latency/resource tradeoffs, rather than selecting or rejecting one global winner.

**Alternatives considered:** Require one model to pass every benchmark case before considering any model; choose the highest-scoring model as the universal assistant; offer only the smallest model regardless of measured capability.

**Reason:** The user explicitly prefers a fast/light option for proven simple tasks and a balanced option with candid limitations, while allowing a slower quality-oriented option only when measurements support its claims. A benchmark imperfection alone must not disqualify a lower tier, but unsafe, wrong-scope, ungrounded, or unsupported behavior must not be relabeled as an acceptable limitation.

**Consequences:** Evaluation artifacts must separate frozen-label scores from corrected-semantic scores, report achievable tool calls and clarification/unsupported cases separately, and explain which failures require clarification/disabled capabilities versus future app-side containment. No tier may be claimed production-safe before deterministic boundaries are implemented and verified; this decision does not authorize app integration or model dispatch.

## [2026-10-19] Keep source captures separate from mutable annotations

**Decision:** Store each supported bank draft as an immutable `BANK_EVIDENCE_REF` containing only its parsed memo and stable draft identity; save user captures and annotations as separately revisioned rows whose explicit deletion clears text and leaves a text-free tombstone.

**Alternatives considered:** Persist full bank notification bodies; edit bank source text in place; keep edited or deleted text in append-only audit history; infer a timezone or source timestamp for version-6 ledger descriptions.

**Reason:** The frozen contract forbids retaining raw notifications, OTP/security text, account identifiers, and balances, and keeps bank evidence separate from user corrections. The legacy ledger has no annotation-specific capture time or timezone, and the deletion requirement forbids hidden copies of deleted raw text.

**Consequences:** Intake writes immutable bank references only for parsed drafts and in the same transaction as their draft identity. User edits increment the current row revision without retaining prior text; deletion clears the capture body while preserving only provenance metadata and never mutates a separately confirmed ledger description. Legacy descriptions remain labeled as legacy-origin annotations with unknown capture time/zone, and later context work must depend on stable capture ids/revisions rather than rewriting source evidence.

## [2026-10-19] Keep event readings and context links as separate explicit states

**Decision:** Store each interpretation of a note span as its own row whose identity is derived from the capture source identity and the span, and store the association between that interpretation and a bank draft as an explicit proposed/accepted/rejected row whose event-draft pair is unique; a refused row is never removed and never moved back to a proposal.

**Alternatives considered:** One free-text event field per capture; inferring a link from amount and time similarity; keeping the link state implicitly on the draft row; allowing an event to hold several active drafts.

**Reason:** The frozen sprint contract requires explicit link states, safe cardinality, no silent resurrection of refused or ambiguous links, and preserved ambiguity instead of a fabricated amount, time, or match. A stored identity per span is what keeps a repeated extraction from appending blind copies, and an explicit state column is what keeps a refusal durable across restarts, retries, and re-processing.

**Consequences:** One span of one capture holds one reading, and a changed reading is an explicit revision; a reading without a stated amount must record why it is missing; an event holds at most one active draft while several events may describe the same draft; editing or deleting the source note clears the derived reading and its active links but keeps a refused link; a user-accepted link is a user choice that a later extraction may not silently replace. This entry covers the S5.3 persistence only and authorizes no classification-context, prediction-invalidation, agent-authority, or command-layer work.

## [2026-10-20] Route every reversible preparation through one scoped, idempotent command boundary

**Decision:** Make one application service the only public way to save or revise a capture, store or revise an event reading, propose, attach, refuse, or remove a context link, and ask for a draft classification; give every command a caller scope supplied by trusted application code, an idempotency key recorded durably with its outcome, the revision the caller read, and one structured outcome vocabulary; keep the storage repositories internal to the module.

**Alternatives considered:** Keep the three preparation repositories public and add the scope and key checks inside each of them; hold the idempotency records in memory only; let the command take the caller scope as a request argument so an adapter could pass it through; give the preparation layer the financial confirmation and correction commands as well.

**Reason:** The frozen contract requires one shared application layer with the same validation for the UI and the later agent adapters, and the plan's authority table requires that caller identity come from trusted app code, never from model arguments. A scope that arrives as an argument is an argument a model can write, so the scope is a value the application code constructs; the key must be durable because a retry after a restart, and a race between two callers, has to be a no-change rather than a second effect; and the revision a caller read has to be part of the request because a stale writer must lose instead of overwrite. Keeping the repositories public would leave a second, unchecked way to write the same rows, which is the bypass the boundary exists to remove.

**Consequences:** A repeated request under the same key answers from the durable record and changes nothing, the same key with a different request or another caller is a conflict, and a refused request records nothing so a corrected retry may reuse the key; the change and the record of it are written in one transaction and a lost race rolls back whole; the link policy stays narrow — proposing is preparation, attaching needs a unique evidence-supported link, refusing is the user's own decision — and unlinking restores the event's context without touching the bank's evidence; the classification is asked only after the replay, scope, and safety checks, so a replay or a refusal never reaches the model, and a late result never replaces a newer context or the user's own choice. Financial confirmation, dismissal, correction, and deletion stay outside this surface, and this entry authorizes no agent adapter, no model-facing schema, and no HTTP surface.

## [2026-10-20] Let the app, not the caller, decide what a link rests on

**Decision:** Take the evidence about a proposed association out of the caller's hands: the link proposal carries no reason, the app computes the evidence itself from the stored reading and the stored draft, and it binds that evidence to the exact source, event, draft, and revisions it was read from; let a caller other than the user attach only a link whose app-computed evidence still proves one association; settle the caller's authority before any protected row or idempotency record is read; and reserve the idempotency key durably before the model is asked.

**Alternatives considered:** Keep the request's evidence field and check that it is not blank; score how alike a note and a notification are and attach above a threshold; treat any recorded reason as a policy; refuse every attach by another caller; hold the model call under an in-memory mutex.

**Reason:** A free-text field is a claim, and a claim is not a policy. The frozen contract reserves attaching for a unique evidence-supported association and leaves an ambiguous or conflicting link to the user, and the plan keeps matching evidence separate from category confidence and forbids turning a similarity into an identity, so the app's own facts must decide instead of a threshold or a stated reason. A caller that may not act on a row must not learn whether the row exists, and a key that belongs to another principal must not be observable either, so the scope is settled before anything protected is read. The model call is the one expensive step, so the key has to be reserved in the database before it starts, not recorded after it ends.

**Consequences:** The proposal stores the app's own evidence, and another caller may attach only while that evidence still proves one association — the same amount, the same day, one candidate notification, no uncertain duplicate, and no unreadable field; the user keeps the explicit decision to attach anything. A request outside the caller's scope is refused with the same answer whether or not the row or the key it names exists, and the same key with a different request or another caller stays a conflict that names no other principal's record. A classification reserves its key before the model runs, so two attempts under one key produce one answer; a refused, failed, or cancelled request gives the key back, and a claim whose attempt was interrupted may be taken over, so the key is never reserved forever and the model is never asked twice for one request.

## [2026-10-21] Keep a running request's claim alive, and answer the two key conflicts alike

**Decision:** Extend the durable claim of a request that runs the model while the model works, by a write of its own and never by a transaction held across the model call; an extension applies only while the row still carries the attempt's own token, is still pending, and its lease has not ended; answer the two idempotency conflicts — a key of another caller and a changed request of the caller's own — with the same words.

**Alternatives considered:** Let the claim end while the model works and accept that a second attempt then asks the model again; hold one transaction open across the model call; renew the claim from memory only; keep one wording per conflict so the caller can tell whose key it is.

**Reason:** The claim exists so that one request is asked once. A lease that ends while the model is still working breaks that guarantee for exactly the slow case it was meant to cover, and a transaction across the model call would hold the database for the whole answer. A renewal that can resurrect an expired claim would undo the recovery of an interrupted attempt, so it is bound to the attempt's own token and to an unexpired lease. Two conflicts that read differently tell a caller whether a key belongs to somebody else, which is protected state it should not learn; the key is one request everywhere, so the answer may say that the key names another request and nothing more.

**Consequences:** A request that runs the model keeps its key while it runs, whatever the model's latency; an attempt whose claim was taken over still writes nothing, and an attempt whose lease already ended cannot extend it; the answer to a key that belongs to another caller and to a changed request of the caller's own is the same generic conflict, so no caller learns whose record holds a key, and a caller outside its scope is refused before any protected row or idempotency record is read.

## [2026-10-21] Hold a running request's claim with the stored row and the running app's own note

**Decision:** Hold the claim of a request that runs the model with two facts that fail independently: the durable row, which fences the writes and lets a request whose attempt was interrupted be taken over, and an in-process guard, which says which attempt is still alive to do the work and dies with the process; refuse a takeover while a live attempt holds the key, even when the claim's lease has ended; report an extension that fails, or one that finds no claim, as a failed request instead of carrying the request out under a claim the attempt cannot prove; run the model work in a scope of its own, so that a caller that stops waiting does not cancel an inference the native runtime cannot stop, and give the claim back only when that work has really ended.

**Alternatives considered:** Keep extending the claim and ignore an extension that fails; cancel the model call when the claim is lost and free the key at once; hold the claim only in the database and let an ended lease mean the attempt is gone; hold a file lock per key; refuse to ever take a claim over, which would reserve a key forever after a crash.

**Reason:** The review of the second correction found that a lost or failed extension did not stop the inference, so a second request could take the expired claim over and ask the model a second time for one request; the completion fence stopped the stale write but not the duplicate call. The lease alone cannot carry the proof, because the lease is extended by a write and a write that fails or arrives late says nothing about whether the attempt is still working; the local model is a native runtime whose execution cannot be interrupted, so cancelling a caller withdraws the answer but not the inference, and a claim freed at that moment lets a second inference start for the same request. The in-process guard is that proof: it cannot be lost to a failing database, and it dies with the process, which keeps the takeover of an interrupted attempt working. Extending a claim is bound to the attempt's own token, so a claim another attempt took over is never extended, and a late extension of a claim nobody took over is the owner's own heartbeat rather than a loss.

**Consequences:** The model is asked once for one request, whether the caller waited, went away, or lost the claim; a request whose claim cannot be kept is failed plainly and writes nothing; the key is given back only after the model work has really ended, so a key is never free while an inference for it runs; a request whose attempt was interrupted is still taken over after a restart; a hung inference keeps its key until the app restarts, which is the price of never asking the model twice; the app runs in one process, so the guard covers every attempt that can compete for a key, and a second process would need the same guard of its own.

## [2026-10-21] Take the key before the claim is written, and never take a key away

**Decision:** Take the idempotency key in the process *before* the durable claim of a request is written, and take it *before* a takeover updates the durable claim; never take a key away from the attempt that holds it; and look at the durable claim once more before the model is asked.

**Alternatives considered:** Register the guard after the claim is written, as the previous attempt did; let a later attempt overwrite the guard's owner, so that the attempt that comes back owns the key again; rely on the durable owner fence alone; let a takeover update the row first and register the guard afterwards; refuse every takeover while the guard is held, which would reserve a key forever after a crash.

**Reason:** The review of the third correction found that the guard could be overwritten and that it was registered only after the durable claim existed. Between the write of the claim and the registration of the guard there was a window in which a competitor found the claim, took it over, and asked the model, and the attempt that came back then took the guard over and asked the model a second time: the durable fence stopped the stale write but not the duplicate call. The guard is taken first and is never taken away, so the row and the guard can never name two different attempts, and an attempt that comes back after its claim was taken over finds the key held and does no work. The row is looked at once more before the model is asked, so an attempt whose claim was taken over while it was starting up does not ask the model at all.

**Consequences:** The model is asked once for one request, whatever the order in which the attempts arrive; a takeover takes the key before it updates the row, so a competitor that arrives in between is refused rather than served; a request whose attempt was interrupted is still taken over after a restart, because the guard dies with the process; the guard has a hook that the host tests use to hold an attempt at the two points of the claim's life, and the hook is null in the app.

## [2026-10-21] Let a held key say which request it is running

**Decision:** Let the in-process reservation of a held idempotency key carry the two values the durable claim compares — the scope of the caller and the digest of the request — and answer a contender that arrives before the durable claim exists with the same answer the claim itself would give: the same request is running, and another request or another caller is a conflict.

**Alternatives considered:** Answer every contender that finds no durable row as running, as the previous attempt did; wait for the durable row before answering, which would let a second attempt into the model; keep the reservation free of any identity and compare the request only once the row exists; store the reservation's identity in the durable table instead of in the process.

**Reason:** The review of the fourth correction found that the answer of a key depended on when the request arrived: a competitor that found the key held but no durable row was answered as running, even when it carried another request or came from another caller, while the same competitor arriving a moment later was answered as the conflict the contract requires. The documented contract says the same key with another request or from another caller is a conflict, and it says the two conflicts are answered with the same words; an answer that depends on a race is not that contract. The reservation already sits in the place that knows which attempt holds the key, and the scope and the digest are the very values the claim compares, so the two windows can answer alike without a new fact, a new table, or a schema change.

**Consequences:** The same key and the same request always get the same answer, whichever side of the durable write the request arrives on; the plain conflict wording is unchanged, so the answer still names neither the other caller nor what that caller changed, and the reservation carries no protected row — the scope is the caller's own identity and the digest is the one-way digest the claim already stores; a caller that may not act on the draft is still refused by its own scope before the key is looked at, so it learns nothing about the request that holds it; the guard's reservation is part of its state, so it is given back with the key and dies with the process.

## [2026-10-21] Verify the migration chain against the exported older schema, and close the last direct write paths

**Decision:** The cumulative migration proof builds its fixture from the schema file the app exported for its own older release - the exported version-6 schema, read at test time - instead of a current database with an old version number written into it, and the failure it exercises is injected inside the upgrade transaction, so the rollback of the whole chain and the retry on the same file are both observed; the category and the saved-rule writes move onto the command boundary, their two reads move onto the query boundary, and the storage layer becomes `internal`, so the app's public surface is the command boundary for writes and the query boundary for reads.

**Alternatives considered:** Keep the hand-written older DDL of the earlier migration test; inject the failure in front of the upgrade rather than inside it; keep the category and rule writes on the storage layer, which is the storage layer's own business; derive the idempotency key of a category action from its content instead of minting one per action.

**Reason:** A current database with an old version number proves nothing about the chain, because it never runs the steps that re-derive an identity or backfill a provenance; the exported schema is the real historical structure, including its identity hash, so the fixture is the database the older release actually wrote. A failure in front of the upgrade would leave the chain untested, while a failure inside it shows that every step - including a successful statement of the failing step - is rolled back together, and that the data is still there afterwards. The category and rule writes were the last ones that reached the storage layer directly, so leaving them there would have kept a public write path around the boundary that owns the caller's authority and the protection against a repeated request. A content-derived key would make a legitimate later action with the same content a replay of the earlier one, so the key is minted per action instead.

**Consequences:** The chain from the version the Sprint 4 app shipped to the current version is proven against real historical DDL, with the exact rows, totals, identities, and links preserved, and with the failure recoverable and unable to wipe anything; the legacy descriptions keep their unknown authoring instant and timezone instead of a fabricated provenance; the catalog and the saved rules are now the user's own decisions with the same authority, idempotency, and refusal rules as the rest of the app's writes, and no caller can write around the boundary any more; the app's public API is the command boundary and the query boundary alone.

## [2026-10-21] Name the note and the reading it was read from together, and answer a decided draft with its stored state

**Decision:** A request that stores a manual ledger record carries its source as one value — `ManualTransactionSource.NoteEvent(eventId, expectedRevision)` — so the request type cannot express a note without the revision of the reading it was read from; a reading that moved on under the caller is a conflict and a reading that was cleared is refused with `EVENT_UNAVAILABLE`; and every terminal path of the draft commands (the review, the confirmation, the dismissal) answers with the state the draft is really in — `DraftConfirmed` with the record it made, or `DraftDismissed` — instead of the state the request asked for.

**Alternatives considered:** Keep the nullable `sourceEventId`/`expectedSourceEventRevision` pair and refuse a missing revision at run time with `NeedsClarification`; keep the pair and treat a missing revision as "no revision check", which is the shape the first attempt had; report a terminal draft with the change the request asked for; describe a terminal draft in one wording for both of its states.

**Reason:** The evidence review of the first attempt found that a request could name a note and leave the revision out, so the record was attached to whatever the reading was at that moment, and that a draft that was already confirmed was reported as dismissed while a decided draft's review was reported as saved. The note and the revision travel together in the value, so the bypass has no shape to exist in at all; the revision is still compared at run time, so a reading that moved on is refused rather than overwritten and a cleared reading has nothing left to be recorded against; and the change a no-change answer carries is the stored truth, so a consumer that reads the change instead of the wording is told what the draft really is.

**Consequences:** A caller cannot ask for a record against a note without saying which reading it means, so the editor and any later adapter carry the revision of the picked note; a note whose text was edited can no longer be recorded by hand against the old reading, and the user picks the note again; the terminal vocabulary of the draft commands is now the stored state, so a caller that switches on the change must handle `DraftConfirmed` and `DraftDismissed` where it used to receive `DraftReviewSaved` or the dismissal of a confirmed draft; a draft that is neither confirmed nor dismissed is answered without a change rather than with a state that was never stored.

## [2026-10-25] Score the S8 workflow suite in versioned v2 semantics, not literal fixture equality

**Decision:** The S8 workflow scorer keeps exact tool intent, scope, revisions, spans, amounts, dates, times, bounds, nullness and authority, and judges exactly four declared fields semantically: idempotency key (non-empty, trimmed, unique, replay evidenced), normalized text (deterministic source-backed content matching the app-stored reading), unresolved-reason label (decision exact, label well-formed), and declared extraction version (one producer identifier per case). The rubric is frozen in `tools/slm/s8_workflow_scoring_v2.json`, results must declare their scoring/contract/suite/prompt/tools/fixture versions and hashes, and `score --scoring v1` still reproduces the checkpoint `b26148e` exact-argument scoring.
**Alternatives considered:** Leak the expected keys/text into the prompt or the tool schema; accept any arguments for the idempotent calls; drop or rewrite the affected cases; keep v1 in place and declare the suite unmeasurable; regrade old results as if they had been scored under v2.
**Reason:** The S8.3-a2 discriminator proved two calls with the same real Room effect scored differently only because of literals the model cannot know. The owner approved a semantic rubric that preserves every authority, scope and money/time constraint, keeps cases and denominators immutable, and forbids a retroactive PASS. Versioning plus hash-bound results is the only way to keep the v1 record honest while future samples are scored comparably.
**Consequences:** S8.3 must rerun against the frozen v2 revision and supply a binding block, a stored reading per event call, and one replay probe per profile; v1 and v2 scores can never be mixed in one report; any later loosening of an exact constraint needs a new versioned contract rather than an edit to v2.

## [2026-10-25] Treat the hosted Gemma/Gemini comparison as a bounded experiment, not a product migration

**Decision:** Evaluate `gemma-4-26b-a4b-it` and `gemini-3.5-flash-lite` through the Google AI Studio endpoint with the unchanged frozen S8.2 v2 cases, prompts, fixture and application boundary, synthetic fixture data only, three independent full-suite passes per model, and one frozen request configuration per model in `tools/slm/s8_hosted_request_config.json`. The transport is recorded as a distinct provider/output adapter (`google-function-call-v1`) from the on-device `s8-native-parser-v1` path, and no product code, runtime selection or cloud wiring is changed by the experiment.
**Alternatives considered:** Keep S8.3 closed at the local 1/10–2/10 results; migrate the product to a cloud provider first and evaluate afterwards; run the hosted models through the local native text-envelope templates for superficial comparability; reuse the local llama.cpp sampler settings verbatim even where the API has no equivalent (repeat penalty); evaluate a substitute model when a requested identifier is unavailable.
**Reason:** The owner authorized a hosted comparison specifically to learn whether a larger model can execute the actual permitted workflow before deciding anything about a cloud-first product. Reusing the frozen contract and the real Room command/query services is the only way that comparison answers that question, while a one-turn hosted request with structured function calls is what the provider actually supports — pretending it is the local envelope protocol would measure an adapter that does not exist. Three passes are required because a single sample cannot separate capability from sampling noise, and the requested identifiers were verified live rather than assumed available.
**Consequences:** Hosted numbers stay a separate, clearly labelled transport in every results document and are not a privacy-safe production claim; the local device results remain the shipping evidence and are unchanged; production data still requires redaction/tokenization that preserves exact amount/date mappings through tool calls and results, which this experiment does not implement; any adoption or runtime migration needs a separate owner decision and a new task, not a reinterpretation of these scores.

## [2026-10-25] Replace generative SLM delivery with a privacy-bounded hosted workflow agent

**Decision:** By explicit owner direction, replace local generative SLM delivery with user-configured hosted LLMs through Gemini, OpenAI Chat Completions and Responses adapters, coordinated by a domain-specific multi-turn harness, bundled workflow skills and a mandatory local masking boundary; retain local Laya and offline non-agent functionality.
**Alternatives considered:** Continue local SLM runtime/profile integration; switch to a larger hosted model without improving the one-turn harness; build a general-purpose coding-agent framework.
**Reason:** Local trials had weak workflow results and phone latency/resource costs. Hosted baselines improved tool choice but exposed argument/revision, truncated-output and one-turn orchestration limitations, so those results describe tested model-plus-harness configurations rather than intrinsic model limits. The owner prioritizes capability and user experience over avoiding inexpensive hosted requests; reported prices/free quotas are planning assumptions, not guarantees.
**Consequences:** Sprint 8 is replanned around adequate model-specific budgets, real tool-result turns, ordered skill branches, verified postconditions, safe retry/restart journals, explicit history retention and provider-aware prompt caching. Mask all outbound context/history/tool results locally, preserve exact local values and workflow relationships through validated scoped mappings, and disclose residual contextual exposure. Users configure endpoint/model/key without bundled developer credentials or silent substitution. Remove obsolete SLM product paths without deleting user files; preserve historical trials/FAILs and all financial authority boundaries. S8.3 baseline closure needs reassessment under its explicitly revised criterion and the existing Bronze checkpoint boundary; agent qualification moves to S8.13–S8.14, not a retroactive PASS. Production hosted inference supersedes the earlier evaluation-only restriction only through implemented masking, secure credentials and explicit user consent.

## [2026-10-27] Scope OpenCode session metadata to first-party runs

**Decision:** Send `x-opencode-session` and the app `User-Agent` only to `opencode.ai`, deriving the session header from a per-run local scope id (and a fresh id for settings probes).
**Alternatives considered:** Persist provider-specific headers in each profile; send OpenCode metadata to every hosted endpoint; omit the metadata.
**Reason:** Actual Android OpenCode Go settings probes were rejected before the metadata was present and all three UI validation/save probes succeeded after adding it. The previously verified live Go probes also used both headers. A per-run id stays stable across a workflow's turns, while exact-host gating avoids sending OpenCode-specific metadata to unrelated endpoints.
**Consequences:** No persisted profile/schema change; OpenCode requests carry the local run id and fixed app user agent in the frozen config, while Gemini and custom non-OpenCode origins remain header-free. Additional first-party origins need explicit evidence before they are included.

## [2026-10-28] Bind durable mutation identities to exact tool requests

**Decision:** Keep the executor's existing exact tool-plus-arguments signature map as the authority for operation identity in durable attempts. Preserve the current per-tool attempt seed for that tool's first request, reuse its mapped key only for an exact replay, and derive stable distinct keys from the seed and signature for later changed requests. The model never supplies these identities; the service continues rejecting the same key with a different payload.

**Alternatives considered:** Reuse one operation key for every request to a tool within an attempt; mint a fresh random key per call, which cannot be reproduced after restart; accept a model-supplied idempotency key; remove the first per-tool seed and break existing durable identity behavior.

**Reason:** A single per-tool key makes two distinct writes conflict, while changing the key on an exact replay would defeat idempotence across retries and process restarts. The executor already distinguishes calls by their exact signature in ordinary runs; using that same map in durable mode keeps the existing convention and preserves the service's payload-mismatch protection.

**Consequences:** Exact repeats map to one durable journal row, distinct writes and corrected retries in the same run have distinct reproducible keys, and a reused key with changed payload remains a service conflict. No model authority, hosted interface, or retry budget changes.

## [2026-10-28] Design the agent interface around domain decisions rather than internal DTOs

**Decision:** Document a proposed S8.13-pre interface using flat primitive arguments and observation-bound references, host-owned invariants, atomic event writes, conditional skills and structured errors; this is design direction only, not owner approval to implement or change qualification semantics.

**Alternatives considered:** Expand descriptions around the current nested service request; split event persistence into per-field setters; hide complex objects in JSON strings; hard-code every workflow including candidate selection; require the model to reconstruct an old mutation payload for every explicit replay.

**Reason:** Actual S8.13 traces show malformed scalar tokens, changed replay arguments and unnecessary unresolved status despite correct final event values. More prose alone leaves the model managing offsets, revisions and bookkeeping already known to the host. Conversely, field setters introduce partial writes and hard-coded candidate choice removes a necessary semantic decision.

**Consequences:** [The direct Main design](../../docs/sprint-8-agent-interface-redesign.md) specifies eleven selectively loaded tools, retains one deliberate six-argument atomic event write, and separates global authority, field contracts, workflow guidance and service enforcement. Observation-reference lifecycle, expected-absence CAS, a narrow journal-backed replay API and cursor consistency require implementation/validation. The replay wire/evaluation change needs explicit approval; existing exact-request identity decisions and historical scores remain authoritative until an approved cutover. No runtime, provider or database change is authorized by this record.

## [2026-10-29] Refuse unsupported event-boundary rewrites and silent field removal

**Decision:** At the `record_event` mutation boundary, reject (a) an expected-absence source selection whose span overlaps an existing reading in the same capture at different boundaries, as `STALE_OBSERVATION` with a fresh capture observation, and (b) a correction that supplies null for an amount, temporal anchor or merchant the selected reading already holds, as `MISSING_REQUIRED_MEANING` naming every erased field. Two disjoint source-grounded occurrences remain independent, merchant sub-span handles remain valid only as `merchant_ref`, and partial-reading resume (a previously absent field) is unchanged.

**Alternatives considered:** Binding an overlapping selection to the existing event and replacing its reading (silently relocates the occurrence); creating a second reading and leaving the first (silent duplicate); merging nulls with stored values (silent partial replacement, contradicts full-replacement semantics); adding a removal flag or a new error code to the approved wire contract.

**Reason:** Event identity is exact source span, and this interface deliberately does not rewrite stored boundaries. The design's version is full replacement, so absence may not silently erase stored data; with no removal argument, the safe outcome is a named refusal leading to clarification/manual review. The reviewer confirmed both refusal contracts as consistent with design §4.3 and §6.5.

**Consequences:** A model that selects a different quote or omits a stored field receives a repairable, effect-none error and no write; the user path for a genuine value removal is clarification/manual, not a model silent edit. Landed with behavioral tests over real Room services; no schema field, new error code or historical fixture changed.
 
## [2026-10-29] Scan provider response bodies before protocol parsing

**Decision:** The masked gateway supplies a session-bound full-response guard to every hosted adapter. Each production adapter applies it to the raw response body immediately after receipt and before status classification, parsing, provider-error detail extraction, or continuation assembly. A refusal is a terminal `PrivacyBlocked` result that the workflow executor maps to `MASKING_BLOCKED`.

**Alternatives considered:** Check only parsed assistant text and tool arguments; scan inside the qualification recorder only; allow the executor to repair/retry the blocked response; persist raw bodies to diagnose the historical false-negative location.

**Reason:** Provider protocols may carry unparsed content such as hidden reasoning/signatures or opaque items that can contain mapped source values. A recorder-only check would leave product execution exposed, and an unsafe response must not trigger another provider turn or any tool dispatch. The previous attempt retained only a redacted body marker and boolean, so it cannot support retrospective field attribution.

**Consequences:** Full-response scanning precedes parser handling for Chat Completions, Gemini, and Responses; parsed text/arguments keep their existing boundary checks. Diagnostic evidence records only mapped kinds and allowlisted structural paths. Raw response content remains ephemeral and redacted from wrapper stringification; historical pass-1 data is preserved without regrading.

## [2026-10-29] Scan decoded provider JSON and reject duplicate keys

**Decision:** Keep the terminal response guard before protocol parsing, but scan every decoded JSON string key/value and numeric leaf; reject duplicate object keys, invalid JSON, and unsupported response roots before any status parsing, tool dispatch, or continuation.

**Alternatives considered:** Keep matching the serialized body (which matches Unicode-escape hex digits that are not decoded content); use the existing object-building parser (which keeps only the last duplicate key value); or skip metadata/reasoning/unknown fields to avoid false positives.

**Reason:** Controlled actual-vault evidence proves a serialization-only false positive from a `\\uXXXX` escape and shows that last-value-wins parsing hides an earlier duplicate value. The approved privacy contract concerns decoded values and keys across the complete response, including metadata, reasoning, and unknown fields; duplicate or malformed JSON cannot be allowed to discard a possible earlier value.

**Consequences:** The fail-closed `PrivacyBlocked` path and no-continuation/no-dispatch behavior remain. A duplicate-preserving streaming JSON reader rejects ambiguous object keys and scans all semantic leaves; invalid or unsupported bodies are refused with the existing sanitized privacy failure. The account-detection rules, mapped values, token stripping, outbound masking, and historical qualification results remain unchanged.

## [2026-10-29] Preserve typed safe privacy refusal diagnostics

**Decision:** Carry a value-free response-guard refusal reason, sensitive-kind label when relevant, and allowlisted structural location from the scanner through the provider adapter and workflow executor into local evidence.

**Alternatives considered:** Keep only the generic `MASKING_BLOCKED` stop; persist response text for diagnosis; or let each layer independently infer its own refusal reason from serialized content.

**Reason:** The existing live stop proved fail-closed behavior but could not distinguish a decoded sensitive match from malformed or duplicate JSON. Raw response persistence would expose exactly the content the guard protects, while independent inference could disagree with the actual adapter decision. Typed metadata records the scanner's real result without carrying values or reasoning.

**Consequences:** Diagnostic artifacts can establish which refusal path occurred and whether the executor received it, using only a fixed reason enum, sensitive-kind enum, and allowlisted path; raw response bodies and decoded values remain ephemeral, and the semantic privacy contract is unchanged.

## [2026-10-29] Exempt only validated provider usage counters from semantic privacy matches

**Decision:** The response guard may treat a sensitive-shaped value as technical metadata only at an exact counter path documented for the selected provider protocol and only when its JSON node is a nonnegative integer; every other decoded key and value remains subject to the full privacy scan.

**Alternatives considered:** Block all matched usage numbers; exempt entire `usage` objects or metadata; relax the account detector; or use the serialized-body matcher as the privacy gate.

**Reason:** Snapshot13 showed an actual ACCOUNT match below `response_body.usage`, but the safe path did not reveal the key names or JSON type, so that response cannot justify an exemption. The owner-authorized boundary instead relies on the existing provider usage schema and strict node-type/value validation; unknown usage fields, strings, keys, model content, reasoning, and tool arguments remain scanned. This also separates meaningful decoded data from serialization-only escape digits without weakening the detector.

**Consequences:** The scanner and adapters share one protocol-specific counter contract; malformed counter values become safe protocol errors, while duplicate or invalid JSON remains fail-closed. Qualification masking uses semantic decoded matches, with serialized-body matches retained only as diagnostics. Existing frozen failures remain unchanged and are never regraded.

## [2026-10-29] Reveal only validated usage-key identifiers in safe diagnostics

**Decision:** Under recognized provider usage roots only, response diagnostics may retain an unknown key name when it is a vault-clean ASCII identifier matching `[a-z_]{1,64}`; all other unknown keys remain `other_field`, and exact cross-protocol counter-path matches are reported only as a boolean.

**Alternatives considered:** Keep every unknown usage path collapsed and accept that the provider schema cannot be diagnosed; persist raw response keys or whole bodies; or broaden the counter allowlist based on an unknown numeric match.

**Reason:** The approved diagnostic exposed an ACCOUNT-shaped NUMBER at `response_body.usage.other_field.other_field`, but the two names were intentionally hidden, preventing comparison with the existing Chat Completions, Responses, and Gemini usage contracts. A tightly bounded, vault-checked identifier path supplies only the schema clue needed to distinguish known usage shapes, while preserving the rule that unrecognized values remain blocked.

**Consequences:** Safe path metadata may contain only validated technical identifiers beneath the provider usage container, plus node type and a `known_other_protocol` boolean; no response values, raw keys outside that scope, reasoning, or bodies are persisted. This is diagnostic only and does not authorize any new counter exemption or change business scoring.
## [2026-10-29] Exempt only schema-validated protocol metadata from semantic privacy matches

**Decision:** The inbound response guard may treat a sensitive-shaped value as technical metadata only when it is one of the fields the selected protocol documents at its response root and the value satisfies that schema: an opaque provider-assigned id (bounded nonempty printable ASCII, no assumed prefix), the configured model echoed at the model field, the protocol's fixed object discriminator, and a nonnegative integer creation timestamp. On the exact configured OpenCode Go Zen route it may also treat `usage.opencode.allowance` and `usage.opencode.list_cost` as technical amounts when each is a finite nonnegative JSON number. The exemption is derived from the adapter's validated configuration, reported as a value-free kind/path/node-type record, and never extends to usage containers, unknown fields, arbitrary nested `id`/`model`/`usage` lookalikes, model content, reasoning, tool arguments or sensitive keys.

**Alternatives considered:** Keep blocking every metadata match and accept that a conforming provider response stops the run; exempt whole metadata/usage objects or every `id`/`model` field anywhere in the object; relax the account detector; coerce string or negative values into numbers; or infer route identity from the response body.

**Reason:** The snapshot19 one-case diagnostic proved a decoded semantic ACCOUNT match at `response_body.id` plus the two Go accounting fields of a real Chat Completions response, while earlier controlled cases had shown that duplicate keys or Unicode escapes can also make a raw serialized scan disagree with the decoded walk. Primary contracts define these fields as provider-assigned strings, fixed literals, Unix timestamps and USD JSON numbers, so validating them exactly is a narrow, reviewable boundary instead of a context-free financial exception. Historical freeze04/08/10 bodies remain unrecoverably redacted and are not regraded.

**Consequences:** Malformed metadata (wrong type, empty or non-ASCII id, wrong discriminator, fractional/negative timestamp, string/negative/overflowing amount) becomes a safe `INVALID_PROTOCOL_METADATA` protocol error instead of a privacy refusal; duplicate keys, invalid/trailing JSON and unsupported roots still fail closed. The semantic masking scorer advances to v3 with unchanged business gates, case identities and denominators, and the device/host evidence records only validated-metadata kinds, allowlisted paths and node types. Serialized-body matches remain diagnostic.

## [2026-10-29] Repair one pre-dispatch UNKNOWN_TOKEN response with fixed feedback instead of changing the token contract

**Decision:** Keep the masked-value token shape, every inbound/outbound semantic scan and the unknown/foreign rejection exactly as they are, and add exactly one host-issued repair opportunity per run: when the complete response body scan refuses with `UNKNOWN_TOKEN` before any call in that response was dispatched, discard the whole refused response (including any tool calls it carried), charge the attempt to the turn budget, append one fixed versioned shape-only notice (`s8-agent-masking-repair/v1`, context `s8-agent-context/v5`) as a dynamic prompt tail, and re-issue the same turn once. The replacement response is fully rescanned. A second `UNKNOWN_TOKEN`, or any `FOREIGN_TOKEN`, raw-literal/decoded-sensitive, malformed-JSON or metadata refusal, stays terminal, with no spelling, raw-value, endpoint, protocol or model fallback.

**Alternatives considered:** (a) Short typed aliases such as `[AMOUNT_1]`; (b) making non-mintable bracket text prose so it can never abort a turn; (c) removing the last required token copy in `select_source.quote`; (d) display-only labels with references-only authority; (e) a blind resample with no feedback. Measured evidence: the refusal is content-dependent (same freeze/model: 8/24 refusals in batch 18 versus 1/3 in the a19 reproduction), the offending strings were bare kind markers (`⟦AMT⟧`, `⟦DATE⟧`), and zero near-miss/altered copies existed.

**Reason:** Aliases and prose narrowing do not remove the class — an invented alias-shaped string must still be refused (same friction) or accepted (weakening) — and short aliases lose positive foreign detection, add an ingestion-escape obligation, and need a stored-history epoch because persisted display text would no longer be inert. Making `⟦…⟧` prose is an acceptance change that also does not fix the user-literal collision. Fixed feedback is the only lever aimed at the measured trigger: it tells the model the syntax rule it violated without echoing anything the guard refused (the refused string can itself be a raw known surface or another session's token, so echo would leak), and it contains no bracket-shaped example the model could copy back. One bounded re-issue is a correction-loop improvement, not an acceptance relaxation: token shape, display, all semantic scans and every terminal category are unchanged.

**Consequences:** The privacy rule is now a versioned exception instead of a blanket "never retried" sentence, and the stable instruction block advances to v5 (changing the cache identity, not the token contract). The repair notice must stay bracket-free and versioned; the refused attempt consumes a turn, so a run at its turn bound repairs nothing. Three protocol adapters must lower the notice natively (`appendUserNotice`), which is why the adapter interface gained that method with a fail-closed default. Chronology is recorded content-free (`masking_repairs`: attempt, reason, reissued/terminal) in durable history and qualification. Any future attempt to shorten the token, admit bare placeholders, or retry other masking categories re-opens the leak/weakness risks this decision avoided.

## [2026-10-29] Make provider reasoning non-guard input and always inject the issued-token inventory

**Decision:** The decoded-response privacy guard classifies every decoded value into an explicit channel. Provider reasoning/thinking (`choices[].message.reasoning_content`/`reasoning`, Gemini `thought` parts and `thoughtSignature`, Responses `type: "reasoning"` items) is never guard input; the model's user-visible answer and its tool-call arguments stay scanned in the decoded body, in the gateway before publication/dispatch, and (arguments) again in the typed tool runtime. Every model turn ends with `s8-agent-token-inventory/v1`, the run vault's complete issued-token list (token and kind only, mint order), and the stable prompt block advances to `s8-agent-context/v6`, which is now part of the cache identity.

**Alternatives considered:** Rewriting the strict streaming scan as a DOM walk (would re-implement metadata/usage validation and risk accepted behavior); exempting whole model-output containers at the body level (loses the accepted refusal location/repair path for answers and arguments); an optional `list_tokens` tool (owner requires automatic inventory, not model cooperation); truncating the inventory to fit the budget (silently hides issued tokens); removing the bounded UNKNOWN_TOKEN repair (still reachable for protocol-channel findings, so not truly dead).

**Reason:** The a19-a22 refusal class came from invented token notation inside the model's own reasoning, which the decoded-body scan treated as content. Reasoning is never displayed, rehydrated or used as authority, so guarding it only produced spurious terminal stops; answer and argument enforcement are the points that actually prevent leaks and unauthorized dispatch. A positional pre-scan resolves the sibling `thought`/`type` discriminators a single streaming pass cannot see, leaving the accepted JSON, metadata and usage behavior unchanged. The inventory exists because the model can only copy a token it has been shown; re-issuing it every turn removes reliance on memory without exposing mappings.

**Consequences:** A new protocol reasoning channel must be added to the classifier or it will be guarded (false refusals return). The inventory grows with the vault (bounded at 256 entries) and is charged to the context budget: a prompt plus inventory that cannot fit stops with `CONTEXT_BUDGET` instead of dropping entries. Reasoning may still be retained/replayed natively for protocol continuation but is never promoted into answer text; the run cache identity changes whenever the prompt contract changes.

## [2026-10-30] Retain opaque provider continuation state, strip only plaintext reasoning

**Decision:** Keep Gemini `thoughtSignature` and the Responses `encrypted_content` blob byte-identical in retained continuations while dropping only plaintext reasoning prose (`thought` text, Responses `summary`/`content`/`text`); Chat Completions continues to retain no reasoning. The Responses fixture uses an opaque value in `encrypted_content` and raw only in the plaintext summary.
**Alternatives considered:** Stripping `encrypted_content` with the prose (destroys provider-required continuation state the stateless resend contract depends on); keeping whole reasoning items verbatim (replays mapped raw literals, the r1 finding); reintroducing inbound reasoning rejection (violates the owner-approved inbound-freedom rule).
**Reason:** The S8.5 live captures show Responses stateless chaining resends function_call+output items with no reasoning item in this probe, while Gemini always echoes `thoughtSignature` per turn; the Chat live second request proves reasoning prose needs no retention. An opaque blob is provider state, never displayable plaintext, so scrubbing must target only the plaintext fields the adapters actually parse.
**Consequences:** A future reasoning item whose only sensitive content is inside `encrypted_content` cannot leak as plaintext through our resend; a provider that encrypts plaintext into that blob is outside our visibility. Tests assert the opaque blob survives and plaintext does not.

## [2026-10-31] Bind diagnostic harness launches to one explicit ordered invocation

**Decision:** Represent each diagnostic launch with one `ReproductionInvocation` containing the
attempt ID, snapshot ID, run name and ordered case IDs, and require those values together at the
runner boundary.

**Alternatives considered:** Keep independent hardcoded allowlists in the Android test; infer the
selection from a prior attempt or snapshot; or pass separate per-component identity values that
the preflight, instrumentation and collector reconcile later.

**Reason:** The a26 launch blocker showed that the host runner and instrumentation could disagree
about attempt, snapshot and selected cases before reaching the executor. A single explicit input
lets preflight validate the actual mapped selection before credentials, and makes the same order
and identity available to every producer and collector without changing the fixture or scorer.

**Consequences:** A diagnostic subset must be frozen and launched with the same exact invocation;
changing identity or order requires a fresh candidate freeze and Main approval. No old-case or
old-snapshot alias is part of this contract.

## [2026-10-30] Teach the merchant counterparty distinction with model-facing fewshots only

**Decision:** Fix the item-promoted-to-merchant failure with focused `select_source`/`record_event` descriptions and `event-reading/4` examples (absent null, explicit stored, uncertain null), bumping the skill version and cache identity, while leaving toolset shape, runtime validation, fixtures and scorer untouched.
**Alternatives considered:** Adding a new merchant value-alias tool or API; loosening the scorer safety stop for present-but-wrong merchants; rewriting frozen fixtures with new merchant cases.
**Reason:** The host already accepts the correct handle (exact merchant-substring source alias); the gap is purely model-facing guidance, and the scorer correctly treats a wrong present merchant as a financial-safety stop. New APIs, gate loosening or fixture rewrites would widen the contract instead of closing the guidance gap.
**Consequences:** The skill catalog version moves 3 to 4 and the tool-schema digest moves with the description text; all consumers must migrate together. No new training examples enter the frozen suites and no old batch is rescored.
