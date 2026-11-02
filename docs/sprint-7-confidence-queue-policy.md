# S7.1 confidence semantics and uncertainty-first queue policy (frozen for S7.2+)

**CAT1 update (2026-11-02):** the active review queue no longer reads model predictions.
It orders unresolved link/bank-data conflicts first, then drafts that have not chosen a
category, then drafts ready for confirm or dismiss; the chronological override is unchanged.
The Laya confidence and prediction sections below are historical S7 policy.

**Scope:** S7.1 policy only. This document freezes what Laya `confidence` means, how
correction rules and unknown outcomes behave, which predictions may be compared, when a
dominant category may be called relatively confident, and the deterministic queue order
with tie, focus, and accessibility rules. It changes no production schema, query,
command, or screen; S7.5/S7.6/S7.7 implement and measure against it.

**Status:** Gold worker, attempt `S7.1-impl-01`. Sprint plan and `docs/PLAN.md` edits
are Main-owned and untouched by this task.

## 1. Sources inspected

The freezes below reuse the S5.1 inspected contract
(`docs/sprint-5-workflow-contract.md`, §2.5) and the implementation it describes. The
following were read for this task; nothing below contradicts them:

- `app/src/main/java/io/github/triet4p/aegisf/data/laya/LayaCalibration.kt` — Jev
  sharpness, temperature buckets, `round4`.
- `app/src/main/java/io/github/triet4p/aegisf/data/laya/LayaCandidatePlanner.kt` —
  pipeline order, shortlist construction, `RetrievalUncertainty`, direct vs model
  decisions, `resolveCurrent`.
- `app/src/main/java/io/github/triet4p/aegisf/data/laya/LayaRuntimeContract.kt` —
  pinned export revision, budgets, runtime states.
- `app/src/main/java/io/github/triet4p/aegisf/data/laya/LayaEffectiveContext.kt` —
  evaluation vector, `LayaContextDecision`, composer room rule.
- `app/src/main/java/io/github/triet4p/aegisf/data/ClassificationSnapshotEntity.kt`,
  `ClassificationSnapshotRepository.kt`, `ClassificationSnapshotDao.kt` — stored
  prediction, dependency digests, fixed stale-reason order.
- `app/src/main/java/io/github/triet4p/aegisf/data/ManualCategoryDecision.kt`,
  `WorkflowCommandService.kt` (`runClaimedClassification` / `completeClaim`) —
  remembered-rule-before-inference, late-result refusal, user-choice preservation.
- `app/src/main/java/io/github/triet4p/aegisf/data/LinkEvidencePolicy.kt`,
  `ContextLinkEntity.kt`, `PendingBankDraftEntity.kt`, `PendingBankDraftDao.kt`,
  `IntakeFieldState.kt`, `IntakeParseStatus.kt` — link verdicts, draft identity and
  inbox order, field states.
- `app/src/main/java/io/github/triet4p/aegisf/data/WorkflowQueryService.kt`,
  `WorkflowQueryTypes.kt` — bounded reads, total orders, paging rules.
- `app/src/main/java/io/github/triet4p/aegisf/ui/BankDraftReviewScreen.kt`,
  `ui/BankDraftSuggestion.kt`, `ui/TransactionEditorScreen.kt` — current inbox,
  suggestion gate, confidence wording.
- `tools/laya/reference_runner.py`, `tools/laya/expense_examples.json`,
  `tools/laya/PROVENANCE.md`,
  `app/src/test/resources/laya/calibration_goldens.json`,
  `artifacts/sprint-4/workers/S4.9-A1-evaluation.json` — real outputs cited in §8.

## 2. Confidence semantics (frozen)

`confidence` is the Jev sharpness of one evaluated distribution:

- `confidence = 1 − H(p)/ln(k)` over exactly the evaluated options plus the final
  `unknown` slot, where `k` is that option count. `1.0` for a single option (the choice
  path always evaluates at least two logits, so this case is theoretical).
- Raw logits are never probabilities. The caller divides by `temperatureFor(optionCount)`
  and applies softmax over exactly the evaluated options in their evaluated order.
