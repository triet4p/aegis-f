# Sprint 5 workflow contract — S5.1 source mapping (frozen for S5.2+)

**Scope:** S5.1 contract mapping only. This document freezes the current-implementation
map and the planned-contract naming for S5.2+. It changes no production schema or
runtime. Every claim below is backed by the sources listed in §1; anything not yet
decided is marked `[PLANNED]` or listed in §8 as an unresolved prerequisite.

**Status:** Gold worker, attempt `S5.1-replan-a1`. Sprint plan and `docs/PLAN.md` edits
are Main-owned and untouched by this task.

## 1. Sources inspected

- `app/src/main/java/io/github/triet4p/aegisf/data/LedgerDatabase.kt` (VERSION 6,
  MIGRATION_1_2 … MIGRATION_5_6, `build()`)
- `app/schemas/io.github.triet4p.aegisf.data.LedgerDatabase/6.json`
  (identity hash `ff97e68ec56591d684c3e5fd371fe854`)
- Entities: `TransactionEntity.kt`, `PendingBankDraftEntity.kt`, `CategoryEntity.kt`,
  `RememberedCategoryRuleEntity.kt`, `RememberedCategoryRuleKey.kt`
- Enums: `TransactionType.kt`, `ConfirmationStatus.kt`, `RecordSource.kt`,
  `IntakeDirection.kt`, `IntakeReviewStatus.kt`, `IntakeFieldState.kt`,
  `IntakeParseStatus.kt`, `PendingDraftIdentity.kt`
- DAOs: `TransactionDao.kt`, `CategoryDao.kt`, `PendingBankDraftDao.kt`,
  `BankDraftReviewDao.kt`, `RememberedCategoryRuleDao.kt`
- Repositories: `LedgerRepository.kt`, `IntakeRepository.kt`,
  `BankDraftReviewRepository.kt`; wiring in `AppContainer.kt`, `AegisApplication.kt`,
  `MainActivity.kt`
- Money/time: `VndMoney.kt`, `LedgerClock.kt`, `ui/ManualDateTime.kt`
- Notification: `notification/NotificationIntake.kt`, `BankNotificationParser.kt`,
  `BankNotificationListener.kt`, `ListenerRecovery.kt`, `NotificationAccess.kt`,
  `data/NotificationAccessStore.kt`
- Review/suggestion UI: `ui/BankDraftReviewScreen.kt`, `ui/TransactionEditorScreen.kt`,
  `ui/TransactionDetailScreen.kt`, `ui/TransactionListScreen.kt`,
  `ui/CategoryManagerScreen.kt`, `ui/ExpenseSummary.kt`, `ui/ExpenseSummaryPanel.kt`,
  `ui/TransactionFilter.kt`, `ui/LedgerDisplay.kt`, `ui/BankDraftSuggestion.kt`,
  `ui/ManualCategorySuggestion.kt`, `ui/BankNotificationSettingsScreen.kt`,
  `ui/LayaProvisioningScreen.kt`
- Laya: `data/laya/LayaRuntimeContract.kt`, `LayaCandidatePlanner.kt`,
  `LayaCalibration.kt`, `LayaRuntime.kt`, `LayaInferenceLifecycle.kt`,
  `LayaSequence.kt`, `LayaTokenizer.kt`, `LayaModelProvisioner.kt`
- Tests exercised (not modified): `PendingBankDraftDeduplicationTest`,
  `PendingBankDraftMigrationTest`, `PendingBankDraftPersistenceTest`,
  `BankDraftReviewTest`, `LedgerDatabasePersistenceTest`, `CategoryDeletionTest`,
  `NotificationIntakeFilterTest`, `NotificationIntakePersistenceTest`,
  `InferenceIntakeIndependenceTest`, `LayaCandidatePlannerTest`,
  `LayaInferenceLifecycleTest`, `LayaCalibrationTest`, and the rest of
  `:app:testDebugUnitTest`

