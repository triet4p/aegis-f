package io.github.triet4p.aegisf.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.provider.HostedProfileImportResult
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedProviderCapabilities
import io.github.triet4p.aegisf.provider.HostedProviderExportProfile
import io.github.triet4p.aegisf.provider.HostedProviderExportSnapshot
import io.github.triet4p.aegisf.provider.HostedReasoningEffort
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompleteStateRestorerTest {
    private lateinit var source: LedgerDatabase
    private lateinit var target: LedgerDatabase
    private lateinit var recoveryDirectory: File
    private var epoch = 0L
    private lateinit var transfer: FakeProfileTransfer

    @Before
    fun setUp() {
        source = newDatabase()
        target = newDatabase()
        epoch = 0L
        transfer = FakeProfileTransfer(previousProfileSnapshot())
        recoveryDirectory = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "restore-recovery-${System.nanoTime()}",
        )
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
        recoveryDirectory.deleteRecursively()
    }

    @Test
    fun `restore replaces populated state exactly and keeps historical snapshots inert`() = runBlocking {
        val fixture = populate(source)
        val backup = export(source)

        val originalCategoryId = insert(target, "Original category", now = ORIGINAL_TIME)
        target.transactionDao().insert(
            TransactionEntity(
                type = TransactionType.INCOME,
                amountVnd = 111L,
                occurredAtEpochMillis = ORIGINAL_TIME,
                description = "Original record",
                categoryId = originalCategoryId,
                confirmation = ConfirmationStatus.CONFIRMED,
                source = RecordSource.MANUAL,
                createdAtEpochMillis = ORIGINAL_TIME,
                updatedAtEpochMillis = ORIGINAL_TIME,
            ),
        )
        target.openHelper.writableDatabase.execSQL(
            "INSERT INTO `workflow_operations` (`idempotencyKey`, `scopeId`, `command`, " +
                "`payloadDigest`, `outcome`, `createdAtEpochMillis`) VALUES " +
                "('old-key', 'old-scope', 'old-command', 'digest', 'APPLIED', $ORIGINAL_TIME)",
        )
        target.openHelper.writableDatabase.execSQL(
            "INSERT INTO `arrival_reconciliation_operations` (`operationId`, `triggerKind`, " +
                "`cursorEventId`, `state`, `createdAtEpochMillis`, `linkedAny`, `incompleteAny`) " +
                "VALUES ('old-operation', 'DRAFT_ARRIVAL', 0, 'PENDING', $ORIGINAL_TIME, 0, 0)",
        )
        val restorer = restorer(target)

        val preview = restorer.preview(backup)
        assertEquals(64, preview.digestSha256.length)
        assertEquals(LedgerDatabase.VERSION.toLong(), preview.schemaVersion)
        assertEquals(2, preview.categoryCount)
        assertEquals(1, preview.ruleCount)
        assertEquals(1, preview.transactionCount)
        assertEquals(1, preview.draftCount)
        assertEquals(1, preview.pendingDraftCount)
        assertEquals(1, preview.captureCount)
        assertEquals(1, preview.eventCount)
        assertEquals(1, preview.linkCount)
        assertEquals(1, preview.historicalSnapshotCount)
        assertEquals(1, preview.hostedProfileCount)
        assertEquals(0, preview.reopenedHostedCategoryCount)
        assertEquals(9, preview.restoredRowCount)

        val result = restorer.restore(backup)
        assertEquals(preview, result.preview)
        assertEquals(9, result.restoredRowCount)
        assertEquals(1L, epoch)
        assertTrue(result.previousState.file.isFile)
        assertEquals(profileSnapshot(), transfer.store)
        assertEquals(1, transfer.importCalls)
        assertEquals(0, transfer.revertCalls)
        assertEquals(1, result.hostedProfiles.imported)
        assertFalse(restorer.isRecoveryPending())

        // Exact replacement: the source's rows and ids, and none of the target's own rows.
        assertEquals(2, count("categories"))
        assertEquals(listOf("Meals", "Travel"), categoryNames())
        assertFalse(categoryNames().contains("Original category"))
        assertEquals(1, count("transactions"))
        val ledger = target.transactionDao().findById(fixture.transactionId)
        assertNotNull(ledger)
        assertEquals(9_876_543_210_123L, checkNotNull(ledger).amountVnd)
        assertEquals("Coffee", ledger.description)
        assertEquals(fixture.mealsCategoryId, ledger.categoryId)

        val restoredDraft = checkNotNull(target.pendingBankDraftDao().findById(fixture.draftId))
        assertEquals("Corrected coffee", restoredDraft.reviewMemo)
        assertEquals("90000", restoredDraft.reviewAmountText)
        assertEquals(fixture.mealsCategoryId, restoredDraft.reviewCategoryId)
        assertEquals(ReviewCategoryOrigin.USER, restoredDraft.reviewCategoryOrigin)
        assertEquals(5, restoredDraft.reviewRevision)
        assertEquals(IntakeReviewStatus.PENDING, restoredDraft.reviewStatus)
        assertNull(restoredDraft.confirmedTransactionId)

        // Historical provenance is present but inert: the draft's own user choice is what the
        // review queue reads, and its rank comes from the stored facts alone.
        val snapshot = target.openHelper.writableDatabase.query(
            "SELECT `suggestedCategoryId`, `staleReason` FROM `classification_snapshots`",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0) to cursor.getString(1)
        }
        assertEquals(fixture.travelCategoryId, snapshot.first)
        assertEquals("historical-only", snapshot.second)
        assertFalse(snapshot.first == restoredDraft.reviewCategoryId)
        val queueEntry = target.reviewQueueDao().findOrderRow(fixture.draftId)
        assertEquals(2, queueEntry?.queueRank)

        // Stored intentions of the replaced world cannot replay.
        assertEquals(0, count("workflow_operations"))
        assertEquals(0, count("arrival_reconciliation_operations"))

        // The pre-restore copy is the target's own previous state, and restores back to it.
        val previous = restorer.readRecoveryCopy(result.previousState)
        val previousPreview = restorer.preview(previous)
        assertEquals(1, previousPreview.categoryCount)
        assertEquals(1, previousPreview.transactionCount)
        restorer.restore(previous)
        assertEquals(listOf("Original category"), categoryNames())
        assertEquals(1, count("transactions"))
        val back = target.transactionDao().findById(1L)
        assertEquals("Original record", back?.description)
        assertEquals(111L, back?.amountVnd)
    }

    @Test
    fun `unsupported and corrupt backups are rejected without mutating live state`() = runBlocking {
        populate(source)
        populate(target)
        val restorer = restorer(target)
        val valid = export(source)
        val before = export(target)

        fun assertRejected(code: RestoreRejectionCode, bytes: ByteArray) {
            try {
                restorer.restore(bytes)
                fail("The restore should have been refused.")
            } catch (rejection: RestoreRejection) {
                assertEquals(rejection.message, code, rejection.code)
            }
            assertEquals(exportText(before), exportText(export(target)))
        }

        assertRejected(RestoreRejectionCode.UNSUPPORTED_FORMAT, "not json".toByteArray())
        assertRejected(
            RestoreRejectionCode.UNSUPPORTED_FORMAT,
            withRoot(valid) { it.put("format", "some-other-app") },
        )
        assertRejected(
            RestoreRejectionCode.UNSUPPORTED_VERSION,
            withRoot(valid) { it.put("format_version", 2) },
        )
        assertRejected(
            RestoreRejectionCode.UNSUPPORTED_SCHEMA,
            withRoot(valid) { it.put("database_schema_version", LedgerDatabase.VERSION - 1) },
        )
        assertRejected(
            RestoreRejectionCode.INVALID,
            withTable(valid, "transactions") { row ->
                row.put("categoryId", 9_999L)
            },
        )
        assertRejected(
            RestoreRejectionCode.INVALID,
            withTable(valid, "transactions") { row ->
                row.put("amountVnd", "9876543210123")
            },
        )
        assertRejected(
            RestoreRejectionCode.INVALID,
            withTable(valid, "transactions") { row ->
                row.put("amountVnd", 0)
            },
        )
        assertRejected(
            RestoreRejectionCode.INVALID,
            withTable(valid, "pending_bank_drafts") { row ->
                row.put("reviewCategoryId", 9_999L)
            },
        )
        assertRejected(
            RestoreRejectionCode.INVALID,
            withTable(valid, "transactions") { row ->
                row.put("type", "TRANSFER_OF_OWNERSHIP")
            },
        )
        assertRejected(
            RestoreRejectionCode.INVALID,
            withRoot(valid) { root ->
                root.getJSONObject("hosted_profiles").getJSONArray("profiles")
                    .getJSONObject(0).put("credential", "secret-value")
            },
        )
        assertRejected(
            RestoreRejectionCode.INVALID,
            withRoot(valid) { root ->
                root.getJSONObject("hosted_profiles").getJSONArray("profiles")
                    .getJSONObject(0).put("endpoint_origin", "https://provider.example/private/path")
            },
        )
        assertRejected(
            RestoreRejectionCode.INVALID,
            withRoot(valid) { root ->
                root.getJSONObject("tables").getJSONArray("note_events").getJSONObject(0)
                    .put("spanEnd", 0)
            },
        )

        // The size ceiling is enforced before anything is decoded or written.
        val smallLimitRestorer = CompleteStateRestorer(
            database = target,
            exporter = exporter(target),
            recoveryDirectory = recoveryDirectory,
            profileTransfer = transfer,
            maxBackupBytes = 512,
        )
        try {
            smallLimitRestorer.preview(valid)
            fail("An oversized backup should have been refused.")
        } catch (rejection: RestoreRejection) {
            assertEquals(RestoreRejectionCode.TOO_LARGE, rejection.code)
        }
        assertEquals(exportText(before), exportText(export(target)))
        assertTrue(restorer.recoveryCopies().isEmpty())
    }

    @Test
    fun `interrupted replacement rolls back to the exact previous state`() = runBlocking {
        populate(source)
        populate(target)
        val restorer = restorer(target)
        val backup = export(source)
        val before = export(target)
        target.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER `abort_restore` BEFORE INSERT ON `context_links` " +
                "BEGIN SELECT RAISE(ABORT, 'simulated interruption'); END",
        )
        try {
            restorer.restore(backup)
            fail("The simulated failure should have aborted the replacement.")
        } catch (_: SQLiteException) {
            // Expected: the transaction is rolled back by SQLite.
        }
        assertEquals(exportText(before), exportText(export(target)))
    }

    @Test
    fun `possible duplicate pointer works for a later row id`() = runBlocking {
        populateDuplicatePair(source)
        val backup = export(source)
        // Rewrite the pointers so the lower id names the higher one, which the insert order
        // alone could not satisfy.
        val rewritten = JSONObject(String(backup, Charsets.UTF_8)).apply {
            val drafts = getJSONObject("tables").getJSONArray("pending_bank_drafts")
            drafts.getJSONObject(0).put("possibleDuplicateOfDraftId", drafts.getJSONObject(1).getLong("id"))
            drafts.getJSONObject(1).put("possibleDuplicateOfDraftId", JSONObject.NULL)
        }.toString()
        val restorer = restorer(target)
        restorer.restore(rewritten.toByteArray(Charsets.UTF_8))
        val rows = target.openHelper.writableDatabase.query(
            "SELECT `id`, `possibleDuplicateOfDraftId` FROM `pending_bank_drafts` ORDER BY `id`",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(0) to cursor.getLong(1))
                }
            }
        }
        assertEquals(2, rows.size)
        assertEquals(rows[1].first, rows[0].second)
        assertEquals(0L, rows[1].second)
    }

    @Test
    fun `a session admitted before a restore cannot apply its hosted category afterwards`() = runBlocking {
        val fixture = populate(source)
        val backup = export(source)
        populate(target)
        val restorer = restorer(target)
        val commands = WorkflowCommandService(database = target, stateEpoch = { epoch })
        val query = WorkflowQueryService(target)
        val contextRevision = query.draftContextSnapshot(fixture.draftId).second
        val catalogRevision = categoryCatalogRevision(query.categoryCatalog())
        val staleSession = WorkflowCallerScope.AgentSession(
            scopeId = "stale-session",
            captureIds = emptySet(),
            draftIds = setOf(fixture.draftId),
            stateEpoch = commands.currentStateEpoch(),
        )

        suspend fun choose(
            session: WorkflowCallerScope.AgentSession,
            key: String,
            expectedRevision: Int,
        ): WorkflowCommandOutcome = commands.selectDraftCategory(
            session,
            SelectDraftCategoryRequest(
                idempotencyKey = key,
                draftId = fixture.draftId,
                expectedReviewRevision = expectedRevision,
                categoryId = fixture.travelCategoryId,
                expectedContextRevision = contextRevision,
                expectedCatalogRevision = catalogRevision,
            ),
        )

        assertTrue(choose(staleSession, "before-restore", 5) is WorkflowCommandOutcome.Applied)
        assertEquals(6, target.pendingBankDraftDao().findById(fixture.draftId)!!.reviewRevision)

        restorer.restore(backup)
        assertEquals(1L, epoch)

        // The restored draft is back at revision 5 with the same context and catalog digests, so
        // a revision-only guard would let this obsolete hosted result through. The generation
        // carried by the stale session is what refuses it.
        assertTrue(choose(staleSession, "after-restore", 5) is WorkflowCommandOutcome.Conflict)
        assertEquals(0, count("workflow_operations"))
        assertEquals(
            fixture.mealsCategoryId,
            target.pendingBankDraftDao().findById(fixture.draftId)!!.reviewCategoryId,
        )

        // A session admitted against the replaced state may prepare its own choice again.
        val freshSession = WorkflowCallerScope.AgentSession(
            scopeId = "fresh-session",
            captureIds = emptySet(),
            draftIds = setOf(fixture.draftId),
            stateEpoch = commands.currentStateEpoch(),
        )
        assertTrue(choose(freshSession, "fresh", 5) is WorkflowCommandOutcome.Applied)
        assertEquals(
            fixture.travelCategoryId,
            target.pendingBankDraftDao().findById(fixture.draftId)!!.reviewCategoryId,
        )
    }

    @Test
    fun `restore reopens a hosted choice on an archived category and keeps the user choice`() = runBlocking {
        val archived = insert(source, "Archived", archived = true)
        insert(source, "Active")
        val hostedDraftId = source.pendingBankDraftDao().store(
            draft(
                deliveryId = "archived-hosted",
                memo = "Hosted memo",
                categoryId = archived,
                origin = ReviewCategoryOrigin.HOSTED,
                revision = 5,
            ),
        )
        val userDraftId = source.pendingBankDraftDao().store(
            draft(
                deliveryId = "archived-user",
                memo = "User memo",
                categoryId = archived,
                origin = ReviewCategoryOrigin.USER,
                revision = 4,
            ),
        )
        val backup = export(source)
        val restorer = restorer(target)

        val preview = restorer.preview(backup)
        assertEquals(1, preview.reopenedHostedCategoryCount)
        restorer.restore(backup)

        val hosted = checkNotNull(target.pendingBankDraftDao().findById(hostedDraftId))
        assertNull(hosted.reviewCategoryId)
        assertFalse(hosted.reviewCategoryResolved)
        assertEquals(ReviewCategoryOrigin.NONE, hosted.reviewCategoryOrigin)
        assertEquals(6, hosted.reviewRevision)

        val user = checkNotNull(target.pendingBankDraftDao().findById(userDraftId))
        assertEquals(archived, user.reviewCategoryId)
        assertTrue(user.reviewCategoryResolved)
        assertEquals(ReviewCategoryOrigin.USER, user.reviewCategoryOrigin)
        assertEquals(4, user.reviewRevision)
    }

    @Test
    fun `a descriptor import failure leaves both stores in their pre-restore state`() = runBlocking {
        populate(source)
        populate(target)
        val before = export(target)
        val previousProviders = transfer.store
        transfer.importFailure = IllegalStateException("simulated descriptor import failure")

        val rejection = assertThrows(RestoreRejection::class.java) {
            restorer(target).restore(export(source))
        }

        assertEquals(RestoreRejectionCode.INVALID, rejection.code)
        assertEquals(exportText(before), exportText(export(target)))
        assertEquals(previousProviders, transfer.store)
        assertEquals(1, transfer.revertCalls)
        assertEquals(0, count("workflow_operations"))
        assertFalse(restorer(target).isRecoveryPending())
    }

    @Test
    fun `a failed replacement after a successful import rolls the descriptors back exactly`() = runBlocking {
        populate(source)
        populate(target)
        val before = export(target)
        val previousProviders = transfer.store
        target.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER `abort_restore` BEFORE INSERT ON `context_links` " +
                "BEGIN SELECT RAISE(ABORT, 'simulated interruption'); END",
        )

        try {
            restorer(target).restore(export(source))
            fail("The simulated failure should have aborted the replacement.")
        } catch (_: SQLiteException) {
            // Expected: SQLite rolls the replacement back.
        }

        assertEquals(exportText(before), exportText(export(target)))
        assertEquals(previousProviders, transfer.store)
        assertTrue("the import must not survive the rollback", transfer.store != profileSnapshot())
        assertEquals(1, transfer.importCalls)
        assertEquals(1, transfer.revertCalls)
        assertEquals(0, count("workflow_operations"))
        assertFalse(restorer(target).isRecoveryPending())
    }

    @Test
    fun `an interrupted rollback is completed at recovery and keeps nothing behind`() = runBlocking {
        populate(source)
        populate(target)
        val before = export(target)
        val previousProviders = transfer.store
        target.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER `abort_restore` BEFORE INSERT ON `context_links` " +
                "BEGIN SELECT RAISE(ABORT, 'simulated interruption'); END",
        )
        transfer.revertFailure = IllegalStateException("simulated rollback failure")
        val restorer = restorer(target)

        val rejection = assertThrows(RestoreRejection::class.java) {
            restorer.restore(export(source))
        }
        assertEquals(RestoreRejectionCode.RECOVERY_PENDING, rejection.code)
        assertTrue(restorer.isRecoveryPending())
        assertEquals(exportText(before), exportText(export(target)))

        transfer.revertFailure = null
        val outcome = restorer.completeInterruptedRestore()

        assertEquals(RestoreRecoveryOutcome.RolledBack, outcome)
        assertEquals(previousProviders, transfer.store)
        assertFalse(restorer.isRecoveryPending())
        assertEquals(exportText(before), exportText(export(target)))
    }

    @Test
    fun `a committed cutover interrupted before cleanup completes descriptors at recovery`() = runBlocking {
        populate(target)
        val previousProviders = transfer.store
        val importedProviders = profileSnapshot()
        val digest = "a".repeat(64)
        RestoreIntentStore(recoveryDirectory).write(
            RestoreIntent(
                backupDigestSha256 = digest,
                createdAtEpochMillis = NOW,
                previousProfiles = previousProviders,
                importedProfiles = importedProviders,
            ),
        )
        target.openHelper.writableDatabase.execSQL(
            "INSERT INTO `workflow_operations` (`idempotencyKey`, `scopeId`, `command`, " +
                "`payloadDigest`, `outcome`, `createdAtEpochMillis`) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "restore-commit:$digest",
                "restore",
                "complete-state-restore",
                digest,
                "APPLIED",
                NOW,
            ),
        )
        val restorer = restorer(target)
        assertTrue(restorer.isRecoveryPending())

        val outcome = restorer.completeInterruptedRestore()

        assertEquals(RestoreRecoveryOutcome.Completed, outcome)
        assertEquals(importedProviders, transfer.store)
        assertFalse(restorer.isRecoveryPending())
        assertEquals(0, count("workflow_operations"))
    }

    @Test
    fun `an unreadable journal blocks recovery, is kept, and closes ordinary commands`() = runBlocking {
        populate(target)
        recoveryDirectory.mkdirs()
        val journal = File(recoveryDirectory, "restore-intent.json")
        journal.writeText("{ this is not a journal", Charsets.UTF_8)
        val restorer = restorer(target)

        assertTrue(restorer.isRecoveryPending())
        assertTrue(restorer.completeInterruptedRestore() is RestoreRecoveryOutcome.Blocked)
        assertTrue(journal.isFile)
        assertEquals(0, transfer.importCalls)
        assertEquals(0, transfer.revertCalls)

        val commands = WorkflowCommandService(
            database = target,
            stateRecoveryPending = restorer::isRecoveryPending,
        )
        val refused = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest(idempotencyKey = "while-pending", bodyText = "must not be stored"),
        )
        assertTrue(refused is WorkflowCommandOutcome.Conflict)
        // Only the fixture's own capture exists; the refused write stored nothing.
        assertEquals(1, count("capture_messages"))
    }

    @Test
    fun `a marker left alone means the cutover completed and only needs cleanup`() = runBlocking {
        populate(target)
        val digest = "b".repeat(64)
        target.openHelper.writableDatabase.execSQL(
            "INSERT INTO `workflow_operations` (`idempotencyKey`, `scopeId`, `command`, " +
                "`payloadDigest`, `outcome`, `createdAtEpochMillis`) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "restore-commit:$digest",
                "restore",
                "complete-state-restore",
                digest,
                "APPLIED",
                NOW,
            ),
        )
        val restorer = restorer(target)
        assertFalse(restorer.isRecoveryPending())
        val providers = transfer.store

        assertEquals(RestoreRecoveryOutcome.Completed, restorer.completeInterruptedRestore())

        assertEquals(providers, transfer.store)
        assertEquals(0, transfer.importCalls)
        assertEquals(0, transfer.revertCalls)
        assertEquals(0, count("workflow_operations"))
    }

    private fun draft(
        deliveryId: String,
        memo: String,
        categoryId: Long,
        origin: ReviewCategoryOrigin,
        revision: Int,
    ) = PendingBankDraftEntity(
        deliveryId = deliveryId,
        sourcePackage = "bank.example",
        notificationKey = null,
        postedAtEpochMillis = NOW,
        bank = "Example Bank",
        parseStatus = IntakeParseStatus.PARSED,
        amountState = IntakeFieldState.PRESENT,
        amountVnd = 20_000L,
        amountUnresolvedReason = null,
        direction = IntakeDirection.DEBIT,
        momentState = IntakeFieldState.PRESENT,
        statedDate = "2026-11-12",
        statedTime = "10:30",
        momentUnresolvedReason = null,
        memoState = IntakeFieldState.PRESENT,
        memo = memo,
        memoUnresolvedReason = null,
        reviewStatus = IntakeReviewStatus.PENDING,
        createdAtEpochMillis = NOW,
        reviewCategoryId = categoryId,
        reviewCategoryResolved = true,
        reviewCategoryOrigin = origin,
        reviewRevision = revision,
    )

    // --- Fixtures -----------------------------------------------------------

    private data class Fixture(
        val mealsCategoryId: Long,
        val travelCategoryId: Long,
        val transactionId: Long,
        val draftId: Long,
    )

    private suspend fun populate(database: LedgerDatabase): Fixture {
        val meals = insert(database, "Meals")
        val travel = insert(database, "Travel")
        assertTrue(
            database.rememberedCategoryRuleDao().rememberIfActive(
                RememberedCategoryRuleEntity(patternKey = "coffee shop", categoryId = meals),
            ),
        )
        val transactionId = database.transactionDao().insert(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountVnd = 9_876_543_210_123L,
                occurredAtEpochMillis = NOW - 1_000,
                description = "Coffee",
                categoryId = meals,
                confirmation = ConfirmationStatus.CONFIRMED,
                source = RecordSource.MANUAL,
                createdAtEpochMillis = NOW,
                updatedAtEpochMillis = NOW,
                revision = 3,
            ),
        )
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "delivery-identity-1",
                sourcePackage = "bank.example",
                notificationKey = "notification-identity-1",
                postedAtEpochMillis = NOW - 10_000,
                bank = "Example Bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = 87_500L,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-11-12",
                statedTime = "10:30",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = "Coffee",
                memoUnresolvedReason = null,
                reviewStatus = IntakeReviewStatus.PENDING,
                createdAtEpochMillis = NOW,
                reviewAmountText = "90000",
                reviewDateText = "2026-11-13",
                reviewTimeText = "11:45",
                reviewMemo = "Corrected coffee",
                reviewMemoEdited = true,
                reviewType = TransactionType.EXPENSE,
                reviewCategoryId = meals,
                reviewCategoryResolved = true,
                reviewCategoryOrigin = ReviewCategoryOrigin.USER,
                reviewRevision = 5,
            ),
        )
        val captureId = database.captureMessageDao().insert(
            CaptureMessageEntity(
                captureKind = CaptureKind.USER_NOTE,
                sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                sourceIdentity = "saved-note-identity-1",
                bodyText = "Saved private note text",
                capturedAtEpochMillis = NOW,
                captureTimezone = "Asia/Ho_Chi_Minh",
                revision = 2,
                originRef = null,
            ),
        )
        val eventId = database.noteEventDao().insert(
            NoteEventEntity(
                captureId = captureId,
                eventIdentity = "saved-note-event-1",
                spanStart = 0,
                spanEnd = 5,
                normalizedText = "Coffee",
                amountVnd = 87_500L,
                merchant = "Coffee shop",
                anchorDate = "2026-11-12",
                anchorTime = "10:30",
                anchorTimezone = "Asia/Ho_Chi_Minh",
                intervalEndDate = null,
                intervalEndTime = null,
                extractionVersion = "fixture-v1",
                unresolvedReason = null,
                status = NoteEventStatus.INTERPRETED,
                revision = 2,
                sourceRevision = 2,
                createdAtEpochMillis = NOW,
                resolvedTransactionId = transactionId,
                userEdited = true,
                resolvedAtEpochMillis = NOW,
            ),
        )
        database.contextLinkDao().insert(
            ContextLinkEntity(
                eventId = eventId,
                draftId = draftId,
                state = ContextLinkState.ACCEPTED,
                evidence = "same stated day and amount",
                revision = 4,
                userOverride = "user accepted",
                activeEventId = eventId,
                createdAtEpochMillis = NOW,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO `classification_snapshots` (" +
                "`draftId`, `contextRevision`, `categoryRevision`, `ruleRevision`, " +
                "`modelRevision`, `candidateSetVersion`, `decisionSource`, `suggestedCategoryId`, " +
                "`candidateIds`, `probabilities`, `unknownShare`, `confidence`, " +
                "`effectiveInputDigest`, `classificationInputKind`, `classificationInputIdentity`, " +
                "`translationSourceRevision`, `eventIds`, `predictionTime`, `staleReason`) VALUES " +
                "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                draftId,
                "context-r1",
                "category-r1",
                "rules-r1",
                "old-classifier-v0",
                "candidate-v1",
                "MODEL",
                travel,
                "[$travel]",
                "[0.9]",
                0.1,
                0.9,
                "input-digest",
                "STORED_SOURCE",
                "input-identity",
                null,
                "[$eventId]",
                NOW - 2_000,
                "historical-only",
            ),
        )
        return Fixture(
            mealsCategoryId = meals,
            travelCategoryId = travel,
            transactionId = transactionId,
            draftId = draftId,
        )
    }

    private suspend fun populateDuplicatePair(database: LedgerDatabase) {
        repeat(2) { index ->
            database.pendingBankDraftDao().store(
                PendingBankDraftEntity(
                    deliveryId = "delivery-$index",
                    sourcePackage = "bank.example",
                    notificationKey = "notification-$index",
                    postedAtEpochMillis = NOW,
                    bank = "Example Bank",
                    parseStatus = IntakeParseStatus.PARSED,
                    amountState = IntakeFieldState.PRESENT,
                    amountVnd = 20_000L,
                    amountUnresolvedReason = null,
                    direction = IntakeDirection.DEBIT,
                    momentState = IntakeFieldState.PRESENT,
                    statedDate = "2026-11-12",
                    statedTime = "10:30",
                    momentUnresolvedReason = null,
                    memoState = IntakeFieldState.PRESENT,
                    memo = "Same statement",
                    memoUnresolvedReason = null,
                    possibleDuplicateOfDraftId = if (index == 1) 1L else null,
                    createdAtEpochMillis = NOW,
                ),
            )
        }
    }

    private suspend fun insert(
        database: LedgerDatabase,
        name: String,
        now: Long = NOW,
        archived: Boolean = false,
    ): Long =
        database.categoryDao().insert(
            CategoryEntity(
                name = name,
                archived = archived,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )

    private fun newDatabase(): LedgerDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        LedgerDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun exporter(database: LedgerDatabase) =
        CompleteStateExporter(database) { profileSnapshot() }

    private fun restorer(database: LedgerDatabase) = CompleteStateRestorer(
        database = database,
        exporter = exporter(database),
        recoveryDirectory = recoveryDirectory,
        profileTransfer = transfer,
        advanceStateEpoch = { epoch += 1 },
    )

    private fun export(database: LedgerDatabase): ByteArray {
        val output = ByteArrayOutputStream()
        exporter(database).writeTo(output, exportedAtEpochMillis = FIXED_EXPORT_TIME)
        return output.toByteArray()
    }

    private fun exportText(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    private fun count(table: String): Int = target.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`")
        .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun categoryNames(): List<String> = target.openHelper.writableDatabase
        .query("SELECT `name` FROM `categories` ORDER BY `id`")
        .use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun categoryIds(): List<Long> = target.openHelper.writableDatabase
        .query("SELECT `id` FROM `categories` ORDER BY `id`")
        .use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }

    private fun withRoot(backup: ByteArray, change: (JSONObject) -> Unit): ByteArray =
        JSONObject(String(backup, Charsets.UTF_8)).apply(change).toString().toByteArray(Charsets.UTF_8)

    private fun withTable(
        backup: ByteArray,
        table: String,
        change: (JSONObject) -> Unit,
    ): ByteArray = withRoot(backup) { root ->
        val rows = root.getJSONObject("tables").getJSONArray(table)
        assertTrue(rows.length() > 0)
        change(rows.getJSONObject(0))
    }

    private fun previousProfileSnapshot() = HostedProviderExportSnapshot(
        activeProfileId = "local-profile",
        profiles = listOf(
            HostedProviderExportProfile(
                id = "local-profile",
                name = "Local profile",
                protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                endpointOrigin = "https://local.example",
                model = "local-model",
                maxOutputTokens = 40_000,
                reasoningEffort = null,
                temperature = 0.1,
                parallelToolCalls = null,
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = false,
                    supportsTemperature = true,
                    maxOutputTokensLimit = 100_000,
                ),
            ),
        ),
    )

    private class FakeProfileTransfer(initial: HostedProviderExportSnapshot) : HostedProfileTransfer {
        var store: HostedProviderExportSnapshot = initial
        var importCalls = 0
        var revertCalls = 0
        var importFailure: Exception? = null
        var revertFailure: Exception? = null

        override fun exportSnapshot(): HostedProviderExportSnapshot = store

        override fun importSnapshot(snapshot: HostedProviderExportSnapshot): HostedProfileImportResult {
            importCalls++
            importFailure?.let { throw it }
            store = snapshot
            return HostedProfileImportResult(
                imported = snapshot.profiles.size,
                withoutCredential = snapshot.profiles.size,
                activeProfileId = snapshot.activeProfileId,
            )
        }

        override fun revertImport(
            previous: HostedProviderExportSnapshot,
            imported: HostedProviderExportSnapshot,
        ) {
            revertCalls++
            revertFailure?.let { throw it }
            store = previous
        }
    }

    private fun profileSnapshot() = HostedProviderExportSnapshot(
        activeProfileId = "active-profile",
        profiles = listOf(
            HostedProviderExportProfile(
                id = "active-profile",
                name = "Fixture provider",
                protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                endpointOrigin = "https://provider.example",
                model = "fixture-model",
                maxOutputTokens = 32_768,
                reasoningEffort = HostedReasoningEffort.MEDIUM,
                temperature = 0.2,
                parallelToolCalls = false,
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = true,
                    supportsTemperature = true,
                    maxOutputTokensLimit = 65_536,
                ),
            ),
        ),
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val ORIGINAL_TIME = 1_700_000_000_000L
        const val FIXED_EXPORT_TIME = 1_810_000_000_000L
    }
}
