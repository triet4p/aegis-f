package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The version 8 to 9 upgrade of a populated database.
 *
 * The fixture holds the rows the app really keeps — categories, ledger records, bank drafts,
 * remembered rules, versioned captures, event readings, and context links — so the upgrade is
 * observed against real content and the exact confirmed total, not against an empty database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClassificationSnapshotMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    @Test
    fun `populated version eight migration preserves every stored row and the exact total`() = runTest {
        writeVersionEightDatabase()
        val database = LedgerDatabase.build(context)
        try {
            val room = database.openHelper.writableDatabase
            assertEquals(LedgerDatabase.VERSION.toLong(), scalar(room, "PRAGMA user_version"))

            val categories = database.categoryDao().findAll().associateBy { it.id }
            assertEquals(setOf(FOOD_CATEGORY_ID, ARCHIVED_CATEGORY_ID), categories.keys)
            assertFalse(categories.getValue(FOOD_CATEGORY_ID).archived)
            assertTrue(categories.getValue(ARCHIVED_CATEGORY_ID).archived)

            val transactions = database.transactionDao().findAll().associateBy { it.id }
            assertEquals(
                setOf(MANUAL_EXPENSE_ID, BANK_INCOME_ID, TRANSFER_ID, ARCHIVED_EXPENSE_ID),
                transactions.keys,
            )
            assertEquals(80_000L, transactions.values
                .filter { it.confirmation == ConfirmationStatus.CONFIRMED && it.type == TransactionType.EXPENSE }
                .sumOf { it.amountVnd })
            assertEquals("Manual description", transactions.getValue(MANUAL_EXPENSE_ID).description)

            val drafts = IntakeRepository(database).drafts().associateBy { it.id }
            assertEquals(setOf(PENDING_DRAFT_ID, CONFIRMED_DRAFT_ID), drafts.keys)
            assertEquals(IntakeReviewStatus.PENDING, drafts.getValue(PENDING_DRAFT_ID).reviewStatus)
            assertEquals(BANK_INCOME_ID, drafts.getValue(CONFIRMED_DRAFT_ID).confirmedTransactionId)

            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM remembered_category_rules"))
            assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM capture_messages"))

            // The event readings and their links survive the upgrade unchanged.
            val events = database.noteEventDao().findForCapture(2L)
            assertEquals(1, events.size)
            assertEquals("Fuel 50,000", events.single().normalizedText)
            assertEquals(0, events.single().revision)
            val links = database.contextLinkDao().findForDraft(PENDING_DRAFT_ID)
            assertEquals(1, links.size)
            assertEquals(ContextLinkState.ACCEPTED, links.single().state)

            // The new table exists and starts empty: version 8 stored no prediction.
            assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM classification_snapshots"))
            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_classification_snapshots_draftId'"))

            room.query(SimpleSQLiteQuery("PRAGMA foreign_key_check")).use { cursor ->
                assertFalse("the migration left a foreign-key violation", cursor.moveToFirst())
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `failed version eight migration rolls back and preserves the populated rows`() = runTest {
        writeVersionEightDatabase(conflictingSnapshots = true)
        val database = LedgerDatabase.build(context)
        var failed = false
        try {
            database.openHelper.writableDatabase
        } catch (_: Exception) {
            failed = true
        } finally {
            database.close()
        }
        assertTrue("the injected migration failure must abort the upgrade", failed)

        val file = context.getDatabasePath(LedgerDatabase.NAME)
        val legacy = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            assertEquals(8L, scalar(legacy, "PRAGMA user_version"))
            assertEquals(2L, scalar(legacy, "SELECT COUNT(*) FROM categories"))
            assertEquals(4L, scalar(legacy, "SELECT COUNT(*) FROM transactions"))
            assertEquals(2L, scalar(legacy, "SELECT COUNT(*) FROM pending_bank_drafts"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM remembered_category_rules"))
            assertEquals(2L, scalar(legacy, "SELECT COUNT(*) FROM capture_messages"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM note_events"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM context_links"))
            assertEquals(
                0L,
                scalar(
                    legacy,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_classification_snapshots_draftId'",
                ),
            )
            assertEquals(
                80_000L,
                scalar(
                    legacy,
                    "SELECT SUM(amountVnd) FROM transactions " +
                        "WHERE type = 'EXPENSE' AND confirmation = 'CONFIRMED'",
                ),
            )
            assertEquals(
                "6bce7af42ee28c754c7eef47dcc498b7",
                text(legacy, "SELECT identity_hash FROM room_master_table WHERE id = 42"),
            )
            legacy.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertFalse("the failed migration left a foreign-key violation", cursor.moveToFirst())
            }
        } finally {
            legacy.close()
        }
    }

    /**
     * Writes a populated version 8 database.
     *
     * [conflictingSnapshots] puts a table of an unexpected shape under the name the migration
     * wants to create, so the migration's own index statement fails and the upgrade has to be
     * rolled back.
     */
    private fun writeVersionEightDatabase(conflictingSnapshots: Boolean = false) {
        val file = context.getDatabasePath(LedgerDatabase.NAME)
        file.parentFile?.mkdirs()
        file.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            VERSION_EIGHT_SCHEMA.forEach { sql -> database.execSQL(sql) }
            if (conflictingSnapshots) {
                database.execSQL(
                    "CREATE TABLE `classification_snapshots` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `note` TEXT)",
                )
            }
            database.execSQL(
                "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                    "`updatedAtEpochMillis`) VALUES " +
                    "($FOOD_CATEGORY_ID, 'Food', 0, $POSTED_AT, $POSTED_AT), " +
                    "($ARCHIVED_CATEGORY_ID, 'Archived', 1, $POSTED_AT, $POSTED_AT)",
            )
            database.execSQL(
                "INSERT INTO `transactions` (`id`, `type`, `amountVnd`, `occurredAtEpochMillis`, " +
                    "`description`, `categoryId`, `confirmation`, `source`, `createdAtEpochMillis`, " +
                    "`updatedAtEpochMillis`) VALUES " +
                    "($MANUAL_EXPENSE_ID, 'EXPENSE', 50000, $POSTED_AT, 'Manual description', " +
                    "$FOOD_CATEGORY_ID, 'CONFIRMED', 'MANUAL', $POSTED_AT, $POSTED_AT), " +
                    "($BANK_INCOME_ID, 'INCOME', 1000000, $POSTED_AT, 'Bank description', " +
                    "$FOOD_CATEGORY_ID, 'CONFIRMED', 'BANK_NOTIFICATION', $POSTED_AT, $POSTED_AT), " +
                    "($TRANSFER_ID, 'TRANSFER', 25000, $POSTED_AT, 'Transfer description', NULL, " +
                    "'CONFIRMED', 'MANUAL', $POSTED_AT, $POSTED_AT), " +
                    "($ARCHIVED_EXPENSE_ID, 'EXPENSE', 30000, $POSTED_AT, 'Archived description', " +
                    "$ARCHIVED_CATEGORY_ID, 'CONFIRMED', 'MANUAL', $POSTED_AT, $POSTED_AT)",
            )
            database.execSQL(
                "INSERT INTO `pending_bank_drafts` (`id`, `deliveryId`, `sourcePackage`, " +
                    "`notificationKey`, `postedAtEpochMillis`, `bank`, `parseStatus`, `amountState`, " +
                    "`amountVnd`, `amountUnresolvedReason`, `direction`, `momentState`, `statedDate`, " +
                    "`statedTime`, `momentUnresolvedReason`, `memoState`, `memo`, " +
                    "`memoUnresolvedReason`, `evidenceDigest`, `reviewStatus`, " +
                    "`confirmedTransactionId`, `possibleDuplicateOfDraftId`, `createdAtEpochMillis`, " +
                    "`reviewAmountText`, `reviewDateText`, `reviewTimeText`, `reviewMemo`, " +
                    "`reviewMemoEdited`, `reviewType`, `reviewCategoryId`, `reviewCategoryResolved`, " +
                    "`reviewRevision`) VALUES " +
                    "($PENDING_DRAFT_ID, 'delivery-pending', 'com.example.bank', 'pending-key', " +
                    "$POSTED_AT, 'VIETINBANK', 'PARSED', 'PRESENT', 50000, NULL, 'DEBIT', 'PRESENT', " +
                    "'2026-10-16', '07:38', NULL, 'PRESENT', 'Original bank memo', NULL, " +
                    "'digest-pending', 'PENDING', NULL, NULL, $POSTED_AT, NULL, NULL, NULL, NULL, 0, " +
                    "NULL, NULL, 0, 0), " +
                    "($CONFIRMED_DRAFT_ID, 'delivery-confirmed', 'com.example.bank', 'confirmed-key', " +
                    "$POSTED_AT, 'VIETINBANK', 'PARSED', 'PRESENT', 1000000, NULL, 'CREDIT', 'PRESENT', " +
                    "'2026-10-16', '07:38', NULL, 'PRESENT', 'Confirmed memo', NULL, " +
                    "'digest-confirmed', 'CONFIRMED', $BANK_INCOME_ID, NULL, $POSTED_AT, NULL, NULL, " +
                    "NULL, NULL, 0, NULL, NULL, 0, 0)",
            )
            database.execSQL(
                "INSERT INTO `remembered_category_rules` (`patternKey`, `categoryId`) VALUES " +
                    "('food', $FOOD_CATEGORY_ID)",
            )
            database.execSQL(
                "INSERT INTO `capture_messages` (`id`, `captureKind`, `sourceKind`, " +
                    "`sourceIdentity`, `bodyText`, `capturedAtEpochMillis`, `captureTimezone`, " +
                    "`revision`, `originRef`, `tombstone`) VALUES " +
                    "(1, 'BANK_EVIDENCE_REF', 'BANK_NOTIFICATION', 'BANK_DRAFT:$PENDING_DRAFT_ID', " +
                    "'Original bank memo', $POSTED_AT, NULL, 0, 'BANK_DRAFT:$PENDING_DRAFT_ID', 0), " +
                    "(2, 'USER_NOTE', 'USER_QUICK_CAPTURE', 'note-identity', 'Saved note', $POSTED_AT, " +
                    "'Asia/Bangkok', 0, NULL, 0)",
            )
            database.execSQL(
                "INSERT INTO `note_events` (`id`, `captureId`, `eventIdentity`, `spanStart`, " +
                    "`spanEnd`, `normalizedText`, `amountVnd`, `merchant`, `anchorDate`, " +
                    "`anchorTime`, `anchorTimezone`, `intervalEndDate`, `intervalEndTime`, " +
                    "`extractionVersion`, `unresolvedReason`, `status`, `revision`, " +
                    "`sourceRevision`, `createdAtEpochMillis`) VALUES " +
                    "(1, 2, 'event-identity', 0, 13, 'Fuel 50,000', 50000, 'Petrol', " +
                    "'2026-10-16', '15:00', 'Asia/Bangkok', NULL, NULL, 'extract-v1', NULL, " +
                    "'INTERPRETED', 0, 0, $POSTED_AT)",
            )
            database.execSQL(
                "INSERT INTO `context_links` (`id`, `eventId`, `draftId`, `state`, `evidence`, " +
                    "`revision`, `userOverride`, `activeEventId`, `createdAtEpochMillis`) VALUES " +
                    "(1, 1, $PENDING_DRAFT_ID, 'ACCEPTED', 'same day fuel', 0, 'USER_ACCEPTED', 1, " +
                    "$POSTED_AT)",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `room_master_table` " +
                    "(`id` INTEGER PRIMARY KEY, `identity_hash` TEXT)",
            )
            database.execSQL(
                "INSERT OR REPLACE INTO `room_master_table` (`id`, `identity_hash`) " +
                    "VALUES (42, '6bce7af42ee28c754c7eef47dcc498b7')",
            )
            database.execSQL("PRAGMA user_version = 8")
        } finally {
            database.close()
        }
    }

    private fun scalar(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
        database.query(SimpleSQLiteQuery(sql)).use { cursor ->
            check(cursor.moveToFirst()) { "$sql returned no row" }
            cursor.getLong(0)
        }

    private fun scalar(database: SQLiteDatabase, sql: String): Long =
        database.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst()) { "$sql returned no row" }
            cursor.getLong(0)
        }

    private fun text(database: SQLiteDatabase, sql: String): String =
        database.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst()) { "$sql returned no row" }
            cursor.getString(0)
        }

    private companion object {
        const val FOOD_CATEGORY_ID = 1L
        const val ARCHIVED_CATEGORY_ID = 2L
        const val MANUAL_EXPENSE_ID = 101L
        const val BANK_INCOME_ID = 102L
        const val TRANSFER_ID = 103L
        const val ARCHIVED_EXPENSE_ID = 104L
        const val PENDING_DRAFT_ID = 201L
        const val CONFIRMED_DRAFT_ID = 202L
        const val POSTED_AT = 1_797_000_000_000L

        val VERSION_EIGHT_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `archived` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `amountVnd` INTEGER NOT NULL, `occurredAtEpochMillis` INTEGER NOT NULL, `description` TEXT NOT NULL, `categoryId` INTEGER, `confirmation` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE CASCADE ON DELETE RESTRICT )",
            "CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)",
            "CREATE TABLE IF NOT EXISTS `pending_bank_drafts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `deliveryId` TEXT NOT NULL, `sourcePackage` TEXT NOT NULL, `notificationKey` TEXT, `postedAtEpochMillis` INTEGER NOT NULL, `bank` TEXT NOT NULL, `parseStatus` TEXT NOT NULL, `amountState` TEXT NOT NULL, `amountVnd` INTEGER, `amountUnresolvedReason` TEXT, `direction` TEXT NOT NULL, `momentState` TEXT NOT NULL, `statedDate` TEXT, `statedTime` TEXT, `momentUnresolvedReason` TEXT, `memoState` TEXT NOT NULL, `memo` TEXT, `memoUnresolvedReason` TEXT, `evidenceDigest` TEXT NOT NULL, `reviewStatus` TEXT NOT NULL, `confirmedTransactionId` INTEGER, `possibleDuplicateOfDraftId` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `reviewAmountText` TEXT, `reviewDateText` TEXT, `reviewTimeText` TEXT, `reviewMemo` TEXT, `reviewMemoEdited` INTEGER NOT NULL DEFAULT 0, `reviewType` TEXT, `reviewCategoryId` INTEGER, `reviewCategoryResolved` INTEGER NOT NULL DEFAULT 0, `reviewRevision` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`confirmedTransactionId`) REFERENCES `transactions`(`id`) ON UPDATE CASCADE ON DELETE SET NULL, FOREIGN KEY(`possibleDuplicateOfDraftId`) REFERENCES `pending_bank_drafts`(`id`) ON UPDATE CASCADE ON DELETE SET NULL )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_bank_drafts_deliveryId_evidenceDigest` ON `pending_bank_drafts` (`deliveryId`, `evidenceDigest`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_evidenceDigest` ON `pending_bank_drafts` (`evidenceDigest`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_possibleDuplicateOfDraftId` ON `pending_bank_drafts` (`possibleDuplicateOfDraftId`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_reviewStatus` ON `pending_bank_drafts` (`reviewStatus`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_confirmedTransactionId` ON `pending_bank_drafts` (`confirmedTransactionId`)",
            "CREATE TABLE IF NOT EXISTS `remembered_category_rules` (`patternKey` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, PRIMARY KEY(`patternKey`), FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_remembered_category_rules_categoryId` ON `remembered_category_rules` (`categoryId`)",
            "CREATE TABLE IF NOT EXISTS `capture_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `captureKind` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `sourceIdentity` TEXT NOT NULL, `bodyText` TEXT, `capturedAtEpochMillis` INTEGER, `captureTimezone` TEXT, `revision` INTEGER NOT NULL, `originRef` TEXT, `tombstone` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_capture_messages_sourceKind_sourceIdentity` ON `capture_messages` (`sourceKind`, `sourceIdentity`)",
            "CREATE INDEX IF NOT EXISTS `index_capture_messages_originRef` ON `capture_messages` (`originRef`)",
            "CREATE INDEX IF NOT EXISTS `index_capture_messages_capturedAtEpochMillis` ON `capture_messages` (`capturedAtEpochMillis`)",
            "CREATE INDEX IF NOT EXISTS `index_capture_messages_tombstone_capturedAtEpochMillis` ON `capture_messages` (`tombstone`, `capturedAtEpochMillis`)",
            "CREATE TABLE IF NOT EXISTS `note_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `captureId` INTEGER NOT NULL, `eventIdentity` TEXT NOT NULL, `spanStart` INTEGER NOT NULL, `spanEnd` INTEGER NOT NULL, `normalizedText` TEXT, `amountVnd` INTEGER, `merchant` TEXT, `anchorDate` TEXT, `anchorTime` TEXT, `anchorTimezone` TEXT, `intervalEndDate` TEXT, `intervalEndTime` TEXT, `extractionVersion` TEXT NOT NULL, `unresolvedReason` TEXT, `status` TEXT NOT NULL, `revision` INTEGER NOT NULL, `sourceRevision` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`captureId`) REFERENCES `capture_messages`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_note_events_captureId_eventIdentity` ON `note_events` (`captureId`, `eventIdentity`)",
            "CREATE INDEX IF NOT EXISTS `index_note_events_captureId` ON `note_events` (`captureId`)",
            "CREATE INDEX IF NOT EXISTS `index_note_events_status` ON `note_events` (`status`)",
            "CREATE TABLE IF NOT EXISTS `context_links` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventId` INTEGER NOT NULL, `draftId` INTEGER NOT NULL, `state` TEXT NOT NULL, `evidence` TEXT, `revision` INTEGER NOT NULL, `userOverride` TEXT, `activeEventId` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`eventId`) REFERENCES `note_events`(`id`) ON UPDATE CASCADE ON DELETE CASCADE , FOREIGN KEY(`draftId`) REFERENCES `pending_bank_drafts`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_context_links_eventId_draftId` ON `context_links` (`eventId`, `draftId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_context_links_activeEventId` ON `context_links` (`activeEventId`)",
            "CREATE INDEX IF NOT EXISTS `index_context_links_draftId` ON `context_links` (`draftId`)",
            "CREATE INDEX IF NOT EXISTS `index_context_links_state` ON `context_links` (`state`)",
        )
    }
}
