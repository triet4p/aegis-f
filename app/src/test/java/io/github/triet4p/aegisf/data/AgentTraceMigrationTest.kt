package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.agent.AgentRunHistory
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The version 13 to 14 upgrade: the local production trace over a real, populated history.
 *
 * The fixture is a genuine version-13 database built from the schema the app exported for
 * version 13, holding the rows the app really keeps — a run, its attempt, and the user-visible
 * conversation — so the upgrade is observed against real content. Version 13 never captured a
 * provider body, tool exchange or clarification, so the upgrade must not invent one: the test
 * asserts one explicit `EVIDENCE_GAP` per stored run and per stored attempt, the older rows and
 * identities intact, a usable trace table afterwards, and a cascading delete.
 *
 * The second test injects a conflicting trace table and asserts the whole upgrade rolls back with
 * the version-13 rows intact, so a schema problem can never delete the user's history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentTraceMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    @Test
    fun `populated version thirteen migration preserves rows and records explicit historical gaps`() =
        runTest {
            writeVersionThirteenDatabase()
            val database = LedgerDatabase.build(context)
            try {
                val room = database.openHelper.writableDatabase
                assertEquals(LedgerDatabase.VERSION.toLong(), scalar(room, "PRAGMA user_version"))
                assertEquals(
                    exportedIdentityHash(LedgerDatabase.VERSION),
                    text(room, "SELECT identity_hash FROM room_master_table WHERE id = 42"),
                )
                // The history rows survive unchanged.
                assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM agent_runs"))
                assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM agent_run_attempts"))
                assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM agent_conversation"))
                assertEquals(
                    RUN_ID,
                    text(room, "SELECT runId FROM agent_runs WHERE id = 1"),
                )
                assertEquals(
                    "List the synthetic drafts.",
                    text(room, "SELECT requestText FROM agent_runs WHERE id = 1"),
                )
                assertEquals(
                    ATTEMPT_KEY,
                    text(room, "SELECT idempotencyKey FROM agent_run_attempts WHERE id = 1"),
                )

                // The trace starts with explicit gaps only: no fabricated request, response or
                // tool evidence for a run that never captured any.
                assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM agent_trace_events"))
                val events = database.agentTraceDao().findForRun(RUN_ID)
                assertEquals(2, events.size)
                assertEquals(
                    listOf(AGENT_TRACE_RUN_SCOPE_ATTEMPT, 1),
                    events.map { it.attempt },
                )
                events.forEach { event ->
                    assertEquals(AGENT_TRACE_KIND_EVIDENCE_GAP, event.kind)
                    assertEquals(AGENT_TRACE_COMPLETION_UNAVAILABLE, event.completion)
                    val payload = JSONObject(event.payloadJson)
                    assertFalse("a gap must not carry a body", payload.has("body"))
                    assertTrue(payload.getString("reason").startsWith("PRE_TRACE_"))
                }
                assertEquals("PRE_TRACE_HISTORY", JSONObject(events[0].payloadJson).getString("reason"))
                assertEquals("PRE_TRACE_ATTEMPT", JSONObject(events[1].payloadJson).getString("reason"))

                // The upgraded trace store is usable, ordered by its own event identity, and the
                // history exposes it the same way the running app does.
                val history = AgentRunHistory(database)
                AgentTraceStore(database.agentTraceDao()).append(
                    runId = RUN_ID,
                    scopeId = SCOPE_ID,
                    attempt = 1,
                    kind = AGENT_TRACE_KIND_PROVIDER_REQUEST,
                    payloadJson = JSONObject().put("body", "{\"probe\":true}").toString(),
                    direction = AGENT_TRACE_DIRECTION_OUTBOUND,
                    completion = AGENT_TRACE_COMPLETION_COMPLETE,
                    turn = 1,
                )
                val ordered = history.traceForRun(RUN_ID)
                assertEquals(3, ordered.size)
                assertEquals(AGENT_TRACE_KIND_PROVIDER_REQUEST, ordered.last().kind)
                assertTrue(
                    "event identities must be ordered",
                    ordered[0].id < ordered[1].id && ordered[1].id < ordered[2].id,
                )

                // Deleting the run (retention or an explicit history delete) cascades its trace.
                assertEquals(1, database.agentRunDao().deleteScope(SCOPE_ID))
                assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM agent_trace_events"))
                room.query(SimpleSQLiteQuery("PRAGMA foreign_key_check")).use { cursor ->
                    assertFalse("the migration left a foreign-key violation", cursor.moveToFirst())
                }
            } finally {
                database.close()
            }
        }

    @Test
    fun `failed version thirteen migration rolls back and preserves the stored history`() = runTest {
        writeVersionThirteenDatabase(conflictingTraceTable = true)
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
            assertEquals(13L, scalar(legacy, "PRAGMA user_version"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM agent_runs"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM agent_run_attempts"))
            assertEquals(1L, scalar(legacy, "SELECT COUNT(*) FROM agent_conversation"))
            assertEquals(
                RUN_ID,
                text(legacy, "SELECT runId FROM agent_runs WHERE id = 1"),
            )
            legacy.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertFalse("the failed migration left a foreign-key violation", cursor.moveToFirst())
            }
        } finally {
            legacy.close()
        }
    }

    private fun writeVersionThirteenDatabase(conflictingTraceTable: Boolean = false) {
        val file = context.getDatabasePath(LedgerDatabase.NAME)
        file.parentFile?.mkdirs()
        file.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            VERSION_THIRTEEN_SCHEMA.forEach { database.execSQL(it) }
            if (conflictingTraceTable) {
                database.execSQL(
                    "CREATE TABLE `agent_trace_events` (`id` INTEGER PRIMARY KEY, `note` TEXT)",
                )
            }
            database.execSQL(
                "INSERT INTO `agent_runs` (`id`, `runId`, `scopeId`, `intentNames`, " +
                    "`requestFingerprint`, `attempt`, `state`, `stopReason`, `maxOutputTokens`, " +
                    "`answerDigest`, `requestText`, `answerText`, `revisionEpochMillis`, " +
                    "`createdAtEpochMillis`, `updatedAtEpochMillis`) VALUES " +
                    "(1, '$RUN_ID', '$SCOPE_ID', 'SEARCH_DRAFTS', 'fingerprint-1', 1, 'FAILED', " +
                    "'REQUIRED_EFFECT_MISSING', 8192, NULL, 'List the synthetic drafts.', NULL, " +
                    "$POSTED_AT, $POSTED_AT, $POSTED_AT)",
            )
            database.execSQL(
                "INSERT INTO `agent_run_attempts` (`id`, `runId`, `attempt`, `idempotencyKey`, " +
                    "`state`, `stopReason`, `effectKind`, `eventId`, `eventRevision`, `linkId`, " +
                    "`linkState`, `linkRevision`, `draftId`, `decisionSource`, `observationJson`, " +
                    "`ownerToken`, `createdAtEpochMillis`, `updatedAtEpochMillis`) VALUES " +
                    "(1, '$RUN_ID', 1, '$ATTEMPT_KEY', 'UNKNOWN', 'REQUIRED_EFFECT_MISSING', " +
                    "'unknown', NULL, NULL, NULL, NULL, NULL, NULL, NULL, '{\"summary\":\"probe\"}', " +
                    "NULL, $POSTED_AT, $POSTED_AT)",
            )
            database.execSQL(
                "INSERT INTO `agent_conversation` (`id`, `runId`, `sequence`, `role`, `text`, " +
                    "`createdAtEpochMillis`) VALUES " +
                    "(1, '$RUN_ID', 0, 'USER', 'List the synthetic drafts.', $POSTED_AT)",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `room_master_table` " +
                    "(`id` INTEGER PRIMARY KEY, `identity_hash` TEXT)",
            )
            database.execSQL(
                "INSERT OR REPLACE INTO `room_master_table` (`id`, `identity_hash`) " +
                    "VALUES (42, '$VERSION_THIRTEEN_IDENTITY')",
            )
            database.execSQL("PRAGMA user_version = 13")
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

    private fun text(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        database.query(SimpleSQLiteQuery(sql)).use { cursor ->
            check(cursor.moveToFirst()) { "$sql returned no row" }
            cursor.getString(0)
        }

    private fun text(database: SQLiteDatabase, sql: String): String =
        database.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst()) { "$sql returned no row" }
            cursor.getString(0)
        }

    private companion object {
        const val RUN_ID = "run-pre-trace-1"
        const val SCOPE_ID = "agent-pre-trace"
        const val ATTEMPT_KEY = "run-pre-trace-1:attempt-1"
        const val POSTED_AT = 1_797_000_000_000L

        /** The identity hash Room wrote for version 13; resolved from the exported schema. */
        const val VERSION_THIRTEEN_IDENTITY = "ab8170fa9b9ec988459c4b15d8ce61ae"

        fun schemaFile(version: Int): java.io.File = listOf(
            java.io.File(
                System.getProperty("user.dir"),
                "app/schemas/io.github.triet4p.aegisf.data.LedgerDatabase/$version.json",
            ),
            java.io.File(
                System.getProperty("user.dir"),
                "schemas/io.github.triet4p.aegisf.data.LedgerDatabase/$version.json",
            ),
        ).first { it.isFile }

        val VERSION_THIRTEEN_SCHEMA: List<String> by lazy {
            val exported = JSONObject(schemaFile(13).readText())
            val appDatabase = exported.getJSONObject("database")
            buildList {
                appDatabase.optJSONArray("setupQueries")?.let { setup ->
                    for (i in 0 until setup.length()) add(setup.getString(i))
                }
                val entities = appDatabase.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    add(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) {
                            add(
                                indices.getJSONObject(j).getString("createSql")
                                    .replace("$" + "{TABLE_NAME}", table),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The current schema's identity hash, read from the exported schema file the build writes.
 *
 * Pinning it in this test would make every unrelated schema change a false failure; reading the
 * current file proves the migration wrote the hash Room expects for the version the app declares.
 */
private fun exportedIdentityHash(version: Int): String = JSONObject(
    listOf(
        java.io.File(
            System.getProperty("user.dir"),
            "app/schemas/io.github.triet4p.aegisf.data.LedgerDatabase/$version.json",
        ),
        java.io.File(
            System.getProperty("user.dir"),
            "schemas/io.github.triet4p.aegisf.data.LedgerDatabase/$version.json",
        ),
    ).first { it.isFile }.readText(),
).getJSONObject("database").getString("identityHash")