- Buckets (pinned, `choice` question): `≤2 → 1.9063563346862793`,
  `≤5 → 1.7601518630981445`, `≤10 → 1.0000158548355103`, `>10 → 0.10058280825614929`.
  Per-qtype pins (`1.6369030475616455` / `1.2514300346374512` / `1.983399510383606`) are
  recorded for provenance; the choice path uses the buckets above.
- Probabilities and confidence are stored rounded to 4 decimals (`round4`), matching the
  upstream reference surface. Ties in §7 compare the stored rounded values.
- Model revision is `CURRENT_MODEL_REVISION` (pinned export
  `68f27dfe5a27a54fb2b1fefc432f43f972e90868` plus budget pins `512/192`); candidate-set
  construction is `CURRENT_CANDIDATE_SET_VERSION` (`laya-candidates-v1`).

What `confidence` is not (frozen prohibitions):

- It is not an accuracy, not a per-category score, and not a match score. A wrong top
  choice can carry `0.6976` (phone `boundary-long-state`, §8 P7); a correct top choice
  can carry `0.5638` (§8 P1).
- It is not comparable across evaluated sets (§5). A `0.95` over one candidate set says
  nothing against a `0.80` over another.
- Sharpness over an `unknown`-topped distribution (e.g. `0.8565` with unknown `0.9491`,
  §8 R8) is not category confidence of any kind.
- There is exactly one confidence field. No second category-confidence column exists,
  and link evidence (§3 of the S5.1 contract) stays a fact comparison, never a score.

## 3. Correction-rule behavior (frozen)

The pipeline order is frozen and unchanged:

1. Privacy first: package/grant gate before any content read; rejected bodies never
   reach storage or inference.
2. A saved exact-description remembered rule answers before inference. Lookup is by the
   request's own description (`decideRememberedBeforeInference`), never by composed
   effective context, and the rule is never passed to the model as a correction, so it
   is counted once. Keys are lowercase with collapsed interior whitespace; only active
   targets answer; rules are never auto-learned, never silently replaced, and never
   applied to ledger rows. Production passes an empty `LayaCategoryCorrectionRule`
   list; the param exists for callers/tests only.
3. Conflicting explicit corrections → `AMBIGUOUS_CORRECTIONS` manual review. Unavailable
   target → `CORRECTION_TARGET_UNAVAILABLE` manual review.
4. A unique exact category-name phrase match answers as `CATEGORY_METADATA` without
   inference. Zero or multiple phrase hits fall through to the model path.
5. Otherwise a budget-exact shortlist is built: the full active catalog when the head
   fits `HEAD_MAX_LEN` (192 tokens), else a deterministic metadata-ranked shortlist with
   ties by stable id; the `unknown` slot is always appended. Refusals before inference:
   `EMPTY_DESCRIPTION`, `NO_ACTIVE_CATEGORIES`, `INVALID_CATEGORY_METADATA`,
   `NO_METADATA_EVIDENCE`, `NO_FIT_CANDIDATES`, `SEQUENCE_UNFIT`,
   `EFFECTIVE_INPUT_OVER_BUDGET` (primary text alone larger than the 316-token room;
   measured S5.4: 317 refused, 316 admitted; the text is never shortened to fit).
   Supplemental readings still follow the existing emptiness, provenance and input-budget
   rules; OTP/security-word and bank-reference patterns are not local Laya input filters.
6. The model decision carries the evaluated category ids in evaluated order, their
   calibrated shares, the same-softmax unknown share, and the one sharpness value.
   Direct decisions (remembered rule, explicit correction, metadata match) carry no
   evaluated set, no shares, and `confidence = null`.

User-category and freshness rules (frozen, owned by the command boundary and snapshot
repository): a late result is refused when the dependency state moved on; a prediction
is never stored over the user's resolved category (`USER_CATEGORY_CHOSEN` is marked
instead); category rename/archive/delete invalidates through `resolveCurrent`
(`StaleCategory`: missing/archived, renamed, duplicate id) and through
`CATEGORY_CHANGED` staleness. A prediction never assigns a category, never confirms
anything, and never reaches the ledger.

