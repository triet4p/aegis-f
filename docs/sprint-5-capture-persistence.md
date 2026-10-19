# S5.2 durable source and capture persistence

This document records the implemented S5.2 persistence behavior, privacy boundaries, v6-to-v7 migration, and the decision/release notes needed to review the task without relying on mixed-ownership files. It describes the code as implemented and links the physical-device evidence in `artifacts/sprint-5/workflow-replan/workers/S5.2-replan-a3.md`; independent evidence review and the separate Bronze checkpoint remain gates.

## Scope and stored model

Room database `aegis-ledger.db` is now schema version 7. The `capture_messages` table stores versioned source text and provenance separately from the financial ledger and pending bank drafts. Its columns are `id`, `captureKind`, `sourceKind`, `sourceIdentity`, nullable `bodyText`, nullable `capturedAtEpochMillis`, nullable `captureTimezone`, `revision`, nullable `originRef`, and `tombstone`. A unique index on `(sourceKind, sourceIdentity)` prevents two rows from claiming the same source identity. Additional indexes cover `originRef`, capture time, and tombstone plus capture time.

`CaptureKind` distinguishes `BANK_EVIDENCE_REF`, `USER_NOTE`, and `MANUAL_ANNOTATION`. `CaptureSourceKind` identifies `BANK_NOTIFICATION`, `USER_QUICK_CAPTURE`, `USER_ANNOTATION`, and `LEGACY_LEDGER_DESCRIPTION`. Source identity is stable and is not derived from the source text. `originRef` is a typed pointer such as `BANK_DRAFT:42` or `LEDGER_RECORD:42`, not copied text.

The `CaptureMessageRepository` exposes explicit quick-capture and manual-annotation saves. These rows receive a UUID source identity, revision 0, the current capture instant, and the device timezone. A manual annotation is separate from the bank evidence or ledger record it describes. There is no capture kind or write API for transient prompts or tool traces.

## Editing, deletion, and failure semantics

Only user-authored quick captures and manual annotations can be edited or deleted through `CaptureMessageRepository`. An edit is compare-and-set against `expectedRevision`: it replaces the single current `bodyText` and increments `revision`; it does not preserve the old body in a history column or change a confirmed transaction. A stale edit reports `Changed`. A deletion likewise requires the expected revision, clears `bodyText`, marks `tombstone`, and increments the revision. The tombstone retains source identity and provenance metadata but not the deleted text. Active-capture queries exclude tombstones.

The capture API refuses edits/deletes of bank evidence (`ImmutableSource`); missing rows report `NotFound`, and repeat deletion reports `AlreadyDeleted`. An explicit confirmed-ledger correction updates the associated migrated legacy-description copy in the same Room transaction. An explicit ledger deletion tombstones that legacy copy and deletes the transaction in one Room transaction. Standalone user annotations retain their own identity and are not rewritten by this legacy synchronization path.

Clearing the database row's body is the implemented deletion behavior. This document does not claim secure erasure from SQLite storage, operating-system backups, or other backup copies; those mechanisms are outside this S5.2 persistence contract.

## Permitted bank evidence and privacy boundary

The existing notification permission/package gate and content screening run before intake. Rejected, unsupported, OTP/security, group-summary, empty, or redaction-only notification bodies do not become capture text. For a permitted, parsed bank draft, S5.2 stores a separate immutable `BANK_EVIDENCE_REF` row containing only the parser's memo when the memo field is present. The capture row's stable source identity and origin reference are `BANK_DRAFT:<id>`; its timestamp is the draft's bank-post instant and its timezone is null because no timezone is inferred. A parsed draft with no present memo still receives a reference row with null `bodyText`; an unsupported draft receives no capture row.

Draft creation and its bank-evidence reference are written inside one Room transaction. If the reference cannot be stored consistently, the transaction fails and the new draft is rolled back rather than leaving partial state. Replays of the same source reference are accepted only when the stored provenance and content match; a conflicting identity fails instead of replacing the prior row. Neither annotations nor inference overwrite the immutable bank source text.

