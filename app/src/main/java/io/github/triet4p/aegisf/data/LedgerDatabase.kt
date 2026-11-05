package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.Cursor
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's only local database: user-defined categories, ledger records, explicit remembered
 * category rules, bank-notification drafts, versioned source captures with their event
 * readings and context links, the historical prediction snapshots about those drafts, and the
 * hosted workflow's run history.
 *
 * The `classification_snapshots` table is retained as historical provenance only. No active
 * code writes or reads it for a decision: the hosted workflow's category choice is stored on
 * the draft's own review state.
 *
 * Schema versioning rules for this database:
 *
 * - [VERSION] is bumped when the stored schema changes, and also when the canonical form of a
 *   stored identity changes, and every bump ships a migration. Building a version mismatch is
 *   deliberately resolved by failing loudly rather than by `fallbackToDestructiveMigration`,
 *   which would delete the user's financial history to make a schema problem go away.
 * - `exportSchema = true` writes one `app/schemas/io.github.triet4p.aegisf.data.LedgerDatabase/<version>.json`
 *   file per version. Those files are the reference for the next migration.
 * - A migration preserves what is already stored: ledger records keep their ids and values,
 *   and a stored draft keeps its id, its review state, and its draft-to-ledger link. Where a
 *   new column is derived from already-stored evidence, or a stored identity is re-derived
 *   because its canonical form changed, the migration derives it from that row's own stored
 *   values with the same function the running app uses, so a draft stored under the old schema
 *   keeps its identity instead of losing the ability to recognize its own replay.
 */