Negative result (backed by search, not assumption): no production table, entity, DAO,
or repository named `capture*`, `note_event*`, `context_link*`, `*snapshot*`,
`idempoten*`, or agent `*scope*` exists in `app/src`. The only CAS/revision field in
production is `pending_bank_drafts.reviewRevision` with `expectedRevision` parameters.

## 2. Current implementation map (`[EXISTS]`)

### 2.1 Stored tables (Room `LedgerDatabase`, VERSION 6, file `aegis-ledger.db`)

| Table | Entity | Identity / constraints | What it stores |
| --- | --- | --- | --- |
| `categories` | `CategoryEntity` | `id` AUTOINCREMENT, never reissued; `archived` flag | User categories; archived rows stay for history |
| `transactions` | `TransactionEntity` | `id` AUTOINCREMENT; FK `categoryId → categories(id)` RESTRICT/CASCADE-update; index on `categoryId` | Ledger records: `type`, positive `amountVnd`, `occurredAtEpochMillis`, `description`, nullable `categoryId`, `confirmation`, `source`, created/updated times |
| `pending_bank_drafts` | `PendingBankDraftEntity` | `id` AUTOINCREMENT; UNIQUE `(deliveryId, evidenceDigest)`; indices on `evidenceDigest`, `possibleDuplicateOfDraftId`, `reviewStatus`, `confirmedTransactionId`; FK `confirmedTransactionId → transactions(id)` SET NULL; self-FK `possibleDuplicateOfDraftId → pending_bank_drafts(id)` SET NULL | Permitted-notification evidence + review overrides + terminal link |
| `remembered_category_rules` | `RememberedCategoryRuleEntity` | PK `patternKey`; FK `categoryId → categories(id)` CASCADE/CASCADE; index on `categoryId` | Deliberately remembered exact-description rules only |

Migrations `[EXISTS]`: `MIGRATION_1_2` (create drafts), `MIGRATION_2_3` (delivery +
evidence identity rebuild, earliest-row-wins dedupe of exact duplicates),
`MIGRATION_3_4` (post time into `deliveryId`), `MIGRATION_4_5` (review override
columns + `reviewRevision`), `MIGRATION_5_6` (remembered rules). No
`fallbackToDestructiveMigration`; version mismatch fails loudly.

### 2.2 Provenance and money/time rules (`[EXISTS]`)

- `RecordSource`: `MANUAL` vs `BANK_NOTIFICATION`. Set at creation
  (`TransactionInput` carries both `confirmation` and `source`);
  `TransactionCorrection` cannot rewrite either.
- `ConfirmationStatus`: `CONFIRMED` vs `DRAFT`. Production writers set `CONFIRMED`
  only: manual `addTransaction` defaults `CONFIRMED`; draft confirmation builds
  `CONFIRMED` + `BANK_NOTIFICATION`. No production path writes `DRAFT`
  (the enum exists; only tests construct `DRAFT` rows).
- `IntakeDirection` (`DEBIT`/`CREDIT`/`UNKNOWN`) is the bank's vocabulary, never a
  ledger type. `TransactionType` (`EXPENSE`/`INCOME`/`TRANSFER`) is the ledger's
  vocabulary. A debit never auto-selects a type.
- `VndMoney`: positive whole-dong magnitude, `MIN_AMOUNT_VND = 1`, ASCII-digits-only
  parse, `checkedAddToTotal` overflow rule (overflow reports a proven lower bound,
  never wraps).
- `ManualDateTime`: `YYYY-MM-DD` / `HH:mm` grammars, device-default zone for the
  instant, non-lenient refusal of impossible moments; month bounds are
  half-open device-local `[monthStart, nextMonthStart)`.
- Bank wall-clock (`BankStatedMoment.date/time`) stays zone-less until the user's
  confirmation converts it under the device-zone policy. Amounts come only from the
  `GD:` line with `DONG_SUFFIXES`; `TK:` is never extracted; masked `SDC:` balance
  is never read as the amount; conflicts stay `Unresolved`/`Ambiguous`, never guessed.

### 2.3 Notification intake, dedupe, and confirmation (`[EXISTS]`)

