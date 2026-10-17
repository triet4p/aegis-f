package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The upgrade path into the draft-identity schema, on real database files written in the older
 * shapes: a version-1 database (the app before pending drafts existed), a version-2 database
 * (pending drafts without identity — including one that stored the same delivery more than once,
 * which version 2 allowed), and a version-3 database (drafts identified by package and
 * notification key alone, the encoding a reused key made lossy).
 *
 * The legacy files are created with the exact table definitions Room exported for those versions,
 * the way a device that has been running the app for a while holds them, and are then opened by the
 * production builder [LedgerDatabase.build] so the real migrations run. What is checked is not only
 * that they run: ledger rows keep their values, a stored draft keeps its id, its review state, and
 * its draft-to-ledger link, and — most importantly — the identity the migration derives for an
 * already-stored draft, from that row's own stored provenance, is the same identity the running app
 * computes, so the draft still recognizes its own post afterwards instead of duplicating it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingBankDraftMigrationTest {

    private class FixedLedgerClock : LedgerClock {
        override fun nowEpochMillis(): Long = CREATED_AT
    }

    private lateinit var context: Context
    private lateinit var clock: FixedLedgerClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        clock = FixedLedgerClock()
    }

    @Test
    fun `a version one database keeps its ledger rows and gains the draft identity schema`() = runTest {
        writeLegacyDatabase(version = 1, schema = VERSION_ONE_SCHEMA) { db ->
            db.execSQL(
                "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                    "`updatedAtEpochMillis`) VALUES (1, 'Food', 0, $LEGACY_TIME, $LEGACY_TIME)",
            )
            db.execSQL(
                "INSERT INTO `transactions` (`id`, `type`, `amountVnd`, `occurredAtEpochMillis`, " +
                    "`description`, `categoryId`, `confirmation`, `source`, " +
                    "`createdAtEpochMillis`, `updatedAtEpochMillis`) " +
                    "VALUES (1, 'EXPENSE', 45000, $LEGACY_TIME, 'Lunch', 1, 'CONFIRMED', " +
                    "'MANUAL', $LEGACY_TIME, $LEGACY_TIME)",
            )
        }

        val database = LedgerDatabase.build(context)
        try {
            val ledger = LedgerRepository(
                database.categoryDao(),
                database.transactionDao(),
                database.rememberedCategoryRuleDao(),
                clock,
            )
            assertEquals(1, ledger.transactions().size)
            assertEquals(45_000L, ledger.transactions().single().amountVnd)
            assertEquals(1, ledger.categories().size)

            val drafts = IntakeRepository(database.pendingBankDraftDao(), clock)
            assertEquals(0, drafts.drafts().size)
            // The table the chain built is the identity table the app writes through: storing a
            // delivery and replaying it leaves exactly one draft.
            val stored = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
            val replay = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
            assertEquals(stored, replay)
            assertEquals(1, drafts.drafts().size)
            assertEquals(LedgerDatabase.VERSION.toLong(), storedVersion(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun `a version two database keeps its draft identity, review state, and ledger link`() = runTest {
        writeLegacyDatabase(version = 2, schema = VERSION_TWO_SCHEMA) { db ->
            db.execSQL(
                "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                    "`updatedAtEpochMillis`) VALUES (1, 'Food', 0, $LEGACY_TIME, $LEGACY_TIME)",
            )
            db.execSQL(
                "INSERT INTO `transactions` (`id`, `type`, `amountVnd`, `occurredAtEpochMillis`, " +
                    "`description`, `categoryId`, `confirmation`, `source`, " +
                    "`createdAtEpochMillis`, `updatedAtEpochMillis`) " +
                    "VALUES ($MIGRATED_TRANSACTION_ID, 'EXPENSE', 50000, $LEGACY_TIME, " +
                    "'VietinBank debit', 1, 'CONFIRMED', 'BANK_NOTIFICATION', $LEGACY_TIME, " +
                    "$LEGACY_TIME)",
            )
            db.execSQL(
                "INSERT INTO `pending_bank_drafts` (`id`, `sourcePackage`, `notificationKey`, " +
                    "`postedAtEpochMillis`, `bank`, `parseStatus`, `amountState`, `amountVnd`, " +
                    "`amountUnresolvedReason`, `direction`, `momentState`, `statedDate`, " +
                    "`statedTime`, `momentUnresolvedReason`, `memoState`, `memo`, " +
                    "`memoUnresolvedReason`, `reviewStatus`, `confirmedTransactionId`, " +
                    "`createdAtEpochMillis`) VALUES ($MIGRATED_DRAFT_ID, '$BANK_PACKAGE', " +
                    "'$FIRST_KEY', $POSTED_AT, 'VIETINBANK', 'PARSED', 'PRESENT', 50000, NULL, " +
                    "'DEBIT', 'PRESENT', '2026-10-16', '07:38', NULL, 'UNRESOLVED', NULL, " +
                    "'REDACTED', 'CONFIRMED', $MIGRATED_TRANSACTION_ID, $CREATED_AT)",
            )
        }

        val database = LedgerDatabase.build(context)
        try {
            val drafts = IntakeRepository(database.pendingBankDraftDao(), clock)
            val migrated = drafts.drafts().single()
            assertEquals(MIGRATED_DRAFT_ID, migrated.id)
            assertEquals(BANK_PACKAGE, migrated.sourcePackage)
            assertEquals(FIRST_KEY, migrated.notificationKey)
            assertEquals("VIETINBANK", migrated.bank)
            assertEquals(IntakeParseStatus.PARSED, migrated.parseStatus)
            assertEquals(50_000L, migrated.amountVnd)
            assertEquals(IntakeReviewStatus.CONFIRMED, migrated.reviewStatus)
            assertEquals(MIGRATED_TRANSACTION_ID, migrated.confirmedTransactionId)
            assertNull(migrated.possibleDuplicateOfDraftId)
            assertEquals(LedgerDatabase.VERSION.toLong(), storedVersion(database))

            // The migrated row's derived identity is the running app's identity for that post: the
            // delivery id names the package, the key, and the post time the row itself stores, so
            // replaying that post stores nothing and reports the row that was already there.
            assertEquals(draftDeliveryId(BANK_PACKAGE, FIRST_KEY, POSTED_AT), migrated.deliveryId)
            val replay = drafts.storePermitted(
                BANK_PACKAGE,
                FIRST_KEY,
                POSTED_AT,
                sample(),
            )
            assertEquals(MIGRATED_DRAFT_ID, replay)
            assertEquals(1, drafts.drafts().size)

            val ledger = LedgerRepository(
                database.categoryDao(),
                database.transactionDao(),
                database.rememberedCategoryRuleDao(),
                clock,
            )
            assertEquals(1, ledger.transactions().size)
            assertEquals(MIGRATED_TRANSACTION_ID, ledger.transactions().single().id)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a version two database with repeated callbacks upgrades without failing or duplicating`() =
        runTest {
            writeLegacyDatabase(version = 2, schema = VERSION_TWO_SCHEMA) { db ->
                db.execSQL(
                    "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                        "`updatedAtEpochMillis`) VALUES (1, 'Food', 0, $LEGACY_TIME, $LEGACY_TIME)",
                )
                db.execSQL(
                    "INSERT INTO `transactions` (`id`, `type`, `amountVnd`, `occurredAtEpochMillis`, " +
                        "`description`, `categoryId`, `confirmation`, `source`, " +
                        "`createdAtEpochMillis`, `updatedAtEpochMillis`) " +
                        "VALUES ($MIGRATED_TRANSACTION_ID, 'EXPENSE', 50000, $LEGACY_TIME, " +
                        "'VietinBank debit', 1, 'CONFIRMED', 'BANK_NOTIFICATION', $LEGACY_TIME, " +
                        "$LEGACY_TIME)",
                )
                // Version 2 had no identity constraint and stored every callback, so this file
                // holds one delivery twice (ids 7 and 8, same key and post time, same evidence) plus
                // a later post under the same reused key (id 9) stating the same evidence. The
                // upgrade must keep the earliest row of the repeated delivery, keep the later post
                // as its own draft, and still create the unique index instead of failing on it.
                insertVersionTwoDraft(db, id = MIGRATED_DRAFT_ID, key = FIRST_KEY, postedAt = POSTED_AT)
                insertVersionTwoDraft(
                    db,
                    id = LEGACY_DUPLICATE_DRAFT_ID,
                    key = FIRST_KEY,
                    postedAt = POSTED_AT,
                )
                insertVersionTwoDraft(
                    db,
                    id = LEGACY_LATER_DRAFT_ID,
                    key = FIRST_KEY,
                    postedAt = POSTED_AT + UPDATE_DELAY,
                )
            }

            val database = LedgerDatabase.build(context)
            try {
                val drafts = IntakeRepository(database.pendingBankDraftDao(), clock)
                val stored = drafts.drafts().sortedBy { it.id }
                assertEquals(
                    listOf(MIGRATED_DRAFT_ID, LEGACY_LATER_DRAFT_ID),
                    stored.map { it.id },
                )
                assertEquals(LedgerDatabase.VERSION.toLong(), storedVersion(database))

                // The kept row is the one the running app's own store would have kept: replaying
                // that post writes nothing and reports the row that survived the upgrade.
                val replay = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
                assertEquals(MIGRATED_DRAFT_ID, replay)
                assertEquals(2, drafts.drafts().size)

                val ledger = LedgerRepository(
                    database.categoryDao(),
                    database.transactionDao(),
                    database.rememberedCategoryRuleDao(),
                    clock,
                )
                assertEquals(1, ledger.transactions().size)
                assertEquals(MIGRATED_TRANSACTION_ID, ledger.transactions().single().id)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a version three database has its stale draft identity re-derived from the row itself`() =
        runTest {
            writeLegacyDatabase(version = 3, schema = VERSION_THREE_SCHEMA) { db ->
                db.execSQL(
                    "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                        "`updatedAtEpochMillis`) VALUES (1, 'Food', 0, $LEGACY_TIME, $LEGACY_TIME)",
                )
                db.execSQL(
                    "INSERT INTO `transactions` (`id`, `type`, `amountVnd`, `occurredAtEpochMillis`, " +
                        "`description`, `categoryId`, `confirmation`, `source`, " +
                        "`createdAtEpochMillis`, `updatedAtEpochMillis`) " +
                        "VALUES ($MIGRATED_TRANSACTION_ID, 'EXPENSE', 50000, $LEGACY_TIME, " +
                        "'VietinBank debit', 1, 'CONFIRMED', 'BANK_NOTIFICATION', $LEGACY_TIME, " +
                        "$LEGACY_TIME)",
                )
                // A version-3 row: the old app encoded the delivery id from package and key alone,
                // leaving the row's own post time out of it. That is the encoding a reused key made
                // lossy, so the upgrade must replace it with the post identity of the same row.
                db.execSQL(
                    "INSERT INTO `pending_bank_drafts` (`id`, `deliveryId`, `sourcePackage`, " +
                        "`notificationKey`, `postedAtEpochMillis`, `bank`, `parseStatus`, " +
                        "`amountState`, `amountVnd`, `amountUnresolvedReason`, `direction`, " +
                        "`momentState`, `statedDate`, `statedTime`, `momentUnresolvedReason`, " +
                        "`memoState`, `memo`, `memoUnresolvedReason`, `evidenceDigest`, " +
                        "`reviewStatus`, `confirmedTransactionId`, `possibleDuplicateOfDraftId`, " +
                        "`createdAtEpochMillis`) VALUES ($MIGRATED_DRAFT_ID, " +
                        "'$LEGACY_DELIVERY_ID', '$BANK_PACKAGE', '$FIRST_KEY', $POSTED_AT, " +
                        "'VIETINBANK', 'PARSED', 'PRESENT', 50000, NULL, 'DEBIT', 'PRESENT', " +
                        "'2026-10-16', '07:38', NULL, 'UNRESOLVED', NULL, 'REDACTED', " +
                        "'${sampleEvidenceDigest()}', 'CONFIRMED', $MIGRATED_TRANSACTION_ID, " +
                        "NULL, $CREATED_AT)",
                )
            }

            val database = LedgerDatabase.build(context)
            try {
                val drafts = IntakeRepository(database.pendingBankDraftDao(), clock)
                val migrated = drafts.drafts().single()
                assertEquals(MIGRATED_DRAFT_ID, migrated.id)
                assertEquals(IntakeReviewStatus.CONFIRMED, migrated.reviewStatus)
                assertEquals(MIGRATED_TRANSACTION_ID, migrated.confirmedTransactionId)
                assertEquals(50_000L, migrated.amountVnd)
                assertEquals(LedgerDatabase.VERSION.toLong(), storedVersion(database))

                // The stale value is gone, replaced by the identity the running app computes for
                // this row's own provenance — the same row, not an invented one.
                assertNotEquals(LEGACY_DELIVERY_ID, migrated.deliveryId)
                assertEquals(
                    draftDeliveryId(BANK_PACKAGE, FIRST_KEY, POSTED_AT),
                    migrated.deliveryId,
                )

                // So the row recognizes the post it arrived as: replaying it stores nothing and
                // does not duplicate what the upgrade already had.
                val replay = drafts.storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample())
                assertEquals(MIGRATED_DRAFT_ID, replay)
                assertEquals(1, drafts.drafts().size)

                // And a later post under the same reused key is kept as its own flagged candidate
                // instead of being deduplicated away, while the reviewed row stays terminal.
                val laterId = drafts.storePermitted(
                    BANK_PACKAGE,
                    FIRST_KEY,
                    POSTED_AT + UPDATE_DELAY,
                    sample(),
                )
                assertNotEquals(MIGRATED_DRAFT_ID, laterId)
                val stored = drafts.drafts().sortedBy { it.id }
                assertEquals(2, stored.size)
                assertEquals(IntakeReviewStatus.CONFIRMED, stored[0].reviewStatus)
                assertEquals(MIGRATED_TRANSACTION_ID, stored[0].confirmedTransactionId)
                assertEquals(MIGRATED_DRAFT_ID, stored[1].possibleDuplicateOfDraftId)
                assertEquals(IntakeReviewStatus.PENDING, stored[1].reviewStatus)

                val ledger = LedgerRepository(
                    database.categoryDao(),
                    database.transactionDao(),
                    database.rememberedCategoryRuleDao(),
                    clock,
                )
                assertEquals(1, ledger.transactions().size)
                assertEquals(MIGRATED_TRANSACTION_ID, ledger.transactions().single().id)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a version four database keeps terminal review state and ledger link when review fields are added`() =
        runTest {
            writeLegacyDatabase(version = 4, schema = VERSION_THREE_SCHEMA) { db ->
                db.execSQL(
                    "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                        "`updatedAtEpochMillis`) VALUES (1, 'Food', 0, $LEGACY_TIME, $LEGACY_TIME)",
                )
                db.execSQL(
                    "INSERT INTO `transactions` (`id`, `type`, `amountVnd`, `occurredAtEpochMillis`, " +
                        "`description`, `categoryId`, `confirmation`, `source`, " +
                        "`createdAtEpochMillis`, `updatedAtEpochMillis`) " +
                        "VALUES ($MIGRATED_TRANSACTION_ID, 'EXPENSE', 50000, $LEGACY_TIME, " +
                        "'VietinBank debit', 1, 'CONFIRMED', 'BANK_NOTIFICATION', $LEGACY_TIME, " +
                        "$LEGACY_TIME)",
                )
                db.execSQL(
                    "INSERT INTO `pending_bank_drafts` (`id`, `deliveryId`, `sourcePackage`, " +
                        "`notificationKey`, `postedAtEpochMillis`, `bank`, `parseStatus`, " +
                        "`amountState`, `amountVnd`, `amountUnresolvedReason`, `direction`, " +
                        "`momentState`, `statedDate`, `statedTime`, `momentUnresolvedReason`, " +
                        "`memoState`, `memo`, `memoUnresolvedReason`, `evidenceDigest`, " +
                        "`reviewStatus`, `confirmedTransactionId`, `possibleDuplicateOfDraftId`, " +
                        "`createdAtEpochMillis`) VALUES ($MIGRATED_DRAFT_ID, " +
                        "'${draftDeliveryId(BANK_PACKAGE, FIRST_KEY, POSTED_AT)}', '$BANK_PACKAGE', " +
                        "'$FIRST_KEY', $POSTED_AT, 'VIETINBANK', 'PARSED', 'PRESENT', 50000, NULL, " +
                        "'DEBIT', 'PRESENT', '2026-10-16', '07:38', NULL, 'UNRESOLVED', NULL, " +
                        "'REDACTED', '${sampleEvidenceDigest()}', 'CONFIRMED', " +
                        "$MIGRATED_TRANSACTION_ID, NULL, $CREATED_AT)",
                )
            }

            val database = LedgerDatabase.build(context)
            try {
                val storedDraft = IntakeRepository(database.pendingBankDraftDao(), clock).drafts().single()
                val ledger = LedgerRepository(
                    database.categoryDao(),
                    database.transactionDao(),
                    database.rememberedCategoryRuleDao(),
                    clock,
                )
                assertEquals(LedgerDatabase.VERSION.toLong(), storedVersion(database))
                assertEquals(MIGRATED_DRAFT_ID, storedDraft.id)
                assertEquals(IntakeReviewStatus.CONFIRMED, storedDraft.reviewStatus)
                assertEquals(MIGRATED_TRANSACTION_ID, storedDraft.confirmedTransactionId)
                assertNull(storedDraft.reviewAmountText)
                assertNull(storedDraft.reviewType)
                assertEquals(0, storedDraft.reviewRevision)
                assertEquals(MIGRATED_TRANSACTION_ID, ledger.transactions().single().id)

                // The post/evidence pair still resolves to the same terminal row after migration.
                assertEquals(
                    MIGRATED_DRAFT_ID,
                    IntakeRepository(database.pendingBankDraftDao(), clock)
                        .storePermitted(BANK_PACKAGE, FIRST_KEY, POSTED_AT, sample()),
                )
                assertEquals(1, IntakeRepository(database.pendingBankDraftDao(), clock).drafts().size)
            } finally {
                database.close()
            }
        }

    /** Writes one legacy database file in the shape the older app version stored. */
    private fun writeLegacyDatabase(
        version: Int,
        schema: List<String>,
        rows: (SQLiteDatabase) -> Unit,
    ) {
        val file = context.getDatabasePath(LedgerDatabase.NAME)
        file.parentFile?.mkdirs()
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            schema.forEach { db.execSQL(it) }
            rows(db)
            db.execSQL("PRAGMA user_version = $version")
        } finally {
            db.close()
        }
    }

    private fun storedVersion(database: LedgerDatabase): Long =
        database.openHelper.writableDatabase
            .query(SimpleSQLiteQuery("PRAGMA user_version"))
            .use { cursor ->
                check(cursor.moveToFirst()) { "PRAGMA user_version returned no row" }
                cursor.getLong(0)
            }

    /** The user's redacted VietinBank sample, kept verbatim. */
    private fun sample(): PermittedNotificationContent = PermittedNotificationContent(
        title = "Tin biến động số dư",
        text = listOf(
            "VietinBank: 16/10/2026 07:38",
            "TK: Redacted",
            "GD: -50,000 VND",
            "SDC: xxx,000 VND",
            "ND: ........",
        ).joinToString("\n"),
        bigText = null,
    )

    /** One version-2 draft row, in the shape version 2 stored it: no identity columns at all. */
    private fun insertVersionTwoDraft(db: SQLiteDatabase, id: Long, key: String, postedAt: Long) {
        db.execSQL(
            "INSERT INTO `pending_bank_drafts` (`id`, `sourcePackage`, `notificationKey`, " +
                "`postedAtEpochMillis`, `bank`, `parseStatus`, `amountState`, `amountVnd`, " +
                "`amountUnresolvedReason`, `direction`, `momentState`, `statedDate`, `statedTime`, " +
                "`momentUnresolvedReason`, `memoState`, `memo`, `memoUnresolvedReason`, " +
                "`reviewStatus`, `confirmedTransactionId`, `createdAtEpochMillis`) VALUES " +
                "($id, '$BANK_PACKAGE', '$key', $postedAt, 'VIETINBANK', 'PARSED', 'PRESENT', " +
                "50000, NULL, 'DEBIT', 'PRESENT', '2026-10-16', '07:38', NULL, 'UNRESOLVED', " +
                "NULL, 'REDACTED', 'PENDING', NULL, $CREATED_AT)",
        )
    }

    /**
     * The evidence digest a version-3 row for this sample states — the same canonical digest the
     * running app derives from those permitted fields, so the fixture holds a real version-3 row
     * instead of an arbitrary one. Only the delivery identity changes across the 3-to-4 upgrade.
     */
    private fun sampleEvidenceDigest(): String = draftEvidenceDigest(
        bank = "VIETINBANK",
        parseStatus = IntakeParseStatus.PARSED,
        amountState = IntakeFieldState.PRESENT,
        amountVnd = 50_000L,
        amountUnresolvedReason = null,
        direction = IntakeDirection.DEBIT,
        momentState = IntakeFieldState.PRESENT,
        statedDate = "2026-10-16",
        statedTime = "07:38",
        momentUnresolvedReason = null,
        memoState = IntakeFieldState.UNRESOLVED,
        memo = null,
        memoUnresolvedReason = "REDACTED",
    )

    private companion object {
        const val BANK_PACKAGE = "com.vietinbank.ipay"
        const val FIRST_KEY = "0|com.vietinbank.ipay|1|sample|100"
        const val POSTED_AT = 1_760_000_000_000L
        const val UPDATE_DELAY = 60_000L
        const val CREATED_AT = 1_760_000_001_000L
        const val LEGACY_TIME = 1_700_000_000_000L
        const val MIGRATED_DRAFT_ID = 7L
        const val MIGRATED_TRANSACTION_ID = 3L

        /** Ids of the extra version-2 rows: an exact duplicate delivery and a later post. */
        const val LEGACY_DUPLICATE_DRAFT_ID = 8L
        const val LEGACY_LATER_DRAFT_ID = 9L

        /** What version 3 stored as the delivery id: package and key, with no post time. */
        const val LEGACY_DELIVERY_ID = "$BANK_PACKAGE|key:$FIRST_KEY"

        /** The tables Room exported for version 1. */
        val VERSION_ONE_SCHEMA: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                "NOT NULL, `name` TEXT NOT NULL, `archived` INTEGER NOT NULL, " +
                "`createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                "NOT NULL, `type` TEXT NOT NULL, `amountVnd` INTEGER NOT NULL, " +
                "`occurredAtEpochMillis` INTEGER NOT NULL, `description` TEXT NOT NULL, " +
                "`categoryId` INTEGER, `confirmation` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, " +
                "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) " +
                "ON UPDATE CASCADE ON DELETE RESTRICT )",
            "CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` " +
                "ON `transactions` (`categoryId`)",
        )

        /** The tables Room exported for version 2: version 1 plus the draft table. */
        val VERSION_TWO_SCHEMA: List<String> = VERSION_ONE_SCHEMA + listOf(
            "CREATE TABLE IF NOT EXISTS `pending_bank_drafts` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `sourcePackage` TEXT NOT NULL, `notificationKey` TEXT, " +
                "`postedAtEpochMillis` INTEGER NOT NULL, `bank` TEXT NOT NULL, " +
                "`parseStatus` TEXT NOT NULL, `amountState` TEXT NOT NULL, `amountVnd` INTEGER, " +
                "`amountUnresolvedReason` TEXT, `direction` TEXT NOT NULL, " +
                "`momentState` TEXT NOT NULL, `statedDate` TEXT, `statedTime` TEXT, " +
                "`momentUnresolvedReason` TEXT, `memoState` TEXT NOT NULL, `memo` TEXT, " +
                "`memoUnresolvedReason` TEXT, `reviewStatus` TEXT NOT NULL, " +
                "`confirmedTransactionId` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, " +
                "FOREIGN KEY(`confirmedTransactionId`) REFERENCES `transactions`(`id`) " +
                "ON UPDATE CASCADE ON DELETE SET NULL)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_reviewStatus` " +
                "ON `pending_bank_drafts` (`reviewStatus`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_confirmedTransactionId` " +
                "ON `pending_bank_drafts` (`confirmedTransactionId`)",
        )

        /**
         * The tables Room exported for version 3: version 1 plus the draft table with its delivery
         * and evidence identity columns, the unique identity index, and the duplicate link.
         */
        val VERSION_THREE_SCHEMA: List<String> = VERSION_ONE_SCHEMA + listOf(
            "CREATE TABLE IF NOT EXISTS `pending_bank_drafts` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `deliveryId` TEXT NOT NULL, `sourcePackage` TEXT NOT NULL, " +
                "`notificationKey` TEXT, `postedAtEpochMillis` INTEGER NOT NULL, " +
                "`bank` TEXT NOT NULL, `parseStatus` TEXT NOT NULL, `amountState` TEXT NOT NULL, " +
                "`amountVnd` INTEGER, `amountUnresolvedReason` TEXT, `direction` TEXT NOT NULL, " +
                "`momentState` TEXT NOT NULL, `statedDate` TEXT, `statedTime` TEXT, " +
                "`momentUnresolvedReason` TEXT, `memoState` TEXT NOT NULL, `memo` TEXT, " +
                "`memoUnresolvedReason` TEXT, `evidenceDigest` TEXT NOT NULL, " +
                "`reviewStatus` TEXT NOT NULL, `confirmedTransactionId` INTEGER, " +
                "`possibleDuplicateOfDraftId` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, " +
                "FOREIGN KEY(`confirmedTransactionId`) REFERENCES `transactions`(`id`) " +
                "ON UPDATE CASCADE ON DELETE SET NULL, " +
                "FOREIGN KEY(`possibleDuplicateOfDraftId`) REFERENCES " +
                "`pending_bank_drafts`(`id`) ON UPDATE CASCADE ON DELETE SET NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_pending_bank_drafts_deliveryId_evidenceDigest` " +
                "ON `pending_bank_drafts` (`deliveryId`, `evidenceDigest`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_evidenceDigest` " +
                "ON `pending_bank_drafts` (`evidenceDigest`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_possibleDuplicateOfDraftId` " +
                "ON `pending_bank_drafts` (`possibleDuplicateOfDraftId`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_reviewStatus` " +
                "ON `pending_bank_drafts` (`reviewStatus`)",
            "CREATE INDEX IF NOT EXISTS `index_pending_bank_drafts_confirmedTransactionId` " +
                "ON `pending_bank_drafts` (`confirmedTransactionId`)",
        )
    }
}