## 4. Unknown handling (frozen)

- The `unknown` no-fit slot is always evaluated and its share comes from the same
  softmax as every category share. Unknown selected (`bestIndex` is the unknown slot)
  is `PredictionSource.NO_FIT` with reason `MODEL_NO_FIT`: the input stays reviewable
  and manually categorizable. The ranked rows stay selectable with truthful shares.
- Every manual-review reason stays visible with its cause, never collapsed into a
  silent fallback: `EMPTY_DESCRIPTION`, `NO_ACTIVE_CATEGORIES`,
  `INVALID_CATEGORY_METADATA`, `CORRECTION_TARGET_UNAVAILABLE`,
  `AMBIGUOUS_CORRECTIONS`, `NO_METADATA_EVIDENCE`, `NO_FIT_CANDIDATES`,
  `SEQUENCE_UNFIT`, `EFFECTIVE_INPUT_OVER_BUDGET`, `MODEL_NO_FIT`. Model provisioning
  is reported honestly as `Missing` / `CorruptOrIncompatible` / `Ready`; intake is
  unaffected.
- Subset scope always travels in `RetrievalUncertainty` (active / offered / omitted /
  omitted-metadata-evidence counts) and is never relabeled as full-catalog. Omitted
  categories never receive fabricated shares. With 206 active categories the
  names-only shortlist recalled 1/6 positives and the other 5 stayed
  `NO_METADATA_EVIDENCE` manual (§8 P9): poor retrieval stays visible, never forced.
- The app never invents a category for an unknown outcome, and unknown never confirms,
  dismisses, or reorders anything by itself.

## 5. Comparability grouping (frozen)

Probabilities and confidence are comparable only within one comparability group. The
canonical group key is:

```text
modelRevision | candidateSetVersion | candidateIds in evaluated order
```

The evaluated option count (candidate ids plus the unknown slot) implies the
temperature bucket, so the key carries the bucket with it. Consequences, frozen:

- Same key, including the same candidate order: shares and sharpness are comparable
  within the group. Different order is a different group (the softmax is positional).
- Any difference in model revision, candidate-set construction, evaluated ids, or
  evaluated order: the two predictions are not comparable. The queue must group by key
  (§7) and must never interleave two groups by raw confidence.
- Cross-run comparisons (host reference vs phone evaluation, 5-option vs 206-option
  catalogs, names-only vs described metadata) are never queue inputs, even when the
  raw numbers look similar. §8 marks every non-comparable pair explicitly.
- Subset groups keep their `RetrievalUncertainty` counts; a subset distribution is
  never presented as a full-catalog distribution, whatever its sharpness.
- Stored ties compare at `round4` resolution; §7 breaks remaining ties by age and id.

## 6. Confidence-label policy (frozen)

A dominant category (the top-ranked evaluated category, unknown not selected) may be
labelled relatively confident only when every condition below holds. The conditions
are the explicit measured policy the Sprint 7 acceptance criterion requires: every
number shown is measured from the same evaluated distribution, and the label is
withheld everywhere else.

- L1: decision source is `MODEL`. Direct decisions (`REMEMBERED_RULE`,
  `EXPLICIT_CORRECTION`, `CATEGORY_METADATA`) carry no confidence and are never
  labelled.
- L2: the snapshot is fresh (`staleReason == null`) and the user has not resolved the
  draft's category (`reviewCategoryResolved == false`). Stale, missing, unknown, and
  user-resolved drafts never carry the label.
- L3: the suggested category resolves to a currently active, same-named category
  (`resolveCurrent` yields `ActiveCategory`). Renamed, archived, missing, or duplicate
  ids never carry the label.
- L4: the evaluated scope is stated alongside the label (evaluated count, and
  offered/omitted counts whenever a subset was evaluated). A subset label never claims
  full-catalog scope.
- L5: the draft's context side is clean: no possible-duplicate family membership, no
  `PROPOSED` link, no `UNRESOLVED` amount/moment, and `PARSED` status. An uncertain
  note match is never hidden behind a high category confidence: a Rank 0 draft (§7)
  never carries the label and instead shows that its context needs a decision.