Privacy order (frozen): allowlist package check first (`isIntakePermitted`: system
grant AND exact package match), then content screening (`decideNotificationIntake`).
`NotPermitted` reads no content field. `Rejected` carries the reason only — no body
is retained, logged, or counted. Only `Permitted` (title/text/bigText) reaches the
parser. OTP/security/group-summary/empty/redaction-only payloads are discarded.

Delivery vs evidence (frozen):

- `deliveryId = draftDeliveryId(package, key, postTime)` — the platform post,
  including keyless `nokey` form. Post time is part of delivery identity.
- `evidenceDigest = draftEvidenceDigest(bank, parseStatus, amount fields, direction,
  moment fields, memo fields)` — length-prefixed canonical form over permitted
  fields only; raw bodies, account identifiers, and balances never enter it.
- `PendingBankDraftDao.store()` in one transaction: same `(delivery, evidence)` →
  return stored id, write nothing, whatever its review state; new delivery → insert
  with `possibleDuplicateOfDraftId = MIN(id)` sharing the digest (or null); unique
  index enforces the rule against concurrent callbacks (loud failure on
  constraint/decision disagreement, never silent second row; never `REPLACE`).

Review transitions (frozen — see §5 for the frozen table):

- Intake writes `PENDING` rows only; nothing in intake writes `CONFIRMED`/`DISMISSED`.
- `saveReview(id, expectedRevision, edits)`: PENDING + revision match only; archived
  or removed category ids refused (`CategoryUnavailable`); returns
  `Saved(revision+1)` / `Changed` / `NotFound` / `AlreadyReviewed` /
  `CategoryUnavailable`.
- `confirmDraft(...)`: validates amount/date/time/type/category (transfer needs no
  category; otherwise an explicitly resolved category is required), builds one
  `TransactionEntity(CONFIRMED, BANK_NOTIFICATION)`, then `BankDraftReviewDao.confirm`
  atomically claims the draft (`PENDING` + revision + link null), inserts the ledger
  row, and links it. Double confirmation returns `AlreadyReviewed`; stale editors get
  `Changed`; failures add no row (`NeedsCorrection` names fields, never writes).
- `dismissDraft(id)`: single conditional update to `DISMISSED`; terminal drafts are
  never reopened — replays resolve to the stored id and report terminal state.
- Reconciliation (`reconcileActivePosts` + `reconcileCurrentActive`) reuses the same
  filter and the same delivery/evidence identity; removed notifications are never
  reconstructed; revocation stops further writes (fail-closed) while stored drafts stay.

### 2.4 Correction and category rules (`[EXISTS]`)

- `editTransaction(id, TransactionCorrection)`: keeps id, creation time,
  confirmation, and source; may retain the row's already-stored archived category
  (history), but may not newly assign an archived/missing category; transfers carry
  no category. `deleteTransaction` deletes the row (unknown id is a no-op).
- `deleteCategory` runs one transaction: detach transactions (become uncategorized,
  totals unchanged) + clear pending-review presets (revision bumped, editor told
  `Changed` on stale write) + cascade-remove rules, then delete the row; reports
  `CategoryDeletionImpact`. No un-archive path exists. Category ids are never reissued.
- Remembered rules: key `rememberedCategoryRuleKey` (lowercase, collapse interior
  whitespace); `rememberIfActive` requires an active target and never silently
  replaces; `findActiveCategory` resolves only while the target stays active;
  explicit `remove` only. Rules are never learned automatically and never applied to
  ledger rows. Production UI checks the stored rule *before* inference
  (`decideRememberedBeforeInference`) and does **not** pass it as
  `LayaCategoryCorrectionRule` — both `TransactionEditorScreen` and
  `BankDraftReviewScreen` call `suggest(input, assignable)` with the default empty
  correction list. `LayaCategoryCorrectionRule` values are currently constructed only
  in tests.

### 2.5 Laya candidate / probability / confidence (`[EXISTS]` — read carefully)

Pipeline order (frozen): explicit correction → category-name metadata match →
budget-exact model shortlist → `ManualReview` (never a silent fallback).

