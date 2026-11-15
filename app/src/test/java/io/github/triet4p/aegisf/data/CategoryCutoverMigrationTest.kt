package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The CAT1 upgrade path from schema 15. The version-15 file is created from Room's own exported
 * schema, populated as a running app would have left it, then opened by the production builder so
 * the real 15 -> 16 migration runs and Room validates the result.
 *
 * The removed on-device classification queue loses only its ephemeral question jobs; stored
 * predictions stay exactly as historical provenance, and the attempt gains the nullable
 * hosted-choice column without inventing a decision for old rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CategoryCutoverMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    @Test
    fun `version 15 keeps history, drops local work, and adds arrival work`() {
        writeVersion15Database()

        val database = LedgerDatabase.build(context)
        try {
            val raw = database.openHelper.writableDatabase
            assertEquals(
                LedgerDatabase.VERSION.toLong(),
                raw.query("PRAGMA user_version").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                },
            )
            assertEquals(
                0,
                raw.query("SELECT count(*) FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'classification_work'").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                },
            )
            assertEquals(
                1,
                raw.query(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                        "AND name = 'arrival_reconciliation_operations'",
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                },
            )

            raw.query(
                "SELECT draftId, suggestedCategoryId, decisionSource, confidence, " +
                    "predictionTime FROM classification_snapshots",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals(ARCHIVED_CATEGORY_ID, cursor.getLong(1))
                assertEquals("MODEL", cursor.getString(2))
                assertEquals(0.75, cursor.getDouble(3), 0.0)
                assertEquals(1_700_000_000_000L, cursor.getLong(4))
            }
            assertMigratedHistory(raw)

            raw.query(
                "SELECT selectedCategoryId, observationJson FROM agent_run_attempts",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNull(cursor.getString(0))
                assertEquals("{\"admitted\":true}", cursor.getString(1))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a failed CAT1 migration leaves version fifteen intact and the same file retries`() {
        writeVersion15Database(injectDuplicateSelectedCategoryId = true)

        var migrationFailure: Exception? = null
        val failedDatabase = LedgerDatabase.build(context)
        try {
            failedDatabase.openHelper.writableDatabase
        } catch (failure: Exception) {
            migrationFailure = failure
        } finally {
            failedDatabase.close()
        }
        assertNotNull("the injected CAT1 defect must fail the upgrade", migrationFailure)
        val failureText = buildString {
            var current: Throwable? = migrationFailure
            while (current != null) {
                append(current.message).append(' ')
                current = current.cause
            }
        }
        assertTrue(
            "the failure must be the duplicate selectedCategoryId injected into v15",
            failureText.contains("duplicate column name", ignoreCase = true),
        )

        val file = context.getDatabasePath(LedgerDatabase.NAME)
        assertTrue("the failed migration must not delete the source database", file.isFile)
        val legacy = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            assertEquals(15L, scalar(legacy, "PRAGMA user_version"))
            assertEquals(2L, scalar(legacy, "SELECT COUNT(*) FROM categories"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM transactions"))
            assertEquals(LEDGER_AMOUNT, scalar(legacy, "SELECT SUM(amountVnd) FROM transactions"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM pending_bank_drafts"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM remembered_category_rules"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM classification_work"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM classification_snapshots"))
            assertEquals(
                1L,
                scalar(
                    legacy,
                    "SELECT COUNT(*) FROM capture_messages WHERE originRef = " +
                        "'LEDGER_RECORD:$TRANSACTION_ID' AND bodyText = 'Legacy description'",
                ),
            )

            // Remove only the injected malformed column; retain the same database and rows for retry.
            repairInjectedSelectedCategoryId(legacy)
            assertEquals(
                0L,
                scalar(
                    legacy,
                    "SELECT COUNT(*) FROM pragma_table_info('agent_run_attempts') " +
                        "WHERE name = 'selectedCategoryId'",
                ),
            )
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM agent_run_attempts"))
        } finally {
            legacy.close()
        }

        val retried = LedgerDatabase.build(context)
        try {
            assertMigratedHistory(retried.openHelper.writableDatabase)
        } finally {
            retried.close()
        }
    }

    private fun assertMigratedHistory(raw: androidx.sqlite.db.SupportSQLiteDatabase) {
        assertEquals(LedgerDatabase.VERSION.toLong(), scalar(raw, "PRAGMA user_version"))
        assertEquals(2L, scalar(raw, "SELECT COUNT(*) FROM categories"))
        assertEquals(1L, scalar(raw, "SELECT COUNT(*) FROM transactions"))
        assertEquals(LEDGER_AMOUNT, scalar(raw, "SELECT SUM(amountVnd) FROM transactions"))
        assertEquals(
            CATEGORY_ID,
            scalar(raw, "SELECT categoryId FROM transactions WHERE id = $TRANSACTION_ID"),
        )
        assertEquals(
            "PENDING",
            text(raw, "SELECT reviewStatus FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
        )
        assertEquals(
            CATEGORY_ID,
            scalar(raw, "SELECT reviewCategoryId FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
        )
        assertEquals(
            7L,
            scalar(raw, "SELECT reviewRevision FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
        )
        assertEquals(
            "NONE",
            text(raw, "SELECT reviewCategoryOrigin FROM pending_bank_drafts WHERE id = $DRAFT_ID"),
        )
        assertEquals(
            "legacy-food",
            text(raw, "SELECT patternKey FROM remembered_category_rules"),
        )
        assertEquals(CATEGORY_ID, scalar(raw, "SELECT categoryId FROM remembered_category_rules"))
        assertEquals(
            1L,
            scalar(
                raw,
                "SELECT COUNT(*) FROM capture_messages WHERE sourceKind = " +
                    "'LEGACY_LEDGER_DESCRIPTION' AND originRef = 'LEDGER_RECORD:$TRANSACTION_ID' " +
                    "AND bodyText = 'Legacy description' AND capturedAtEpochMillis IS NULL " +
                    "AND captureTimezone IS NULL",
            ),
        )
        assertEquals(
            ARCHIVED_CATEGORY_ID,
            scalar(raw, "SELECT suggestedCategoryId FROM classification_snapshots WHERE draftId = $DRAFT_ID"),
        )
        assertEquals(
            0L,
            scalar(
                raw,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'classification_work'",
            ),
        )
    }

    private fun scalar(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
        database.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun scalar(database: SQLiteDatabase, sql: String): Long =
        database.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun text(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        database.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun repairInjectedSelectedCategoryId(database: SQLiteDatabase) {
        val schema = JSONObject(
            javaClass.classLoader!!.getResourceAsStream("schema/15.json")!!
                .bufferedReader()
                .use { it.readText() },
        ).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val entity = (0 until entities.length())
            .map(entities::getJSONObject)
            .single { it.getString("tableName") == "agent_run_attempts" }
        val tableName = entity.getString("tableName")
        val repairTable = "${tableName}_s113_repair"
        val fields = entity.getJSONArray("fields")
        val columns = (0 until fields.length())
            .map { index -> "`${fields.getJSONObject(index).getString("columnName")}`" }
            .joinToString(", ")
        database.execSQL(
            entity.getString("createSql").replace("\${TABLE_NAME}", repairTable),
        )
        database.execSQL(
            "INSERT INTO `$repairTable` ($columns) SELECT $columns FROM `$tableName`",
        )
        database.execSQL("DROP TABLE `$tableName`")
        database.execSQL("ALTER TABLE `$repairTable` RENAME TO `$tableName`")
        val indices = entity.optJSONArray("indices") ?: return
        for (index in 0 until indices.length()) {
            database.execSQL(
                indices.getJSONObject(index).getString("createSql")
                    .replace("\${TABLE_NAME}", tableName),
            )
        }
    }

    private fun writeVersion15Database(injectDuplicateSelectedCategoryId: Boolean = false) {
        val file = context.getDatabasePath(LedgerDatabase.NAME)
        file.parentFile?.mkdirs()
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val schema = JSONObject(
                javaClass.classLoader!!.getResourceAsStream("schema/15.json")!!
                    .bufferedReader()
                    .use { it.readText() },
            ).getJSONObject("database")
            repeat(schema.getJSONArray("setupQueries").length()) { index ->
                db.execSQL(schema.getJSONArray("setupQueries").getString(index))
            }
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: return@repeat
                repeat(indices.length()) { typeIndex ->
                    val index = indices.getJSONObject(typeIndex)
                    db.execSQL(index.getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            if (injectDuplicateSelectedCategoryId) {
                db.execSQL(
                    "ALTER TABLE `agent_run_attempts` ADD COLUMN `selectedCategoryId` INTEGER",
                )
            }
            db.execSQL(
                "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                    "`updatedAtEpochMillis`) VALUES ($CATEGORY_ID, 'Food', 0, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO `categories` (`id`, `name`, `archived`, `createdAtEpochMillis`, " +
                    "`updatedAtEpochMillis`) VALUES ($ARCHIVED_CATEGORY_ID, 'Archived', 1, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO `transactions` (`id`, `type`, `amountVnd`, `occurredAtEpochMillis`, " +
                    "`description`, `categoryId`, `confirmation`, `source`, `createdAtEpochMillis`, " +
                    "`updatedAtEpochMillis`, `revision`) VALUES " +
                    "($TRANSACTION_ID, 'EXPENSE', $LEDGER_AMOUNT, 1, 'Legacy description', " +
                    "$CATEGORY_ID, 'CONFIRMED', 'MANUAL', 1, 1, 4)",
            )
            db.execSQL(
                "INSERT INTO `pending_bank_drafts` (`id`, `deliveryId`, `sourcePackage`, " +
                    "`notificationKey`, `postedAtEpochMillis`, `bank`, `parseStatus`, `amountState`, " +
                    "`amountVnd`, `amountUnresolvedReason`, `direction`, `momentState`, `statedDate`, " +
                    "`statedTime`, `momentUnresolvedReason`, `memoState`, `memo`, `memoUnresolvedReason`, " +
                    "`evidenceDigest`, `reviewStatus`, `confirmedTransactionId`, " +
                    "`possibleDuplicateOfDraftId`, `createdAtEpochMillis`, `reviewAmountText`, " +
                    "`reviewDateText`, `reviewTimeText`, `reviewMemo`, `reviewMemoEdited`, `reviewType`, " +
                    "`reviewCategoryId`, `reviewCategoryResolved`, `reviewRevision`) VALUES " +
                    "($DRAFT_ID, 'cat1-delivery', 'com.example.bank', NULL, 1, 'VIETINBANK', " +
                    "'PARSED', 'PRESENT', 87000, NULL, 'DEBIT', 'PRESENT', '2023-11-14', '10:00', " +
                    "NULL, 'PRESENT', 'Legacy memo', NULL, 'cat1-digest', 'PENDING', NULL, NULL, 1, " +
                    "'87000', '2023-11-14', '10:00', 'User corrected meal', 1, 'EXPENSE', " +
                    "$CATEGORY_ID, 1, 7)",
            )
            db.execSQL(
                "INSERT INTO `remembered_category_rules` (`patternKey`, `categoryId`) " +
                    "VALUES ('legacy-food', $CATEGORY_ID)",
            )
            db.execSQL(
                "INSERT INTO `capture_messages` (`captureKind`, `sourceKind`, `sourceIdentity`, " +
                    "`bodyText`, `capturedAtEpochMillis`, `captureTimezone`, `revision`, `originRef`, " +
                    "`tombstone`) VALUES ('MANUAL_ANNOTATION', 'LEGACY_LEDGER_DESCRIPTION', " +
                    "'LEDGER_RECORD:$TRANSACTION_ID', 'Legacy description', NULL, NULL, 0, " +
                    "'LEDGER_RECORD:$TRANSACTION_ID', 0)",
            )

            db.execSQL(
                "INSERT INTO `classification_work` (`draftId`, `state`, `requestedQuestion`, " +
                    "`attemptCount`, `createdAtEpochMillis`, `updatedAtEpochMillis`) " +
                    "VALUES (7, 'QUEUED', 'Foods', 1, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO `classification_snapshots` (`draftId`, `contextRevision`, " +
                    "`categoryRevision`, `ruleRevision`, `modelRevision`, `candidateSetVersion`, " +
                    "`decisionSource`, `suggestedCategoryId`, `candidateIds`, `probabilities`, " +
                    "`confidence`, `effectiveInputDigest`, `classificationInputKind`, " +
                    "`classificationInputIdentity`, `eventIds`, `predictionTime`) " +
                    "VALUES ($DRAFT_ID, 'ctx', 'cat', 'rule', 'model', 'laya-candidates-v1', " +
                    "'MODEL', $ARCHIVED_CATEGORY_ID, '[$ARCHIVED_CATEGORY_ID]', '[0.75]', " +
                    "0.75, 'digest', 'SOURCE_TEXT', 'identity', '[]', 1700000000000)",
            )
            db.execSQL(
                "INSERT INTO `agent_runs` (`runId`, `scopeId`, `intentNames`, `requestFingerprint`, " +
                    "`attempt`, `state`, `requestText`, `createdAtEpochMillis`, `updatedAtEpochMillis`) " +
                    "VALUES ('run-legacy', 'legacy-scope', '[]', 'legacy-fingerprint', 1, " +
                    "'SETTLED', 'legacy run', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO `agent_run_attempts` (`runId`, `attempt`, `idempotencyKey`, " +
                    "`state`, `observationJson`, `createdAtEpochMillis`, " +
                    "`updatedAtEpochMillis`) VALUES ('run-legacy', 1, 'key', 'SETTLED', " +
                    "'{\"admitted\":true}', 1, 1)",
            )
            db.execSQL("PRAGMA user_version = 15")
        } finally {
            db.close()
        }
    }
    private companion object {
        const val CATEGORY_ID = 1L
        const val ARCHIVED_CATEGORY_ID = 2L
        const val TRANSACTION_ID = 41L
        const val DRAFT_ID = 7L
        const val LEDGER_AMOUNT = 9_876_543_210_123L
    }

}