- L6: wording is always relative and always shows its numbers: the label names the
  evaluated scope, the top share, the unknown share, and the sharpness, states that it
  is a suggestion among the evaluated options, and never claims a guarantee or an
  accuracy. It never auto-confirms, never blocks override, and never moves the draft
  out of reach: a labelled draft sorts later within its own group and stays
  reviewable, re-linkable, and confirmable-only-by-the-user.

No numeric cutoff is set in S7.1. Dominance is ordinal (top-ranked within its own
distribution); ordering does the work that a threshold must not do silently. The S4.9
`0.8` value was a reporting threshold for counting confidently-incorrect cases (zero
observed), not a production gate, and it must not be reused as one. Any future absolute
cutoff needs S7.7 measured evidence and owner approval, and S7.5 must implement the
L1–L6 gating exactly as frozen here.

## 7. Queue ordering, ties, focus, and accessibility (frozen for S7.5)

Every pending draft appears in exactly one visible group. Stale or incompatible scores
never drive priority: any stored confidence on a stale snapshot is ignored for
ordering. User overrides never corrupt priority: a result that arrives after a user
choice, category change, unlink, or source edit is refused or marked stale under the
fixed reason order (user choice, context/source, category catalog, remembered rules,
model, candidate-set construction), and the stored first-mover reason is kept.

### 7.1 Groups

Rank 0 — unresolved linking/data conflicts, oldest first (`createdAtEpochMillis`
ascending, then `id` ascending):

- possible-duplicate family membership (points at an earlier same-evidence draft, or is
  pointed at by one): uncertain identity is the user's decision.
- a `PROPOSED` link: an unresolved association the user must accept or refuse.
  (`ACCEPTED` links are context, not conflict; `REJECTED` links are settled decisions.)
- link evidence that is not `UNIQUE` under `LinkEvidencePolicy` (amount/day mismatch,
  missing field, competing candidate, or duplicate partner): ambiguous stays with the
  user.
- bank data needing attention: `amountState`/`momentState` `UNRESOLVED`, or
  `parseStatus` `UNSUPPORTED_FORMAT` (kept as reviewable evidence with no financial
  content).

A missing memo alone is not a Rank 0 conflict: opaque memos are classified or end as
visible no-fit. An incomplete event reading contributes no context text; it does not
by itself promote the draft.

Rank 1 — stale / missing / unknown / non-comparable classification, ordered by
`(subkind, createdAtEpochMillis ascending, id ascending)`:

- 1a missing: no snapshot row (never classified for missing input, model unavailable,
  an over-budget refusal, or failure/cancellation before anything was recorded).
- 1b stale: `staleReason != null` (first-mover reason kept; check order frozen in §7
  preamble). Stored confidence is ignored.
- 1c unknown: `PredictionSource.NO_FIT`, including unknown-topped model distributions
  whose scores are retained. Stored sharpness is ignored for ordering.
- 1d direct: `REMEMBERED_RULE`, `EXPLICIT_CORRECTION`, or `CATEGORY_METADATA`
  (`confidence == null`, not comparable with anything).
- 1e user-resolved: `reviewCategoryResolved == true`
  (`USER_CATEGORY_CHOSEN`). The user's choice stands; the draft waits for explicit
  confirm/dismiss and its old confidence is never shown as a suggestion.

Rank 2 — fresh comparable model predictions, in group sections ordered by canonical
group key ascending (§5); within a section by `(confidence ascending,
createdAtEpochMillis ascending, id ascending)`. Lower sharpness comes first: flatter
distributions need human attention sooner. Higher sharpness sorts later, stays
accessible, and is never auto-confirmed.

### 7.2 Ties, starvation, and the chronological override

- Confidence ties compare at stored `round4` resolution, then oldest
  `createdAtEpochMillis`, then smallest `id`. Ids are unique, so the order is total
  within every section.