- `LayaCandidatePlanner.plan`: rejects invalid metadata, blank input, and zero active
  categories; conflicting correction triggers → `AMBIGUOUS_CORRECTIONS`; unavailable
  target → `CORRECTION_TARGET_UNAVAILABLE`; single phrase match → `MetadataMatch`;
  full-catalog fit → `Model(all)`; otherwise deterministic metadata-ranked shortlist
  bounded by `HEAD_MAX_LEN`, or `ManualReview(NO_METADATA_EVIDENCE /
  NO_FIT_CANDIDATES / SEQUENCE_UNFIT)` with `RetrievalUncertainty`
  (active/offered/omitted counts + omitted-metadata-evidence count).
- `decide`: direct plans never invoke inference. Model plans calibrate exactly once
  via `LayaCalibration.decide`: per-bucket temperature (`temperatureFor`: ≤2, ≤5,
  ≤10, else), softmax over exactly the evaluated options plus the final `unknown`
  slot, best index, rounded probabilities (`round4`), and rounded Jev confidence.
- `confidence` meaning (frozen): distribution sharpness `1 − H(p)/ln(k)` over the
  evaluated vector, `1.0` for a single option. It is **not** an accuracy, a
  per-category score, or a match score. Direct plans carry `confidence = null` and no
  scores (`CategoryScores`); there is no second confidence field anywhere.
- Comparability (frozen): probabilities/confidence are comparable only within the
  same evaluated option set, same order, same temperature bucket, and same
  model/config revision. Subset scope always travels in `RetrievalUncertainty` and is
  never relabeled as full-catalog. Omitted categories never receive fabricated shares.
- Display-time staleness: `resolveCurrent` re-checks every evaluated row against
  fresh metadata by stable id — renamed, archived, missing, or duplicate ids become
  `StaleCategory` (or non-selectable `ResolvedOption`s); valid rows stay selectable
  with their truthful shares. Permanent deletion reuses this path.
- Suggestion gate (frozen input precedence): remembered rule before inference;
  blank input stays manual; OTP/security-like text and mixed letter-digit reference
  codes stay manual (never a language allowlist); explicit note evaluated **alone**
  when present, else the visible memo text as-is — the two are never concatenated,
  the stored memo is never rewritten, the note is never persisted, and the category
  field changes only on explicit user action. Results that arrive after form/text/
  category changes are discarded (`takeIfCurrent` → `Idle`/`Stale`).
- Lifecycle (frozen): `LayaInferenceLifecycle` serializes one active + one replaceable
  pending request; cancellation withdraws delivery but never claims to stop native
  work; idle sessions/threads release; notification intake never touches inference
  (`InferenceIntakeIndependenceTest` pins this).

## 3. Caller table — every existing caller and its S5.2+ migration

`→` marks the planned migration direction. Nothing below is migrated in S5.1.

