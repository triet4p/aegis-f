package io.github.triet4p.aegisf.data

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists and resumes bounded matching work caused by an interpreted note or a bank draft.
 *
 * The arrival row and its operation identity are inserted in the same Room transaction as the
 * event or draft. Each processing page and its resulting link decision commit together, so a
 * process death leaves either a durable pending operation or its complete result, never a link
 * without the operation that produced it.
 */
internal class ArrivalReconciliationCoordinator(
    private val database: LedgerDatabase,
    private val clock: LedgerClock = SystemLedgerClock,
) {
    private val operationDao = database.arrivalReconciliationOperationDao()
    private val eventDao = database.noteEventDao()
    private val captureDao = database.captureMessageDao()
    private val draftDao = database.pendingBankDraftDao()
    private val linkDao = database.contextLinkDao()
    private val events = EventContextRepository(database, clock)
    private val queries = WorkflowQueryService(database)
    private val attachmentPolicy = AutomaticAttachmentPolicy(database)
    private val processor = Mutex()

    /** Call inside the transaction that stored or revised this event. */
    internal suspend fun enqueueEvent(
        eventId: Long,
        expectedEventRevision: Int,
        expectedCaptureRevision: Int,
    ) {
        operationDao.insertIfAbsent(
            ArrivalReconciliationOperationEntity(
                operationId = eventOperationId(eventId, expectedEventRevision),
                triggerKind = ARRIVAL_TRIGGER_EVENT,
                eventId = eventId,
                expectedEventRevision = expectedEventRevision,
                expectedCaptureRevision = expectedCaptureRevision,
                draftId = null,
                expectedDraftReviewRevision = null,
                cursorEventId = 0L,
                state = ARRIVAL_OPERATION_PENDING,
                outcome = null,
                createdAtEpochMillis = clock.nowEpochMillis(),
            ),
        )
    }

    /** Call inside the transaction that stored or replayed this notification draft. */
    internal suspend fun enqueueDraft(draftId: Long, expectedDraftReviewRevision: Int) {
        operationDao.insertIfAbsent(
            ArrivalReconciliationOperationEntity(
                operationId = draftOperationId(draftId),
                triggerKind = ARRIVAL_TRIGGER_DRAFT,
                eventId = null,
                expectedEventRevision = null,
                expectedCaptureRevision = null,
                draftId = draftId,
                expectedDraftReviewRevision = expectedDraftReviewRevision,
                cursorEventId = 0L,
                state = ARRIVAL_OPERATION_PENDING,
                outcome = null,
                createdAtEpochMillis = clock.nowEpochMillis(),
            ),
        )
    }

    /** Resumes pending operations after an arrival or after the application process restarts. */
    internal suspend fun resumePending() = processor.withLock {
        while (true) {
            val pending = operationDao.findPending(MAX_OPERATION_PAGE)
            if (pending.isEmpty()) return@withLock
            for (operation in pending) {
                process(operation.operationId)
            }
        }
    }

    private suspend fun process(operationId: String) {
        database.withTransaction {
            val operation = operationDao.findById(operationId) ?: return@withTransaction
            if (operation.state != ARRIVAL_OPERATION_PENDING) return@withTransaction
            when (operation.triggerKind) {
                ARRIVAL_TRIGGER_EVENT -> processEvent(operation)
                ARRIVAL_TRIGGER_DRAFT -> processDraftPage(operation)
                else -> error("Unknown arrival reconciliation trigger ${operation.triggerKind}.")
            }
        }
    }

    private suspend fun processEvent(operation: ArrivalReconciliationOperationEntity) {
        val eventId = requireNotNull(operation.eventId)
        val expectedEventRevision = requireNotNull(operation.expectedEventRevision)
        val expectedCaptureRevision = requireNotNull(operation.expectedCaptureRevision)
        val event = eventDao.findById(eventId)
        if (event == null || event.revision != expectedEventRevision) {
            check(
                operationDao.complete(
                    operationId = operation.operationId,
                    outcome = ARRIVAL_OUTCOME_STALE,
                    linkedAny = operation.linkedAny,
                    incompleteAny = operation.incompleteAny,
                ) == 1,
            )
            return
        }
        completeEventOperation(
            operation = operation,
            event = event,
            expectedCaptureRevision = expectedCaptureRevision,
        )
    }

    private suspend fun completeEventOperation(
        operation: ArrivalReconciliationOperationEntity,
        event: NoteEventEntity,
        expectedCaptureRevision: Int,
    ) {
        val outcome = reconcileEvent(
            event = event,
            expectedEventRevision = event.revision,
            expectedCaptureRevision = expectedCaptureRevision,
        )
        check(
            operationDao.complete(
                operationId = operation.operationId,
                outcome = outcome,
                linkedAny = outcome == ARRIVAL_OUTCOME_LINKED,
                incompleteAny = outcome == ARRIVAL_OUTCOME_INCOMPLETE,
            ) == 1,
        ) {
            "Arrival event operation ${operation.operationId} changed while it was processed."
        }
    }

    /** One fixed-size page is committed with its cursor; later pages resume from that identity. */
    private suspend fun processDraftPage(operation: ArrivalReconciliationOperationEntity) {
        val draftId = requireNotNull(operation.draftId)
        val expectedDraftReviewRevision = requireNotNull(operation.expectedDraftReviewRevision)
        val draft = draftDao.findById(draftId)
        if (draft == null || draft.reviewRevision != expectedDraftReviewRevision ||
            draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null
        ) {
            check(
                operationDao.complete(
                    operationId = operation.operationId,
                    outcome = ARRIVAL_OUTCOME_STALE,
                    linkedAny = operation.linkedAny,
                    incompleteAny = operation.incompleteAny,
                ) == 1,
            )
            return
        }

        val page = eventDao.findUnmatchedForReconciliation(
            afterEventId = operation.cursorEventId,
            limit = MAX_EVENT_PAGE + 1,
        )
        val hasMore = page.size > MAX_EVENT_PAGE
        val batchSize = minOf(page.size, MAX_EVENT_PAGE)
        var linked = false
        var incomplete = false
        for (index in 0 until batchSize) {
            val event = page[index]
            when (
                reconcileEvent(
                    event = event,
                    expectedEventRevision = event.revision,
                    expectedCaptureRevision = event.sourceRevision,
                )
            ) {
                ARRIVAL_OUTCOME_LINKED -> linked = true
                ARRIVAL_OUTCOME_INCOMPLETE -> incomplete = true
            }
        }
        val linkedAny = operation.linkedAny || linked
        val incompleteAny = operation.incompleteAny || incomplete

        if (hasMore) {
            val nextCursor = page[MAX_EVENT_PAGE - 1].id
            check(
                operationDao.advanceDraftCursor(
                    operationId = operation.operationId,
                    expectedCursorEventId = operation.cursorEventId,
                    nextEventId = nextCursor,
                    linkedAny = linkedAny,
                    incompleteAny = incompleteAny,
                ) == 1,
            ) { "Arrival draft operation ${operation.operationId} lost its page cursor." }
        } else {
            val outcome = when {
                linkedAny -> ARRIVAL_OUTCOME_LINKED
                incompleteAny -> ARRIVAL_OUTCOME_INCOMPLETE
                else -> ARRIVAL_OUTCOME_NO_MATCH
            }
            check(
                operationDao.complete(
                    operationId = operation.operationId,
                    outcome = outcome,
                    linkedAny = linkedAny,
                    incompleteAny = incompleteAny,
                ) == 1,
            ) {
                "Arrival draft operation ${operation.operationId} changed while it was processed."
            }
        }
    }

    private suspend fun reconcileEvent(
        event: NoteEventEntity,
        expectedEventRevision: Int,
        expectedCaptureRevision: Int,
    ): String {
        val current = eventDao.findById(event.id) ?: return ARRIVAL_OUTCOME_STALE
        if (current.revision != expectedEventRevision) return ARRIVAL_OUTCOME_STALE
        val capture = captureDao.findById(current.captureId) ?: return ARRIVAL_OUTCOME_STALE
        if (capture.tombstone || capture.revision != expectedCaptureRevision ||
            current.sourceRevision != expectedCaptureRevision || capture.bodyText == null
        ) {
            return ARRIVAL_OUTCOME_STALE
        }
        if (current.normalizedText == null || current.unresolvedReason != null ||
            current.resolvedTransactionId != null ||
            linkDao.findActiveForEvent(current.id).isNotEmpty()
        ) {
            return ARRIVAL_OUTCOME_NO_MATCH
        }

        val pendingPage = queries.draftCandidates(
            DraftCandidateQuery(
                reviewStatus = IntakeReviewStatus.PENDING,
                bounds = QueryPageRequest(limit = QueryPageRequest.MAX_PAGE_SIZE),
            ),
        )
        if (pendingPage.hasMore) return ARRIVAL_OUTCOME_INCOMPLETE
        val authorizedDraftIds = pendingPage.items.mapTo(linkedSetOf()) { draft -> draft.id }
        if (authorizedDraftIds.isEmpty()) return ARRIVAL_OUTCOME_NO_MATCH

        for (draft in pendingPage.items) {
            if (draft.amountVnd != current.amountVnd || draft.statedDate != current.anchorDate) continue
            val evidence = attachmentPolicy.trustedEvidence(
                event = current,
                draft = draft,
                authorizedDraftIds = authorizedDraftIds,
                pendingDatabaseComplete = true,
            ) ?: continue
            if (evidence.eventRevision != current.revision ||
                evidence.captureRevision != capture.revision ||
                evidence.draftReviewRevision != draft.reviewRevision
            ) {
                return ARRIVAL_OUTCOME_STALE
            }
            if (linkDao.findActiveForEvent(current.id).isNotEmpty()) return ARRIVAL_OUTCOME_NO_MATCH
            when (val proposed = events.proposeLink(current.id, draft.id)) {
                is ContextLinkResult.Linked -> {
                    if (proposed.state != ContextLinkState.PROPOSED) continue
                    when (
                        val accepted = events.acceptLink(
                            eventId = current.id,
                            draftId = draft.id,
                            expectedRevision = proposed.revision,
                        )
                    ) {
                        is ContextLinkResult.Linked ->
                            if (accepted.state == ContextLinkState.ACCEPTED) {
                                return ARRIVAL_OUTCOME_LINKED
                            }
                        else -> continue
                    }
                }
                else -> continue
            }
        }
        return ARRIVAL_OUTCOME_NO_MATCH
    }

    private companion object {
        const val MAX_OPERATION_PAGE = 32
        const val MAX_EVENT_PAGE = 32

        fun eventOperationId(eventId: Long, revision: Int): String = "event:$eventId:$revision"
        fun draftOperationId(draftId: Long): String = "draft:$draftId"
    }
}