- Rank 0 and Rank 1 use age/id order, so the oldest unresolved work is always first
  in its group and is never silently starved. Rank 2 sections are all reachable by
  bounded paging; no record is hidden and no group is dropped.
- A chronological override is always available: all pending drafts by
  (`createdAtEpochMillis` ascending, `id` ascending). Users can inspect every
  high-confidence item there regardless of its group section.

### 7.3 Focus and interaction stability

- F1: the open draft is read by stable id and follows its own decisions. Score and
  list updates never replace the open draft's displayed values mid-interaction; changed
  scores apply on explicit refresh, on the next-work action, or on navigation, and a
  context that moved on marks the viewed prediction stale instead of applying over it.
- F2: paging keeps the S5.5 rules — a new filter starts at the first page; a page that
  lost its rows steps back to the nearest page that still holds rows; a page is never
  appended to; selection is a stable id that survives renames, archives, and deletions
  without silently becoming something else.
- F3: newly changed scores never move the item under the finger. Advancement is an
  explicit next-work action that goes to the oldest Rank 0 draft, not an automatic jump.
- F4: status, provenance, and options stay visible per group (queued / busy / stale /
  error / relatively-confident-with-scope), with cancel/retry/manual-choice actions
  owned by S7.6. Group membership, scope counts, and shares are exposed to
  accessibility labels so a high-confidence item is announced with its scope, not as a
  guaranteed answer.
- F5: nothing auto-confirms, auto-merges, auto-deletes, or resurrects a settled
  decision. Confirmation stays an explicit user action against the exact displayed
  values and revisions.

The current inbox (newest-first by creation instant, then id) is unchanged by this
task. S7.5 implements the Rank 0 → Rank 1 → Rank 2 order above behind its own gate;
until then this document is the frozen rulebook, not a second behavior.

## 8. Representative actual outputs

Measured with the pinned export (`68f27dfe…`; host reference) and the production
lifecycle on the phone (S4.9 evaluation). Host and phone rows are not comparable with
each other for queue purposes (different candidate metadata and scope, §5); they are
shown together to prove the semantics, not to rank across runs.

Host reference (`reference_runner.py`, 5 categories plus `unknown`, temperature
`1.0000158548355103`, 6 evaluated options):

- R1 `meaningful-food`: choice `unknown` (`Food 0.1553`, `unknown 0.7792`),
  confidence `0.5822`. Honest miss: the unknown share outranks the wording match, and
  the draft must end in Rank 1c, never forced into `Food`.
- R2 `meaningful-rent`: `Housing 0.9796`, confidence `0.9336`. Correct and sharp.
- R3 `meaningful-transport`: `Transport 0.9722`, confidence `0.9225`. Correct.
- R4 `meaningful-health`: `Health 0.8411` (`unknown 0.1427`), confidence `0.7153`.
  Correct but softer: ordering input, not a quality claim.
- R5 `ambiguous-alex`: `unknown 0.8532`, confidence `0.6445`. No-fit recalled for
  review.
- R6 `unknown-gift`: `unknown 0.9131`, confidence `0.7631`. High sharpness on unknown:
  proves sharpness is not category confidence.
- R7 `boundary-mask-injection`: `Food 0.7693`, confidence `0.5576`. A high top share
  with modest sharpness: confidence is not the top probability.
- R8 `boundary-long-state`: `unknown 0.9491`, confidence `0.8565`. The sharpest host
  distribution tops unknown: it must stay Rank 1c and must never promote anything.
- R9 `boundary-empty-description`: the planner refuses `EMPTY_DESCRIPTION` before any
  inference. The reference raw shares exist only to prove nothing may be read from
  them; no snapshot, no confidence, Rank 1a.

Phone evaluation (production `LayaInferenceLifecycle`, synthetic names-only catalog,
same pinned export; `artifacts/sprint-4/workers/S4.9-A1-evaluation.json`):

- P1 `meaningful-food`: `Food 0.7271` (`unknown 0.1901`), confidence `0.5638`.
  Correct at modest sharpness.