| # | Existing caller (`[EXISTS]`) | Current call | S5.2+ migration (`[PLANNED]`) |
| --- | --- | --- | --- |
| C1 | `AegisApplication.onCreate` | Builds `AppContainer`; triggers active reconciliation when granted | Unchanged wiring; new tables open through the same `LedgerDatabase.build` + migration chain |
| C2 | `AppContainer` | Constructs `ledgerRepository`, `intakeRepository`, `bankDraftReviewRepository`, `layaInferenceLifecycle`, `notificationAccessStore` | Owns the new shared application layer instance; still the only graph entry |
| C3 | `MainActivity` destinations | Reads all four providers; passes repos + lifecycle into inbox/detail/editor/list/manager/summary screens | Pass the shared command/query layer alongside (not around) current repos until cutover; no parallel write paths at S5.8 |
| C4 | `BankNotificationListener.persistOne / drainQueuedIntake / reconcileCurrentActive` (+ `updateIntakeRepository`, `updateAllowedPackages`, `intakeRepositoryFor` fallback) | `IntakeRepository.storePermitted` per permitted/active candidate behind the `Connected` guard | Same entry point; store path gains capture/message persistence behind it (S5.2) without changing dedupe identity |
| C5 | `IntakeRepository.storePermitted / drafts / observeDrafts` | Parse → `PendingBankDraftDao.store` | Keep exact identity function; later tasks add capture/event writes in the same background step, never inside the DAO |
| C6 | `PendingBankDraftDao.store / findById / findAll / observeAll` | Delivery/evidence CAS + newest-first reads | Add sibling DAOs for captures/events/links/snapshots; this DAO's SQL stays until S5.8 cutover removes no column early |
| C7 | `BankDraftReviewRepository.saveReview / confirmDraft / dismissDraft` | Validation + `BankDraftReviewDao` CAS calls | Migrate to shared user-only confirm/dismiss commands (S5.7) with caller scope + idempotency; keep `expectedRevision` CAS semantics |
| C8 | `BankDraftReviewDao.saveReview / confirm / dismiss` (+ `claimForConfirmation`, `linkConfirmedTransaction`, `markDismissed`) | The one cross-table atomic transition | Remains the terminal-write pattern to copy (conditional claim → insert → link in one `@Transaction`); new commands reuse it |
| C9 | `LedgerRepository` categories | `add/rename/archive/deleteCategory`, `categoryDeletionImpact`, `categories/observeCategories/assignable` | Reads move to shared bounded query layer (S5.5); deletes keep the detach-count-report transaction |
| C10 | `LedgerRepository` transactions/rules | `addTransaction(TransactionInput)`, `editTransaction(id, TransactionCorrection)`, `deleteTransaction`, `transactions/observeAll/findTransaction`, `remember*/findRemembered*/removeRemembered*` | Writes move to shared commands (S5.6 user-prep vs S5.7 user-only confirm/correct); correction still cannot rewrite confirmation/source |
| C11 | `CategoryDao / TransactionDao / RememberedCategoryRuleDao` | Direct SQL for the four tables | Add new DAOs beside them; each schema task ships its own migration + populated-data smoke |
| C12 | `BankDraftReviewScreen` inbox + `DraftDetailContent` | `observeDrafts/observeCategories/categories`, `findRememberedCategoryForDescription`, `layaInferenceLifecycle.suggest`, `resolveCurrent`, `saveReview/confirmDraft/dismissDraft` | Same screen drives link/propose/reject + classification-snapshot commands (S5.6/7, S6); suggestion gate precedence unchanged |
| C13 | `TransactionEditorScreen` | `findTransaction/findCategory`, `observeCategories/categories`, remembered-rule lookup, `suggest`, `addTransaction/editTransaction`, `rememberCategoryForDescription/removeRememberedCategoryRule` | Manual save/correct flows re-route through shared commands with idempotency keys; rule actions stay explicit |
| C14 | `TransactionDetailScreen` | `findTransaction/findCategory/deleteTransaction` | Delete stays an explicit user-only command (never agent-exposed) |
| C15 | `TransactionListScreen` | `observeTransactions/observeCategories` + `applyTransactionFilters` | Reads move to shared typed queries (S5.5); filters stay read-only |
| C16 | `CategoryManagerScreen` | `observeCategories/addCategory/renameCategory/archiveCategory/categoryDeletionImpact/deleteCategory` | Same commands via shared layer; impact-count contract preserved |
| C17 | `CurrentMonthExpenseSummary` + `summarizeMonthExpenses` | Derives confirmed-expense month totals from the live stream | Exact filter (EXPENSE + CONFIRMED + month window) and overflow rule frozen; S5.5 exposes it as a shared aggregate primitive |
| C18 | `TransactionFilter/LedgerDisplay/ManualDateTime/VndMoney/LedgerClock` | Filtering, labels, grammars, zone policy, clock indirection | Reused as-is; new inputs reuse the same grammars/zone/overflow rules |
| C19 | `draftSuggestionInput / isBlockedForSuggestionSafety` + `ManualCategorySuggestionMachine` | Note-vs-memo precedence, safety blocks, stale-result discard | Frozen as the suggestion input contract; link confidence stays separate from category confidence |
| C20 | Laya stack (`LayaRuntime.suggest`, `plan/decide/resolveCurrent`, `LayaCalibration`, `LayaInferenceLifecycle.suggest`, `LayaSequence`, provisioner + provisioning screen) | Review-only suggestions, never writes | Gains versioned snapshot persistence (S5.4) and effective-context inputs; no new confidence field; thresholds need explicit establishment first |
| C21 | Settings/access (`BankNotificationSettingsScreen`, `NotificationAccessStore`, `isNotificationAccessGranted/isIntakePermitted/requestListenerRebind`/intents, `ListenerRecovery`) | Allowlist + grant plumbing, fail-closed | Unchanged authority root for intake; capture retention/deletion UI is new work, not a change here |
| C22 | Host tests (dedup, migration, persistence, review, intake, inference-independence, planner, lifecycle, calibration) | Pin current behavior on the real Room file | Extended per schema task (migration + populated-data smoke); no source-text/mock-echo tests as delivery |