## v6-to-v7 migration and legacy provenance

`LedgerDatabase.MIGRATION_6_7` creates `capture_messages` and its indexes, then backfills two source classes:

- Each v6 draft with `parseStatus = PARSED` becomes a `BANK_EVIDENCE_REF` / `BANK_NOTIFICATION` row keyed by `BANK_DRAFT:<draft id>`. Only an already-present parsed memo is copied to `bodyText`; the source instant is the stored bank post time and timezone remains unknown/null. Unsupported drafts are not copied into this table.
- Each existing transaction description becomes a `MANUAL_ANNOTATION` / `LEGACY_LEDGER_DESCRIPTION` row keyed by `legacy-ledger:<transaction id>` and linked by `LEDGER_RECORD:<transaction id>`. The old schema did not establish when that description was originally authored or its timezone, so both fields remain null rather than fabricated.

The migration adds source rows without rewriting the v6 category, rule, draft, or transaction rows. The database builder registers the full migration chain and does not configure destructive-migration fallback; a missing/failed migration is not resolved by deleting user data. A migration failure is expected to abort the upgrade transaction and preserve the prior schema/data. The prior host migration fixture recorded this rollback behavior; that host evidence is not physical-device proof.

## Decision and release note

**S5.2 decision:** Keep parsed bank evidence immutable and separate from user-authored captures/annotations. User text has one current revision guarded by compare-and-set; explicit deletion clears its stored body and leaves only a text-free tombstone. Where v6 lacks original authoring time or timezone, preserve that uncertainty instead of inventing provenance. Confirmed ledger changes remain explicit ledger operations and synchronize only the migrated legacy-description copy atomically.

**S5.2 release note:** The local database now supports durable quick captures and manual annotations alongside immutable parsed-bank evidence references. User-authored text can be revised with stale-write protection or deleted without retaining its body in the active database row. Existing v6 records gain truthful legacy/source references without changing their financial identities or values.

## Verification boundary

Attempt `S5.2-replan-a3` records physical-device evidence on an ALT-LX2/API 34 using only the isolated `io.github.triet4p.aegisf.s52device` package. A populated synthetic v6 fixture migrated to v7 and preserved five categories, five ledger rows, three original drafts, two rules, and the exact 80,000 VND confirmed-expense total; the v6 legacy rows remained time/zone-unknown and the foreign-key check was clean. A quick capture save/edit survived force-stop/reopen with identity, revision, timestamp and timezone intact; explicit delete left a text-free tombstone. An injected migration abort retained v6 `user_version`, identity hash and populated counts, with zero capture rows and no new unique capture index. The device privacy filter rejected synthetic OTP/security content without persistence and rejected an unapproved package before content read; permitted parsed intake created one pending draft and only the parsed memo source row. The app UI then showed the two pending drafts and their detail without confirmation. Manual UI saved a synthetic 45,000 VND Food record; the monthly summary moved from 80,000 to 125,000 VND. The local Laya distribution ranked Food at 92%, and the user-selected Food choice remained unsaved until the explicit manual save. The implementation was run under the isolated package; the real user database and signing material were not touched, and no notification-listener access grant was fabricated. The system-bound listener callback itself was not invoked; the physical intake evidence covers the Android notification filter, permitted repository write and review UI. Exact commands, observations, and fixture isolation/cleanup are in `artifacts/sprint-5/workflow-replan/workers/S5.2-replan-a3.md`. These device results do not replace the fresh evidence review or separate Bronze checkpoint.

Implementation references: `app/src/main/java/io/github/triet4p/aegisf/data/CaptureMessageEntity.kt`, `CaptureMessageDao.kt`, `CaptureMessageRepository.kt`, `IntakeRepository.kt`, `LedgerDatabase.kt`, and `TransactionDao.kt`. The frozen source/privacy contract is `docs/sprint-5-workflow-contract.md`.
