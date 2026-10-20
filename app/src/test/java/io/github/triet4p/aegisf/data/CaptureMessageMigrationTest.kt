package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureMessageMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    @Test
    fun `populated version six migration preserves user data and adds truthful source rows`() = runTest {
        writeVersionSixDatabase()
        val database = LedgerDatabase.build(context)
        val query = WorkflowQueryService(database)
        try {
            val room = database.openHelper.writableDatabase
            assertEquals(LedgerDatabase.VERSION.toLong(), scalar(room, "PRAGMA user_version"))

            val categories = database.categoryDao().findAll().associateBy { it.id }
            assertEquals(setOf(FOOD_CATEGORY_ID, ARCHIVED_CATEGORY_ID), categories.keys)
            assertFalse(categories.getValue(FOOD_CATEGORY_ID).archived)
            assertTrue(categories.getValue(ARCHIVED_CATEGORY_ID).archived)
            assertEquals("Food", categories.getValue(FOOD_CATEGORY_ID).name)
            assertEquals("Archived", categories.getValue(ARCHIVED_CATEGORY_ID).name)

            val transactions = database.transactionDao().findAll().associateBy { it.id }
            assertEquals(setOf(MANUAL_EXPENSE_ID, BANK_INCOME_ID, TRANSFER_ID, ARCHIVED_EXPENSE_ID), transactions.keys)
            assertEquals(FOOD_CATEGORY_ID, transactions.getValue(MANUAL_EXPENSE_ID).categoryId)
            assertEquals(FOOD_CATEGORY_ID, transactions.getValue(BANK_INCOME_ID).categoryId)
            assertNull(transactions.getValue(TRANSFER_ID).categoryId)
            assertEquals(ARCHIVED_CATEGORY_ID, transactions.getValue(ARCHIVED_EXPENSE_ID).categoryId)
            assertEquals(80_000L, transactions.values
                .filter { it.confirmation == ConfirmationStatus.CONFIRMED && it.type == TransactionType.EXPENSE }
                .sumOf { it.amountVnd })
            assertEquals("Manual description", transactions.getValue(MANUAL_EXPENSE_ID).description)
            assertEquals("Bank-confirmed description", transactions.getValue(BANK_INCOME_ID).description)

            val drafts = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.associateBy { it.id }
            assertEquals(setOf(PENDING_DRAFT_ID, CONFIRMED_DRAFT_ID, DISMISSED_DRAFT_ID), drafts.keys)
            assertEquals(IntakeReviewStatus.PENDING, drafts.getValue(PENDING_DRAFT_ID).reviewStatus)
            assertEquals(3, drafts.getValue(PENDING_DRAFT_ID).reviewRevision)
            assertEquals("User review memo", drafts.getValue(PENDING_DRAFT_ID).reviewMemo)
            assertEquals(IntakeReviewStatus.CONFIRMED, drafts.getValue(CONFIRMED_DRAFT_ID).reviewStatus)
            assertEquals(BANK_INCOME_ID, drafts.getValue(CONFIRMED_DRAFT_ID).confirmedTransactionId)
            assertEquals(IntakeReviewStatus.DISMISSED, drafts.getValue(DISMISSED_DRAFT_ID).reviewStatus)
            assertEquals(PENDING_DRAFT_ID, drafts.getValue(DISMISSED_DRAFT_ID).possibleDuplicateOfDraftId)

            assertNotNull(database.rememberedCategoryRuleDao().findActiveCategory("food"))
            assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM remembered_category_rules"))

            val captures = database.captureMessageDao().findAllIncludingTombstones()
            assertEquals(6, captures.size)
            val bankDraft = drafts.getValue(PENDING_DRAFT_ID)
            val bankEvidence = captures.single {
                it.sourceKind == CaptureSourceKind.BANK_NOTIFICATION &&
                    it.sourceIdentity == bankEvidenceSourceIdentity(PENDING_DRAFT_ID)
            }
            assertEquals(CaptureKind.BANK_EVIDENCE_REF, bankEvidence.captureKind)
            assertEquals("Original bank memo", bankEvidence.bodyText)
            assertEquals(POSTED_AT + PENDING_DRAFT_ID, bankEvidence.capturedAtEpochMillis)
            assertNull(bankEvidence.captureTimezone)
            assertEquals("BANK_DRAFT:$PENDING_DRAFT_ID", bankEvidence.originRef)
            assertEquals(0, bankEvidence.revision)
            assertFalse(bankEvidence.tombstone)

            val legacyManual = captures.single {
                it.sourceIdentity == "legacy-ledger:$MANUAL_EXPENSE_ID"
            }
            assertEquals(CaptureKind.MANUAL_ANNOTATION, legacyManual.captureKind)
            assertEquals(CaptureSourceKind.LEGACY_LEDGER_DESCRIPTION, legacyManual.sourceKind)
            assertEquals("Manual description", legacyManual.bodyText)
            assertNull(legacyManual.capturedAtEpochMillis)
            assertNull(legacyManual.captureTimezone)
            assertEquals("LEDGER_RECORD:$MANUAL_EXPENSE_ID", legacyManual.originRef)
            val legacyBank = captures.single {
                it.sourceIdentity == "legacy-ledger:$BANK_INCOME_ID"
            }
            assertEquals(CaptureSourceKind.LEGACY_LEDGER_DESCRIPTION, legacyBank.sourceKind)
            assertEquals("Bank-confirmed description", legacyBank.bodyText)
            assertNull(legacyBank.capturedAtEpochMillis)

            room.query(SimpleSQLiteQuery("PRAGMA foreign_key_check")).use { cursor ->
                assertFalse("migration left a foreign-key violation", cursor.moveToFirst())
            }
            val duplicate = database.captureMessageDao().insert(
                bankEvidence.copy(id = 0L, bodyText = "must not replace stored source text"),
            )
            assertEquals(-1L, duplicate)
            assertEquals(bankEvidence, database.captureMessageDao().findById(bankEvidence.id))

            val captureRepository = CaptureMessageRepository(database)
            assertEquals(
                CaptureMessageWriteResult.ImmutableSource,
                captureRepository.revise(legacyManual.id, 0, "direct annotation mutation"),
            )
            val ledger = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
            )
            ledger.editTransaction(
                MANUAL_EXPENSE_ID,
                TransactionCorrection(
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = POSTED_AT,
                    description = "Corrected manual description",
                    categoryId = FOOD_CATEGORY_ID,
                ),
            )
            val correctedLegacy = database.captureMessageDao().findById(legacyManual.id)!!
            assertEquals("Corrected manual description", correctedLegacy.bodyText)
            assertEquals(1, correctedLegacy.revision)
            assertNull(correctedLegacy.capturedAtEpochMillis)
            ledger.deleteTransaction(MANUAL_EXPENSE_ID)
            val deletedLegacy = database.captureMessageDao().findById(legacyManual.id)!!
            assertNull(deletedLegacy.bodyText)
            assertTrue(deletedLegacy.tombstone)
            assertEquals(2, deletedLegacy.revision)
            assertNull(query.findLedgerRecord(MANUAL_EXPENSE_ID))
        } finally {
            database.close()
        }
    }

    @Test
    fun `failed version six migration rolls back schema changes and preserves populated rows`() = runTest {
        writeVersionSixDatabase(abortCaptureBackfill = true)
        val database = LedgerDatabase.build(context)
        val query = WorkflowQueryService(database)
        var failed = false
        try {
            database.openHelper.writableDatabase
        } catch (_: Exception) {
            failed = true
        } finally {
            database.close()
        }
        assertTrue("the injected capture backfill failure must abort migration", failed)

        val file = context.getDatabasePath(LedgerDatabase.NAME)
        val legacy = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            assertEquals(6L, scalar(legacy, "PRAGMA user_version"))
            assertEquals(2L, scalar(legacy, "SELECT COUNT(*) FROM categories"))
            assertEquals(4L, scalar(legacy, "SELECT COUNT(*) FROM transactions"))
            assertEquals(3L, scalar(legacy, "SELECT COUNT(*) FROM pending_bank_drafts"))
            assertEquals(2L, scalar(legacy, "SELECT COUNT(*) FROM remembered_category_rules"))
            assertEquals(0L, scalar(legacy, "SELECT COUNT(*) FROM capture_messages"))
            assertEquals(
                0L,
                scalar(
                    legacy,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_capture_messages_sourceKind_sourceIdentity'",
                ),
            )
            assertEquals(80_000L, scalar(
                legacy,
                "SELECT SUM(amountVnd) FROM transactions WHERE type = 'EXPENSE' AND confirmation = 'CONFIRMED'",
            ))
            assertEquals(
                "ff97e68ec56591d684c3e5fd371fe854",
                text(legacy, "SELECT identity_hash FROM room_master_table WHERE id = 42"),
            )
            legacy.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertFalse("failed migration left a foreign-key violation", cursor.moveToFirst())
            }
        } finally {
            legacy.close()
        }
    }

    private fun writeVersionSixDatabase(abortCaptureBackfill: Boolean = false) {
        val file = context.getDatabasePath(LedgerDatabase.NAME)
        file.parentFile?.mkdirs()
        file.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            VERSION_SIX_SCHEMA.forEach { sql -> database.execSQL(sql) }
            if (abortCaptureBackfill) {
                database.execSQL(CAPTURE_TABLE_SQL)
                database.execSQL(
                    "CREATE TRIGGER `abort_capture_backfill` BEFORE INSERT ON `capture_messages` " +
                        "BEGIN SELECT RAISE(ABORT, 'injected migration failure'); END",
                )
            }
            insertCategory(database, FOOD_CATEGORY_ID, "Food", archived = 0)
            insertCategory(database, ARCHIVED_CATEGORY_ID, "Archived", archived = 1)
            insertTransaction(
                database,
                MANUAL_EXPENSE_ID,
                "EXPENSE",
                50_000L,
                "Manual description",
                FOOD_CATEGORY_ID,
                "MANUAL",
            )
            insertTransaction(
                database,
                BANK_INCOME_ID,
                "INCOME",
                1_000_000L,
                "Bank-confirmed description",
                FOOD_CATEGORY_ID,
                "BANK_NOTIFICATION",
            )
            insertTransaction(
                database,
                TRANSFER_ID,
                "TRANSFER",
                25_000L,
                "Transfer description",
                null,
                "MANUAL",
            )
            insertTransaction(
                database,
                ARCHIVED_EXPENSE_ID,
                "EXPENSE",
                30_000L,
                "Archived-category description",
                ARCHIVED_CATEGORY_ID,
                "MANUAL",
            )
            insertDraft(database, PENDING_DRAFT_ID, "pending-key", "digest-pending", "PENDING", null, "Original bank memo")
            insertDraft(database, CONFIRMED_DRAFT_ID, "confirmed-key", "digest-confirmed", "CONFIRMED", BANK_INCOME_ID, "Confirmed source memo")
            insertDraft(database, DISMISSED_DRAFT_ID, "dismissed-key", "digest-dismissed", "DISMISSED", null, null)
            database.execSQL(
                "INSERT INTO `remembered_category_rules` (`patternKey`, `categoryId`) VALUES " +
                    "('food', $FOOD_CATEGORY_ID), ('archived', $ARCHIVED_CATEGORY_ID)",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `room_master_table` " +
                    "(`id` INTEGER PRIMARY KEY, `identity_hash` TEXT)",
            )
            database.execSQL(
                "INSERT OR REPLACE INTO `room_master_table` (`id`, `identity_hash`) " +
                    "VALUES (42, 'ff97e68ec56591d684c3e5fd371fe854')",
            )
            database.execSQL("PRAGMA user_version = 6")
        } finally {
            database.close()
        }
    }

    private fun insertCategory(database: SQLiteDatabase, id: Long, name: String, archived: Int) {
        database.execSQL(
            "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                "`updatedAtEpochMillis`) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, name, archived, POSTED_AT, POSTED_AT),
        )
    }

    private fun insertTransaction(
        database: SQLiteDatabase,
        id: Long,
        type: String,
        amount: Long,
        description: String,
        categoryId: Long?,
        source: String,
    ) {
        database.execSQL(
            "INSERT INTO `transactions` (`id`, `type`, `amountVnd`, `occurredAtEpochMillis`, " +
                "`description`, `categoryId`, `confirmation`, `source`, `createdAtEpochMillis`, " +
                "`updatedAtEpochMillis`) VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)",
            arrayOf<Any?>(id, type, amount, POSTED_AT, description, categoryId, source, POSTED_AT, POSTED_AT),
        )
    }

    private fun insertDraft(
        database: SQLiteDatabase,
        id: Long,
        key: String,
        digest: String,
        status: String,
        transactionId: Long?,
        memo: String?,
    ) {
        val delivery = draftDeliveryId(BANK_PACKAGE, key, POSTED_AT + id)
        database.execSQL(
            "INSERT INTO `pending_bank_drafts` (`id`, `deliveryId`, `sourcePackage`, `notificationKey`, " +
                "`postedAtEpochMillis`, `bank`, `parseStatus`, `amountState`, `amountVnd`, " +
                "`amountUnresolvedReason`, `direction`, `momentState`, `statedDate`, `statedTime`, " +
                "`momentUnresolvedReason`, `memoState`, `memo`, `memoUnresolvedReason`, " +
                "`evidenceDigest`, `reviewStatus`, `confirmedTransactionId`, `possibleDuplicateOfDraftId`, " +
                "`createdAtEpochMillis`, `reviewAmountText`, `reviewDateText`, `reviewTimeText`, " +
                "`reviewMemo`, `reviewMemoEdited`, `reviewType`, `reviewCategoryId`, " +
                "`reviewCategoryResolved`, `reviewRevision`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id,
                delivery,
                BANK_PACKAGE,
                key,
                POSTED_AT + id,
                "VIETINBANK",
                if (status == "DISMISSED") "UNSUPPORTED_FORMAT" else "PARSED",
                if (status == "DISMISSED") "MISSING" else "PRESENT",
                if (status == "DISMISSED") null else 50_000L,
                null,
                if (status == "DISMISSED") "UNKNOWN" else "DEBIT",
                if (status == "DISMISSED") "MISSING" else "PRESENT",
                if (status == "DISMISSED") null else "2026-10-16",
                if (status == "DISMISSED") null else "07:38",
                null,
                if (memo == null) "MISSING" else "PRESENT",
                memo,
                null,
                digest,
                status,
                transactionId,
                if (status == "DISMISSED") PENDING_DRAFT_ID else null,
                POSTED_AT + id,
                if (status == "PENDING") "50,000" else null,
                null,
                null,
                if (status == "PENDING") "User review memo" else null,
                if (status == "PENDING") 1 else 0,
                if (status == "PENDING") "EXPENSE" else null,
                if (status == "PENDING") FOOD_CATEGORY_ID else null,
                if (status == "PENDING") 1 else 0,
                if (status == "PENDING") 3 else if (status == "CONFIRMED") 4 else 0,
            ),
        )
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
        const val BANK_PACKAGE = "com.example.bank"
        const val FOOD_CATEGORY_ID = 1L
        const val ARCHIVED_CATEGORY_ID = 2L
        const val MANUAL_EXPENSE_ID = 101L
        const val BANK_INCOME_ID = 102L
        const val TRANSFER_ID = 103L
        const val ARCHIVED_EXPENSE_ID = 104L
        const val PENDING_DRAFT_ID = 201L
        const val CONFIRMED_DRAFT_ID = 202L
        const val DISMISSED_DRAFT_ID = 203L
        const val POSTED_AT = 1_797_000_000_000L

        const val CAPTURE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS `capture_messages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`captureKind` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, " +
                "`sourceIdentity` TEXT NOT NULL, `bodyText` TEXT, " +
                "`capturedAtEpochMillis` INTEGER, `captureTimezone` TEXT, " +
                "`revision` INTEGER NOT NULL, `originRef` TEXT, `tombstone` INTEGER NOT NULL)"

        val VERSION_SIX_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `archived` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `amountVnd` INTEGER NOT NULL, `occurredAtEpochMillis` INTEGER NOT NULL, `description` TEXT NOT NULL, `categoryId` INTEGER, `confirmation` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE CASCADE ON DELETE RESTRICT )",
            "CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)",
            "CREATE TABLE IF NOT EXISTS `pending_bank_drafts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `deliveryId` TEXT NOT NULL, `sourcePackage` TEXT NOT NULL, `notificationKey` TEXT, `postedAtEpochMillis` INTEGER NOT NULL, `bank` TEXT NOT NULL, `parseStatus` TEXT NOT NULL, `amountState` TEXT NOT NULL, `amountVnd` INTEGER, `amountUnresolvedReason` TEXT, `direction` TEXT NOT NULL, `momentState` TEXT NOT NULL, `statedDate` TEXT, `statedTime` TEXT, `momentUnresolvedReason` TEXT, `memoState` TEXT NOT NULL, `memo` TEXT, `memoUnresolvedReason` TEXT, `evidenceDigest` TEXT NOT NULL, `reviewStatus` TEXT NOT NULL, `confirmedTransactionId` INTEGER, `possibleDuplicateOfDraftId` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `reviewAmountText` TEXT, `reviewDateText` TEXT, `reviewTimeText` TEXT, `reviewMemo` TEXT, `reviewMemoEdited` INTEGER NOT NULL DEFAULT 0, `reviewType` TEXT, `reviewCategoryId` INTEGER, `reviewCategoryResolved` INTEGER NOT NULL DEFAULT 0, `reviewRevision` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`confirmedTransactionId`) REFERENCES `transactions`(`id`) ON UPDATE CASCADE ON DELETE SET NULL , FOREIGN KEY(`possibleDuplicateOfDraftId`) REFERENCES `pending_bank_drafts`(`id`) ON UPDATE CASCADE ON DELETE SET NULL )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_bank_drafts_deliveryId_evidenceDigest` ON `pending_bank_drafts` (`deliveryId`, `evidenceDigest`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_evidenceDigest` ON `pending_bank_drafts` (`evidenceDigest`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_possibleDuplicateOfDraftId` ON `pending_bank_drafts` (`possibleDuplicateOfDraftId`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_reviewStatus` ON `pending_bank_drafts` (`reviewStatus`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_confirmedTransactionId` ON `pending_bank_drafts` (`confirmedTransactionId`)",
            "CREATE TABLE IF NOT EXISTS `remembered_category_rules` (`patternKey` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, PRIMARY KEY(`patternKey`), FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_remembered_category_rules_categoryId` ON `remembered_category_rules` (`categoryId`)",
        )
    }
}