## 4. Frozen lifecycle transitions

- Draft: `PENDING → CONFIRMED` (one atomic claim→insert→link) or `PENDING → DISMISSED`
  (one conditional update). No other transition exists; terminal states never reopen,
  never rewrite evidence/digest, never change identity.
- Ledger record: created `CONFIRMED` (manual or draft-confirm); corrected in place
  (same id, same creation/confirmation/source); deleted explicitly (no-op on unknown
  id). No draft-promotion path except the atomic draft-confirm.
- Category: active → archived (history kept) → optionally deleted (links cleared,
  rules cascaded, ids never reissued). No un-archive. Archived ids are refused for
  new assignments but may be retained by the record already carrying them.
- Remembered rule: absent → remembered (active target only) → removed (explicit) or
  cascade-removed (category delete) or silently ineligible (target archived). Never
  auto-learned, never silently replaced, never applied to stored rows.
- Laya request: idle → loading → suggested / no-fit / stale / unavailable; late or
  superseded results never assign. Suggestions never write.

## 5. Frozen input precedence

1. Privacy first: package/grant gate before any content read; rejected bodies never
   retained.
2. Evidence first: bank fields stay as stated (or explicitly unresolved); intake never
   invents amount/type/zone/memo/reference.
3. User text wins at review: confirmation writes only the user's explicit
   amount/date/time/type/category/memo; bank values prefill display but never
   auto-complete confirmation. Missing/invalid required fields block with named
   corrections.
4. Suggestion order: remembered exact rule → category-name phrase match → model on a
   budget-exact candidate set → explicit manual review. Direct hits carry no scores.
5. Note vs memo: explicit note alone, else visible memo as-is; never concatenated;
   safety-blocked note yields null (no silent fallback to memo).
6. Freshness wins: any context/link/category/rule/model revision change invalidates
   pending predictions; stale results are shown as stale or discarded, never applied
   over a newer user choice; background work never overwrites the user.

## 6. Privacy, retention, authority, and user-owned overrides (frozen)

- Privacy: raw notification bodies, OTP/security text, account identifiers, balances,
  and rejected payloads are never stored, logged, or counted beyond reason/state/count.
  Intake stores only permitted fields + routing provenance. Inference receives only the
  effective suggestion input; intake never retains inference. Logs carry no financial bodies.
- Retention: drafts persist until the user confirms/dismisses; terminal rows and links
  persist; ledger rows persist until explicitly deleted/corrected; remembered rules
  persist until removed/cascaded/invalidated. No auto-confirmation, auto-merge,
  auto-deletion, or resurrection exists. Capture/note retention, deletion, tombstones,
  and backup scope are `[PLANNED]` (S5.2) — not decided here.
- Authority: every mutation today is local UI/listener code calling repositories
  directly; there is no caller-scope parameter, no agent adapter, and no idempotency
  key — only `reviewRevision`/`expectedRevision` CAS. Financial confirmation and
  correction are user-only by construction (no exposed agent path exists to remove).
  Agent scoped reads/preparation, unique-evidence attach policy, and denied/stale/
  duplicate outcomes are `[PLANNED]` and must follow the table in `docs/PLAN.md`
  §Application Authority.
