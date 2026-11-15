package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.provider.HostedProfileImportResult
import io.github.triet4p.aegisf.provider.HostedProviderExportSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S11.3 device proof: the accepted Room-v6 schema uses the production migration objects on an
 * isolated source file, is exported, and is restored into another app-private database file.
 */
@RunWith(AndroidJUnit4::class)
class S113MigrationRestoreInstrumentedTest {
    @Test
    fun acceptedVersionSixMigrationExportRestoreAndRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        context.deleteDatabase(SOURCE_DATABASE_NAME)
        context.deleteDatabase(TARGET_DATABASE_NAME)
        val recoveryDirectory = File(context.noBackupFilesDir, RECOVERY_DIRECTORY_NAME)
        recoveryDirectory.deleteRecursively()

        var migrated: LedgerDatabase? = null
        var restored: LedgerDatabase? = null
        try {
            writeAcceptedVersionSixDatabase(context)
            val legacyFile = context.getDatabasePath(SOURCE_DATABASE_NAME)
            val legacy = SQLiteDatabase.openDatabase(
                legacyFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                assertEquals(
                    6L,
                    legacy.rawQuery("PRAGMA user_version", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getLong(0)
                    },
                )
                assertEquals(
                    V6_IDENTITY_HASH,
                    legacy.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null)
                        .use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            cursor.getString(0)
                        },
                )
                assertEquals(
                    0L,
                    legacy.rawQuery(
                        "SELECT COUNT(*) FROM pragma_table_info('transactions') " +
                            "WHERE name = 'revision'",
                        null,
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getLong(0)
                    },
                )
            } finally {
                legacy.close()
            }
            migrated = buildReleaseDatabase(context, SOURCE_DATABASE_NAME)
            val source = checkNotNull(migrated)
            val sourceSql = source.openHelper.writableDatabase
            assertEquals(LedgerDatabase.VERSION.toLong(), scalar(sourceSql, "PRAGMA user_version"))
            assertEquals(2L, scalar(sourceSql, "SELECT COUNT(*) FROM categories"))
            assertEquals(1L, scalar(sourceSql, "SELECT COUNT(*) FROM transactions"))
            assertEquals(LEDGER_AMOUNT, scalar(sourceSql, "SELECT SUM(amountVnd) FROM transactions"))
            assertEquals(1L, scalar(sourceSql, "SELECT COUNT(*) FROM remembered_category_rules"))
            assertEquals(1L, scalar(sourceSql, "SELECT COUNT(*) FROM pending_bank_drafts"))
            assertEquals(
                1L,
                scalar(
                    sourceSql,
                    "SELECT COUNT(*) FROM capture_messages WHERE sourceKind = " +
                        "'LEGACY_LEDGER_DESCRIPTION' AND originRef = 'LEDGER_RECORD:$TRANSACTION_ID' " +
                        "AND bodyText = 'Legacy breakfast' AND capturedAtEpochMillis IS NULL " +
                        "AND captureTimezone IS NULL",
                ),
            )
            assertEquals(
                "NONE",
                text(sourceSql, "SELECT reviewCategoryOrigin FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
            )
            assertEquals(
                CATEGORY_ID,
                scalar(sourceSql, "SELECT reviewCategoryId FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
            )
            assertEquals(
                0L,
                scalar(
                    sourceSql,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'classification_work'",
                ),
            )

            val noteCaptureId = source.captureMessageDao().insert(
                CaptureMessageEntity(
                    captureKind = CaptureKind.USER_NOTE,
                    sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                    sourceIdentity = "s113-note-source",
                    bodyText = "Payment note",
                    capturedAtEpochMillis = FIXTURE_MOMENT,
                    captureTimezone = "Asia/Ho_Chi_Minh",
                    revision = 0,
                    originRef = null,
                ),
            )
            val eventId = source.noteEventDao().insert(
                NoteEventEntity(
                    captureId = noteCaptureId,
                    eventIdentity = "s113-payment-event",
                    spanStart = 0,
                    spanEnd = 12,
                    normalizedText = "Breakfast",
                    amountVnd = 87_000L,
                    merchant = "Cafe",
                    anchorDate = "2026-10-15",
                    anchorTime = "12:00",
                    anchorTimezone = "Asia/Ho_Chi_Minh",
                    intervalEndDate = null,
                    intervalEndTime = null,
                    extractionVersion = "s113-fixture-v1",
                    unresolvedReason = null,
                    status = NoteEventStatus.INTERPRETED,
                    revision = 0,
                    sourceRevision = 0,
                    createdAtEpochMillis = FIXTURE_MOMENT,
                    resolvedTransactionId = TRANSACTION_ID,
                    userEdited = true,
                    resolvedAtEpochMillis = FIXTURE_MOMENT,
                ),
            )
            source.contextLinkDao().insert(
                ContextLinkEntity(
                    eventId = eventId,
                    draftId = DRAFT_ID,
                    state = ContextLinkState.ACCEPTED,
                    evidence = "same stated day and amount",
                    revision = 2,
                    userOverride = "user accepted",
                    activeEventId = eventId,
                    createdAtEpochMillis = FIXTURE_MOMENT,
                ),
            )
            sourceSql.execSQL(
                "INSERT INTO `classification_snapshots` (" +
                    "`draftId`, `contextRevision`, `categoryRevision`, `ruleRevision`, " +
                    "`modelRevision`, `candidateSetVersion`, `decisionSource`, `suggestedCategoryId`, " +
                    "`candidateIds`, `probabilities`, `unknownShare`, `confidence`, " +
                    "`effectiveInputDigest`, `classificationInputKind`, `classificationInputIdentity`, " +
                    "`translationSourceRevision`, `eventIds`, `predictionTime`, `staleReason`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    DRAFT_ID,
                    "context-r1",
                    "category-r1",
                    "rules-r1",
                    "old-classifier-v0",
                    "candidate-v1",
                    "MODEL",
                    ARCHIVED_CATEGORY_ID,
                    "[$ARCHIVED_CATEGORY_ID]",
                    "[0.99]",
                    0.01,
                    0.99,
                    "historical-input-digest",
                    "STORED_SOURCE",
                    "historical-input-identity",
                    null,
                    "[$eventId]",
                    FIXTURE_MOMENT - 2_000,
                    "historical-only",
                ),
            )

            val backupOutput = ByteArrayOutputStream()
            CompleteStateExporter(source) { EMPTY_PROFILES }
                .writeTo(backupOutput, exportedAtEpochMillis = FIXTURE_MOMENT)
            val backup = backupOutput.toByteArray()

            restored = Room.databaseBuilder(
                context.applicationContext,
                LedgerDatabase::class.java,
                TARGET_DATABASE_NAME,
            ).build()
            val target = checkNotNull(restored)
            target.categoryDao().insert(
                CategoryEntity(
                    name = "Pre-restore category",
                    createdAtEpochMillis = ORIGINAL_TIME,
                    updatedAtEpochMillis = ORIGINAL_TIME,
                ),
            )
            target.transactionDao().insert(
                TransactionEntity(
                    type = TransactionType.INCOME,
                    amountVnd = 111L,
                    occurredAtEpochMillis = ORIGINAL_TIME,
                    description = "Pre-restore record",
                    categoryId = 1L,
                    confirmation = ConfirmationStatus.CONFIRMED,
                    source = RecordSource.MANUAL,
                    createdAtEpochMillis = ORIGINAL_TIME,
                    updatedAtEpochMillis = ORIGINAL_TIME,
                ),
            )

            val restorer = CompleteStateRestorer(
                database = target,
                exporter = CompleteStateExporter(target) { EMPTY_PROFILES },
                recoveryDirectory = recoveryDirectory,
                profileTransfer = EmptyProfileTransfer,
            )
            val result = restorer.restore(backup)
            assertTrue(result.previousState.file.isFile)
            assertEquals(LedgerDatabase.VERSION.toLong(), result.preview.schemaVersion)
            assertEquals(2, result.preview.categoryCount)
            assertEquals(1, result.preview.ruleCount)
            assertEquals(1, result.preview.transactionCount)
            assertEquals(1, result.preview.draftCount)
            assertEquals(3, result.preview.captureCount)
            assertEquals(1, result.preview.eventCount)
            assertEquals(1, result.preview.linkCount)
            assertEquals(1, result.preview.historicalSnapshotCount)

            val targetSql = target.openHelper.writableDatabase
            assertEquals(setOf(CATEGORY_ID, ARCHIVED_CATEGORY_ID), ids(targetSql, "categories", "id"))
            assertEquals(LEDGER_AMOUNT, scalar(targetSql, "SELECT SUM(amountVnd) FROM transactions"))
            assertEquals(
                "Legacy breakfast",
                text(targetSql, "SELECT description FROM transactions WHERE id = $TRANSACTION_ID"),
            )
            assertEquals(
                CATEGORY_ID,
                scalar(targetSql, "SELECT categoryId FROM transactions WHERE id = $TRANSACTION_ID"),
            )
            assertEquals(
                "legacy-coffee",
                text(targetSql, "SELECT patternKey FROM remembered_category_rules"),
            )
            assertEquals(
                CATEGORY_ID,
                scalar(targetSql, "SELECT categoryId FROM remembered_category_rules"),
            )
            assertEquals(
                "USER_QUICK_CAPTURE",
                text(targetSql, "SELECT sourceKind FROM capture_messages WHERE sourceIdentity = 's113-note-source'"),
            )
            assertEquals(
                1L,
                scalar(
                    targetSql,
                    "SELECT COUNT(*) FROM capture_messages WHERE sourceKind = " +
                        "'LEGACY_LEDGER_DESCRIPTION' AND originRef = 'LEDGER_RECORD:$TRANSACTION_ID' " +
                        "AND bodyText = 'Legacy breakfast' AND capturedAtEpochMillis IS NULL " +
                        "AND captureTimezone IS NULL",
                ),
            )
            assertEquals(
                1L,
                scalar(
                    targetSql,
                    "SELECT COUNT(*) FROM context_links WHERE eventId = $eventId " +
                        "AND draftId = $DRAFT_ID AND state = 'ACCEPTED' AND activeEventId = $eventId",
                ),
            )
            assertEquals(
                TRANSACTION_ID,
                scalar(targetSql, "SELECT resolvedTransactionId FROM note_events WHERE id = $eventId"),
            )
            assertEquals(
                "NONE",
                text(targetSql, "SELECT reviewCategoryOrigin FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
            )
            assertEquals(
                CATEGORY_ID,
                scalar(targetSql, "SELECT reviewCategoryId FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
            )
            assertEquals(
                "PENDING",
                text(targetSql, "SELECT reviewStatus FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
            )
            assertEquals(
                "User selected coffee",
                text(targetSql, "SELECT reviewMemo FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
            )
            assertEquals(
                5L,
                scalar(targetSql, "SELECT reviewRevision FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
            )
            assertEquals(
                ARCHIVED_CATEGORY_ID,
                scalar(targetSql, "SELECT suggestedCategoryId FROM classification_snapshots WHERE draftId = $DRAFT_ID"),
            )
            assertEquals(
                "historical-only",
                text(targetSql, "SELECT staleReason FROM classification_snapshots WHERE draftId = $DRAFT_ID"),
            )
            assertEquals(2, target.reviewQueueDao().findOrderRow(DRAFT_ID)?.queueRank)
            assertEquals(
                0L,
                scalar(
                    targetSql,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'classification_work'",
                ),
            )
            targetSql.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse("restored fixture must satisfy foreign keys", cursor.moveToFirst())
            }

            val previousState = restorer.readRecoveryCopy(result.previousState)
            val previousPreview = restorer.preview(previousState)
            assertEquals(1, previousPreview.categoryCount)
            assertEquals(1, previousPreview.transactionCount)
            restorer.restore(previousState)
            val recoveredSql = target.openHelper.writableDatabase
            assertEquals(
                "Pre-restore category",
                text(recoveredSql, "SELECT name FROM categories"),
            )
            assertEquals(
                "Pre-restore record",
                text(recoveredSql, "SELECT description FROM transactions"),
            )
            assertEquals(111L, scalar(recoveredSql, "SELECT amountVnd FROM transactions"))
        } finally {
            migrated?.close()
            restored?.close()
            recoveryDirectory.deleteRecursively()
            context.deleteDatabase(SOURCE_DATABASE_NAME)
            context.deleteDatabase(TARGET_DATABASE_NAME)
        }
    }

    private fun buildReleaseDatabase(context: Context, name: String): LedgerDatabase =
        Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, name)
            .addMigrations(
                LedgerDatabase.MIGRATION_1_2,
                LedgerDatabase.MIGRATION_2_3,
                LedgerDatabase.MIGRATION_3_4,
                LedgerDatabase.MIGRATION_4_5,
                LedgerDatabase.MIGRATION_5_6,
                LedgerDatabase.MIGRATION_6_7,
                LedgerDatabase.MIGRATION_7_8,
                LedgerDatabase.MIGRATION_8_9,
                LedgerDatabase.MIGRATION_9_10,
                LedgerDatabase.MIGRATION_10_11,
                LedgerDatabase.MIGRATION_11_12,
                LedgerDatabase.MIGRATION_12_13,
                LedgerDatabase.MIGRATION_13_14,
                LedgerDatabase.MIGRATION_14_15,
                LedgerDatabase.MIGRATION_15_16,
                LedgerDatabase.MIGRATION_16_17,
                LedgerDatabase.MIGRATION_17_18,
            )
            .build()

    private fun writeAcceptedVersionSixDatabase(context: Context) {
        val file = context.getDatabasePath(SOURCE_DATABASE_NAME)
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val exported = JSONObject(
                InstrumentationRegistry.getInstrumentation().context.assets
                    .open("$SCHEMA_ASSET_DIRECTORY/6.json")
                    .bufferedReader()
                    .use { it.readText() },
            ).getJSONObject("database")
            val setup = exported.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) {
                database.execSQL(setup.getString(index))
            }
            val entities = exported.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (indexInEntity in 0 until indices.length()) {
                    database.execSQL(
                        indices.getJSONObject(indexInEntity).getString("createSql")
                            .replace("\${TABLE_NAME}", table),
                    )
                }
            }
            database.execSQL(
                "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                    "`updatedAtEpochMillis`) VALUES ($CATEGORY_ID, 'Food', 0, $FIXTURE_MOMENT, $FIXTURE_MOMENT)",
            )
            database.execSQL(
                "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                    "`updatedAtEpochMillis`) VALUES ($ARCHIVED_CATEGORY_ID, 'Archived', 1, " +
                    "$FIXTURE_MOMENT, $FIXTURE_MOMENT)",
            )
            database.execSQL(
                "INSERT INTO `transactions` (`id`, `type`, `amountVnd`, `occurredAtEpochMillis`, " +
                    "`description`, `categoryId`, `confirmation`, `source`, " +
                    "`createdAtEpochMillis`, `updatedAtEpochMillis`) VALUES " +
                    "($TRANSACTION_ID, 'EXPENSE', $LEDGER_AMOUNT, $FIXTURE_MOMENT, " +
                    "'Legacy breakfast', $CATEGORY_ID, 'CONFIRMED', 'MANUAL', " +
                    "$FIXTURE_MOMENT, $FIXTURE_MOMENT)",
            )
            val notificationKey = "accepted-v6-notification"
            val deliveryId = draftDeliveryId(
                "com.example.bank",
                notificationKey,
                FIXTURE_MOMENT,
            )
            val evidenceDigest = draftEvidenceDigest(
                bank = "VIETINBANK",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = 87_000L,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-10-15",
                statedTime = "12:00",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = "Legacy coffee memo",
                memoUnresolvedReason = null,
            )
            database.execSQL(
                "INSERT INTO `pending_bank_drafts` (`id`, `deliveryId`, `sourcePackage`, " +
                    "`notificationKey`, `postedAtEpochMillis`, `bank`, `parseStatus`, " +
                    "`amountState`, `amountVnd`, `amountUnresolvedReason`, `direction`, " +
                    "`momentState`, `statedDate`, `statedTime`, `momentUnresolvedReason`, " +
                    "`memoState`, `memo`, `memoUnresolvedReason`, `evidenceDigest`, `reviewStatus`, " +
                    "`confirmedTransactionId`, `possibleDuplicateOfDraftId`, `createdAtEpochMillis`, " +
                    "`reviewAmountText`, `reviewDateText`, `reviewTimeText`, `reviewMemo`, " +
                    "`reviewMemoEdited`, `reviewType`, `reviewCategoryId`, `reviewCategoryResolved`, " +
                    "`reviewRevision`) VALUES ($DRAFT_ID, '$deliveryId', 'com.example.bank', " +
                    "'$notificationKey', $FIXTURE_MOMENT, 'VIETINBANK', 'PARSED', 'PRESENT', " +
                    "87000, NULL, 'DEBIT', 'PRESENT', '2026-10-15', '12:00', NULL, 'PRESENT', " +
                    "'Legacy coffee memo', NULL, '$evidenceDigest', 'PENDING', NULL, NULL, " +
                    "$FIXTURE_MOMENT, '87000', NULL, NULL, 'User selected coffee', 1, 'EXPENSE', " +
                    "$CATEGORY_ID, 1, 5)",
            )
            database.execSQL(
                "INSERT INTO `remembered_category_rules` (`patternKey`, `categoryId`) " +
                    "VALUES ('legacy-coffee', $CATEGORY_ID)",
            )
            database.execSQL("PRAGMA user_version = 6")
        } finally {
            database.close()
        }
    }

    private fun scalar(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
        database.query(sql).use { cursor ->
            assertTrue("query returned no row: $sql", cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun text(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        database.query(sql).use { cursor ->
            assertTrue("query returned no row: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun ids(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        column: String,
    ): Set<Long> = database.query("SELECT `$column` FROM `$table` ORDER BY `$column`").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getLong(0))
        }
    }

    private object EmptyProfileTransfer : HostedProfileTransfer {
        override fun exportSnapshot(): HostedProviderExportSnapshot = EMPTY_PROFILES

        override fun importSnapshot(snapshot: HostedProviderExportSnapshot) =
            HostedProfileImportResult(
                imported = 0,
                withoutCredential = 0,
                activeProfileId = null,
            )

        override fun revertImport(
            previous: HostedProviderExportSnapshot,
            imported: HostedProviderExportSnapshot,
        ) = Unit
    }

    private companion object {
        const val ISOLATED_PACKAGE = "io.github.triet4p.aegisf.s111evidence"
        const val SCHEMA_ASSET_DIRECTORY = "io.github.triet4p.aegisf.data.LedgerDatabase"
        const val SOURCE_DATABASE_NAME = "s113-migration-source.db"
        const val V6_IDENTITY_HASH = "ff97e68ec56591d684c3e5fd371fe854"
        const val TARGET_DATABASE_NAME = "s113-restore-target.db"
        const val RECOVERY_DIRECTORY_NAME = "s113-restore-recovery"
        const val CATEGORY_ID = 41L
        const val ARCHIVED_CATEGORY_ID = 42L
        const val TRANSACTION_ID = 401L
        const val DRAFT_ID = 501L
        const val LEDGER_AMOUNT = 9_876_543_210_123L
        const val FIXTURE_MOMENT = 1_792_040_400_000L
        const val ORIGINAL_TIME = 1_700_000_000_000L
        val EMPTY_PROFILES = HostedProviderExportSnapshot(activeProfileId = null, profiles = emptyList())
    }
}