- P2 `meaningful-rent`: `Housing 0.9849`, confidence `0.9526`.
- P3 `meaningful-transport`: `Transport 0.9743`, confidence `0.9223`.
- P4 `meaningful-health`: `Health 0.9061`, confidence `0.801`.
- P5 `ambiguous-alex`: `unknown 0.9388`, confidence `0.8252`. No-fit recalled.
- P6 `unknown-gift`: `unknown 0.9375`, confidence `0.8345`. No-fit recalled.
- P7 `boundary-long-state`: top `Fun 0.7784` against expected `Food`, confidence
  `0.6976`. Wrong at 0.70: confidence is not accuracy and never auto-confirms.
- P8 overlapping labels (`Food` / `Food travel` / `Travel`): `Travel`, confidence
  `0.4678`. Overlapping names flatten sharpness; the number still only orders within
  its own group.
- P9 206 active categories: recall 1/6; five positives stayed `NO_METADATA_EVIDENCE`
  manual with full uncertainty counts. Poor retrieval stays visible.

Calibration goldens (`app/src/test/resources/laya/calibration_goldens.json`) reproduce
R1–R8 exactly (same logits, temperature, probabilities, confidence), pinning the
interpretation the queue policy relies on.

## 9. Boundary fixtures demonstrating priority

Illustrative drafts. Confidence values are assignments drawn from the measured outputs
in §8 (shown as `← R2` etc.), not new measurements; ages increase with ids so the
chronological override order is `101…114`. Evaluated groups: `G5` (same 5-category
evaluated set in the same order) holds drafts 109–111 and 114; `G2` (a different
11-option evaluated set, hence a different temperature bucket) holds draft 112. The
canonical key of `G5` sorts before `G2`.

- 101: possible-duplicate member; fresh `MODEL` confidence `0.9526` (← P2).
  Rank 0.
- 102: `PROPOSED` link with a competing same-amount/same-day candidate.
  Rank 0, after 101 by age/id.
- 103: `amountState` `UNRESOLVED`. Rank 0, after 102.
- 104: `UNSUPPORTED_FORMAT`, no snapshot. Rank 0, after 103.
- 105: no snapshot (model unavailable at intake). Rank 1a.
- 106: stale `CATEGORY_CHANGED`; stored confidence `0.9336` (← R2) ignored.
  Rank 1b.
- 107: unknown-topped `MODEL` (`unknown 0.9491`, sharpness `0.8565`, ← R8) ignored.
  Rank 1c.
- 108: `REMEMBERED_RULE`, confidence null. Rank 1d.
- 113: user chose the category (`USER_CATEGORY_CHOSEN`). Rank 1e, by age.
- 109: fresh `MODEL` `G5` confidence `0.5638` (← P1). Rank 2, first in section.
- 114: fresh `MODEL` `G5` confidence `0.6976` (← P7 wrong-case values). Rank 2,
  second: ordering follows sharpness, never correctness.
- 110: fresh `MODEL` `G5` confidence `0.801` (← P4). Rank 2, third.
- 111: fresh `MODEL` `G5` confidence `0.801`, newer than 110. Rank 2, fourth: the
  tie breaks by age, then id.
- 112: fresh `MODEL` `G2` confidence `0.9526` (← P2). Rank 2, own section after `G5`:
  never interleaved with `G5` by raw value.

Expected queue order:

```text
101, 102, 103, 104, 105, 106, 107, 108, 113, 109, 114, 110, 111, 112
```

Assertions the fixtures prove:

- A: draft 101 carries the highest confidence in the fixture yet reviews first:
  unresolved identity outranks every prediction.
- B: draft 106 stores `0.9336` but reviews behind missing 105 and ahead of every
  fresh prediction: stale scores never drive priority.
- C: draft 107's `0.8565` sharpness on unknown never promotes it to Rank 2.
- D: drafts 110/111 tie at `0.801` deterministically by age, then id.
- E: draft 112's `0.9526` never jumps ahead of `G5`: cross-group raw values are not
  compared.
- F: draft 114 is ordered by its sharpness, not by being wrong: the queue does not
  know correctness.
- G: drafts 110–112 stay reachable through their group sections and sit at override
  positions 10–12; the label from §6 may apply to the link-clean fresh drafts among
  them, and none of them can confirm itself.