- User-owned overrides (frozen): bank direction never decides ledger type; stored memo
  never rewritten by suggestions; corrections never rewrite confirmation/source;
  category deletion impact is counted and reported before/after; stale editors lose
  (`Changed`) instead of overwriting; possible duplicates stay paired for human
  resolution.

## 7. Planned schema / migration / API naming (`[PLANNED]`, reusing conventions)

Conventions reused: snake_case tables, `*Entity`/`*Dao`/`*Repository` names,
`MIGRATION_X_Y` chain with exported schemas, `draftDeliveryId`-style canonical
identity functions, `reviewRevision`-style CAS, `IntakeReviewStatus`-style terminal
enums, `BankDraftReviewResult`-style sealed outcomes, `VndMoney`/`ManualDateTime`
validation placement. Planned names are reservations for S5.2+, not existing symbols.

- S5.2 — `capture_messages` (`CaptureMessageEntity` / `CaptureMessageDao`,
  `MIGRATION_6_7`): `id`, `captureKind` (`BANK_EVIDENCE_REF`/`USER_NOTE`/
  `MANUAL_ANNOTATION`), `sourceKind`, `bodyText` (user/allowed-bank text only),
  `capturedAtEpochMillis`, `captureTimezone`, `revision`, `originRef`
  (draft/ledger link), `tombstone`. Legacy descriptions backfill as
  legacy-origin annotations — never invented provenance.
- S5.3 — `note_events` (`NoteEventEntity`: `id`, `captureId`, `spanStart/spanEnd`,
  `normalizedText`, `amountVnd?`, `merchant?`, `anchorDate/anchorTime/anchorTimezone?`,
  `extractionVersion`, `status`, `revision`) and `context_links`
  (`ContextLinkEntity`: `id`, `eventId`, `draftId`, `state`
  (`PROPOSED`/`ACCEPTED`/`REJECTED`), `evidence`, `revision`, `userOverride?`) with
  UNIQUE `(eventId, draftId)` + no-resurrection rule for `REJECTED`.
- S5.4 — `classification_snapshots` (`ClassificationSnapshotEntity`: `id`, `draftId`,
  `contextRevision`, `candidateIds`, `probabilities`, `unknownShare`, `confidence`,
  `candidateSetVersion`, `modelRevision`, `ruleRevision`, `predictionTime`,
  `staleReason?`). Still one Jev sharpness value; no second confidence column.
- S5.5/6/7 — one in-process shared layer (name reserved: `WorkflowApplicationService`;
  exact object name chosen at implementation): bounded query primitives and
  `saveCapture / reviseCapture / proposeLink / attachLink / rejectLink / unlink /
  requestClassification / confirmDraft / dismissDraft / correctRecord` commands with
  `(callerScope, expectedRevision, idempotencyKey)` and
  `Applied / NoChange / NeedsClarification / Conflict / NotFound / Denied /
  Cancelled / Failed` outcomes. Agent scope is supplied by trusted code, never by
  model arguments; financial confirm/correct/delete stay user-only.

## 8. Unresolved prerequisites (nothing silently narrowed)

- P1: No capture/event/link/snapshot/operation storage, DAO, or migration past v6.
- P2: No agent adapter, caller-scope plumbing, or tool boundary; authority is
  currently implicit process-local calls.
- P3: No idempotency keys or operation log; only draft `reviewRevision` CAS exists.
- P4: No capture timezone field; bank moments are zone-less wall clocks and the only
  zone policy is device-default at confirmation.
- P5: No multi-event extraction, bounded candidate retrieval, or match-evidence
  scoring; amount/time similarity must not imply identity (pinned by current dedupe).
- P6: No prediction-staleness engine across context/link/category/rule/model revisions.
- P7: No capture retention/deletion/tombstone/backup UX; raw financial chat-log
  prohibition vs durable-note requirement still needs the S5.2 delete semantics.
