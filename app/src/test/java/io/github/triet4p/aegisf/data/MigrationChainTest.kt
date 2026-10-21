package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.ui.ManualDateTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
import java.io.File
import java.util.TimeZone

/**
 * The cumulative migration chain, exercised against a real previous-schema database.
 *
 * The fixture of this test is not a current database with an old version number written
 * into it: it is a version-6 database built from the schema that the app itself exported
 * for version 6 — the real historical DDL, with the real historical identity hash — and
 * then filled with the shapes the older app stored: an active category, an archived
 * category, a category that was deleted before the snapshot was taken, the records that
 * were filed under it, the saved rules, and bank drafts in every lifecycle state,
 * including a possible-duplicate pair and a draft whose payload could not be parsed.
 *
 * The chain is then run by the production builder, and the test asserts what the older
 * rows are *after* the upgrade: the same records, the same totals, the same category and
 * rule identities, the same draft identities and review state, the same link between a
 * confirmed draft and its record, and the truthful provenance of the descriptions that
 * the old schema never timestamped.
 *
 * The second test injects a failure into the last step of the chain and asserts that the
 * whole upgrade rolls back — the older rows, their values, and their totals intact, the
 * version unchanged, and nothing deleted — and that the same file opens and migrates
 * successfully once the injected defect is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationChainTest {

    private val zone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val zoneId = "Asia/Ho_Chi_Minh"

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    @Test
    fun `the full chain migrates a real version six database and preserves every record total and identity`() =
        runTest {
            writeVersionSixDatabase()
            val database = LedgerDatabase.build(context)
            try {
                val room = database.openHelper.writableDatabase
                assertEquals(
                    "the chain must end at the version the app declares",
                    LedgerDatabase.VERSION.toLong(),
                    scalar(room, "PRAGMA user_version"),
                )
                assertEquals(
                    "the upgraded file must carry the identity hash of the current schema",
                    exportedIdentityHash(LedgerDatabase.VERSION),
                    text(room, "SELECT identity_hash FROM room_master_table WHERE id = 42"),
                )

                // --- The categories -------------------------------------------------
                val categories = database.categoryDao().findAll().associateBy { it.id }
                assertEquals(setOf(FOOD_CATEGORY_ID, ARCHIVED_CATEGORY_ID), categories.keys)
                assertEquals("Food", categories.getValue(FOOD_CATEGORY_ID).name)
                assertFalse(categories.getValue(FOOD_CATEGORY_ID).archived)
                assertEquals("Archived", categories.getValue(ARCHIVED_CATEGORY_ID).name)
                assertTrue(categories.getValue(ARCHIVED_CATEGORY_ID).archived)
                assertFalse(
                    "a deleted category must not come back, and its id must not be reissued",
                    categories.containsKey(DELETED_CATEGORY_ID),
                )

                // --- The ledger records ---------------------------------------------
                val records = database.transactionDao().findAll().associateBy { it.id }
                assertEquals(
                    setOf(
                        MANUAL_EXPENSE_ID,
                        BANK_INCOME_ID,
                        TRANSFER_ID,
                        ARCHIVED_EXPENSE_ID,
                        FREED_EXPENSE_ID,
                    ),
                    records.keys,
                )
                for (id in records.keys) {
                    assertEquals(
                        "a stored record that was never corrected has been corrected zero times",
                        0,
                        records.getValue(id).revision,
                    )
                }
                val manual = records.getValue(MANUAL_EXPENSE_ID)
                assertEquals(TransactionType.EXPENSE, manual.type)
                assertEquals(50_000L, manual.amountVnd)
                assertEquals("Manual description", manual.description)
                assertEquals(FOOD_CATEGORY_ID, manual.categoryId)
                assertEquals(ConfirmationStatus.CONFIRMED, manual.confirmation)
                assertEquals(RecordSource.MANUAL, manual.source)
                assertEquals(FIXTURE_MOMENT, manual.occurredAtEpochMillis)
                assertEquals(FIXTURE_MOMENT, manual.createdAtEpochMillis)
                assertEquals(FIXTURE_MOMENT, manual.updatedAtEpochMillis)
                assertEquals(RecordSource.BANK_NOTIFICATION, records.getValue(BANK_INCOME_ID).source)
                assertEquals(TransactionType.INCOME, records.getValue(BANK_INCOME_ID).type)
                assertNull(records.getValue(TRANSFER_ID).categoryId)
                assertEquals(
                    ARCHIVED_CATEGORY_ID,
                    records.getValue(ARCHIVED_EXPENSE_ID).categoryId,
                )
                assertNull(
                    "the record of a deleted category keeps its own values and loses only the link",
                    records.getValue(FREED_EXPENSE_ID).categoryId,
                )
                assertEquals(
                    "Freed by a deleted category",
                    records.getValue(FREED_EXPENSE_ID).description,
                )
                assertEquals(20_000L, records.getValue(FREED_EXPENSE_ID).amountVnd)

                // --- The exact totals ------------------------------------------------
                assertEquals(
                    100_000L,
                    records.values
                        .filter {
                            it.confirmation == ConfirmationStatus.CONFIRMED &&
                                it.type == TransactionType.EXPENSE
                        }
                        .sumOf { it.amountVnd },
                )
                val spending = WorkflowQueryService(database).confirmedLedgerAggregate(
                    LedgerDateRange.closed(
                        fromEpochMillis = ManualDateTime.monthStartEpochMillis(
                            ManualDateTime.toEpochMillis("2026-10-15", "12:00", zone),
                            zone,
                        ),
                        throughExclusiveEpochMillis = ManualDateTime.nextMonthStartEpochMillis(
                            ManualDateTime.monthStartEpochMillis(
                                ManualDateTime.toEpochMillis("2026-10-15", "12:00", zone),
                                zone,
                            ),
                            zone,
                        ),
                        zoneId = zoneId,
                    ),
                )
                assertEquals(100_000L, spending.spending.totalVnd)
                assertFalse(spending.spending.overflowed)
                assertEquals(3, spending.spending.recordCount)
                assertEquals(
                    "income and transfers never enter the spending",
                    setOf(FOOD_CATEGORY_ID, ARCHIVED_CATEGORY_ID, null),
                    spending.spending.categoryTotals.map { it.categoryId }.toSet(),
                )
                assertEquals(
                    "the income is reported apart from the spending",
                    1_000_000L,
                    spending.income.totalVnd,
                )
                assertEquals(1, spending.income.recordCount)
                assertEquals(25_000L, spending.transfers.totalVnd)
                assertEquals(1, spending.transfers.recordCount)

                // --- The saved rules -------------------------------------------------
                val rules = database.rememberedCategoryRuleDao().findAll()
                assertEquals(
                    setOf("food" to FOOD_CATEGORY_ID, "archived" to ARCHIVED_CATEGORY_ID),
                    rules.map { it.patternKey to it.categoryId }.toSet(),
                )
                assertEquals(
                    FOOD_CATEGORY_ID,
                    database.rememberedCategoryRuleDao().findActiveCategory("food")?.id,
                )
                assertNull(
                    "a rule whose category is archived no longer answers",
                    database.rememberedCategoryRuleDao().findActiveCategory("archived"),
                )

                // --- The bank drafts --------------------------------------------------
                val drafts = WorkflowQueryService(database)
                    .draftCandidates(ALL_DRAFTS)
                    .items
                    .associateBy { it.id }
                assertEquals(
                    setOf(PENDING_DRAFT_ID, CONFIRMED_DRAFT_ID, DISMISSED_DRAFT_ID, DUPLICATE_DRAFT_ID),
                    drafts.keys,
                )
                val pending = drafts.getValue(PENDING_DRAFT_ID)
                assertEquals(IntakeReviewStatus.PENDING, pending.reviewStatus)
                assertEquals(3, pending.reviewRevision)
                assertEquals("50,000", pending.reviewAmountText)
                assertEquals("User review memo", pending.reviewMemo)
                assertEquals(TransactionType.EXPENSE, pending.reviewType)
                assertEquals(FOOD_CATEGORY_ID, pending.reviewCategoryId)
                assertTrue(pending.reviewCategoryResolved)
                assertEquals(
                    "the delivery identity of the older draft must survive the upgrade",
                    draftDeliveryId(BANK_PACKAGE, PENDING_KEY, FIXTURE_MOMENT + PENDING_DRAFT_ID),
                    pending.deliveryId,
                )
                assertEquals("digest-pending", pending.evidenceDigest)
                assertEquals(
                    IntakeReviewStatus.CONFIRMED,
                    drafts.getValue(CONFIRMED_DRAFT_ID).reviewStatus,
                )
                assertEquals(
                    "the link between a confirmed draft and its record must survive",
                    BANK_INCOME_ID,
                    drafts.getValue(CONFIRMED_DRAFT_ID).confirmedTransactionId,
                )
                assertEquals(
                    IntakeReviewStatus.DISMISSED,
                    drafts.getValue(DISMISSED_DRAFT_ID).reviewStatus,
                )
                assertEquals(
                    "the possible-duplicate pair must stay paired",
                    PENDING_DRAFT_ID,
                    drafts.getValue(DUPLICATE_DRAFT_ID).possibleDuplicateOfDraftId,
                )
                assertEquals("digest-pending", drafts.getValue(DUPLICATE_DRAFT_ID).evidenceDigest)
                assertEquals(
                    "a pair of equal evidence under two deliveries stays two candidates",
                    draftDeliveryId(BANK_PACKAGE, DUPLICATE_KEY, FIXTURE_MOMENT + DUPLICATE_DRAFT_ID),
                    drafts.getValue(DUPLICATE_DRAFT_ID).deliveryId,
                )

                // --- The provenance of the migrated descriptions -----------------------
                val captures = database.captureMessageDao().findAllIncludingTombstones()
                assertEquals(
                    "three parsed drafts and five records leave eight source rows",
                    8,
                    captures.size,
                )
                val bankEvidence = captures.filter {
                    it.sourceKind == CaptureSourceKind.BANK_NOTIFICATION
                }
                assertEquals(3, bankEvidence.size)
                for (evidence in bankEvidence) {
                    assertEquals(CaptureKind.BANK_EVIDENCE_REF, evidence.captureKind)
                    assertNotNull(evidence.bodyText)
                    assertEquals(
                        "a bank reference keeps the bank's own post instant",
                        FIXTURE_MOMENT + evidence.originRef!!.removePrefix("BANK_DRAFT:").toLong(),
                        evidence.capturedAtEpochMillis,
                    )
                    assertNull(
                        "the old schema stated no timezone, so none is invented",
                        evidence.captureTimezone,
                    )
                    assertEquals(0, evidence.revision)
                    assertFalse(evidence.tombstone)
                }
                val legacy = captures.filter {
                    it.sourceKind == CaptureSourceKind.LEGACY_LEDGER_DESCRIPTION
                }
                assertEquals(5, legacy.size)
                for (annotation in legacy) {
                    assertEquals(CaptureKind.MANUAL_ANNOTATION, annotation.captureKind)
                    assertEquals(
                        "a legacy description keeps the text the older row carried",
                        records.getValue(
                            annotation.originRef!!.removePrefix("LEDGER_RECORD:").toLong(),
                        ).description,
                        annotation.bodyText,
                    )
                    assertNull(
                        "the older schema recorded no authoring instant, so none is invented",
                        annotation.capturedAtEpochMillis,
                    )
                    assertNull(
                        "the older schema recorded no timezone, so none is invented",
                        annotation.captureTimezone,
                    )
                    assertEquals(0, annotation.revision)
                    assertFalse(annotation.tombstone)
                }
                assertFalse(
                    "an unsupported draft never becomes source text",
                    captures.any { it.sourceIdentity == "BANK_DRAFT:$DISMISSED_DRAFT_ID" },
                )

                // --- The tables the newer versions introduced ---------------------------
                assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM note_events"))
                assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM context_links"))
                assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM classification_snapshots"))
                assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM workflow_operations"))

                // --- The constraints the newer versions rely on -------------------------
                room.query(SimpleSQLiteQuery("PRAGMA foreign_key_check")).use { cursor ->
                    assertFalse("the upgrade left a foreign-key violation", cursor.moveToFirst())
                }
                for (index in listOf(
                    "index_capture_messages_sourceKind_sourceIdentity",
                    "index_note_events_resolvedTransactionId",
                    "index_transactions_categoryId",
                    "index_workflow_operations_idempotencyKey",
                    "index_context_links_eventId_draftId",
                    "index_classification_snapshots_draftId",
                )) {
                    assertEquals(
                        "the index $index must exist after the upgrade",
                        1L,
                        scalar(
                            room,
                            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                                "AND name = '$index'",
                        ),
                    )
                }
                val duplicateSource = database.captureMessageDao().insert(
                    bankEvidence.first().copy(id = 0L, bodyText = "must not replace stored text"),
                )
                assertEquals(
                    "one source identity can be claimed only once",
                    -1L,
                    duplicateSource,
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `the upgraded database still obeys the revision idempotency and resolution rules`() =
        runTest {
            writeVersionSixDatabase()
            val database = LedgerDatabase.build(context)
            try {
                val commands = WorkflowCommandService(
                    database = database,
                    clock = SystemLedgerClock,
                    timezoneId = { zoneId },
                    engine = object : ClassificationEngine {
                        override suspend fun classify(
                            context: io.github.triet4p.aegisf.data.laya.LayaEffectiveContext,
                            categories: List<CategoryEntity>,
                        ): ClassificationEngineResult =
                            ClassificationEngineResult.Failed("not used by this test")
                    },
                )
                val query = WorkflowQueryService(database)

                // A stored record keeps its revision, and a correction that was made
                // against the revision the caller read is the one that applies.
                val stored = query.findLedgerRecord(MANUAL_EXPENSE_ID)!!
                assertEquals(0, stored.revision)
                val corrected = commands.correctTransaction(
                    scope = WorkflowCallerScope.User,
                    request = CorrectTransactionRequest(
                        idempotencyKey = "chain-correct-1",
                        transactionId = MANUAL_EXPENSE_ID,
                        expectedRevision = 0,
                        type = TransactionType.EXPENSE,
                        amountVnd = 55_000L,
                        occurredAtEpochMillis = FIXTURE_MOMENT,
                        description = "Manual description",
                        categoryId = FOOD_CATEGORY_ID,
                    ),
                )
                assertTrue(corrected is WorkflowCommandOutcome.Applied)
                assertEquals(1, query.findLedgerRecord(MANUAL_EXPENSE_ID)!!.revision)
                val stale = commands.correctTransaction(
                    scope = WorkflowCallerScope.User,
                    request = CorrectTransactionRequest(
                        idempotencyKey = "chain-correct-2",
                        transactionId = MANUAL_EXPENSE_ID,
                        expectedRevision = 0,
                        type = TransactionType.EXPENSE,
                        amountVnd = 77_000L,
                        occurredAtEpochMillis = FIXTURE_MOMENT,
                        description = "Stale correction",
                        categoryId = FOOD_CATEGORY_ID,
                    ),
                )
                assertTrue(stale is WorkflowCommandOutcome.Conflict)
                assertEquals(
                    "a refused correction writes nothing",
                    55_000L,
                    query.findLedgerRecord(MANUAL_EXPENSE_ID)!!.amountVnd,
                )

                // A repeated request under one key changes nothing and is recorded once.
                val first = commands.saveManualTransaction(
                    scope = WorkflowCallerScope.User,
                    request = SaveManualTransactionRequest(
                        idempotencyKey = "chain-save-1",
                        type = TransactionType.EXPENSE,
                        amountVnd = 12_000L,
                        occurredAtEpochMillis = FIXTURE_MOMENT,
                        description = "Written by hand",
                        categoryId = FOOD_CATEGORY_ID,
                    ),
                )
                val repeated = commands.saveManualTransaction(
                    scope = WorkflowCallerScope.User,
                    request = SaveManualTransactionRequest(
                        idempotencyKey = "chain-save-1",
                        type = TransactionType.EXPENSE,
                        amountVnd = 12_000L,
                        occurredAtEpochMillis = FIXTURE_MOMENT,
                        description = "Written by hand",
                        categoryId = FOOD_CATEGORY_ID,
                    ),
                )
                assertTrue(first is WorkflowCommandOutcome.Applied)
                assertTrue(repeated is WorkflowCommandOutcome.NoChange)
                assertEquals(
                    "one key is one recorded operation",
                    1L,
                    scalar(
                        database.openHelper.writableDatabase,
                        "SELECT COUNT(*) FROM workflow_operations WHERE idempotencyKey = " +
                            "'chain-save-1'",
                    ),
                )
                assertEquals(
                    "the repeated request added no second record",
                    1,
                    query.ledgerRecords(ALL_LEDGER_RECORDS).items.count {
                        it.description == "Written by hand"
                    },
                )

                // A note that was recorded by hand is spent: it is resolved against the
                // record, and a second record cannot be written from it.
                val saved = commands.saveCapture(
                    scope = WorkflowCallerScope.User,
                    request = SaveCaptureRequest(
                        idempotencyKey = "chain-capture-1",
                        bodyText = "Lunch at a cafe 45,000 VND",
                    ),
                )
                assertTrue(saved is WorkflowCommandOutcome.Applied)
                val captureId = (saved as WorkflowCommandOutcome.Applied)
                    .change as WorkflowChange.CaptureSaved
                val reading = commands.updateEventInterpretation(
                    scope = WorkflowCallerScope.User,
                    request = UpdateEventRequest(
                        idempotencyKey = "chain-event-1",
                        captureId = captureId.captureId,
                        expectedCaptureRevision = captureId.revision,
                        interpretation = NoteEventInterpretation(
                            spanStart = 0,
                            spanEnd = 10,
                            normalizedText = "Lunch at a cafe",
                            amountVnd = 45_000L,
                        ),
                        extractionVersion = "chain-test-1",
                    ),
                )
                assertTrue(reading is WorkflowCommandOutcome.Applied)
                val event = (reading as WorkflowCommandOutcome.Applied)
                    .change as WorkflowChange.EventStored
                val fromNote = commands.saveManualTransaction(
                    scope = WorkflowCallerScope.User,
                    request = SaveManualTransactionRequest(
                        idempotencyKey = "chain-save-2",
                        type = TransactionType.EXPENSE,
                        amountVnd = 45_000L,
                        occurredAtEpochMillis = FIXTURE_MOMENT,
                        description = "Lunch at a cafe",
                        categoryId = FOOD_CATEGORY_ID,
                        source = ManualTransactionSource.NoteEvent(
                            eventId = event.eventId,
                            expectedRevision = event.revision,
                        ),
                    ),
                )
                assertTrue(fromNote is WorkflowCommandOutcome.Applied)
                val resolved = database.noteEventDao().findById(event.eventId)!!
                assertEquals(
                    "a note that was recorded is resolved against that record",
                    (fromNote as WorkflowCommandOutcome.Applied)
                        .change.let { change ->
                            (change as WorkflowChange.TransactionSaved).transactionId
                        },
                    resolved.resolvedTransactionId,
                )
                assertNotNull(resolved.resolvedAtEpochMillis)
                val again = commands.saveManualTransaction(
                    scope = WorkflowCallerScope.User,
                    request = SaveManualTransactionRequest(
                        idempotencyKey = "chain-save-3",
                        type = TransactionType.EXPENSE,
                        amountVnd = 45_000L,
                        occurredAtEpochMillis = FIXTURE_MOMENT,
                        description = "Lunch at a cafe",
                        categoryId = FOOD_CATEGORY_ID,
                        source = ManualTransactionSource.NoteEvent(
                            eventId = event.eventId,
                            expectedRevision = event.revision,
                        ),
                    ),
                )
                assertTrue(
                    "a spent note cannot be recorded a second time",
                    again is WorkflowCommandOutcome.Conflict,
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `an injected failure in the last migration rolls the whole chain back and a retry keeps the data`() =
        runTest {
            writeVersionSixDatabase(injectFailureInLastMigration = true)
            val database = LedgerDatabase.build(context)
            var failed = false
            try {
                database.openHelper.writableDatabase
            } catch (_: Exception) {
                failed = true
            } finally {
                database.close()
            }
            assertTrue("the injected failure must abort the upgrade", failed)

            // The upgrade rolled back whole: the file is still the older database, with
            // its rows, its totals, and its identity hash, and nothing of the newer
            // schema is left behind.
            val file = context.getDatabasePath(LedgerDatabase.NAME)
            assertTrue("the database file must not be deleted", file.isFile)
            val legacy = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            try {
                assertEquals(6L, scalar(legacy, "PRAGMA user_version"))
                assertEquals(2L, scalar(legacy, "SELECT COUNT(*) FROM categories"))
                assertEquals(5L, scalar(legacy, "SELECT COUNT(*) FROM transactions"))
                assertEquals(4L, scalar(legacy, "SELECT COUNT(*) FROM pending_bank_drafts"))
                assertEquals(2L, scalar(legacy, "SELECT COUNT(*) FROM remembered_category_rules"))
                assertEquals(
                    100_000L,
                    scalar(
                        legacy,
                        "SELECT SUM(amountVnd) FROM transactions WHERE type = 'EXPENSE' " +
                            "AND confirmation = 'CONFIRMED'",
                    ),
                )
                assertEquals(
                    "the older identity hash must still be the one the file carries",
                    exportedIdentityHash(6),
                    text(legacy, "SELECT identity_hash FROM room_master_table WHERE id = 42"),
                )
                for (table in listOf(
                    "capture_messages",
                    "context_links",
                    "classification_snapshots",
                    "workflow_operations",
                )) {
                    assertEquals(
                        "the rolled-back upgrade must not leave the table $table behind",
                        0L,
                        scalar(
                            legacy,
                            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                                "AND name = '$table'",
                        ),
                    )
                }
                assertEquals(
                    "the column the failed step added must be gone again",
                    0L,
                    scalar(
                        legacy,
                        "SELECT COUNT(*) FROM pragma_table_info('transactions') " +
                            "WHERE name = 'revision'",
                    ),
                )
                legacy.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    assertFalse("the failed upgrade left a foreign-key violation", cursor.moveToFirst())
                }
                // The injected defect is removed: the file is the older database again,
                // without the object that made the last step fail.
                legacy.execSQL("DROP TABLE `note_events`")
            } finally {
                legacy.close()
            }

            // The same file opens again and the whole chain runs to the end.
            val reopened = LedgerDatabase.build(context)
            try {
                val room = reopened.openHelper.writableDatabase
                assertEquals(
                    LedgerDatabase.VERSION.toLong(),
                    scalar(room, "PRAGMA user_version"),
                )
                assertEquals(5L, scalar(room, "SELECT COUNT(*) FROM transactions"))
                assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM categories"))
                assertEquals(4L, scalar(room, "SELECT COUNT(*) FROM pending_bank_drafts"))
                assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM remembered_category_rules"))
                assertEquals(
                    100_000L,
                    scalar(
                        room,
                        "SELECT SUM(amountVnd) FROM transactions WHERE type = 'EXPENSE' " +
                            "AND confirmation = 'CONFIRMED'",
                    ),
                )
                assertEquals(
                    8L,
                    scalar(room, "SELECT COUNT(*) FROM capture_messages"),
                )
                room.query(SimpleSQLiteQuery("PRAGMA foreign_key_check")).use { cursor ->
                    assertFalse("the retry left a foreign-key violation", cursor.moveToFirst())
                }
            } finally {
                reopened.close()
            }
        }

    // --- The fixture -----------------------------------------------------------

    /**
     * Writes the older database the app really used.
     *
     * The schema is the exported version-6 schema — the file the app wrote for its own
     * version-6 release — so the fixture is the real historical structure and not a
     * current one wearing an old version number.
     *
     * With [injectFailureInLastMigration] the fixture also holds a `note_events` table
     * that already carries the column the last migration adds. The last step of the
     * chain then fails on the duplicate column, which is a failure inside the upgrade
     * transaction rather than before it.
     */
    private fun writeVersionSixDatabase(injectFailureInLastMigration: Boolean = false) {
        val file = context.getDatabasePath(LedgerDatabase.NAME)
        file.parentFile?.mkdirs()
        file.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            exportedStatements(6).forEach { statement -> database.execSQL(statement) }
            if (injectFailureInLastMigration) {
                val noteEvents = exportedStatements(8).first { statement ->
                    statement.contains("CREATE TABLE IF NOT EXISTS `note_events`")
                }
                database.execSQL(noteEvents)
                database.execSQL(
                    "ALTER TABLE `note_events` ADD COLUMN `resolvedTransactionId` INTEGER",
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
            insertTransaction(
                database,
                FREED_EXPENSE_ID,
                "EXPENSE",
                20_000L,
                "Freed by a deleted category",
                null,
                "MANUAL",
            )
            insertDraft(
                database,
                PENDING_DRAFT_ID,
                PENDING_KEY,
                "digest-pending",
                "PENDING",
                null,
                "Original bank memo",
            )
            insertDraft(
                database,
                CONFIRMED_DRAFT_ID,
                "confirmed-key",
                "digest-confirmed",
                "CONFIRMED",
                BANK_INCOME_ID,
                "Confirmed source memo",
            )
            insertDraft(
                database,
                DISMISSED_DRAFT_ID,
                "dismissed-key",
                "digest-dismissed",
                "DISMISSED",
                null,
                null,
            )
            insertDraft(
                database,
                DUPLICATE_DRAFT_ID,
                DUPLICATE_KEY,
                "digest-pending",
                "PENDING",
                null,
                "Original bank memo",
                possibleDuplicateOfDraftId = PENDING_DRAFT_ID,
            )
            database.execSQL(
                "INSERT INTO `remembered_category_rules` (`patternKey`, `categoryId`) VALUES " +
                    "('food', $FOOD_CATEGORY_ID), ('archived', $ARCHIVED_CATEGORY_ID)",
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
            arrayOf<Any?>(id, name, archived, FIXTURE_MOMENT, FIXTURE_MOMENT),
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
            arrayOf<Any?>(
                id,
                type,
                amount,
                FIXTURE_MOMENT,
                description,
                categoryId,
                source,
                FIXTURE_MOMENT,
                FIXTURE_MOMENT,
            ),
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
        possibleDuplicateOfDraftId: Long? = null,
    ) {
        val parsed = status != "DISMISSED"
        database.execSQL(
            "INSERT INTO `pending_bank_drafts` (`id`, `deliveryId`, `sourcePackage`, " +
                "`notificationKey`, `postedAtEpochMillis`, `bank`, `parseStatus`, `amountState`, " +
                "`amountVnd`, `amountUnresolvedReason`, `direction`, `momentState`, `statedDate`, " +
                "`statedTime`, `momentUnresolvedReason`, `memoState`, `memo`, " +
                "`memoUnresolvedReason`, `evidenceDigest`, `reviewStatus`, " +
                "`confirmedTransactionId`, `possibleDuplicateOfDraftId`, `createdAtEpochMillis`, " +
                "`reviewAmountText`, `reviewDateText`, `reviewTimeText`, `reviewMemo`, " +
                "`reviewMemoEdited`, `reviewType`, `reviewCategoryId`, " +
                "`reviewCategoryResolved`, `reviewRevision`) VALUES " +
                "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                "?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id,
                draftDeliveryId(BANK_PACKAGE, key, FIXTURE_MOMENT + id),
                BANK_PACKAGE,
                key,
                FIXTURE_MOMENT + id,
                "VIETINBANK",
                if (parsed) "PARSED" else "UNSUPPORTED_FORMAT",
                if (parsed) "PRESENT" else "MISSING",
                if (parsed) 50_000L else null,
                null,
                if (parsed) "DEBIT" else "UNKNOWN",
                if (parsed) "PRESENT" else "MISSING",
                if (parsed) "2026-10-15" else null,
                if (parsed) "12:00" else null,
                null,
                if (memo == null) "MISSING" else "PRESENT",
                memo,
                null,
                digest,
                status,
                transactionId,
                possibleDuplicateOfDraftId,
                FIXTURE_MOMENT + id,
                if (status == "PENDING") "50,000" else null,
                null,
                null,
                if (status == "PENDING") "User review memo" else null,
                if (status == "PENDING") 1 else 0,
                if (status == "PENDING") "EXPENSE" else null,
                if (status == "PENDING") FOOD_CATEGORY_ID else null,
                if (status == "PENDING") 1 else 0,
                if (status == "PENDING") 3 else 0,
            ),
        )
    }

    // --- The exported schemas ---------------------------------------------------

    /**
     * The statements that build the database of one exported version.
     *
     * They are read from the schema file the app itself exported for that version, so the
     * fixture is the real historical structure: the same tables, the same columns, the
     * same foreign keys, the same indices, and the same identity hash.
     */
    private fun exportedStatements(version: Int): List<String> {
        val exported = JSONObject(schemaFile(version).readText())
        val database = exported.getJSONObject("database")
        val statements = mutableListOf<String>()
        val setup = database.optJSONArray("setupQueries")
        if (setup != null) {
            for (index in 0 until setup.length()) {
                statements += setup.getString(index)
            }
        }
        val entities = database.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val tableName = entity.getString("tableName")
            statements += entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
            val indices = entity.optJSONArray("indices")
            if (indices != null) {
                for (indexInEntity in 0 until indices.length()) {
                    statements += indices.getJSONObject(indexInEntity)
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", tableName)
                }
            }
        }
        return statements
    }

    /** The identity hash the app exported for one version. */
    private fun exportedIdentityHash(version: Int): String {
        val exported = JSONObject(schemaFile(version).readText())
        return exported.getJSONObject("database").getString("identityHash")
    }

    /** The exported schema file of one version, found from the test's working directory. */
    private fun schemaFile(version: Int): File {
        val relative = "$SCHEMA_DIRECTORY/$version.json"
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val current = directory ?: return@repeat
            for (candidate in listOf(File(current, relative), File(current, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            directory = current.parentFile
        }
        throw AssertionError(
            "The exported schema $relative was not found under " +
                "${System.getProperty("user.dir")}.",
        )
    }

    // --- Small helpers -----------------------------------------------------------

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

    private fun text(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        database.query(SimpleSQLiteQuery(sql)).use { cursor ->
            check(cursor.moveToFirst()) { "$sql returned no row" }
            cursor.getString(0)
        }

    private companion object {
        const val SCHEMA_DIRECTORY =
            "schemas/io.github.triet4p.aegisf.data.LedgerDatabase"
        const val BANK_PACKAGE = "com.example.bank"
        const val PENDING_KEY = "pending-key"
        const val DUPLICATE_KEY = "duplicate-key"
        const val FOOD_CATEGORY_ID = 1L
        const val ARCHIVED_CATEGORY_ID = 2L
        const val DELETED_CATEGORY_ID = 3L
        const val MANUAL_EXPENSE_ID = 101L
        const val BANK_INCOME_ID = 102L
        const val TRANSFER_ID = 103L
        const val ARCHIVED_EXPENSE_ID = 104L
        const val FREED_EXPENSE_ID = 105L
        const val PENDING_DRAFT_ID = 201L
        const val CONFIRMED_DRAFT_ID = 202L
        const val DISMISSED_DRAFT_ID = 203L
        const val DUPLICATE_DRAFT_ID = 204L

        /** 2026-10-15T12:00 in the fixture's own zone. */
        const val FIXTURE_MOMENT = 1_792_040_400_000L
    }
}
