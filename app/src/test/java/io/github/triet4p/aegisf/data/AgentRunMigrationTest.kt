package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.agent.WorkflowIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The version 12 to 13 upgrade of a populated database: the durable S8.10 agent run
 * history over real user content.
 *
 * The fixture holds the rows the app really keeps — categories, ledger records, bank
 * drafts, captures, an event reading, a link, a stored prediction, and command
 * records — so the upgrade is observed against real content and the exact confirmed
 * total, not against an empty database. The three new tables start empty (version 12
 * kept no agent run state); every older row, identity, and confirmed total survives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentRunMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    @Test
    fun `populated version twelve migration preserves every stored row and the exact total`() = runTest {
        writeVersionTwelveDatabase()
        val database = LedgerDatabase.build(context)
        try {
            val room = database.openHelper.writableDatabase
            assertEquals(LedgerDatabase.VERSION.toLong(), scalar(room, "PRAGMA user_version"))
            assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM categories"))
            assertEquals(4L, scalar(room, "SELECT COUNT(*) FROM transactions"))
            assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM pending_bank_drafts"))
            assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM capture_messages"))
            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM note_events"))
            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM context_links"))
            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM classification_snapshots"))
            assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM workflow_operations"))
            assertEquals(
                "the obsolete local queue table is removed",
                0L,
                scalar(
                    room,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'classification_work'",
                ),
            )
            assertEquals(
                80_000L,
                scalar(
                    room,
                    "SELECT SUM(amountVnd) FROM transactions " +
                        "WHERE type = 'EXPENSE' AND confirmation = 'CONFIRMED'",
                ),
            )
            // The new S8.10 tables exist and start empty: version 12 kept no run state.
            assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM agent_runs"))
            assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM agent_run_attempts"))
            assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM agent_conversation"))
            assertEquals(
                1L,
                scalar(
                    room,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_agent_runs_requestFingerprint_scopeId'",
                ),
            )
            assertEquals(
                1L,
                scalar(
                    room,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_agent_run_attempts_idempotencyKey'",
                ),
            )
            // The history is usable in the upgraded file: admit, claim, settle, export.
            val runs = database.agentRunDao()
            val attempts = database.agentRunAttemptDao()
            val conversation = database.agentConversationDao()
            val runId = "run-migrated-1"
            runs.insert(
                AgentRunEntity(
                    runId = runId,
                    scopeId = "agent-1",
                    intentNames = WorkflowIntent.SEARCH_DRAFTS.name,
                    requestFingerprint = "fingerprint-1",
                    attempt = 1,
                    state = AGENT_RUN_STATE_PENDING,
                    stopReason = null,
                    maxOutputTokens = 8192,
                    answerDigest = null,
                    requestText = "Find the pending drafts.",
                    answerText = null,
                    revisionEpochMillis = POSTED_AT,
                    createdAtEpochMillis = POSTED_AT,
                    updatedAtEpochMillis = POSTED_AT,
                ),
            )
            assertEquals(1, runs.claimPending(runId, 1, POSTED_AT + 1L))
            attempts.insert(
                AgentRunAttemptEntity(
                    runId = runId,
                    attempt = 1,
                    idempotencyKey = "$runId:attempt-1",
                    state = AGENT_RUN_ATTEMPT_PENDING,
                    stopReason = null,
                    effectKind = "query",
                    eventId = null,
                    eventRevision = null,
                    linkId = null,
                    linkState = null,
                    linkRevision = null,
                    draftId = PENDING_DRAFT_ID,
                    decisionSource = null,
                    observationJson = "{}",
                    ownerToken = "owner-1",
                    createdAtEpochMillis = POSTED_AT,
                    updatedAtEpochMillis = POSTED_AT,
                ),
            )
            conversation.insert(
                AgentConversationEntity(
                    runId = runId,
                    sequence = 0,
                    role = AGENT_CONVERSATION_ROLE_USER,
                    text = "Find the pending drafts.",
                    createdAtEpochMillis = POSTED_AT,
                ),
            )
            assertEquals(runId, conversation.findForRun(runId).single().runId)
            room.query(SimpleSQLiteQuery("PRAGMA foreign_key_check")).use { cursor ->
                assertFalse("the migration left a foreign-key violation", cursor.moveToFirst())
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `failed version twelve migration rolls back and preserves the populated rows`() = runTest {
        writeVersionTwelveDatabase(conflictingAgentRuns = true)
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
            assertEquals(12L, scalar(legacy, "PRAGMA user_version"))
            assertEquals(2L, scalar(legacy, "SELECT COUNT(*) FROM categories"))
            assertEquals(4L, scalar(legacy, "SELECT COUNT(*) FROM transactions"))
            assertEquals(2L, scalar(legacy, "SELECT COUNT(*) FROM pending_bank_drafts"))
            assertEquals(
                80_000L,
                scalar(
                    legacy,
                    "SELECT SUM(amountVnd) FROM transactions " +
                        "WHERE type = 'EXPENSE' AND confirmation = 'CONFIRMED'",
                ),
            )
            assertEquals(
                0L,
                scalar(
                    legacy,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_agent_runs_requestFingerprint_scopeId'",
                ),
            )
            legacy.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertFalse("the failed migration left a foreign-key violation", cursor.moveToFirst())
            }
        } finally {
            legacy.close()
        }
    }

    private fun writeVersionTwelveDatabase(conflictingAgentRuns: Boolean = false) {
        val file = context.getDatabasePath(LedgerDatabase.NAME)
        file.parentFile?.mkdirs()
        file.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            VERSION_TWELVE_SCHEMA.forEach { database.execSQL(it) }
            if (conflictingAgentRuns) {
                database.execSQL("CREATE TABLE `agent_runs` (`id` INTEGER PRIMARY KEY, `note` TEXT)")
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
                    "`updatedAtEpochMillis`, `revision`) VALUES " +
                    "($MANUAL_EXPENSE_ID, 'EXPENSE', 50000, $POSTED_AT, 'Manual description', " +
                    "$FOOD_CATEGORY_ID, 'CONFIRMED', 'MANUAL', $POSTED_AT, $POSTED_AT, 0), " +
                    "($BANK_INCOME_ID, 'INCOME', 1000000, $POSTED_AT, 'Bank description', " +
                    "$FOOD_CATEGORY_ID, 'CONFIRMED', 'BANK_NOTIFICATION', $POSTED_AT, $POSTED_AT, 0), " +
                    "($TRANSFER_ID, 'TRANSFER', 25000, $POSTED_AT, 'Transfer description', NULL, " +
                    "'CONFIRMED', 'MANUAL', $POSTED_AT, $POSTED_AT, 0), " +
                    "($ARCHIVED_EXPENSE_ID, 'EXPENSE', 30000, $POSTED_AT, 'Archived description', " +
                    "$ARCHIVED_CATEGORY_ID, 'CONFIRMED', 'MANUAL', $POSTED_AT, $POSTED_AT, 0)",
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
                    "'digest-pending', 'PENDING', NULL, NULL, $POSTED_AT, NULL, NULL, " +
                    "NULL, NULL, 0, NULL, NULL, 0, 0), " +
                    "($CONFIRMED_DRAFT_ID, 'delivery-confirmed', 'com.example.bank', 'confirmed-key', " +
                    "$POSTED_AT, 'VIETINBANK', 'PARSED', 'PRESENT', 1000000, NULL, 'CREDIT', 'PRESENT', " +
                    "'2026-10-16', '07:38', NULL, 'PRESENT', 'Confirmed memo', NULL, " +
                    "'digest-confirmed', 'CONFIRMED', $BANK_INCOME_ID, NULL, $POSTED_AT, NULL, NULL, " +
                    "NULL, NULL, 0, NULL, NULL, 0, 0)",
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
                    "`sourceRevision`, `createdAtEpochMillis`, `resolvedTransactionId`, " +
                    "`resolvedAtEpochMillis`) VALUES " +
                    "(1, 2, 'event-identity', 0, 13, 'Fuel 50,000', 50000, 'Petrol', " +
                    "'2026-10-16', '15:00', 'Asia/Bangkok', NULL, NULL, 'extract-v1', NULL, " +
                    "'INTERPRETED', 0, 0, $POSTED_AT, NULL, NULL)",
            )
            database.execSQL(
                "INSERT INTO `context_links` (`id`, `eventId`, `draftId`, `state`, `evidence`, " +
                    "`revision`, `userOverride`, `activeEventId`, `createdAtEpochMillis`) VALUES " +
                    "(1, 1, $PENDING_DRAFT_ID, 'ACCEPTED', 'same day fuel', 0, 'USER_ACCEPTED', 1, " +
                    "$POSTED_AT)",
            )
            database.execSQL(
                "INSERT INTO `classification_snapshots` (`id`, `draftId`, `contextRevision`, " +
                    "`categoryRevision`, `ruleRevision`, `modelRevision`, `candidateSetVersion`, " +
                    "`decisionSource`, `suggestedCategoryId`, `candidateIds`, `probabilities`, " +
                    "`unknownShare`, `confidence`, `effectiveInputDigest`, `eventIds`, " +
                    "`predictionTime`, `staleReason`) VALUES " +
                    "(1, $PENDING_DRAFT_ID, 'context-revision', 'category-revision', 'rule-revision', " +
                    "'model-revision', 'candidate-set-version', 'MODEL', $FOOD_CATEGORY_ID, " +
                    "'$FOOD_CATEGORY_ID', '0.75', 0.25, 0.5, 'input-digest', '1', $POSTED_AT, NULL)",
            )
            database.execSQL(
                "INSERT INTO `workflow_operations` (`id`, `idempotencyKey`, `scopeId`, `command`, " +
                    "`payloadDigest`, `outcome`, `changeSummary`, `ownerToken`, " +
                    "`leaseExpiresAtEpochMillis`, `createdAtEpochMillis`) VALUES " +
                    "(1, 'key-1', 'agent-1', 'UPDATE_EVENT', 'digest-1', 'APPLIED', NULL, NULL, NULL, " +
                    "$POSTED_AT), " +
                    "(2, 'key-2', 'agent-1', 'ATTACH_LINK', 'digest-2', 'NO_CHANGE', NULL, NULL, NULL, " +
                    "$POSTED_AT)",
            )
            database.execSQL(
                "INSERT INTO `classification_work` (`id`, `draftId`, `state`, `requestedQuestion`, " +
                    "`executedQuestion`, `attemptCount`, `snapshotId`, `statusReason`, `ownerToken`, " +
                    "`createdAtEpochMillis`, `updatedAtEpochMillis`) VALUES " +
                    "(1, $PENDING_DRAFT_ID, 'RESULT', 'question', 'question', 1, 1, NULL, NULL, " +
                    "$POSTED_AT, $POSTED_AT)",
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `room_master_table` " +
                    "(`id` INTEGER PRIMARY KEY, `identity_hash` TEXT)",
            )
            database.execSQL(
                "INSERT OR REPLACE INTO `room_master_table` (`id`, `identity_hash`) " +
                    "VALUES (42, '$VERSION_TWELVE_IDENTITY')",
            )
            database.execSQL("PRAGMA user_version = 12")
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

        /**
         * The identity hash Room wrote for version 12. Read it from the exported schema
         * under `app/schemas` if this pins stale: the chain test resolves the same file
         * per version at runtime.
         */
        const val VERSION_TWELVE_IDENTITY = "cd3a962f89dbb087a12c1e8d5ee770d5"

        val VERSION_TWELVE_SCHEMA: List<String> by lazy {
            val schemaFile = listOf(
                java.io.File(System.getProperty("user.dir"), "app/schemas/io.github.triet4p.aegisf.data.LedgerDatabase/12.json"),
                java.io.File(System.getProperty("user.dir"), "schemas/io.github.triet4p.aegisf.data.LedgerDatabase/12.json"),
            ).first { it.isFile }
            val exported = org.json.JSONObject(schemaFile.readText())
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