- P8: Laya confidence comparability unestablished: Jev sharpness is not comparable
  across candidate sets/temperatures/models; queue ordering must group by evaluated
  scope or stay conservative — no thresholds set in S5.1.
- P9: Remembered-rule storage vs `LayaCategoryCorrectionRule` param are currently
  unwired in production (empty list passed); S5.4 must decide how stored rules enter
  effective context without double-counting the pre-inference short-circuit.
- P10: No cash/unmatched-event Manual-prefill flow with source-event resolution marking.
- P11: Real-bank live-notification behavior (OEM timing, lock screen, restart,
  revocation on device) remains user-run; this task exercised only stored-database
  state plus host fixtures.

## 9. Current-database walkthrough (privacy-safe, read-only)

Method: `adb exec-out run-as … cat databases/aegis-ledger.db(+wal/shm)` to a host
temp copy (single consistent snapshot, app versionCode 4 / versionName 1.0, listener
package enabled, allowlist holds exactly the one user-configured VietinBank package);
opened read-only; no writes, no notification posted, no confirmation executed, no
uninstall. A stray in-package copy made during discovery was removed; the user
database was left intact. Raw memo bodies, account digits, and personal names are
deliberately not reproduced here — only shapes, states, and linkage.

Observed (snapshot 2026-10-19):

- `PRAGMA user_version = 6`; `room_master_table` identity hash matches schema `6.json`
  (`ff97e68e…`); `PRAGMA foreign_key_check` clean; zero orphan confirmed-links.
- `categories`: 4 rows, all active (`archived = 0`).
- `transactions`: 12 rows, all `CONFIRMED`; by type 9 `EXPENSE` / 2 `INCOME` /
  1 `TRANSFER`; by source 11 `MANUAL` / 1 `BANK_NOTIFICATION` (the single
  draft-confirm linkage is intact).
- `pending_bank_drafts`: 13 rows — 9 `PENDING`, 1 `CONFIRMED` (linked), 3
  `DISMISSED`; all 13 `PARSED` with `PRESENT` amount/moment/memo in this snapshot
  (no `UNSUPPORTED_FORMAT`/`MISSING`/`UNRESOLVED` rows present this time).
- Dedupe linkage live in user data: 5 rows carry `possibleDuplicateOfDraftId`
  pointing at the earliest same-evidence row; each pair shares one evidence digest
  under distinct delivery identities (keyless posts distinguished by post time),
  matching the tested rule that equal evidence under a different delivery stays two
  flagged candidates. Terminal rows keep their state and links.
- Review columns present (`reviewAmountText/DateText/TimeText/Memo/MemoEdited/Type/
  CategoryId/CategoryResolved/reviewRevision`); sampled pending rows sit at
  `reviewRevision 0`. Full per-row override values are not enumerated here to avoid
  publishing user financial text.
- App-private files observed by name only: `files/laya/model/` holds the pinned
  `laya.onnx`, `laya.onnx.data`, `laya_config.json`, and `tokenizer/`; intake
  preferences hold only the allowlist package name.

Limitations: `sqlite3` is absent on the device (snapshot-then-host-read was the only
read path); WAL was copied alongside the main file for consistency; only one phone
was reachable; no live notification arrival, confirmation, dismissal, migration, or
restart was executed against user data in this task.

## 10. Verification performed

- Host suite: `cmd /c "gradlew.bat :app:testDebugUnitTest --console=plain --offline"`
  → `BUILD SUCCESSFUL`, 29 classes / 214 tests / 0 failures / 0 errors / 0 skipped
  (JUnit XML in `app/build/test-results/testDebugUnitTest/`).
- Device walkthrough above via ADB (`AAAJBB4930914141`, model `ALT_LX2`); read-only
  snapshot + `PRAGMA` checks observed.
- No permanent tests added: documentation/workflow task — verification is the
  instruction cross-check plus the exercised behavior above, not a new automated test.
  No production files changed in this task.
