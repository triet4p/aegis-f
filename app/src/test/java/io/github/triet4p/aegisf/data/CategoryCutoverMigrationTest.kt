package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
                assertEquals(1L, cursor.getLong(1))
                assertEquals("MODEL", cursor.getString(2))
                assertEquals(0.75, cursor.getDouble(3), 0.0)
                assertEquals(1_700_000_000_000L, cursor.getLong(4))
            }

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

    private fun writeVersion15Database() {
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
                    "VALUES (7, 'ctx', 'cat', 'rule', 'model', 'laya-candidates-v1', 'MODEL', 1, " +
                    "'[1]', '[0.75]', 0.75, 'digest', 'SOURCE_TEXT', 'identity', '[]', " +
                    "1700000000000)",
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
}