S7.5 encodes these fixtures as deterministic queue tests; until then they are the
frozen acceptance illustrations, not a second test suite.

## 10. Privacy and authority boundary

- This task stores nothing: no schema change, no migration, no new retention. The
  composed model input stays digest-only; prompts stay ephemeral; logs carry no
  financial bodies. No notification content, OTP/security text, account identifier, or
  balance enters any new record, because no new record is created.
- Intake permission and privacy order are unchanged: allowlist then content screening,
  `NotPermitted` reads nothing, `Rejected` keeps only the reason.
- Financial confirmation and correction stay user-only through the shared command
  boundary with expected revisions and idempotency. Agent scope rules are untouched.
- No user data was touched for this task: the real outputs in §8 come from the pinned
  host bundle and the archived S4.9 isolated-fixture evaluation. No ADB session, no
  device write, no installed-app or model change was made or needed.

## 11. What S7.1 does not change

- No Room version change (the chain through v11 from S5.8 is untouched), no DAO, query,
  command, or screen change. The inbox keeps its current newest-first order until S7.5.
- No thresholds are introduced: any absolute confidence cutoff needs S7.7 measured
  evidence and owner approval.
- No S7.2–S7.8 work is started. Triggering, coalescing, snapshot application, queue
  queries, review controls, quality measurement, and effort proof keep their own tasks
  and gates. S7.5 owns the bounded/paged queries, S7.6 the status controls and label
  rendering, S7.7 the real-model quality and lifecycle measurements.

## 12. Decision and release note

**S7.1 decision:** Freeze Laya `confidence` as the Jev sharpness of the evaluated
distribution (same options, order, temperature bucket, and model/config revision or it
is not comparable); keep the remembered-rule → metadata-match → budget-exact-model →
manual-review pipeline with unknown/no-fit always visible; allow the relative-
confidence label only for fresh, active-resolving, link-clean `MODEL` predictions with
stated scope and numbers, never as auto-confirmation; and order review as unresolved
conflicts, then stale/missing/unknown/non-comparable, then comparable fresh
predictions by ascending sharpness with age/id ties, an always-available chronological
override, and focus that never jumps. No numeric cutoff is set.

**S7.1 release note:** Review will put the drafts that need you first — uncertain
matches and missing information before suggestions, and less certain suggestions
before more certain ones — while every suggestion stays yours to confirm. A
"relatively confident" note only ever means "among the options evaluated", always
shows its numbers and scope, and never confirms anything by itself. Nothing about
your data, screens, or saved records changes in this step; the ordering itself
arrives with the review update.

## 13. Verification boundary

- Attempt `S7.1-impl-01` records its evidence in
  `artifacts/sprint-7/workers/S7.1-impl-01.md`. No production files changed.
- Real-output proof: `tools/laya/reference_runner.py` against the machine-local pinned
  bundle (`68f27dfe…`) reproduced the calibration goldens exactly (R1–R8 in §8,
  including the honest `Food → unknown` miss and the `0.8565`-sharp unknown), and the
  archived phone evaluation supplies the production-lifecycle rows (P1–P9, including
  the wrong-at-`0.6976` case and 1/6 large-catalog recall). Host runs prove
  weights-plus-calibration behavior, not Android UI; the phone rows cover the
  production lifecycle.
- Instruction check: every source path cited in §1 exists at the cited symbol; no
  invented threshold or cross-group comparison appears in §§5–7 and §9 (verified by
  re-reading the frozen rules against the fixture order).
- No permanent automated test is added: a policy document has no runtime behavior to
  pin, and inventing a source-text test would violate the delivery contract. S7.5
  encodes §9 as deterministic queue tests behind its own gate.
- No schema change was necessary, so no populated-data upgrade smoke applies.
- `CHANGELOG.md` is intentionally untouched: it carries Main-owned/unreleased Sprint
  6 entries and mixed worktree changes, and S5 precedent keeps contract/policy records
  (with their decision/release notes) as standalone task-owned documents.
