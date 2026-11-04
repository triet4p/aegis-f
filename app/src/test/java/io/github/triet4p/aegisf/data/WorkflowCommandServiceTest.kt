package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The behavior of the application's one preparation-command boundary.
 *
 * These run against the real Room file, so an idempotency record, a refused request, a
 * conflict, and a stored change are observed the way the app observes them after a
 * restart — not as a mocked return value.
 *
 * The boundary has no model collaborator: every preparation command writes only what the
 * caller supplied and the app's own stored evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowCommandServiceTest {
    private class FixedClock(@Volatile var now: Long = CAPTURE_TIME) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private lateinit var context: Context
    private lateinit var clock: FixedClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        clock = FixedClock()
    }

    private fun open(): LedgerDatabase = LedgerDatabase.build(context)

    private fun commands(database: LedgerDatabase): WorkflowCommandService = WorkflowCommandService(
        database = database,
        clock = clock,
        timezoneId = { CAPTURE_ZONE },
    )
    // --- Idempotency --------------------------------------------------------

    @Test
    fun `a repeated save request stores one capture and changes nothing the second time`() =
        runTest {
            val database = open()
            try {
                val commands = commands(database)
                val request = SaveCaptureRequest(
                    idempotencyKey = "save-note-1",
                    bodyText = "Fuel 50,000 at 15:00",
                )

                val first = commands.saveCapture(WorkflowCallerScope.User, request)
                assertTrue("the first request was refused: $first", first is WorkflowCommandOutcome.Applied)
                val change = (first as WorkflowCommandOutcome.Applied).change
                assertTrue(change is WorkflowChange.CaptureSaved)
                val captureId = (change as WorkflowChange.CaptureSaved).captureId

                val second = commands.saveCapture(WorkflowCallerScope.User, request)
                assertTrue("the repeat was not a no-change: $second", second is WorkflowCommandOutcome.NoChange)
                val repeated = (second as WorkflowCommandOutcome.NoChange).change
                assertTrue(repeated is WorkflowChange.CaptureSaved)
                assertEquals(captureId, (repeated as WorkflowChange.CaptureSaved).captureId)

                // One capture, one revision, one text: the repeat changed nothing.
                val stored = database.captureMessageDao().findAllIncludingTombstones()
                assertEquals(1, stored.size)
                assertEquals("Fuel 50,000 at 15:00", stored.single().bodyText)
                assertEquals(0, stored.single().revision)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a repeated request after a restart changes nothing`() = runTest {
        val database = open()
        val captureId = when (val first = commands(database).saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
        )) {
            is WorkflowCommandOutcome.Applied -> (first.change as WorkflowChange.CaptureSaved).captureId
            else -> error("the first request was refused: $first")
        }
        database.close()

        val reopened = open()
        try {
            val again = commands(reopened).saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
            )
            assertTrue("the repeat was not a no-change: $again", again is WorkflowCommandOutcome.NoChange)
            assertEquals(1, reopened.captureMessageDao().findAllIncludingTombstones().size)
            assertNotNull(reopened.captureMessageDao().findById(captureId))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `two callers racing for one key apply the change once`() = runTest {
        val database = open()
        try {
            val request = SaveCaptureRequest(
                idempotencyKey = "save-note-race",
                bodyText = "Circle K snack 20,000",
            )
            val outcomes = coroutineScope {
                val first = async(Dispatchers.IO) {
                    commands(database).saveCapture(WorkflowCallerScope.User, request)
                }
                val second = async(Dispatchers.IO) {
                    commands(database).saveCapture(WorkflowCallerScope.User, request)
                }
                listOf(first.await(), second.await())
            }
            assertEquals(1, database.captureMessageDao().findAllIncludingTombstones().size)
            assertEquals(1, outcomes.count { it is WorkflowCommandOutcome.Applied })
            assertEquals(1, outcomes.count { it is WorkflowCommandOutcome.NoChange })
        } finally {
            database.close()
        }
    }

    @Test
    fun `the same key with a different request is a conflict and changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
            )

            val changed = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Something else"),
            )
            assertTrue("the changed request was not a conflict: $changed", changed is WorkflowCommandOutcome.Conflict)
            val stored = database.captureMessageDao().findAllIncludingTombstones()
            assertEquals(1, stored.size)
            assertEquals("Fuel 50,000 at 15:00", stored.single().bodyText)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the same key from another caller is a conflict and changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
            )

            val other = commands.saveCapture(
                WorkflowCallerScope.AgentSession(
                    scopeId = "agent-1",
                    captureIds = emptySet(),
                    draftIds = emptySet(),
                ),
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
            )
            assertTrue("the other caller was not a conflict: $other", other is WorkflowCommandOutcome.Conflict)
            assertEquals(1, database.captureMessageDao().findAllIncludingTombstones().size)
        } finally {
            database.close()
        }
    }

    // --- Revisions -----------------------------------------------------------

    @Test
    fun `a stale capture revision is a conflict and changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", "Fuel 50,000 at 15:00")

            val revised = commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest(
                    idempotencyKey = "revise-note-1",
                    captureId = captureId,
                    expectedRevision = 0,
                    bodyText = "Fuel 55,000 at 15:05",
                ),
            )
            assertTrue("the revision was refused: $revised", revised is WorkflowCommandOutcome.Applied)

            val stale = commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest(
                    idempotencyKey = "revise-note-2",
                    captureId = captureId,
                    expectedRevision = 0,
                    bodyText = "Fuel 60,000 at 15:10",
                ),
            )
            assertTrue("the stale revision was not a conflict: $stale", stale is WorkflowCommandOutcome.Conflict)
            val stored = database.captureMessageDao().findById(captureId)!!
            assertEquals(1, stored.revision)
            assertEquals("Fuel 55,000 at 15:05", stored.bodyText)
        } finally {
            database.close()
        }
    }

    @Test
    fun `editing a linked capture clears derived context and retains its provenance`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val fixture = linkedCaptureFixture(commands, database, "edit-linked")
            val original = database.captureMessageDao().findById(fixture.captureId)!!
            val query = WorkflowQueryService(database)
            val before = query.captureContextSummaries(listOf(fixture.captureId)).getValue(
                fixture.captureId,
            )
            assertEquals(1, before.interpretedEventCount)
            assertEquals(1, before.unmatchedEventCount)
            assertEquals(1, before.acceptedLinkCount)
            assertEquals(1, before.rejectedLinkCount)
            assertTrue(database.transactionDao().findAll().isEmpty())

            val outcome = commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest(
                    idempotencyKey = "edit-linked-source",
                    captureId = fixture.captureId,
                    expectedRevision = original.revision,
                    bodyText = "Plans changed after lunch.",
                ),
            )
            assertTrue("the source edit was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)

            val revised = database.captureMessageDao().findById(fixture.captureId)!!
            assertEquals("Plans changed after lunch.", revised.bodyText)
            assertEquals(original.sourceIdentity, revised.sourceIdentity)
            assertEquals(original.capturedAtEpochMillis, revised.capturedAtEpochMillis)
            assertEquals(original.captureTimezone, revised.captureTimezone)
            assertEquals(1, revised.revision)

            val events = database.noteEventDao().findForCapture(fixture.captureId)
                .associateBy { it.id }
            assertEquals(NoteEventStatus.UNRESOLVED, events.getValue(fixture.acceptedEventId).status)
            assertEquals("SOURCE_EDITED", events.getValue(fixture.acceptedEventId).unresolvedReason)
            assertNull(events.getValue(fixture.acceptedEventId).normalizedText)
            assertEquals(NoteEventStatus.NO_MATCH, events.getValue(fixture.rejectedEventId).status)
            assertNull(events.getValue(fixture.rejectedEventId).normalizedText)
            assertTrue(database.contextLinkDao().findForEvent(fixture.acceptedEventId).isEmpty())
            assertEquals(
                ContextLinkState.REJECTED,
                database.contextLinkDao().findForEvent(fixture.rejectedEventId).single().state,
            )

            val after = query.captureContextSummaries(listOf(fixture.captureId)).getValue(
                fixture.captureId,
            )
            assertEquals(1, after.unresolvedEventCount)
            assertEquals(1, after.unmatchedEventCount)
            assertEquals(0, after.acceptedLinkCount)
            assertEquals(1, after.rejectedLinkCount)
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `deleting a linked capture is idempotent and leaves its text free tombstone`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val fixture = linkedCaptureFixture(commands, database, "delete-linked")
            val original = database.captureMessageDao().findById(fixture.captureId)!!
            val request = DeleteCaptureRequest(
                idempotencyKey = "delete-linked-source",
                captureId = fixture.captureId,
                expectedRevision = original.revision,
            )
            assertTrue(database.transactionDao().findAll().isEmpty())

            val first = commands.deleteCapture(WorkflowCallerScope.User, request)
            assertEquals(
                WorkflowCommandOutcome.Applied(WorkflowChange.CaptureDeleted(fixture.captureId, 1)),
                first,
            )
            val replay = commands.deleteCapture(WorkflowCallerScope.User, request)
            assertTrue(
                "delete replay was not a no-change: $replay",
                replay is WorkflowCommandOutcome.NoChange,
            )
            assertEquals(
                WorkflowChange.CaptureDeleted(fixture.captureId, 1),
                (replay as WorkflowCommandOutcome.NoChange).change,
            )

            val deleted = database.captureMessageDao().findById(fixture.captureId)!!
            assertNull(deleted.bodyText)
            assertTrue(deleted.tombstone)
            assertEquals(original.sourceIdentity, deleted.sourceIdentity)
            assertEquals(original.capturedAtEpochMillis, deleted.capturedAtEpochMillis)
            assertEquals(original.captureTimezone, deleted.captureTimezone)
            assertEquals(1, deleted.revision)

            val query = WorkflowQueryService(database)
            assertTrue(
                query.sourceExcerpts(
                    SourceQuery(
                        kind = CaptureKind.USER_NOTE,
                        sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                        bounds = QueryPageRequest(LIST_PAGE_SIZE),
                    ),
                ).items.isEmpty(),
            )
            val tombstone = query.sourceExcerpts(
                SourceQuery(
                    kind = CaptureKind.USER_NOTE,
                    sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                    state = SourceState.DELETED,
                    bounds = QueryPageRequest(LIST_PAGE_SIZE),
                ),
            ).items.single()
            assertNull(tombstone.bodyText)
            assertTrue(tombstone.tombstone)

            val events = database.noteEventDao().findForCapture(fixture.captureId)
                .associateBy { it.id }
            assertEquals("SOURCE_DELETED", events.getValue(fixture.acceptedEventId).unresolvedReason)
            assertNull(events.getValue(fixture.acceptedEventId).normalizedText)
            assertNull(events.getValue(fixture.rejectedEventId).normalizedText)
            assertTrue(database.contextLinkDao().findForEvent(fixture.acceptedEventId).isEmpty())
            assertEquals(
                ContextLinkState.REJECTED,
                database.contextLinkDao().findForEvent(fixture.rejectedEventId).single().state,
            )
            val summary = query.captureContextSummaries(listOf(fixture.captureId)).getValue(
                fixture.captureId,
            )
            assertEquals(0, summary.acceptedLinkCount)
            assertEquals(1, summary.rejectedLinkCount)
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `a stale reading revision is a conflict and changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)

            val stored = commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "event-1",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = reading("Fuel 50,000 at 15:00", "Fuel 50,000 at 15:00"),
                    extractionVersion = EXTRACTION_VERSION,
                ),
            )
            assertTrue("the reading was refused: $stored", stored is WorkflowCommandOutcome.Applied)

            // The capture moves on: the text the reading was taken from is gone.
            commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest(
                    idempotencyKey = "revise-note-1",
                    captureId = captureId,
                    expectedRevision = 0,
                    bodyText = "Fuel 50,000 at 15:00; Circle K snack 20,000 at 15:15 edited",
                ),
            )

            val stale = commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "event-2",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = reading("Fuel 50,000 at 15:00", "Fuel 55,000 at 15:05"),
                    extractionVersion = EXTRACTION_VERSION,
                ),
            )
            assertTrue("the stale reading was not a conflict: $stale", stale is WorkflowCommandOutcome.Conflict)
            // The reading the caller wanted to revise was never stored against the old text.
            val events = database.noteEventDao().findForCapture(captureId)
            assertEquals(1, events.size)
            assertTrue(
                "the stale reading was stored against the old text",
                events.none { it.normalizedText == "Fuel 55,000 at 15:05" },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `editing an interpretation requires the revision that was read`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-revision", NOTE_TEXT)
            val first = commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "event-revision-create",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = reading("Fuel 50,000 at 15:00", "Fuel"),
                    extractionVersion = EXTRACTION_VERSION,
                ),
            )
            assertTrue(first is WorkflowCommandOutcome.Applied)
            val updated = commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "event-revision-update",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = reading("Fuel 50,000 at 15:00", "Fuel purchase"),
                    extractionVersion = EXTRACTION_VERSION,
                    expectedEventRevision = 0,
                ),
            )
            assertTrue(updated is WorkflowCommandOutcome.Applied)

            val stale = commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "event-revision-stale",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = reading("Fuel 50,000 at 15:00", "Stale overwrite"),
                    extractionVersion = EXTRACTION_VERSION,
                    expectedEventRevision = 0,
                ),
            )
            assertTrue(
                "the stale interpretation was not a conflict: $stale",
                stale is WorkflowCommandOutcome.Conflict,
            )
            val stored = database.noteEventDao().findForCapture(captureId).single()
            assertEquals(1, stored.revision)
            assertEquals("Fuel purchase", stored.normalizedText)
        } finally {
            database.close()
        }
    }

    @Test
    fun `link decisions never change the bank draft or ledger`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-link-invariant", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-link-invariant")
            val draftId = storeDraft(database, "link-invariant", 50_000L)
            val originalDraft = database.pendingBankDraftDao().findById(draftId)!!
            val query = WorkflowQueryService(database)

            val proposed = commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest("propose-invariant", eventId, draftId),
            )
            assertTrue(proposed is WorkflowCommandOutcome.Applied)
            assertEquals(originalDraft, database.pendingBankDraftDao().findById(draftId))

            val attached = commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest("attach-invariant", eventId, draftId, expectedRevision = 0),
            )
            assertTrue(attached is WorkflowCommandOutcome.Applied)
            assertEquals(originalDraft, database.pendingBankDraftDao().findById(draftId))

            val unlinked = commands.unlinkLink(
                WorkflowCallerScope.User,
                UnlinkLinkRequest("unlink-invariant", eventId, draftId, expectedRevision = 1),
            )
            assertTrue(unlinked is WorkflowCommandOutcome.Applied)
            assertEquals(originalDraft, database.pendingBankDraftDao().findById(draftId))

            val reproposed = commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest("repropose-invariant", eventId, draftId),
            )
            assertTrue(reproposed is WorkflowCommandOutcome.Applied)
            val rejected = commands.rejectLink(
                WorkflowCallerScope.User,
                RejectLinkRequest("reject-invariant", eventId, draftId, expectedRevision = 0),
            )
            assertTrue(rejected is WorkflowCommandOutcome.Applied)
            assertEquals(originalDraft, database.pendingBankDraftDao().findById(draftId))
            assertTrue(
                query.ledgerRecords(LedgerRecordQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)))
                    .items.isEmpty(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `a stale link revision is a conflict and changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)

            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )

            val stale = commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 7,
                ),
            )
            assertTrue("the stale attach was not a conflict: $stale", stale is WorkflowCommandOutcome.Conflict)
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.PROPOSED, link.state)
            assertEquals(0, link.revision)
        } finally {
            database.close()
        }
    }

    // --- Caller scope --------------------------------------------------------

    @Test
    fun `an agent cannot change a capture outside its own scope`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val mine = saveCapture(commands, "save-note-mine", "Fuel 50,000 at 15:00")
            val other = saveCapture(commands, "save-note-other", "Circle K snack 20,000")
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(mine),
                draftIds = emptySet(),
            )

            val refused = commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "revise-other",
                    captureId = other,
                    expectedRevision = 0,
                    bodyText = "not yours",
                ),
            )
            assertTrue("the other capture was not refused: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.CALLER_SCOPE,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            val stored = database.captureMessageDao().findById(other)!!
            assertEquals("Circle K snack 20,000", stored.bodyText)
            assertEquals(0, stored.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an unauthorized caller cannot tell a missing target from a foreign one`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val mine = saveCapture(commands, "save-note-mine", NOTE_TEXT)
            val foreign = saveCapture(commands, "save-note-foreign", NOTE_TEXT)
            val foreignEvent = storeEvent(commands, foreign, "event-foreign")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(mine),
                draftIds = setOf(draftId),
            )

            val missing = commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-missing",
                    eventId = 9_999L,
                    draftId = draftId,
                ),
            )
            val outOfScope = commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-foreign",
                    eventId = foreignEvent,
                    draftId = draftId,
                ),
            )
            val missingAttach = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-missing",
                    eventId = 9_999L,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            val missingUnlink = commands.unlinkLink(
                agent,
                UnlinkLinkRequest(
                    idempotencyKey = "unlink-missing",
                    eventId = 9_999L,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            listOf(missing, outOfScope, missingAttach, missingUnlink).forEach { outcome ->
                assertTrue("the request was not refused: $outcome", outcome is WorkflowCommandOutcome.Denied)
                assertEquals(
                    WorkflowDenialReason.CALLER_SCOPE,
                    (outcome as WorkflowCommandOutcome.Denied).reason,
                )
            }
            // Nothing was written for any of them.
            assertTrue(database.contextLinkDao().findForDraft(draftId).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `a key another caller used is refused like an unused one`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val mine = saveCapture(commands, "save-note-mine", NOTE_TEXT)
            val foreign = saveCapture(commands, "save-note-foreign", NOTE_TEXT)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(mine),
                draftIds = emptySet(),
            )

            // The user's own request already used this key.
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "shared-key", bodyText = NOTE_TEXT),
            )

            val withUsedKey = commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "shared-key",
                    captureId = foreign,
                    expectedRevision = 0,
                    bodyText = "not yours",
                ),
            )
            val withUnusedKey = commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "unused-key",
                    captureId = foreign,
                    expectedRevision = 0,
                    bodyText = "not yours",
                ),
            )
            assertTrue("the used key was not refused: $withUsedKey", withUsedKey is WorkflowCommandOutcome.Denied)
            assertTrue("the unused key was not refused: $withUnusedKey", withUnusedKey is WorkflowCommandOutcome.Denied)
            // The same refusal either way: the use of another caller's key is not
            // observable to a caller that may not make the request at all.
            assertEquals(
                (withUsedKey as WorkflowCommandOutcome.Denied).reason,
                (withUnusedKey as WorkflowCommandOutcome.Denied).reason,
            )
            // The foreign capture is untouched.
            val stored = database.captureMessageDao().findById(foreign)!!
            assertEquals(NOTE_TEXT, stored.bodyText)
            assertEquals(0, stored.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a key another caller used is a conflict that names no record of theirs`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val mine = saveCapture(commands, "save-note-mine", NOTE_TEXT)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(mine),
                draftIds = emptySet(),
            )
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "shared-key", bodyText = NOTE_TEXT),
            )

            // The agent may act on its own capture, but the key belongs to the user's
            // request: the answer is a conflict that names neither the other caller nor
            // what that caller changed.
            val refused = commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "shared-key",
                    captureId = mine,
                    expectedRevision = 0,
                    bodyText = "another text",
                ),
            )
            assertTrue("the foreign key was not a conflict: $refused", refused is WorkflowCommandOutcome.Conflict)
            val reason = (refused as WorkflowCommandOutcome.Conflict).reason
            assertTrue("the conflict named the other caller: $reason", !reason.contains("user"))
            // The capture is untouched.
            val stored = database.captureMessageDao().findById(mine)!!
            assertEquals(NOTE_TEXT, stored.bodyText)
            assertEquals(0, stored.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an agent cannot attach a link the app cannot prove`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(
                commands,
                captureId,
                "event-1",
                span = "Circle K snack 20,000 at 15:15",
                amountVnd = 20_000L,
            )
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )

            // The reading states another amount than the draft: the app cannot prove that
            // this reading describes this draft, so the pair is the user's to resolve.
            commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            val refused = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the attach was not refused: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.ATTACH_POLICY,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.PROPOSED, link.state)

            // The user's own decision is not subject to that policy: the user may attach
            // the pair the app cannot prove.
            val attached = commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest(
                    idempotencyKey = "attach-2",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the user's own attach was refused: $attached", attached is WorkflowCommandOutcome.Applied)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an agent can attach a link the app proves`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )

            commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            // The evidence the proposal rests on is the app's own.
            val proposed = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertTrue(
                "the proposal carries no app evidence: ${proposed.evidence}",
                isTrustedLinkEvidence(proposed.evidence),
            )

            val attached = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the proven attach was refused: $attached", attached is WorkflowCommandOutcome.Applied)
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.ACCEPTED, link.state)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a reason somebody stated cannot make an agent attach a link`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )

            // A link row that carries a reason a caller wrote — what a request field used
            // to store — is not evidence the app computed, however plausible it reads.
            database.contextLinkDao().insert(
                ContextLinkEntity(
                    eventId = eventId,
                    draftId = draftId,
                    state = ContextLinkState.PROPOSED,
                    evidence = "same day, same amount",
                    revision = 0,
                    userOverride = null,
                    activeEventId = eventId,
                    createdAtEpochMillis = CAPTURE_TIME,
                ),
            )
            val refused = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the stated reason was not refused: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.ATTACH_POLICY,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.PROPOSED, link.state)
            assertEquals(0, link.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a proposal whose evidence moved on cannot be attached by an agent`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )
            commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )

            // The draft moves on: the evidence the proposal was made on is not the
            // evidence the app can read now.
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            val saved = reviewCommands(database, clock).saveReview(
                draftId = draftId,
                expectedRevision = draft.reviewRevision,
                edits = BankDraftReviewEdits(
                    amountText = draft.amountVnd.toString(),
                    dateText = draft.statedDate.orEmpty(),
                    timeText = draft.statedTime.orEmpty(),
                    memoText = draft.memo.orEmpty(),
                    type = null,
                    categoryId = null,
                    categoryResolved = false,
                ),
            )
            assertTrue("the review was not saved: $saved", saved is BankDraftReviewResult.Saved)

            val refused = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the stale evidence was not refused: $refused", refused is WorkflowCommandOutcome.Conflict)
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.PROPOSED, link.state)
            assertEquals(0, link.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the evidence of one pair does not attach another pair`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val provenId = storeEvent(commands, captureId, "event-1")
            val otherId = storeEvent(
                commands,
                captureId,
                "event-2",
                span = "Circle K snack 20,000 at 15:15",
                amountVnd = 20_000L,
            )
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )
            commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = provenId,
                    draftId = draftId,
                ),
            )
            commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-2",
                    eventId = otherId,
                    draftId = draftId,
                ),
            )

            // The other pair rests on its own evidence, which does not prove it.
            val refused = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = otherId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the other pair was attached: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.ATTACH_POLICY,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            assertEquals(
                ContextLinkState.PROPOSED,
                database.contextLinkDao().findByPair(otherId, draftId)!!.state,
            )
            // The proven pair is untouched by the refused one.
            assertEquals(
                ContextLinkState.PROPOSED,
                database.contextLinkDao().findByPair(provenId, draftId)!!.state,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `a refusal of a link is the user's own and stays refused`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )

            val refused = commands.rejectLink(
                agent,
                RejectLinkRequest(
                    idempotencyKey = "reject-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the agent's refusal was not refused: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.USER_DECISION_REQUIRED,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )

            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            val rejected = commands.rejectLink(
                WorkflowCallerScope.User,
                RejectLinkRequest(
                    idempotencyKey = "reject-2",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                    userOverride = "USER_REJECTED",
                ),
            )
            assertTrue("the refusal was not applied: $rejected", rejected is WorkflowCommandOutcome.Applied)

            // The refused pair is never recreated, by anyone.
            val again = commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-2",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            assertTrue("the refused pair was recreated: $again", again is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.LINK_ALREADY_REJECTED,
                (again as WorkflowCommandOutcome.Denied).reason,
            )
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.REJECTED, link.state)
            assertEquals("USER_REJECTED", link.userOverride)
        } finally {
            database.close()
        }
    }

    @Test
    fun `unlinking restores the event context and keeps the bank evidence`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)

            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertEquals(NoteEventStatus.INTERPRETED, database.noteEventDao().findById(eventId)!!.status)

            val unlinked = commands.unlinkLink(
                WorkflowCallerScope.User,
                UnlinkLinkRequest(
                    idempotencyKey = "unlink-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 1,
                ),
            )
            assertTrue("the unlink was refused: $unlinked", unlinked is WorkflowCommandOutcome.Applied)

            // The event keeps its own reading and holds no draft any more.
            val event = database.noteEventDao().findById(eventId)!!
            assertEquals("Fuel 50,000 at 15:00", event.normalizedText)
            assertEquals(NoteEventStatus.INTERPRETED, event.status)
            assertTrue(database.contextLinkDao().findByPair(eventId, draftId) == null)

            // The bank evidence is untouched: the draft and its parsed fields are as they were.
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            assertEquals(IntakeReviewStatus.PENDING, draft.reviewStatus)
            assertEquals(BANK_MEMO, draft.memo)
            assertEquals(50_000L, draft.amountVnd)
            assertNull(draft.confirmedTransactionId)
        } finally {
            database.close()
        }
    }

    // --- The ledger is untouched --------------------------------------------

    @Test
    fun `the preparation commands never change the confirmed ledger`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val ledger = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
                clock = clock,
            )
            val categoryId = ledger.addCategory("Food")
            ledger.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 120_000L,
                    occurredAtEpochMillis = CAPTURE_TIME,
                    description = "Lunch",
                    categoryId = categoryId,
                ),
            )
            val before = WorkflowQueryService(database).confirmedLedgerAggregate(
                LedgerDateRange(
                    fromEpochMillis = 0L,
                    throughExclusiveEpochMillis = CAPTURE_TIME + 1_000L,
                    zoneId = CAPTURE_ZONE,
                ),
            )

            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            val after = WorkflowQueryService(database).confirmedLedgerAggregate(
                LedgerDateRange(
                    fromEpochMillis = 0L,
                    throughExclusiveEpochMillis = CAPTURE_TIME + 1_000L,
                    zoneId = CAPTURE_ZONE,
                ),
            )
            assertEquals(before.spending.totalVnd, after.spending.totalVnd)
            assertEquals(before.spending.recordCount, after.spending.recordCount)
            assertEquals(120_000L, after.spending.totalVnd)
            assertEquals(before.excludedDraftCount, after.excludedDraftCount)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the recorded operation holds the request's identity and not its text`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
            )

            val operations = WorkflowQueryService(database).workflowOperations(
                WorkflowOperationQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)),
            )
            assertEquals(1, operations.items.size)
            val operation = operations.items.single()
            assertEquals("save-note-1", operation.idempotencyKey)
            assertEquals(WorkflowCallerScope.User.scopeId, operation.scopeId)
            assertEquals("SAVE_CAPTURE", operation.command)
            assertTrue(operation.change is WorkflowChange.CaptureSaved)
            // The record names the request; it does not hold the text it was about.
            assertTrue(
                "the operation record holds the request's text",
                operation.change.toString().contains("Fuel").not(),
            )
        } finally {
            database.close()
        }
    }

    // --- Fixtures ------------------------------------------------------------

    private suspend fun saveCapture(
        commands: WorkflowCommandService,
        key: String,
        text: String,
    ): Long {
        val outcome = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest(idempotencyKey = key, bodyText = text),
        )
        assertTrue("the capture was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)
        return (outcome as WorkflowCommandOutcome.Applied).change.let {
            (it as WorkflowChange.CaptureSaved).captureId
        }
    }

    private suspend fun storeEvent(
        commands: WorkflowCommandService,
        captureId: Long,
        key: String,
        span: String = "Fuel 50,000 at 15:00",
        amountVnd: Long? = 50_000L,
        anchorDate: String? = DRAFT_DATE,
    ): Long {
        val outcome = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = key,
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(span, span, amountVnd, anchorDate),
                extractionVersion = EXTRACTION_VERSION,
            ),
        )
        val change = when (outcome) {
            is WorkflowCommandOutcome.Applied -> outcome.change
            is WorkflowCommandOutcome.NoChange -> outcome.change
            else -> error("the reading was refused: $outcome")
        }
        return when (change) {
            is WorkflowChange.EventStored -> change.eventId
            is WorkflowChange.EventRevised -> change.eventId
            else -> error("the reading was not stored: $change")
        }
    }

    private fun reading(
        span: String,
        normalizedText: String,
        amountVnd: Long? = 50_000L,
        anchorDate: String? = DRAFT_DATE,
    ) = NoteEventInterpretation(
        spanStart = NOTE_TEXT.indexOf(span),
        spanEnd = NOTE_TEXT.indexOf(span) + span.length,
        normalizedText = normalizedText,
        amountVnd = amountVnd,
        merchant = "Petrol",
        timeAnchor = anchorDate?.let { NoteEventTimeAnchor(date = it, time = "15:00") },
    )

    private suspend fun storeDraft(
        database: LedgerDatabase,
        key: String,
        amountVnd: Long,
    ): Long {
        val postedAt = POSTED_AT + key.length
        return database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = draftDeliveryId(BANK_PACKAGE, key, postedAt),
                sourcePackage = BANK_PACKAGE,
                notificationKey = key,
                postedAtEpochMillis = postedAt,
                bank = "VietinBank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = amountVnd,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-10-16",
                statedTime = "07:38",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = BANK_MEMO,
                memoUnresolvedReason = null,
                createdAtEpochMillis = clock.nowEpochMillis(),
            ),
        )
    }

    private data class LinkedCaptureFixture(
        val captureId: Long,
        val acceptedEventId: Long,
        val rejectedEventId: Long,
    )

    private suspend fun linkedCaptureFixture(
        commands: WorkflowCommandService,
        database: LedgerDatabase,
        key: String,
    ): LinkedCaptureFixture {
        val captureId = saveCapture(commands, "$key-save", NOTE_TEXT)
        val acceptedEventId = storeEvent(commands, captureId, "$key-fuel-event")
        val rejectedEventId = storeEvent(
            commands = commands,
            captureId = captureId,
            key = "$key-snack-event",
            span = "Circle K snack 20,000 at 15:15",
            amountVnd = 20_000L,
        )
        val acceptedDraftId = storeDraft(database, "$key-fuel-draft", 50_000L)
        val rejectedDraftId = storeDraft(database, "$key-snack-draft", 20_000L)

        val acceptedProposal = commands.proposeLink(
            WorkflowCallerScope.User,
            ProposeLinkRequest(
                idempotencyKey = "$key-fuel-proposal",
                eventId = acceptedEventId,
                draftId = acceptedDraftId,
            ),
        )
        assertTrue(
            "the fixture link was not proposed: $acceptedProposal",
            acceptedProposal is WorkflowCommandOutcome.Applied,
        )
        val acceptedLink = database.contextLinkDao()
            .findByPair(acceptedEventId, acceptedDraftId)!!
        val attached = commands.attachLink(
            WorkflowCallerScope.User,
            AttachLinkRequest(
                idempotencyKey = "$key-fuel-attach",
                eventId = acceptedEventId,
                draftId = acceptedDraftId,
                expectedRevision = acceptedLink.revision,
            ),
        )
        assertTrue(
            "the fixture link was not accepted: $attached",
            attached is WorkflowCommandOutcome.Applied,
        )

        val rejectedProposal = commands.proposeLink(
            WorkflowCallerScope.User,
            ProposeLinkRequest(
                idempotencyKey = "$key-snack-proposal",
                eventId = rejectedEventId,
                draftId = rejectedDraftId,
            ),
        )
        assertTrue(
            "the refused fixture link was not proposed: $rejectedProposal",
            rejectedProposal is WorkflowCommandOutcome.Applied,
        )
        val rejectedLink = database.contextLinkDao()
            .findByPair(rejectedEventId, rejectedDraftId)!!
        val rejected = commands.rejectLink(
            WorkflowCallerScope.User,
            RejectLinkRequest(
                idempotencyKey = "$key-snack-reject",
                eventId = rejectedEventId,
                draftId = rejectedDraftId,
                expectedRevision = rejectedLink.revision,
            ),
        )
        assertTrue(
            "the fixture refusal was not stored: $rejected",
            rejected is WorkflowCommandOutcome.Applied,
        )
        assertEquals(
            ContextLinkState.REJECTED,
            database.contextLinkDao().findForEvent(rejectedEventId).single().state,
        )

        return LinkedCaptureFixture(
            captureId = captureId,
            acceptedEventId = acceptedEventId,
            rejectedEventId = rejectedEventId,
        )
    }

    @Test
    fun `editing linked context invalidates an in flight category choice`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val fixture = linkedCaptureFixture(commands, database, "category-context")
            val draftId = database.contextLinkDao().findForEvent(fixture.acceptedEventId).single().draftId
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            val categoryId = database.categoryDao().insert(
                CategoryEntity(name = "Fuel", createdAtEpochMillis = CAPTURE_TIME, updatedAtEpochMillis = CAPTURE_TIME),
            )
            val query = WorkflowQueryService(database)
            val contextRevision = query.draftContextSnapshot(draftId).second
            val catalogRevision = categoryCatalogRevision(query.categoryCatalog())
            val capture = database.captureMessageDao().findById(fixture.captureId)!!
            val revised = commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest("category-context-edit", fixture.captureId, capture.revision, "Changed note"),
            )
            assertTrue(revised is WorkflowCommandOutcome.Applied)
            val outcome = commands.selectDraftCategory(
                WorkflowCallerScope.AgentSession("category-context", setOf(fixture.captureId), setOf(draftId)),
                SelectDraftCategoryRequest(
                    "category-context-write", draftId, draft.reviewRevision, categoryId,
                    contextRevision, catalogRevision,
                ),
            )
            assertTrue(outcome is WorkflowCommandOutcome.Conflict)
            assertNull(database.pendingBankDraftDao().findById(draftId)!!.reviewCategoryId)
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `unlinking context invalidates an in flight category choice`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val fixture = linkedCaptureFixture(commands, database, "category-unlink")
            val link = database.contextLinkDao().findForEvent(fixture.acceptedEventId).single()
            val draft = database.pendingBankDraftDao().findById(link.draftId)!!
            val categoryId = database.categoryDao().insert(
                CategoryEntity(name = "Fuel", createdAtEpochMillis = CAPTURE_TIME, updatedAtEpochMillis = CAPTURE_TIME),
            )
            val query = WorkflowQueryService(database)
            val contextRevision = query.draftContextSnapshot(draft.id).second
            val catalogRevision = categoryCatalogRevision(query.categoryCatalog())
            val unlink = commands.unlinkLink(
                WorkflowCallerScope.User,
                UnlinkLinkRequest("category-unlink-action", fixture.acceptedEventId, draft.id, link.revision),
            )
            assertTrue(unlink is WorkflowCommandOutcome.Applied)
            val outcome = commands.selectDraftCategory(
                WorkflowCallerScope.AgentSession("category-unlink", setOf(fixture.captureId), setOf(draft.id)),
                SelectDraftCategoryRequest(
                    "category-unlink-write", draft.id, draft.reviewRevision, categoryId,
                    contextRevision, catalogRevision,
                ),
            )
            assertTrue(outcome is WorkflowCommandOutcome.Conflict)
            assertNull(database.pendingBankDraftDao().findById(draft.id)!!.reviewCategoryId)
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `newer user category is not overwritten by an older hosted choice`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database, "user-category-race", 50_000L)
            val fuel = database.categoryDao().insert(
                CategoryEntity(name = "Fuel", createdAtEpochMillis = CAPTURE_TIME, updatedAtEpochMillis = CAPTURE_TIME),
            )
            val food = database.categoryDao().insert(
                CategoryEntity(name = "Food", createdAtEpochMillis = CAPTURE_TIME, updatedAtEpochMillis = CAPTURE_TIME),
            )
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            val query = WorkflowQueryService(database)
            val context = query.draftContextSnapshot(draftId).second
            val catalog = categoryCatalogRevision(query.categoryCatalog())
            val userChoice = commands.selectDraftCategory(
                WorkflowCallerScope.User,
                SelectDraftCategoryRequest("user-category", draftId, draft.reviewRevision, food),
            )
            assertTrue(userChoice is WorkflowCommandOutcome.Applied)
            val oldChoice = commands.selectDraftCategory(
                WorkflowCallerScope.AgentSession("category-race", emptySet(), setOf(draftId)),
                SelectDraftCategoryRequest("old-agent-category", draftId, draft.reviewRevision, fuel, context, catalog),
            )
            assertTrue(oldChoice is WorkflowCommandOutcome.Conflict)
            assertEquals(food, database.pendingBankDraftDao().findById(draftId)!!.reviewCategoryId)
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `the hosted workflow saves an existing category without confirming the draft`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database, "hosted-category", 50_000L)
            val categoryId = database.categoryDao().insert(
                CategoryEntity(
                    name = "Foods",
                    createdAtEpochMillis = CAPTURE_TIME,
                    updatedAtEpochMillis = CAPTURE_TIME,
                ),
            )
            val stored = database.pendingBankDraftDao().findById(draftId)!!
            val scope = WorkflowCallerScope.AgentSession(
                scopeId = "hosted-category-session",
                captureIds = emptySet(),
                draftIds = setOf(draftId),
            )
            val queries = WorkflowQueryService(database)
            val contextRevision = queries.draftContextSnapshot(draftId).second
            val catalogRevision = categoryCatalogRevision(queries.categoryCatalog())

            val applied = commands.selectDraftCategory(
                scope = scope,
                request = SelectDraftCategoryRequest(
                    idempotencyKey = "category-1",
                    draftId = draftId,
                    expectedReviewRevision = stored.reviewRevision,
                    categoryId = categoryId,
                    expectedContextRevision = contextRevision,
                    expectedCatalogRevision = catalogRevision,
                ),
            )
            assertTrue("the category was not stored: $applied", applied is WorkflowCommandOutcome.Applied)
            assertTrue((applied as WorkflowCommandOutcome.Applied).change is WorkflowChange.DraftCategorySelected)

            val after = database.pendingBankDraftDao().findById(draftId)!!
            assertEquals(IntakeReviewStatus.PENDING, after.reviewStatus)
            assertEquals(categoryId, after.reviewCategoryId)
            assertTrue(after.reviewCategoryResolved)
            assertEquals(stored.reviewRevision + 1, after.reviewRevision)
            assertNull(after.confirmedTransactionId)
            assertTrue("the ledger stays untouched", database.transactionDao().findAll().isEmpty())

            // A stale review revision is a conflict and changes nothing.
            val stale = commands.selectDraftCategory(
                scope = scope,
                request = SelectDraftCategoryRequest(
                    idempotencyKey = "category-2",
                    draftId = draftId,
                    expectedReviewRevision = stored.reviewRevision,
                    categoryId = categoryId,
                    expectedContextRevision = contextRevision,
                    expectedCatalogRevision = catalogRevision,
                ),
            )
            assertEquals(
                WorkflowCommandOutcome.Conflict("The draft changed under the caller."),
                stale,
            )

            // An archived category is refused and leaves the stored choice in place.
            val archived = commands.archiveCategory(
                scope = WorkflowCallerScope.User,
                request = ArchiveCategoryRequest("archive-1", categoryId),
            )
            assertTrue("the category was not archived: $archived", archived is WorkflowCommandOutcome.Applied)
            val refused = commands.selectDraftCategory(
                scope = scope,
                request = SelectDraftCategoryRequest(
                    idempotencyKey = "category-3",
                    draftId = draftId,
                    expectedReviewRevision = after.reviewRevision,
                    categoryId = categoryId,
                    expectedContextRevision = contextRevision,
                    expectedCatalogRevision = categoryCatalogRevision(queries.categoryCatalog()),
                ),
            )
            assertEquals(
                WorkflowCommandOutcome.Denied(WorkflowDenialReason.CATEGORY_UNAVAILABLE),
                refused,
            )
            val unchanged = database.pendingBankDraftDao().findById(draftId)!!
            assertEquals(after.reviewRevision, unchanged.reviewRevision)
            assertEquals(categoryId, unchanged.reviewCategoryId)
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    private companion object {
        const val BANK_PACKAGE = "com.example.bank"
        const val BANK_MEMO = "Circle K store"
        const val CAPTURE_ZONE = "Asia/Bangkok"
        const val CAPTURE_TIME = 1_797_000_000_000L
        const val POSTED_AT = 1_797_000_000_000L
        const val EXTRACTION_VERSION = "extract-v1"
        const val FOOD_CATEGORY_ID = 1L
        const val NOTE_TEXT = "Fuel 50,000 at 15:00; Circle K snack 20,000 at 15:15"

        /** The calendar day the fixture's bank draft states. */
        const val DRAFT_DATE = "2026-10-16"
    }
}