@Database(
    entities = [
        CategoryEntity::class,
        TransactionEntity::class,
        PendingBankDraftEntity::class,
        RememberedCategoryRuleEntity::class,
        CaptureMessageEntity::class,
        NoteEventEntity::class,
        ContextLinkEntity::class,
        ClassificationSnapshotEntity::class,
        WorkflowOperationEntity::class,
        ArrivalReconciliationOperationEntity::class,
        AgentRunEntity::class,
        AgentRunAttemptEntity::class,
        AgentConversationEntity::class,
        AgentTraceEventEntity::class,
    ],
    version = LedgerDatabase.VERSION,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao

    abstract fun transactionDao(): TransactionDao

    /** Pending bank-notification drafts: reviewable intake, never ledger records. */
    abstract fun pendingBankDraftDao(): PendingBankDraftDao

    /** Atomic draft-review transitions across the pending and ledger tables. */
    abstract fun bankDraftReviewDao(): BankDraftReviewDao

    /** The uncertainty-first review order of the pending drafts. */
    abstract fun reviewQueueDao(): ReviewQueueDao

    /** Explicit description-scoped category rules for manual expense entry. */
    abstract fun rememberedCategoryRuleDao(): RememberedCategoryRuleDao

    /** Versioned user captures, manual annotations, and immutable permitted bank evidence refs. */
    abstract fun captureMessageDao(): CaptureMessageDao

    /** Interpreted events of a capture, one row per source span. */
    abstract fun noteEventDao(): NoteEventDao

    /** Proposed, accepted, and refused links between an event and a pending draft. */
    abstract fun contextLinkDao(): ContextLinkDao

    /** The durable record of the preparation commands and their idempotency keys. */
    abstract fun workflowOperationDao(): WorkflowOperationDao

    /** Durable note/draft arrival work, resumed from this process's startup path. */
    abstract fun arrivalReconciliationOperationDao(): ArrivalReconciliationOperationDao


    /** The durable identity and lifecycle of one agent run; user-visible history. */
    abstract fun agentRunDao(): AgentRunDao

    /** The durable per-attempt records of agent runs; allowed effect refs only. */
    abstract fun agentRunAttemptDao(): AgentRunAttemptDao

    /** The user-visible conversation lines of agent runs; safe export source. */
    abstract fun agentConversationDao(): AgentConversationDao

    /** The append-only local production trace of agent runs; private diagnostics, never exported. */
    abstract fun agentTraceDao(): AgentTraceDao

    companion object {
        const val VERSION = 18

        /** File name of the on-device database. */
        const val NAME = "aegis-ledger.db"

        /**
         * Version 1 to 2: add the pending bank-draft intake table. Existing categories
         * and ledger records are untouched; a version-1 database keeps every row.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_bank_drafts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sourcePackage` TEXT NOT NULL, " +
                        "`notificationKey` TEXT, " +
                        "`postedAtEpochMillis` INTEGER NOT NULL, " +
                        "`bank` TEXT NOT NULL, " +
                        "`parseStatus` TEXT NOT NULL, " +
                        "`amountState` TEXT NOT NULL, " +
                        "`amountVnd` INTEGER, " +
                        "`amountUnresolvedReason` TEXT, " +
                        "`direction` TEXT NOT NULL, " +
                        "`momentState` TEXT NOT NULL, " +
                        "`statedDate` TEXT, " +
                        "`statedTime` TEXT, " +
                        "`momentUnresolvedReason` TEXT, " +
                        "`memoState` TEXT NOT NULL, " +
                        "`memo` TEXT, " +
                        "`memoUnresolvedReason` TEXT, " +
                        "`reviewStatus` TEXT NOT NULL, " +
                        "`confirmedTransactionId` INTEGER, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`confirmedTransactionId`) REFERENCES `transactions`(`id`) " +
                        "ON UPDATE CASCADE ON DELETE SET NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_reviewStatus` " +
                        "ON `pending_bank_drafts` (`reviewStatus`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_confirmedTransactionId` " +
                        "ON `pending_bank_drafts` (`confirmedTransactionId`)",
                )
            }
        }

        /**
         * Version 2 to 3: give each stored draft its delivery and evidence identity.
         *
         * Two columns are added and the table is rebuilt around them, because SQLite cannot add a
         * `NOT NULL` column without a default or a self-referencing foreign key in place:
         * `deliveryId` (the platform post the row arrived as: package, key, post time) and
         * `evidenceDigest` (the canonical digest of the evidence it states), constrained unique
         * together so a replayed callback cannot store a second row while a later post under a
         * reused notification key stays its own draft, plus `possibleDuplicateOfDraftId`, the
         * earliest draft a later row's identical evidence may duplicate. A version-2 row still
         * carries its own post time in `postedAtEpochMillis`, so the delivery identity derived
         * here is the one the running app computes for that post.
         *
         * Every existing row is copied unchanged — same id, values, review state, and
         * draft-to-ledger link — with its delivery identity and evidence digest derived by
         * [draftDeliveryId] and [draftEvidenceDigest], the same functions the running app uses, so
         * an already-stored draft still recognizes its own replay after the upgrade. Ledger and
         * category rows are not touched at all.
         *
         * Version 2 had no identity constraint — every permitted callback stored its own row — so
         * that file can hold two rows for one delivery that the version-3 identity cannot tell
         * apart: same package, key, post time, and evidence digest. The upgrade keeps the earliest
         * such row (lowest id, the same row the running app's own store would have kept, since a
         * repeated delivery of one identity writes nothing) and does not copy its exact duplicates;
         * otherwise the unique index below could not be created at all and the upgrade would fail
         * instead of running. A version-2 draft could only ever be `PENDING` with no ledger link
         * (version 2 had no review transition), so no reviewed state can be collapsed this way.
         * Rows that share a key but differ in post time or evidence are *not* duplicates and are all
         * copied.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(DRAFT_TABLE_V3_SQL)
                // Read the rows in id order and keep the first row of each derived identity: the
                // temp table has no constraint yet, and copying an exact duplicate would make the
                // unique index below impossible to create.
                val keptRows = mutableListOf<PendingBankDraftEntity>()
                val keptIdentities = mutableSetOf<Pair<String, String>>()
                db.query("SELECT * FROM `pending_bank_drafts` ORDER BY `id`").use { rows ->
                    while (rows.moveToNext()) {
                        val draft = rows.toStoredDraft()
                        if (keptIdentities.add(draft.deliveryId to draft.evidenceDigest)) {
                            keptRows += draft
                        }
                    }
                }
                for (draft in keptRows) {
                    db.execSQL(INSERT_DRAFT_V3_SQL, draft.asBindings())
                }
                db.execSQL("DROP TABLE `pending_bank_drafts`")
                db.execSQL("ALTER TABLE `$DRAFT_TABLE_V3` RENAME TO `pending_bank_drafts`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_pending_bank_drafts_deliveryId_evidenceDigest` " +
                        "ON `pending_bank_drafts` (`deliveryId`, `evidenceDigest`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_evidenceDigest` " +
                        "ON `pending_bank_drafts` (`evidenceDigest`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_possibleDuplicateOfDraftId` " +
                        "ON `pending_bank_drafts` (`possibleDuplicateOfDraftId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_reviewStatus` " +
                        "ON `pending_bank_drafts` (`reviewStatus`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_pending_bank_drafts_confirmedTransactionId` " +
                        "ON `pending_bank_drafts` (`confirmedTransactionId`)",
                )
            }
        }

        /**
         * Version 3 to 4: make the stored delivery identity name the platform post, not just the
         * notification slot.
         *
         * Version 3 encoded `deliveryId` from the source package and the notification key alone,
         * so a bank that reuses one key for a later real transaction whose permitted evidence was
         * byte-identical to the earlier delivery's produced exactly the earlier row's identity and
         * the later real transaction was deduplicated away. [draftDeliveryId] now includes the
         * post time the row already stores, which makes that later post a different delivery.
         *
         * The stored values are therefore re-derived row by row from the row's own stored
         * provenance ([sourcePackage], [notificationKey], [postedAtEpochMillis]) — the same
         * function and the same columns the running app uses, so no identity is invented and the
         * rewrite is idempotent. Nothing else changes: the table keeps its shape, and every row
         * keeps its id, its values, its review state, its draft-to-ledger link, and its
         * possible-duplicate link. The unique `(deliveryId, evidenceDigest)` index cannot be
         * violated by the rewrite, because version 3 already allowed at most one row per
         * (package, key, evidence) and rows that share a key keep different evidence digests.
         * Ledger, category, and index definitions are untouched.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Read every row's provenance first: updating the table while its own cursor is
                // still open is not a defined scan. The values are then written outside it.
                val reDerived = mutableListOf<Pair<Long, String>>()
                db.query(
                    "SELECT `id`, `sourcePackage`, `notificationKey`, `postedAtEpochMillis` " +
                        "FROM `pending_bank_drafts`",
                ).use { rows ->
                    while (rows.moveToNext()) {
                        reDerived += rows.getLong(0) to draftDeliveryId(
                            sourcePackage = rows.getString(1),
                            notificationKey = if (rows.isNull(2)) null else rows.getString(2),
                            postedAtEpochMillis = rows.getLong(3),
                        )
                    }
                }
                for ((id, deliveryId) in reDerived) {
                    db.execSQL(
                        "UPDATE `pending_bank_drafts` SET `deliveryId` = ? WHERE `id` = ?",
                        arrayOf<Any?>(deliveryId, id),
                    )
                }
            }
        }

        /**
         * Version 4 to 5: keep user review corrections separate from the parsed evidence.
         *
         * These nullable override fields leave the S3.5 delivery/evidence identity untouched;
         * a replay still resolves to the same row after a correction, confirmation, or dismissal.
         * Existing rows start with no overrides, no chosen ledger type, and revision zero, while
         * every draft id, terminal state, ledger link, duplicate link, and ledger record survives.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pending_bank_drafts` ADD COLUMN `reviewAmountText` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `pending_bank_drafts` ADD COLUMN `reviewDateText` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `pending_bank_drafts` ADD COLUMN `reviewTimeText` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `pending_bank_drafts` ADD COLUMN `reviewMemo` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `pending_bank_drafts` ADD COLUMN `reviewMemoEdited` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `pending_bank_drafts` ADD COLUMN `reviewType` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `pending_bank_drafts` ADD COLUMN `reviewCategoryId` INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE `pending_bank_drafts` ADD COLUMN `reviewCategoryResolved` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `pending_bank_drafts` ADD COLUMN `reviewRevision` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Version 5 to 6: store only explicit exact-description category rules. Rules use a
         * category foreign key so deleting an unreferenced category removes its rules, while
         * archiving leaves the rule stored but makes it ineligible for new assignments.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remembered_category_rules` (" +
                        "`patternKey` TEXT NOT NULL, " +
                        "`categoryId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`patternKey`), " +
                        "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) " +
                        "ON UPDATE CASCADE ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_remembered_category_rules_categoryId` " +
                        "ON `remembered_category_rules` (`categoryId`)",
                )
            }
        }

        /**
         * Version 6 to 7: add versioned durable captures without rewriting ledger or draft rows.
         *
         * Parsed bank drafts contribute only their already-allowed memo plus a stable local draft
         * identity. Unsupported drafts are not copied into this source-text table. Existing
         * transaction descriptions are labeled legacy-origin annotations; the old schema did not
         * record an annotation capture instant or timezone, so those remain unknown.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `capture_messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`captureKind` TEXT NOT NULL, " +
                        "`sourceKind` TEXT NOT NULL, " +
                        "`sourceIdentity` TEXT NOT NULL, " +
                        "`bodyText` TEXT, " +
                        "`capturedAtEpochMillis` INTEGER, " +
                        "`captureTimezone` TEXT, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`originRef` TEXT, " +
                        "`tombstone` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_capture_messages_sourceKind_sourceIdentity` " +
                        "ON `capture_messages` (`sourceKind`, `sourceIdentity`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_capture_messages_originRef` " +
                        "ON `capture_messages` (`originRef`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_capture_messages_capturedAtEpochMillis` " +
                        "ON `capture_messages` (`capturedAtEpochMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_capture_messages_tombstone_capturedAtEpochMillis` " +
                        "ON `capture_messages` (`tombstone`, `capturedAtEpochMillis`)",
                )
                db.execSQL(
                    "INSERT INTO `capture_messages` (`captureKind`, `sourceKind`, " +
                        "`sourceIdentity`, `bodyText`, `capturedAtEpochMillis`, `captureTimezone`, " +
                        "`revision`, `originRef`, `tombstone`) " +
                        "SELECT 'BANK_EVIDENCE_REF', 'BANK_NOTIFICATION', " +
                        "'BANK_DRAFT:' || `id`, " +
                        "CASE WHEN `memoState` = 'PRESENT' THEN `memo` ELSE NULL END, " +
                        "`postedAtEpochMillis`, NULL, 0, 'BANK_DRAFT:' || `id`, 0 " +
                        "FROM `pending_bank_drafts` WHERE `parseStatus` = 'PARSED'",
                )
                db.execSQL(
                    "INSERT INTO `capture_messages` (`captureKind`, `sourceKind`, " +
                        "`sourceIdentity`, `bodyText`, `capturedAtEpochMillis`, `captureTimezone`, " +
                        "`revision`, `originRef`, `tombstone`) " +
                        "SELECT 'MANUAL_ANNOTATION', 'LEGACY_LEDGER_DESCRIPTION', " +
                        "'legacy-ledger:' || `id`, `description`, NULL, NULL, 0, " +
                        "'LEDGER_RECORD:' || `id`, 0 FROM `transactions`",
                )
            }
        }

        /**
         * Version 7 to 8: add the event interpretations and their context links.
         *
         * The new tables start empty, because version 7 stored no event reading and no link.
         * Nothing that version 7 already held is rewritten: the captures, their source
         * identities and revisions, the bank drafts, the categories, the rules, and the
         * ledger records keep their rows, ids, and values, and the confirmed totals computed
         * from them are unchanged by the upgrade.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`captureId` INTEGER NOT NULL, " +
                        "`eventIdentity` TEXT NOT NULL, " +
                        "`spanStart` INTEGER NOT NULL, " +
                        "`spanEnd` INTEGER NOT NULL, " +
                        "`normalizedText` TEXT, " +
                        "`amountVnd` INTEGER, " +
                        "`merchant` TEXT, " +
                        "`anchorDate` TEXT, " +
                        "`anchorTime` TEXT, " +
                        "`anchorTimezone` TEXT, " +
                        "`intervalEndDate` TEXT, " +
                        "`intervalEndTime` TEXT, " +
                        "`extractionVersion` TEXT NOT NULL, " +
                        "`unresolvedReason` TEXT, " +
                        "`status` TEXT NOT NULL, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`sourceRevision` INTEGER NOT NULL, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`captureId`) REFERENCES `capture_messages`(`id`) " +
                        "ON UPDATE CASCADE ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_note_events_captureId_eventIdentity` " +
                        "ON `note_events` (`captureId`, `eventIdentity`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_note_events_captureId` " +
                        "ON `note_events` (`captureId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_note_events_status` " +
                        "ON `note_events` (`status`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `context_links` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`eventId` INTEGER NOT NULL, " +
                        "`draftId` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`evidence` TEXT, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`userOverride` TEXT, " +
                        "`activeEventId` INTEGER, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`eventId`) REFERENCES `note_events`(`id`) " +
                        "ON UPDATE CASCADE ON DELETE CASCADE, " +
                        "FOREIGN KEY(`draftId`) REFERENCES `pending_bank_drafts`(`id`) " +
                        "ON UPDATE CASCADE ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_context_links_eventId_draftId` " +
                        "ON `context_links` (`eventId`, `draftId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_context_links_activeEventId` " +
                        "ON `context_links` (`activeEventId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_context_links_draftId` " +
                        "ON `context_links` (`draftId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_context_links_state` " +
                        "ON `context_links` (`state`)",
                )
            }
        }

        /**
         * Version 8 to 9: add the stored prediction snapshots.
         *
         * The new table starts empty, because version 8 stored no prediction: a prediction is
         * made from the current context, and there was no place to keep one. Nothing that
         * version 8 already held is rewritten: the captures, the event readings, the links,
         * the bank drafts, the categories, the rules, and the ledger records keep their rows,
         * ids, and values, and the confirmed totals computed from them are unchanged by the
         * upgrade.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `classification_snapshots` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`draftId` INTEGER NOT NULL, " +
                        "`contextRevision` TEXT NOT NULL, " +
                        "`categoryRevision` TEXT NOT NULL, " +
                        "`ruleRevision` TEXT NOT NULL, " +
                        "`modelRevision` TEXT NOT NULL, " +
                        "`candidateSetVersion` TEXT NOT NULL, " +
                        "`decisionSource` TEXT NOT NULL, " +
                        "`suggestedCategoryId` INTEGER, " +
                        "`candidateIds` TEXT NOT NULL, " +
                        "`probabilities` TEXT NOT NULL, " +
                        "`unknownShare` REAL, " +
                        "`confidence` REAL, " +
                        "`effectiveInputDigest` TEXT NOT NULL, " +
                        "`eventIds` TEXT NOT NULL, " +
                        "`predictionTime` INTEGER NOT NULL, " +
                        "`staleReason` TEXT, " +
                        "FOREIGN KEY(`draftId`) REFERENCES `pending_bank_drafts`(`id`) " +
                        "ON UPDATE CASCADE ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_classification_snapshots_draftId` " +
                        "ON `classification_snapshots` (`draftId`)",
                )
            }
        }

        /**
         * Version 9 to 10: add the durable record of the preparation commands.
         *
         * The new table starts empty, because version 9 recorded no command: an
         * idempotency key, a caller scope, and a structured outcome are what this table
         * holds, and version 9 had nowhere to keep them. Nothing that version 9 already
         * held is rewritten: the captures, the event readings, the links, the bank drafts,
         * the categories, the rules, the ledger records, and the stored predictions keep
         * their rows, ids, and values, and the confirmed totals computed from them are
         * unchanged by the upgrade.
         *
         * The unique index on the idempotency key is what makes a repeated request a
         * no-change rather than a second effect, and it is created with the table so the
         * upgrade cannot leave a table that accepts a duplicate key.
         *
         * The row is also the durable claim of a request that is still running: the owner
         * token names the attempt that holds the key, and the lease says when a claim whose
         * attempt is gone may be taken over. Both are null for a request that finished.
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workflow_operations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`idempotencyKey` TEXT NOT NULL, " +
                        "`scopeId` TEXT NOT NULL, " +
                        "`command` TEXT NOT NULL, " +
                        "`payloadDigest` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`changeSummary` TEXT, " +
                        "`ownerToken` TEXT, " +
                        "`leaseExpiresAtEpochMillis` INTEGER, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_workflow_operations_idempotencyKey` " +
                        "ON `workflow_operations` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workflow_operations_createdAtEpochMillis` " +
                        "ON `workflow_operations` (`createdAtEpochMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workflow_operations_command` " +
                        "ON `workflow_operations` (`command`)",
                )
            }
        }

        /**
         * Version 10 to 11: the compare-and-set revision of a ledger record, and the
         * resolution of a note event into a ledger record.
         *
         * The revision column starts at zero for every stored record, because a record
         * that was never corrected has been corrected zero times. The two resolution
         * columns start empty, because version 10 could not record an occurrence that the
         * user wrote down by hand: an idempotency key, a caller scope, a structured
         * outcome, and a resolution are what the newer tables hold, and version 10 had
         * nowhere to keep them. Nothing that version 10 already held is rewritten: the
         * captures, the event readings, the links, the bank drafts, the categories, the
         * rules, the ledger records, and the stored predictions keep their rows, ids, and
         * values, and the confirmed totals computed from them are unchanged by the
         * upgrade.
         *
         * The columns are added in place, so no stored row is copied, no identity is
         * re-derived, and no reference is left dangling: the ledger records keep their ids,
         * and every stored reading keeps its own identity, its span, and its revision.
         */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `transactions` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE `note_events` ADD COLUMN `resolvedTransactionId` INTEGER")
                db.execSQL("ALTER TABLE `note_events` ADD COLUMN `resolvedAtEpochMillis` INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_note_events_resolvedTransactionId` " +
                        "ON `note_events` (`resolvedTransactionId`)",
                )
            }
        }

        /**
         * Version 11 to 12: the durable classification jobs of the pending bank drafts.
         *
         * The new table starts empty. Version 11 scheduled classification work in process
         * memory only, so there is no job to carry over: a draft that was stored under the
         * old schema is still stored, still reviewable, and still classified when a
         * supported delivery for it is re-read, when a relevant change re-opens it, or
         * when the user asks for a suggestion. The migration invents no job and therefore
         * starts no inference for drafts the user never touched.
         *
         * Nothing that version 11 already held is rewritten: the categories, ledger
         * records, drafts, rules, captures, readings, links, stored predictions, and
         * command records keep their rows, ids, and values, and the confirmed totals
         * computed from them are unchanged by the upgrade.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `classification_work` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`draftId` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`requestedQuestion` TEXT NOT NULL, " +
                        "`executedQuestion` TEXT, " +
                        "`attemptCount` INTEGER NOT NULL, " +
                        "`snapshotId` INTEGER, " +
                        "`statusReason` TEXT, " +
                        "`ownerToken` TEXT, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, " +
                        "`updatedAtEpochMillis` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_classification_work_draftId` " +
                        "ON `classification_work` (`draftId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_classification_work_state_updatedAtEpochMillis` " +
                        "ON `classification_work` (`state`, `updatedAtEpochMillis`)",
                )
            }
        }

        /**
         * Version 12 to 13: the durable S8.10 agent run history.
         *
         * Three new tables start empty, because version 12 kept no agent run state: the
         * run's durable identity and lifecycle (`agent_runs`), its per-attempt records
         * with allowed effect references and privacy-safe observations
         * (`agent_run_attempts`), and the user-visible conversation lines
         * (`agent_conversation`). Nothing that version 12 already held is rewritten.
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_runs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`runId` TEXT NOT NULL, " +
                        "`scopeId` TEXT NOT NULL, " +
                        "`intentNames` TEXT NOT NULL, " +
                        "`requestFingerprint` TEXT NOT NULL, " +
                        "`attempt` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`stopReason` TEXT, " +
                        "`maxOutputTokens` INTEGER, " +
                        "`answerDigest` TEXT, " +
                        "`requestText` TEXT NOT NULL, " +
                        "`answerText` TEXT, " +
                        "`revisionEpochMillis` INTEGER, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, " +
                        "`updatedAtEpochMillis` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_runs_runId` " +
                        "ON `agent_runs` (`runId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_agent_runs_requestFingerprint_scopeId` " +
                        "ON `agent_runs` (`requestFingerprint`, `scopeId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_runs_scopeId_createdAtEpochMillis` " +
                        "ON `agent_runs` (`scopeId`, `createdAtEpochMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_runs_state` " +
                        "ON `agent_runs` (`state`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_run_attempts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`runId` TEXT NOT NULL, " +
                        "`attempt` INTEGER NOT NULL, " +
                        "`idempotencyKey` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`stopReason` TEXT, " +
                        "`effectKind` TEXT, " +
                        "`eventId` INTEGER, " +
                        "`eventRevision` INTEGER, " +
                        "`linkId` INTEGER, " +
                        "`linkState` TEXT, " +
                        "`linkRevision` INTEGER, " +
                        "`draftId` INTEGER, " +
                        "`decisionSource` TEXT, " +
                        "`observationJson` TEXT NOT NULL, " +
                        "`ownerToken` TEXT, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, " +
                        "`updatedAtEpochMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`) " +
                        "ON UPDATE CASCADE ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_run_attempts_runId_attempt` " +
                        "ON `agent_run_attempts` (`runId`, `attempt`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_run_attempts_idempotencyKey` " +
                        "ON `agent_run_attempts` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_run_attempts_state` " +
                        "ON `agent_run_attempts` (`state`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_run_attempts_createdAtEpochMillis` " +
                        "ON `agent_run_attempts` (`createdAtEpochMillis`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_conversation` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`runId` TEXT NOT NULL, " +
                        "`sequence` INTEGER NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`) " +
                        "ON UPDATE CASCADE ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_conversation_runId_sequence` " +
                        "ON `agent_conversation` (`runId`, `sequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_conversation_runId` " +
                        "ON `agent_conversation` (`runId`)",
                )
            }
        }

        /**
         * Version 13 to 14: the local production trace of agent runs.
         *
         * The new `agent_trace_events` table starts empty of live evidence, because version 13
         * never captured a provider body, tool exchange or clarification. Every run and attempt
         * that version 13 already stored gets one explicit `EVIDENCE_GAP` row instead: the owner
         * requires missing historical evidence to be marked, and inventing a request, a response
         * or a reasoning item that was never observed would be a fabricated trace.
         *
         * Nothing that version 13 already held is rewritten: the run/attempt journal, the
         * user-visible conversation, and every business row keep their values, ids and totals.
         * The gap rows are new diagnostic rows only; they authorize nothing.
         */
        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_trace_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`runId` TEXT NOT NULL, " +
                        "`scopeId` TEXT NOT NULL, " +
                        "`attempt` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`turn` INTEGER, " +
                        "`callId` TEXT, " +
                        "`direction` TEXT NOT NULL, " +
                        "`completion` TEXT NOT NULL, " +
                        "`payloadJson` TEXT NOT NULL, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`) " +
                        "ON UPDATE CASCADE ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_trace_events_runId_attempt` " +
                        "ON `agent_trace_events` (`runId`, `attempt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_trace_events_createdAtEpochMillis` " +
                        "ON `agent_trace_events` (`createdAtEpochMillis`)",
                )
                db.execSQL(
                    "INSERT INTO `agent_trace_events` (`runId`, `scopeId`, `attempt`, `kind`, " +
                        "`turn`, `callId`, `direction`, `completion`, `payloadJson`, " +
                        "`createdAtEpochMillis`) " +
                        "SELECT `runId`, `scopeId`, " +
                        AGENT_TRACE_RUN_SCOPE_ATTEMPT + ", '" +
                        AGENT_TRACE_KIND_EVIDENCE_GAP + "', NULL, NULL, '" +
                        AGENT_TRACE_DIRECTION_INTERNAL + "', '" +
                        AGENT_TRACE_COMPLETION_UNAVAILABLE + "', " +
                        PRE_TRACE_RUN_GAP_PAYLOAD + ", `updatedAtEpochMillis` " +
                        "FROM `agent_runs`",
                )
                db.execSQL(
                    "INSERT INTO `agent_trace_events` (`runId`, `scopeId`, `attempt`, `kind`, " +
                        "`turn`, `callId`, `direction`, `completion`, `payloadJson`, " +
                        "`createdAtEpochMillis`) " +
                        "SELECT attempts.`runId`, runs.`scopeId`, attempts.`attempt`, '" +
                        AGENT_TRACE_KIND_EVIDENCE_GAP + "', NULL, NULL, '" +
                        AGENT_TRACE_DIRECTION_INTERNAL + "', '" +
                        AGENT_TRACE_COMPLETION_UNAVAILABLE + "', " +
                        PRE_TRACE_ATTEMPT_GAP_PAYLOAD + ", attempts.`updatedAtEpochMillis` " +
                        "FROM `agent_run_attempts` AS attempts " +
                        "INNER JOIN `agent_runs` AS runs ON runs.`runId` = attempts.`runId`",
                )
            }
        }
        /**
         * Version 14 to 15 records the derivation and source binding of a classification input.
         *
         * Existing snapshots came from the stored source, so their kind is backfilled to
         * `STORED_SOURCE` and their existing effective-input digest remains their legacy identity.
         * No translated text is added to the database; later hosted English inputs store only
         * one-way source/input digests.
         */
        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `classification_snapshots` ADD COLUMN " +
                        "`classificationInputKind` TEXT NOT NULL DEFAULT 'STORED_SOURCE'",
                )
                db.execSQL(
                    "ALTER TABLE `classification_snapshots` ADD COLUMN " +
                        "`classificationInputIdentity` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE `classification_snapshots` ADD COLUMN " +
                        "`translationSourceRevision` TEXT",
                )
                db.execSQL(
                    "UPDATE `classification_snapshots` SET `classificationInputIdentity` = " +
                        "`effectiveInputDigest` WHERE `classificationInputIdentity` = ''",
                )
            }
        }

        /**
         * Version 15 to 16 removes the on-device classification queue and records the hosted
         * workflow's category provenance on attempts.
         *
         * `classification_work` held only ephemeral question jobs for the local model, so
         * dropping it loses no owner record. `classification_snapshots` is intentionally
         * retained exactly as stored: it is historical provenance, not a hosted decision, and
         * no active code writes it. `agent_run_attempts.selectedCategoryId` records the
         * category a verified draft-review attempt stored; historical rows keep null.
         */
        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `classification_work`")
                db.execSQL(
                    "ALTER TABLE `agent_run_attempts` ADD COLUMN `selectedCategoryId` INTEGER",
                )
            }
        }
        /** Version 16 to 17 adds durable arrival work and user-edited reading protection. */
        val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `note_events` ADD COLUMN `userEdited` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `arrival_reconciliation_operations` (" +
                        "`operationId` TEXT NOT NULL, " +
                        "`triggerKind` TEXT NOT NULL, " +
                        "`eventId` INTEGER, " +
                        "`expectedEventRevision` INTEGER, " +
                        "`expectedCaptureRevision` INTEGER, " +
                        "`draftId` INTEGER, " +
                        "`expectedDraftReviewRevision` INTEGER, " +
                        "`cursorEventId` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`outcome` TEXT, " +
                        "`createdAtEpochMillis` INTEGER NOT NULL, " +
                        "`linkedAny` INTEGER NOT NULL, " +
                        "`incompleteAny` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`operationId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_arrival_reconciliation_operations_state_createdAtEpochMillis` " +
                        "ON `arrival_reconciliation_operations` (`state`, `createdAtEpochMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_arrival_reconciliation_operations_triggerKind_createdAtEpochMillis` " +
                        "ON `arrival_reconciliation_operations` (`triggerKind`, `createdAtEpochMillis`)",
                )
            }
        }

        /**
         * Version 17 to 18 adds the provenance of a pending draft's chosen review category.
         *
         * An existing row keeps its stored category and revision; the new column only says
         * that the old schema did not record who chose it, so a legacy category is never
         * silently reopened by a later note or link change.
         */
        val MIGRATION_17_18: Migration = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pending_bank_drafts` ADD COLUMN `reviewCategoryOrigin` " +
                        "TEXT NOT NULL DEFAULT 'NONE'",
                )
            }
        }

        /** Builds the production database on the app's real file directory. */
        fun build(context: Context): LedgerDatabase =
            Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, NAME)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                )
                .build()

    }
}

/** The table the 2-to-3 migration builds before renaming it over the version-2 table. */
private const val DRAFT_TABLE_V3 = "pending_bank_drafts_v3"

/**
 * The explicit historical gap a version-13 run receives at upgrade. The run was stored before
 * local production trace capture existed, so the trace records that no body or event is
 * available instead of inventing one. The value is a complete SQL string literal.
 */
private const val PRE_TRACE_RUN_GAP_PAYLOAD =
    "'{\"reason\":\"PRE_TRACE_HISTORY\",\"detail\":\"this run was recorded before local " +
        "production trace capture; no provider request, response, tool call or clarification " +
        "evidence exists for it\"}'"

/** The same explicit gap for every pre-trace attempt row of those runs. */
private const val PRE_TRACE_ATTEMPT_GAP_PAYLOAD =
    "'{\"reason\":\"PRE_TRACE_ATTEMPT\",\"detail\":\"this attempt was recorded before local " +
        "production trace capture; no exchange, tool or clarification evidence exists for it\"}'"

/** The version-3 draft table: the entity's columns, its two foreign keys, and no indices yet. */
private const val DRAFT_TABLE_V3_SQL =
    "CREATE TABLE IF NOT EXISTS `pending_bank_drafts_v3` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`deliveryId` TEXT NOT NULL, " +
        "`sourcePackage` TEXT NOT NULL, " +
        "`notificationKey` TEXT, " +
        "`postedAtEpochMillis` INTEGER NOT NULL, " +
        "`bank` TEXT NOT NULL, " +
        "`parseStatus` TEXT NOT NULL, " +
        "`amountState` TEXT NOT NULL, " +
        "`amountVnd` INTEGER, " +
        "`amountUnresolvedReason` TEXT, " +
        "`direction` TEXT NOT NULL, " +
        "`momentState` TEXT NOT NULL, " +
        "`statedDate` TEXT, " +
        "`statedTime` TEXT, " +
        "`momentUnresolvedReason` TEXT, " +
        "`memoState` TEXT NOT NULL, " +
        "`memo` TEXT, " +
        "`memoUnresolvedReason` TEXT, " +
        "`evidenceDigest` TEXT NOT NULL, " +
        "`reviewStatus` TEXT NOT NULL, " +
        "`confirmedTransactionId` INTEGER, " +
        "`possibleDuplicateOfDraftId` INTEGER, " +
        "`createdAtEpochMillis` INTEGER NOT NULL, " +
        "FOREIGN KEY(`confirmedTransactionId`) REFERENCES `transactions`(`id`) " +
        "ON UPDATE CASCADE ON DELETE SET NULL, " +
        // The new row's next-of-kin link points at the table this one is renamed to, so it ends
        // up referencing itself; no copied row carries a link, so the rename cannot dangle.
        "FOREIGN KEY(`possibleDuplicateOfDraftId`) REFERENCES `pending_bank_drafts`(`id`) " +
        "ON UPDATE CASCADE ON DELETE SET NULL)"

/** Copies one migrated row, id included, into the version-3 table. */
private const val INSERT_DRAFT_V3_SQL =
    "INSERT INTO `pending_bank_drafts_v3` (`id`, `deliveryId`, `sourcePackage`, `notificationKey`, " +
        "`postedAtEpochMillis`, `bank`, `parseStatus`, `amountState`, `amountVnd`, " +
        "`amountUnresolvedReason`, `direction`, `momentState`, `statedDate`, `statedTime`, " +
        "`momentUnresolvedReason`, `memoState`, `memo`, `memoUnresolvedReason`, `evidenceDigest`, " +
        "`reviewStatus`, `confirmedTransactionId`, `possibleDuplicateOfDraftId`, " +
        "`createdAtEpochMillis`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
        "?, ?, ?, ?, ?)"

/**
 * One version-2 draft row as a version-3 entity: the stored fields unchanged, the delivery
 * identity derived from the row's own provenance, and the evidence digest derived from the
 * row's own evidence by the entity itself. A version-2 row has no next-of-kin link, so none is
 * invented; nothing is attributed to a delivery the row never arrived under.
 */
private fun Cursor.toStoredDraft(): PendingBankDraftEntity {
    val sourcePackage = requiredText("sourcePackage")
    val notificationKey = optionalText("notificationKey")
    val postedAtEpochMillis = getLong(getColumnIndexOrThrow("postedAtEpochMillis"))
    return PendingBankDraftEntity(
        id = getLong(getColumnIndexOrThrow("id")),
        deliveryId = draftDeliveryId(sourcePackage, notificationKey, postedAtEpochMillis),
        sourcePackage = sourcePackage,
        notificationKey = notificationKey,
        postedAtEpochMillis = postedAtEpochMillis,
        bank = requiredText("bank"),
        parseStatus = IntakeParseStatus.valueOf(requiredText("parseStatus")),
        amountState = IntakeFieldState.valueOf(requiredText("amountState")),
        amountVnd = optionalLong("amountVnd"),
        amountUnresolvedReason = optionalText("amountUnresolvedReason"),
        direction = IntakeDirection.valueOf(requiredText("direction")),
        momentState = IntakeFieldState.valueOf(requiredText("momentState")),
        statedDate = optionalText("statedDate"),
        statedTime = optionalText("statedTime"),
        momentUnresolvedReason = optionalText("momentUnresolvedReason"),
        memoState = IntakeFieldState.valueOf(requiredText("memoState")),
        memo = optionalText("memo"),
        memoUnresolvedReason = optionalText("memoUnresolvedReason"),
        reviewStatus = IntakeReviewStatus.valueOf(requiredText("reviewStatus")),
        confirmedTransactionId = optionalLong("confirmedTransactionId"),
        createdAtEpochMillis = getLong(getColumnIndexOrThrow("createdAtEpochMillis")),
    )
}

/** The same row's columns in [INSERT_DRAFT_V3_SQL] order. */
private fun PendingBankDraftEntity.asBindings(): Array<Any?> = arrayOf(
    id,
    deliveryId,
    sourcePackage,
    notificationKey,
    postedAtEpochMillis,
    bank,
    parseStatus.name,
    amountState.name,
    amountVnd,
    amountUnresolvedReason,
    direction.name,
    momentState.name,
    statedDate,
    statedTime,
    momentUnresolvedReason,
    memoState.name,
    memo,
    memoUnresolvedReason,
    evidenceDigest,
    reviewStatus.name,
    confirmedTransactionId,
    possibleDuplicateOfDraftId,
    createdAtEpochMillis,
)

private fun Cursor.requiredText(column: String): String =
    optionalText(column) ?: throw IllegalStateException("migrated draft has no $column")

private fun Cursor.optionalText(column: String): String? =
    getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }

private fun Cursor.optionalLong(column: String): Long? =
    getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getLong(index) }
