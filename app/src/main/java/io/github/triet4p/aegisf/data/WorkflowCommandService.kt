package io.github.triet4p.aegisf.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import io.github.triet4p.aegisf.ui.ManualDateTime
import java.util.TimeZone

/**
 * The app's one write surface for the reversible preparation commands.
 *
 * Every preparation the app can make — saving a capture, revising its text, reading a
 * span of it, proposing, attaching, refusing, or removing the link between an event and a
 * bank draft, and saving the category the hosted workflow chose for a draft — goes through
 * this service.
 * It is the only public way to make those changes: the repositories that store them are
 * internal, so no caller can write around the boundary.
 *
 * What the boundary guarantees:
 *
 * - **The caller decides what may be changed.** Every command takes a
 *   [WorkflowCallerScope] that the trusted application code supplied. A scope is never
 *   read from a request, a model, or the stored data. A target outside the scope is
 *   refused before anything is read or written, and the refusal is the same whether or
 *   not the row it names exists, so the existence of a row the caller may not touch is
 *   not observable.
 * - **A repeated request changes nothing.** Every command carries an idempotency key,
 *   and the key is recorded durably with the outcome. The same key, from the same
 *   caller, with the same request, is answered from that record: the state is not
 *   changed a second time, whatever the number of attempts, the number of processes, or
 *   the number of callers that race for it. The same key with a different request, or
 *   from a different caller, is a conflict — and it is that conflict whether the
 *   request arrives before or after the durable record of the key was written.
 * - **A stale request changes nothing.** A command that names the revision it read is
 *   refused when the row has moved on, instead of overwriting what the caller never saw.
 * - **A refusal changes nothing.** A denied, conflicting, or unanswerable request writes
 *   no row, touches no revision, and leaves no record of the request itself behind.
 * - **One transaction.** The change a command makes and the record of that change are
 *   written in the same transaction, so a command is either fully applied and recorded
 *   or not applied at all.
 * - **A category choice is review state.** The hosted workflow may save one existing,
 *   assignable category on a still-pending draft in its own scope; the update touches no
 *   other review field, writes no ledger record, and never confirms the draft. Only the
 *   user's own confirmation turns a draft into money.
 * - **Preparation and the user's own financial decisions.** The preparation commands are
 *   open to the preparation callers; the financial commands — the manual record, the
 *   correction, the removal, the review edits, the confirmation of a bank draft, and its
 *   dismissal — are the user's alone, and another caller is refused before anything is
 *   read or written.
 */
class WorkflowCommandService(
    private val database: LedgerDatabase,
    private val clock: LedgerClock = SystemLedgerClock,
    private val timezoneId: () -> String = { TimeZone.getDefault().id },
) {

    private val captureDao = database.captureMessageDao()
    private val eventDao = database.noteEventDao()
    private val linkDao = database.contextLinkDao()
    private val draftDao = database.pendingBankDraftDao()
    private val operations = database.workflowOperationDao()
    private val transactionDao = database.transactionDao()
    private val categoryDao = database.categoryDao()
    private val reviewDao = database.bankDraftReviewDao()
    private val captures = CaptureMessageRepository(database = database, clock = clock, timezoneId = timezoneId)
    private val events = EventContextRepository(database = database, clock = clock)
    /** The app's own evidence about a proposed association; never read from a request. */
    private val linkEvidence = LinkEvidencePolicy(database)
    private val arrivalReconciliation = ArrivalReconciliationCoordinator(database, clock)
    private val automaticAttachmentPolicy = AutomaticAttachmentPolicy(database)
    /** The app's rules for the fields a financial write stores; one rule, one place. */
    private val rules = FinancialFieldRules(categoryDao)
    /** The ledger's own writes; they are reachable only through this boundary. */
    private val ledger = LedgerRepository(
        categoryDao = categoryDao,
        transactionDao = transactionDao,
        rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
        captureMessageDao = captureDao,
        clock = clock,
    )

    /** Resumes durable arrival work left pending by a process interruption. */
    internal suspend fun resumePendingArrivalReconciliation() {
        arrivalReconciliation.resumePending()
    }

    // --- Captures -----------------------------------------------------------

    /**
     * Stores one user-authored capture.
     *
     * The capture is a new row of its own: it is independent of any bank notification and
     * of any ledger record, and it holds the text the user saved and nothing else. A
     * repeated request under the same key stores one capture, not two.
     */
    suspend fun saveCapture(
        scope: WorkflowCallerScope,
        request: SaveCaptureRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_SAVE_CAPTURE,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(COMMAND_SAVE_CAPTURE, request.captureKind.name, request.bodyText),
        authorize = { null },
    ) {
        if (request.bodyText.isBlank()) {
            return@runCommand WorkflowCommandOutcome.NeedsClarification(
                "bodyText",
                "A saved capture needs text; an empty capture is not stored.",
            )
        }
        val captureId = when (request.captureKind) {
            UserCaptureKind.QUICK_CAPTURE -> captures.saveQuickCapture(request.bodyText)
            UserCaptureKind.MANUAL_ANNOTATION -> captures.saveManualAnnotation(request.bodyText)
        }
        WorkflowCommandOutcome.Applied(WorkflowChange.CaptureSaved(captureId, 0))
    }

    /**
     * Replaces the text of one capture.
     *
     * The revision the caller read is part of the request: when the capture has moved on,
     * the request is refused as a conflict. Replacing the text ends what was read from
     * it — the readings of that capture lose their derived content and the links that
     * described them go with them — in the same transaction as the replacement.
     */
    suspend fun reviseCapture(
        scope: WorkflowCallerScope,
        request: ReviseCaptureRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_REVISE_CAPTURE,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_REVISE_CAPTURE,
            request.captureId.toString(),
            request.expectedRevision.toString(),
            request.bodyText,
        ),
        authorize = { authorizeCapture(scope, request.captureId) },
    ) {
        if (request.bodyText.isBlank()) {
            return@runCommand WorkflowCommandOutcome.NeedsClarification(
                "bodyText",
                "A capture needs text; an empty capture is not stored.",
            )
        }
        val current = captureDao.findById(request.captureId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        captureWriteEligibility(current)?.let { refusal ->
            return@runCommand refusal.toOutcome()
        }
        if (current.revision != request.expectedRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict(
                "The capture changed under the caller.",
            )
        }
        if (current.bodyText == request.bodyText) {
            return@runCommand WorkflowCommandOutcome.NoChange(
                change = WorkflowChange.CaptureRevised(current.id, current.revision),
                detail = "The capture already holds this text.",
            )
        }
        when (val result = captures.revise(current.id, request.expectedRevision, request.bodyText)) {
            is CaptureMessageWriteResult.Saved -> WorkflowCommandOutcome.Applied(
                WorkflowChange.CaptureRevised(current.id, result.revision),
            )
            is CaptureMessageWriteResult.Deleted -> WorkflowCommandOutcome.Conflict(
                "The capture was deleted under the caller.",
            )
            CaptureMessageWriteResult.NotFound -> WorkflowCommandOutcome.NotFound
            CaptureMessageWriteResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The capture changed under the caller.",
            )
            CaptureMessageWriteResult.AlreadyDeleted -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.ALREADY_DELETED,
            )
            CaptureMessageWriteResult.ImmutableSource -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.IMMUTABLE_SOURCE,
            )
        }
    }

    /**
     * Deletes the text of one user-authored capture without deleting its source identity.
     *
     * The capture's revision is part of the request, and the repository applies the
     * existing source-edit policy atomically: derived readings are cleared, active links
     * are removed, refused links remain, and no ledger record is changed.
     */
    suspend fun deleteCapture(
        scope: WorkflowCallerScope,
        request: DeleteCaptureRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_DELETE_CAPTURE,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_DELETE_CAPTURE,
            request.captureId.toString(),
            request.expectedRevision.toString(),
        ),
        authorize = { authorizeCapture(scope, request.captureId) },
    ) {
        val current = captureDao.findById(request.captureId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        captureWriteEligibility(current)?.let { refusal ->
            return@runCommand refusal.toOutcome()
        }
        if (current.revision != request.expectedRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict(
                "The capture changed under the caller.",
            )
        }
        when (val result = captures.delete(current.id, request.expectedRevision)) {
            is CaptureMessageWriteResult.Deleted -> WorkflowCommandOutcome.Applied(
                WorkflowChange.CaptureDeleted(current.id, result.revision),
            )
            is CaptureMessageWriteResult.Saved -> WorkflowCommandOutcome.Conflict(
                "The capture changed under the caller.",
            )
            CaptureMessageWriteResult.NotFound -> WorkflowCommandOutcome.NotFound
            CaptureMessageWriteResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The capture changed under the caller.",
            )
            CaptureMessageWriteResult.AlreadyDeleted -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.ALREADY_DELETED,
            )
            CaptureMessageWriteResult.ImmutableSource -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.IMMUTABLE_SOURCE,
            )
        }
    }

    // --- Event interpretations ----------------------------------------------

    /**
     * Stores or revises the reading of one span of one capture.
     *
     * An edit is compare-and-set against both the capture revision and, when supplied, the
     * interpretation revision. A stale caller is refused without overwriting newer input.
     * A span outside the current text, a deleted capture, an unusable amount, and a
     * malformed time are also refused. One span of one capture holds one reading: a
     * repeated extraction of the same span addresses the same event instead of appending
     * a second copy.
     */
    suspend fun updateEventInterpretation(
        scope: WorkflowCallerScope,
        request: UpdateEventRequest,
    ): WorkflowCommandOutcome {
        val outcome = runCommand(
            scope = scope,
            command = COMMAND_UPDATE_EVENT,
            idempotencyKey = request.idempotencyKey,
            payload = listOf(
                COMMAND_UPDATE_EVENT,
                request.captureId.toString(),
                request.expectedCaptureRevision.toString(),
                request.expectedEventRevision?.toString(),
                request.extractionVersion,
                request.interpretation.spanStart.toString(),
                request.interpretation.spanEnd.toString(),
                request.interpretation.normalizedText,
                request.interpretation.amountVnd?.toString(),
                request.interpretation.merchant,
                request.interpretation.timeAnchor?.date,
                request.interpretation.timeAnchor?.time,
                request.interpretation.timeAnchor?.timezoneId,
                request.interpretation.timeAnchor?.intervalEndDate,
                request.interpretation.timeAnchor?.intervalEndTime,
                request.interpretation.unresolvedReason,
            ),
            authorize = { authorizeCapture(scope, request.captureId) },
        ) {
            when (
                val result = events.recordEvent(
                    captureId = request.captureId,
                    expectedCaptureRevision = request.expectedCaptureRevision,
                    interpretation = request.interpretation,
                    extractionVersion = request.extractionVersion,
                    expectedEventRevision = request.expectedEventRevision,
                    userEdited = scope is WorkflowCallerScope.User,
                )
            ) {
                is NoteEventWriteResult.Stored -> {
                    arrivalReconciliation.enqueueEvent(
                        result.eventId,
                        result.revision,
                        request.expectedCaptureRevision,
                    )
                    WorkflowCommandOutcome.Applied(
                        WorkflowChange.EventStored(result.eventId, result.revision),
                    )
                }
                is NoteEventWriteResult.Revised -> {
                    arrivalReconciliation.enqueueEvent(
                        result.eventId,
                        result.revision,
                        request.expectedCaptureRevision,
                    )
                    WorkflowCommandOutcome.Applied(
                        WorkflowChange.EventRevised(result.eventId, result.revision),
                    )
                }
                NoteEventWriteResult.NoChange -> {
                    val stored = storedEventOf(request)
                    stored?.let {
                        arrivalReconciliation.enqueueEvent(it.id, it.revision, it.sourceRevision)
                    }
                    WorkflowCommandOutcome.NoChange(
                        change = stored?.let { event ->
                            WorkflowChange.EventStored(event.id, event.revision)
                        },
                        detail = "The same reading is already stored for that span.",
                    )
                }
                NoteEventWriteResult.NotFound -> WorkflowCommandOutcome.NotFound
                NoteEventWriteResult.Changed -> WorkflowCommandOutcome.Conflict(
                    "The capture or interpretation changed under the caller.",
                )
                NoteEventWriteResult.SourceDeleted -> WorkflowCommandOutcome.Denied(
                    WorkflowDenialReason.ALREADY_DELETED,
                )
                is NoteEventWriteResult.Invalid -> WorkflowCommandOutcome.NeedsClarification(
                    result.field.name,
                    result.reason,
                )
                NoteEventWriteResult.AcceptedLink -> WorkflowCommandOutcome.Denied(
                    WorkflowDenialReason.USER_DECISION_REQUIRED,
                )
                NoteEventWriteResult.UserEdited -> WorkflowCommandOutcome.Denied(
                    WorkflowDenialReason.USER_DECISION_REQUIRED,
                )
            }
        }
        if (outcome is WorkflowCommandOutcome.Applied || outcome is WorkflowCommandOutcome.NoChange) {
            arrivalReconciliation.resumePending()
        }
        return outcome
    }

    // --- Context links ------------------------------------------------------

    /**
     * Proposes that one event describes one draft.
     *
     * A pair that was refused before stays refused and is reported as such without any
     * write, so a repeated proposal after a refusal changes nothing. The proposal
     * records the app's *own* evidence about the pair — the facts the app can read from
     * the stored reading and the stored draft, bound to the exact source, event, draft,
     * and revisions they were read from. A caller states no evidence at all, so a
     * proposal can never rest on a reason a model or a note made up.
     */
    suspend fun proposeLink(
        scope: WorkflowCallerScope,
        request: ProposeLinkRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_PROPOSE_LINK,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_PROPOSE_LINK,
            request.eventId.toString(),
            request.draftId.toString(),
        ),
        authorize = { authorizeLink(scope, request.eventId, request.draftId) },
    ) {
        when (
            val result = events.proposeLink(
                eventId = request.eventId,
                draftId = request.draftId,
            )
        ) {
            is ContextLinkResult.Linked -> WorkflowCommandOutcome.Applied(
                WorkflowChange.LinkDecided(result.linkId, result.state, result.revision),
            )
            is ContextLinkResult.Unlinked -> WorkflowCommandOutcome.Conflict(
                "The link was removed under the caller.",
            )
            ContextLinkResult.NoChange -> WorkflowCommandOutcome.NoChange(
                change = linkDao.findByPair(request.eventId, request.draftId)?.let { link ->
                    WorkflowChange.LinkDecided(link.id, link.state, link.revision)
                },
                detail = "The link already stands in that state.",
            )
            ContextLinkResult.NotFound -> WorkflowCommandOutcome.NotFound
            ContextLinkResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The link changed under the caller.",
            )
            ContextLinkResult.Rejected -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.LINK_ALREADY_REJECTED,
            )
            ContextLinkResult.EventUnavailable -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_UNAVAILABLE,
            )
            ContextLinkResult.DraftUnavailable -> WorkflowCommandOutcome.NotFound
            ContextLinkResult.MultipleDrafts -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.MULTIPLE_ACTIVE_DRAFTS,
            )
            ContextLinkResult.EventAlreadyRecorded -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_ALREADY_RECORDED,
            )
            ContextLinkResult.DraftAlreadyReviewed -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.CONFIRMED_RECORD,
            )
        }
    }

    /**
     * Attaches an event to the draft it was proposed for.
     *
     * The user may attach any proposed link: resolving a conflict is the user's own
     * decision, and it needs no proof. Another caller may attach only a link the app's
     * own policy accepts — the app's evidence about the pair must still be the evidence
     * the proposal was made on, it must still prove one association, and it must be
     * bound to the very source, event, draft, and revisions it was read from. A link
     * that does not meet that policy is refused and left for the user, because an
     * ambiguous or conflicting association is the user's to resolve.
     */
    suspend fun attachLink(
        scope: WorkflowCallerScope,
        request: AttachLinkRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_ATTACH_LINK,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_ATTACH_LINK,
            request.eventId.toString(),
            request.draftId.toString(),
            request.expectedRevision.toString(),
            request.userOverride,
        ),
        authorize = { authorizeLink(scope, request.eventId, request.draftId) },
    ) {
        val link = linkDao.findByPair(request.eventId, request.draftId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (link.state == ContextLinkState.REJECTED) {
            return@runCommand WorkflowCommandOutcome.Denied(WorkflowDenialReason.LINK_ALREADY_REJECTED)
        }
        if (link.state == ContextLinkState.ACCEPTED) {
            return@runCommand WorkflowCommandOutcome.NoChange(
                change = WorkflowChange.LinkDecided(link.id, link.state, link.revision),
                detail = "The link is already attached.",
            )
        }
        if (link.revision != request.expectedRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict("The link changed under the caller.")
        }
        if (scope is WorkflowCallerScope.AgentSession) {
            attachPolicyRefusal(scope, link)?.let { refusal -> return@runCommand refusal }
        }
        when (
            val result = events.acceptLink(
                eventId = request.eventId,
                draftId = request.draftId,
                expectedRevision = request.expectedRevision,
                userOverride = request.userOverride,
            )
        ) {
            is ContextLinkResult.Linked -> WorkflowCommandOutcome.Applied(
                WorkflowChange.LinkDecided(result.linkId, result.state, result.revision),
            )
            ContextLinkResult.NoChange -> WorkflowCommandOutcome.NoChange(
                change = WorkflowChange.LinkDecided(link.id, link.state, link.revision),
                detail = "The link is already attached.",
            )
            ContextLinkResult.NotFound -> WorkflowCommandOutcome.NotFound
            ContextLinkResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The link changed under the caller.",
            )
            ContextLinkResult.Rejected -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.LINK_ALREADY_REJECTED,
            )
            ContextLinkResult.EventUnavailable -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_UNAVAILABLE,
            )
            ContextLinkResult.DraftUnavailable -> WorkflowCommandOutcome.NotFound
            ContextLinkResult.MultipleDrafts -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.MULTIPLE_ACTIVE_DRAFTS,
            )
            ContextLinkResult.EventAlreadyRecorded -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_ALREADY_RECORDED,
            )
            ContextLinkResult.DraftAlreadyReviewed -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.CONFIRMED_RECORD,
            )
            is ContextLinkResult.Unlinked -> WorkflowCommandOutcome.Conflict(
                "The link was removed under the caller.",
            )
        }
    }

    /**
     * Evaluates one chosen pair with the same trusted evidence the attachment mutation uses.
     *
     * Read-only: nothing is proposed, attached, or written. An event or draft outside this
     * caller's scope is answered exactly like a missing row, so the read never discloses the
     * existence of a subject the caller may not touch.
     */
    suspend fun inspectLinkEvidence(
        scope: WorkflowCallerScope,
        eventId: Long,
        draftId: Long,
    ): LinkInspection? {
        val event = eventDao.findById(eventId) ?: return null
        if (!scope.mayTouchCapture(event.captureId)) return null
        val draft = draftDao.findById(draftId) ?: return null
        if (!scope.mayTouchDraft(draft.id)) return null
        val evidence = linkEvidence.evidenceFor(event, draft)
        val reason = evidence.reasons.firstOrNull()
        return LinkInspection(
            eventId = event.id,
            draftId = draft.id,
            captureId = event.captureId,
            captureRevision = evidence.captureRevision,
            eventRevision = evidence.eventRevision,
            draftReviewRevision = evidence.draftReviewRevision,
            eligibility = when {
                reason == null -> LinkEligibility.ELIGIBLE
                isAmbiguousLinkReason(reason) -> LinkEligibility.AMBIGUOUS
                else -> LinkEligibility.INELIGIBLE
            },
            reason = reason,
            eventAmountVnd = evidence.eventAmountVnd,
            eventAnchorDate = evidence.eventAnchorDate,
            draftAmountVnd = evidence.draftAmountVnd,
            draftStatedDate = evidence.draftStatedDate,
            candidateDraftCount = evidence.candidateDraftCount,
            duplicatePartnerCount = evidence.duplicatePartnerCount,
        )
    }

    /**
     * Replays one recorded operation identity through the journal.
     *
     * This is the domain's narrow replay entry point: the receipt must belong to this caller
     * scope, name the same command family, and carry the same recorded request digest. A
     * settled receipt is answered with the change it made and no new write; a pending
     * receipt is reported as still running, never as a success. The request payload is not
     * re-dispatched and nothing is fabricated just because a row exists.
     */
    suspend fun replayOperation(
        scope: WorkflowCallerScope,
        idempotencyKey: String,
        expectedCommand: String,
        expectedDigest: String,
    ): WorkflowCommandOutcome {
        if (idempotencyKey.isBlank() || expectedCommand.isBlank() || expectedDigest.isBlank()) {
            return WorkflowCommandOutcome.Failed("A replay needs a recorded operation identity.")
        }
        val recorded = operations.findByKey(idempotencyKey) ?: return WorkflowCommandOutcome.NotFound
        if (recorded.scopeId != scope.scopeId ||
            recorded.command != expectedCommand ||
            recorded.payloadDigest != expectedDigest
        ) {
            return WorkflowCommandOutcome.Conflict(CONFLICT_KEY_IN_USE)
        }
        if (recorded.outcome == OPERATION_OUTCOME_PENDING) {
            return WorkflowCommandOutcome.Failed(IN_PROGRESS_REASON)
        }
        val change = decodeWorkflowChange(recorded.changeSummary)
            ?: return WorkflowCommandOutcome.Failed("The recorded operation holds no change.")
        return WorkflowCommandOutcome.NoChange(
            change = change,
            detail = "The recorded operation already ran under this identity.",
        )
    }

    /**
     * Refuses the association between an event and a draft.
     *
     * A refusal is the user's own decision and is not removable, so it is the user's own
     * to make: another caller is refused before anything is read or written. A refused
     * pair stays refused, and no later proposal or attachment of it moves it.
     */
    suspend fun rejectLink(
        scope: WorkflowCallerScope,
        request: RejectLinkRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_REJECT_LINK,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_REJECT_LINK,
            request.eventId.toString(),
            request.draftId.toString(),
            request.expectedRevision.toString(),
            request.userOverride,
        ),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val link = linkDao.findByPair(request.eventId, request.draftId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (link.state == ContextLinkState.REJECTED) {
            return@runCommand WorkflowCommandOutcome.NoChange(
                change = WorkflowChange.LinkDecided(link.id, link.state, link.revision),
                detail = "The link is already refused.",
            )
        }
        if (link.revision != request.expectedRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict("The link changed under the caller.")
        }
        when (
            val result = events.rejectLink(
                eventId = request.eventId,
                draftId = request.draftId,
                expectedRevision = request.expectedRevision,
                userOverride = request.userOverride,
            )
        ) {
            is ContextLinkResult.Linked -> WorkflowCommandOutcome.Applied(
                WorkflowChange.LinkDecided(result.linkId, result.state, result.revision),
            )
            ContextLinkResult.NoChange -> WorkflowCommandOutcome.NoChange(
                change = WorkflowChange.LinkDecided(link.id, link.state, link.revision),
                detail = "The link is already refused.",
            )
            ContextLinkResult.NotFound -> WorkflowCommandOutcome.NotFound
            ContextLinkResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The link changed under the caller.",
            )
            ContextLinkResult.Rejected -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.LINK_ALREADY_REJECTED,
            )
            ContextLinkResult.EventUnavailable -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_UNAVAILABLE,
            )
            ContextLinkResult.DraftUnavailable -> WorkflowCommandOutcome.NotFound
            ContextLinkResult.MultipleDrafts -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.MULTIPLE_ACTIVE_DRAFTS,
            )
            ContextLinkResult.EventAlreadyRecorded -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_ALREADY_RECORDED,
            )
            ContextLinkResult.DraftAlreadyReviewed -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.CONFIRMED_RECORD,
            )
            is ContextLinkResult.Unlinked -> WorkflowCommandOutcome.Conflict(
                "The link was removed under the caller.",
            )
        }
    }

    /**
     * Removes an active link and restores the event's own context.
     *
     * Removing the association returns the event to the state it had before it was
     * linked, and it touches nothing else: the bank draft, its parsed evidence, and its
     * review state stay exactly as they are. A refused link is not removable, because
     * dropping it would let the very link the user refused be proposed again.
     */
    suspend fun unlinkLink(
        scope: WorkflowCallerScope,
        request: UnlinkLinkRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_UNLINK_LINK,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_UNLINK_LINK,
            request.eventId.toString(),
            request.draftId.toString(),
            request.expectedRevision.toString(),
        ),
        authorize = { authorizeLink(scope, request.eventId, request.draftId) },
    ) {
        val link = linkDao.findByPair(request.eventId, request.draftId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (link.state == ContextLinkState.REJECTED) {
            return@runCommand WorkflowCommandOutcome.Denied(WorkflowDenialReason.LINK_ALREADY_REJECTED)
        }
        if (link.revision != request.expectedRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict("The link changed under the caller.")
        }
        when (
            val result = events.unlink(
                eventId = request.eventId,
                draftId = request.draftId,
                expectedRevision = request.expectedRevision,
            )
        ) {
            is ContextLinkResult.Unlinked -> WorkflowCommandOutcome.Applied(
                WorkflowChange.LinkUnlinked(result.linkId),
            )
            ContextLinkResult.NoChange -> WorkflowCommandOutcome.NoChange(
                change = WorkflowChange.LinkDecided(link.id, link.state, link.revision),
                detail = "The link is not active.",
            )
            ContextLinkResult.NotFound -> WorkflowCommandOutcome.NotFound
            ContextLinkResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The link changed under the caller.",
            )
            ContextLinkResult.Rejected -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.LINK_ALREADY_REJECTED,
            )
            ContextLinkResult.EventUnavailable -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_UNAVAILABLE,
            )
            ContextLinkResult.DraftUnavailable -> WorkflowCommandOutcome.NotFound
            ContextLinkResult.MultipleDrafts -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.MULTIPLE_ACTIVE_DRAFTS,
            )
            ContextLinkResult.EventAlreadyRecorded -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_ALREADY_RECORDED,
            )
            ContextLinkResult.DraftAlreadyReviewed -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.CONFIRMED_RECORD,
            )
            is ContextLinkResult.Linked -> WorkflowCommandOutcome.Conflict(
                "The link changed under the caller.",
            )
        }
    }

    // --- Draft category selection -------------------------------------------

    /**
     * Saves the category the hosted workflow chose for one still-pending bank draft.
     *
     * It is the ordinary draft-review mutation restricted to the category field: the
     * amount, the moment, the memo, and the type are not rewritten, the draft stays
     * pending, and no ledger record is written. Only the user's own confirmation can make
     * the draft money.
     *
     * The request is answered in this order:
     *
     * - a draft outside the caller's scope is refused before anything protected is read;
     * - a draft that does not exist is not found;
     * - a draft that was already confirmed or dismissed reports the state it is really in;
     * - a draft whose review revision moved on is a conflict, so a stale form never
     *   overwrites newer corrections;
     * - a category that is no longer in the assignable catalog is refused.
     *
     * The category itself is written by the draft-review table's own conditional update,
     * inside the same transaction that re-checks the revision and the catalog, so a removed
     * or archived category cannot reappear in review.
     */
    suspend fun selectDraftCategory(
        scope: WorkflowCallerScope,
        request: SelectDraftCategoryRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_SELECT_DRAFT_CATEGORY,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_SELECT_DRAFT_CATEGORY,
            request.draftId.toString(),
            request.expectedReviewRevision.toString(),
            request.categoryId.toString(),
            request.expectedContextRevision,
            request.expectedCatalogRevision,
        ),
        authorize = {
            if (!scope.mayTouchDraft(request.draftId)) {
                WorkflowCommandOutcome.Denied(WorkflowDenialReason.CALLER_SCOPE)
            } else {
                null
            }
        },
    ) {
        val draft = draftDao.findById(request.draftId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
            return@runCommand WorkflowCommandOutcome.NoChange(
                change = terminalDraftChange(draft),
                detail = terminalDraftDetail(draft, "the category was not stored"),
            )
        }
        if (draft.reviewRevision != request.expectedReviewRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict("The draft changed under the caller.")
        }
        if (scope is WorkflowCallerScope.AgentSession) {
            val query = WorkflowQueryService(database)
            if (request.expectedContextRevision == null ||
                request.expectedContextRevision != query.draftContextSnapshot(request.draftId).second ||
                request.expectedCatalogRevision == null ||
                request.expectedCatalogRevision != categoryCatalogRevision(query.categoryCatalog())
            ) {
                return@runCommand WorkflowCommandOutcome.Conflict("The draft context or category catalog changed.")
            }
        }
        if (!rules.categoryAssignable(request.categoryId)) {
            return@runCommand WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.CATEGORY_UNAVAILABLE,
            )
        }
        when (
            val result = reviewDao.saveReviewCategory(
                id = request.draftId,
                expectedRevision = request.expectedReviewRevision,
                categoryId = request.categoryId,
                categoryOrigin = if (scope is WorkflowCallerScope.AgentSession) {
                    ReviewCategoryOrigin.HOSTED
                } else {
                    ReviewCategoryOrigin.USER
                },
            )
        ) {
            is BankDraftReviewResult.Saved -> WorkflowCommandOutcome.Applied(
                WorkflowChange.DraftCategorySelected(
                    draftId = request.draftId,
                    revision = result.revision,
                    categoryId = request.categoryId,
                ),
            )
            BankDraftReviewResult.AlreadyReviewed -> {
                val terminal = draftDao.findById(request.draftId) ?: draft
                WorkflowCommandOutcome.NoChange(
                    change = terminalDraftChange(terminal),
                    detail = terminalDraftDetail(terminal, "the category was not stored"),
                )
            }
            BankDraftReviewResult.CategoryUnavailable -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.CATEGORY_UNAVAILABLE,
            )
            BankDraftReviewResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The draft changed under the caller.",
            )
            BankDraftReviewResult.NotFound -> WorkflowCommandOutcome.NotFound
            is BankDraftReviewResult.Confirmed,
            is BankDraftReviewResult.Dismissed,
            is BankDraftReviewResult.NeedsCorrection,
            -> WorkflowCommandOutcome.NoChange(
                change = terminalDraftChange(draftDao.findById(request.draftId) ?: draft),
                detail = "The draft was decided while the category was being stored.",
            )
        }
    }

    // --- The command machinery ----------------------------------------------

    /**
     * Runs one command under its idempotency key.
     *
     * The caller's own authority is settled first, before anything protected is read: a
     * request the caller may not make is refused the same way whether or not the row it
     * names exists, and whether or not its key was ever used, so neither the existence of
     * a row the caller may not touch nor the use of a key it does not own is observable.
     *
     * The command and the record of it are written in one transaction. When two callers
     * race for the same key, the second one's write is refused by the unique index, its
     * transaction is rolled back whole, and it is answered from the record the winner
     * wrote — so the effect happens once.
     */
    private suspend fun runCommand(
        scope: WorkflowCallerScope,
        command: String,
        idempotencyKey: String,
        payload: List<String?>,
        authorize: suspend () -> WorkflowCommandOutcome?,
        block: suspend () -> WorkflowCommandOutcome,
    ): WorkflowCommandOutcome {
        if (idempotencyKey.isBlank()) {
            return WorkflowCommandOutcome.Failed("A command needs a non-blank idempotency key.")
        }
        authorize()?.let { refusal -> return refusal }
        val digest = canonicalDependencyDigest(payload)
        val recorded = operations.findByKey(idempotencyKey)
        if (recorded != null) return replayOf(recorded, scope, digest)
        val outcome = try {
            database.withTransaction {
                val outcome = block()
                recordOperation(command, idempotencyKey, scope, digest, outcome)
                outcome
            }
        } catch (error: SQLiteConstraintException) {
            val winner = operations.findByKey(idempotencyKey)
            if (winner == null) throw error
            replayOf(winner, scope, digest)
        }
        return outcome
    }

    /**
     * The answer to a request that was already made.
     *
     * The same key under the same caller with the same request is answered with the
     * change that was made then: the state is not changed again. The same key with a
     * different request, or from a different caller, is a conflict — the key names one
     * request, and a key that was used for something else is not this request's key. The
     * two are answered with the same words, so the answer never says whose record holds
     * the key. A key whose request is still running is answered as running, because the
     * work behind it has not finished yet.
     */
    private fun replayOf(
        recorded: WorkflowOperationEntity,
        scope: WorkflowCallerScope,
        digest: String,
    ): WorkflowCommandOutcome {
        if (recorded.scopeId != scope.scopeId) {
            return WorkflowCommandOutcome.Conflict(
                CONFLICT_KEY_IN_USE,
            )
        }
        if (recorded.payloadDigest != digest) {
            return WorkflowCommandOutcome.Conflict(
                CONFLICT_KEY_IN_USE,
            )
        }
        if (recorded.outcome == OPERATION_OUTCOME_PENDING) {
            return WorkflowCommandOutcome.Failed(IN_PROGRESS_REASON)
        }
        return WorkflowCommandOutcome.NoChange(
            change = decodeWorkflowChange(recorded.changeSummary),
            detail = "The request was already made under this idempotency key.",
        )
    }

    /**
     * Records the outcome of one command.
     *
     * Only an outcome that reached a durable state is recorded: a change that was applied,
     * or a state that was already what the request asked for. A refusal — denied,
     * conflicting, not found, or failed — records nothing, so the key stays free for a
     * corrected request.
     */
    private suspend fun recordOperation(
        command: String,
        idempotencyKey: String,
        scope: WorkflowCallerScope,
        digest: String,
        outcome: WorkflowCommandOutcome,
    ) {
        val change = when (outcome) {
            is WorkflowCommandOutcome.Applied -> outcome.change
            is WorkflowCommandOutcome.NoChange -> outcome.change
            else -> null
        }
        val kind = when (outcome) {
            is WorkflowCommandOutcome.Applied -> OPERATION_OUTCOME_APPLIED
            is WorkflowCommandOutcome.NoChange -> OPERATION_OUTCOME_NO_CHANGE
            else -> return
        }
        operations.insert(
            WorkflowOperationEntity(
                idempotencyKey = idempotencyKey,
                scopeId = scope.scopeId,
                command = command,
                payloadDigest = digest,
                outcome = kind,
                changeSummary = change?.let { encodeWorkflowChange(it) },
                ownerToken = null,
                leaseExpiresAtEpochMillis = null,
                createdAtEpochMillis = clock.nowEpochMillis(),
            ),
        )
    }

    // --- Financial commands ---------------------------------------------------

    /**
     * Stores one manual ledger record.
     *
     * The record is the user's own decision to write a row down, so only the user may
     * make this request: another caller is refused before anything is read or written,
     * and the refusal is the same whether or not the request names a row that exists.
     *
     * [SaveManualTransactionRequest.source] names the note the record was written from,
     * when the user records an unmatched note by hand, and it carries the revision of the
     * reading the caller read: a record may only be associated with the exact reading the
     * caller saw, so a note that moved on under the caller is a conflict and a note whose
     * reading no longer holds the occurrence cannot be associated at all. The event is
     * then resolved against the stored record, in the same transaction as the record
     * itself: a note that has been recorded is spent, so a bank notification that arrives
     * later cannot consume the same occurrence again. A note that is already resolved, or
     * that is already linked to a bank notification, is refused instead of being recorded
     * twice.
     */
    suspend fun saveManualTransaction(
        scope: WorkflowCallerScope,
        request: SaveManualTransactionRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_SAVE_MANUAL_TRANSACTION,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_SAVE_MANUAL_TRANSACTION,
            request.type.name,
            request.amountVnd.toString(),
            request.occurredAtEpochMillis.toString(),
            request.description,
            request.categoryId?.toString(),
            (request.source as? ManualTransactionSource.NoteEvent)?.eventId?.toString(),
            (request.source as? ManualTransactionSource.NoteEvent)?.expectedRevision?.toString(),
        ),
        authorize = { authorizeUserDecision(scope) },
    ) {
        rules.rejection(
            type = request.type,
            amountVnd = request.amountVnd,
            categoryId = request.categoryId,
            retainedCategoryId = null,
        )?.let { rejection -> return@runCommand rejection.toOutcome() }
        val sourceEvent = when (val source = request.source) {
            ManualTransactionSource.None -> null
            is ManualTransactionSource.NoteEvent -> {
                val event = eventDao.findById(source.eventId)
                    ?: return@runCommand WorkflowCommandOutcome.NotFound
                if (event.resolvedTransactionId != null) {
                    return@runCommand WorkflowCommandOutcome.Conflict(
                        "That note was already recorded as a ledger record.",
                    )
                }
                // The reading the caller read is named by its revision: a note that moved
                // on under the caller is a conflict, never an association against a
                // reading the caller never saw.
                if (event.revision != source.expectedRevision) {
                    return@runCommand WorkflowCommandOutcome.Conflict(
                        "The note changed under the caller.",
                    )
                }
                // The reading must still describe an occurrence. A note whose text was
                // edited or deleted no longer holds the reading it was read from, so the
                // occurrence it described cannot be recorded against it.
                if (event.normalizedText == null || event.unresolvedReason != null) {
                    return@runCommand WorkflowCommandOutcome.Denied(
                        WorkflowDenialReason.EVENT_UNAVAILABLE,
                    )
                }
                // A note whose bank notification was already accepted is that notification's
                // business: the occurrence is recorded, and recording it again would count it
                // twice. A proposal is only a candidate, so the user may still record the
                // occurrence by hand — and the proposal then cannot be attached any more,
                // because the note is spent.
                if (linkDao.findForEvent(event.id).any { it.state == ContextLinkState.ACCEPTED }) {
                    return@runCommand WorkflowCommandOutcome.Conflict(
                        "That note is already linked to a bank notification.",
                    )
                }
                event
            }
        }
        val transactionId = ledger.addTransaction(
            TransactionInput(
                type = request.type,
                amountVnd = request.amountVnd,
                occurredAtEpochMillis = request.occurredAtEpochMillis,
                description = request.description,
                categoryId = request.categoryId,
            ),
        )
        sourceEvent?.let { event ->
            eventDao.markResolved(event.id, transactionId, clock.nowEpochMillis())
        }
        WorkflowCommandOutcome.Applied(
            WorkflowChange.TransactionSaved(transactionId, 0, sourceEvent?.id),
        )
    }

    /**
     * Corrects the user-editable fields of a stored ledger record.
     *
     * The correction is the user's own decision, so only the user may make it. The
     * revision the caller read is part of the request: a record that has moved on is a
     * conflict instead of an overwrite of what the caller never saw. The record keeps its
     * id, its creation time, its confirmation, and its provenance — a correction can
     * never promote a record into another one or claim it came from somewhere else.
     */
    suspend fun correctTransaction(
        scope: WorkflowCallerScope,
        request: CorrectTransactionRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_CORRECT_TRANSACTION,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_CORRECT_TRANSACTION,
            request.transactionId.toString(),
            request.expectedRevision.toString(),
            request.type.name,
            request.amountVnd.toString(),
            request.occurredAtEpochMillis.toString(),
            request.description,
            request.categoryId?.toString(),
        ),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val stored = transactionDao.findById(request.transactionId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (stored.revision != request.expectedRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict(
                "The record changed under the caller.",
            )
        }
        rules.rejection(
            type = request.type,
            amountVnd = request.amountVnd,
            categoryId = request.categoryId,
            retainedCategoryId = stored.categoryId,
        )?.let { rejection -> return@runCommand rejection.toOutcome() }
        when (
            val result = ledger.editTransaction(
                transactionId = request.transactionId,
                expectedRevision = request.expectedRevision,
                correction = TransactionCorrection(
                    type = request.type,
                    amountVnd = request.amountVnd,
                    occurredAtEpochMillis = request.occurredAtEpochMillis,
                    description = request.description,
                    categoryId = request.categoryId,
                ),
            )
        ) {
            is LedgerCorrectionResult.Corrected -> WorkflowCommandOutcome.Applied(
                WorkflowChange.TransactionCorrected(request.transactionId, result.revision),
            )
            LedgerCorrectionResult.NotFound -> WorkflowCommandOutcome.NotFound
            LedgerCorrectionResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The record changed under the caller.",
            )
        }
    }

    /**
     * Removes a stored ledger record.
     *
     * The removal is the user's own decision, so only the user may make it, and the
     * revision the caller read is part of the request: a record that has moved on is a
     * conflict instead of a removal of something the caller never saw.
     *
     * The note that resolved the record is released with it, in the same transaction: the
     * occurrence is no longer recorded, so the note may be recorded again.
     */
    suspend fun deleteTransaction(
        scope: WorkflowCallerScope,
        request: DeleteTransactionRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_DELETE_TRANSACTION,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_DELETE_TRANSACTION,
            request.transactionId.toString(),
            request.expectedRevision.toString(),
        ),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val stored = transactionDao.findById(request.transactionId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (stored.revision != request.expectedRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict(
                "The record changed under the caller.",
            )
        }
        ledger.deleteTransaction(request.transactionId)
        eventDao.clearResolutionOf(request.transactionId)
        WorkflowCommandOutcome.Applied(WorkflowChange.TransactionDeleted(request.transactionId))
    }

    /**
     * Stores the user's explicit edits on a still-pending bank draft.
     *
     * The edits are the review's own fields — the amount, the moment, the memo, the type,
     * and the category — and they stay on the draft: a draft is a draft until it is
     * confirmed, so storing them never writes a ledger record. The revision the caller
     * read is part of the request, so a stale editor is refused instead of overwriting
     * newer corrections.
     *
     * A draft that is already decided answers with the state it is really in — confirmed
     * or dismissed — and never with the state the request asked for.
     */
    suspend fun saveDraftReview(
        scope: WorkflowCallerScope,
        request: SaveDraftReviewRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_SAVE_DRAFT_REVIEW,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_SAVE_DRAFT_REVIEW,
            request.draftId.toString(),
            request.expectedRevision.toString(),
            request.edits.amountText,
            request.edits.dateText,
            request.edits.timeText,
            request.edits.memoText,
            request.edits.type?.name,
            request.edits.categoryId?.toString(),
            request.edits.categoryResolved.toString(),
        ),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val draft = draftDao.findById(request.draftId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
            return@runCommand WorkflowCommandOutcome.NoChange(
                change = terminalDraftChange(draft),
                detail = terminalDraftDetail(draft, "the corrections were not stored"),
            )
        }
        if (draft.reviewRevision != request.expectedRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict(
                "The draft changed under the caller.",
            )
        }
        val categoryId = request.edits.categoryId
        if (categoryId != null && !rules.categoryAssignable(categoryId)) {
            return@runCommand WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.CATEGORY_UNAVAILABLE,
            )
        }
        when (
            val result = reviewDao.saveReview(
                request.draftId,
                request.expectedRevision,
                request.edits,
                categoryOrigin = if (request.edits.categoryResolved) {
                    ReviewCategoryOrigin.USER
                } else {
                    ReviewCategoryOrigin.NONE
                },
            )
        ) {
            is BankDraftReviewResult.Saved -> {
                WorkflowCommandOutcome.Applied(
                    WorkflowChange.DraftReviewSaved(request.draftId, result.revision),
                )
            }
            BankDraftReviewResult.AlreadyReviewed -> {
                // The draft became terminal under this request: the answer names the state
                // it is really in, not the state the request asked for.
                val terminal = draftDao.findById(request.draftId) ?: draft
                WorkflowCommandOutcome.NoChange(
                    change = terminalDraftChange(terminal),
                    detail = terminalDraftDetail(terminal, "the corrections were not stored"),
                )
            }
            BankDraftReviewResult.CategoryUnavailable -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.CATEGORY_UNAVAILABLE,
            )
            BankDraftReviewResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The draft changed under the caller.",
            )
            BankDraftReviewResult.NotFound -> WorkflowCommandOutcome.NotFound
            is BankDraftReviewResult.Confirmed,
            BankDraftReviewResult.Dismissed,
            is BankDraftReviewResult.NeedsCorrection,
            -> WorkflowCommandOutcome.Failed("The draft review could not be stored.")
        }
    }

    /**
     * Looks at what a confirmation of a bank draft would write.
     *
     * The preview is a read: it changes nothing, it is not recorded, and it is never
     * answered from a stored record, so the fields it shows are always the fields the app
     * would write now. It resolves the wall-clock text under the device's own zone
     * policy, so the amount, the moment, the description, the type, and the category it
     * shows are the ones a ledger record would carry.
     *
     * The binding it returns names those fields together with the revision of the draft,
     * the state of the chosen category, and the source the draft was read against: a
     * confirmation that carries a binding the app would not write now is a conflict, so a
     * stale preview can never overwrite newer data.
     */
    suspend fun previewDraftConfirmation(
        scope: WorkflowCallerScope,
        request: PreviewDraftConfirmationRequest,
    ): DraftConfirmationPreview {
        if (scope !is WorkflowCallerScope.User) {
            return DraftConfirmationPreview.Denied(WorkflowDenialReason.USER_DECISION_REQUIRED)
        }
        val draft = draftDao.findById(request.draftId)
            ?: return DraftConfirmationPreview.NotFound
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
            val state = terminalDraftState(draft) ?: "decided"
            return DraftConfirmationPreview.Conflict(
                "The draft was already $state.",
            )
        }
        val resolution = rules.resolveReviewFields(request.edits, zone())
        if (resolution is ReviewFieldResolution.Invalid) {
            return DraftConfirmationPreview.NeedsCorrection(resolution.fields)
        }
        val fields = (resolution as ReviewFieldResolution.Resolved).fields
        val categoryId = fields.categoryId
        if (categoryId != null && !rules.categoryAssignable(categoryId)) {
            return DraftConfirmationPreview.Denied(WorkflowDenialReason.CATEGORY_UNAVAILABLE)
        }
        return DraftConfirmationPreview.Ready(
            draftId = draft.id,
            expectedRevision = draft.reviewRevision,
            binding = confirmationBinding(draft, fields),
            fields = fields,
            duplicateRisk = duplicateRiskOf(fields),
        )
    }

    /**
     * Confirms a bank draft as a ledger record.
     *
     * The confirmation is the user's own decision, so only the user may make it. The
     * record and the draft's link to it are written in one transaction with the record of
     * the request, so a confirmation is either fully applied and recorded or not applied
     * at all.
     *
     * A repeated request is a no-change and names the record that was made the first
     * time; a request under a different key against a draft that is already confirmed is
     * also a no-change, because the draft is terminal: two confirmations of one draft
     * produce one ledger record, whatever the keys are and whatever the order is.
     *
     * The binding the request carries is checked against the state the app would write
     * now, so a preview that has gone stale — a moved draft, an archived category, a
     * changed source — is a conflict and writes nothing.
     */
    suspend fun confirmDraft(
        scope: WorkflowCallerScope,
        request: ConfirmDraftRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_CONFIRM_DRAFT,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_CONFIRM_DRAFT,
            request.draftId.toString(),
            request.expectedRevision.toString(),
            request.expectedBinding,
            request.edits.amountText,
            request.edits.dateText,
            request.edits.timeText,
            request.edits.memoText,
            request.edits.type?.name,
            request.edits.categoryId?.toString(),
            request.edits.categoryResolved.toString(),
        ),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val draft = draftDao.findById(request.draftId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
            return@runCommand WorkflowCommandOutcome.NoChange(
                change = terminalDraftChange(draft),
                detail = terminalDraftDetail(draft, "no additional transaction was added"),
            )
        }
        if (draft.reviewRevision != request.expectedRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict(
                "The draft changed under the caller.",
            )
        }
        val resolution = rules.resolveReviewFields(request.edits, zone())
        if (resolution is ReviewFieldResolution.Invalid) {
            return@runCommand WorkflowCommandOutcome.NeedsClarification(
                resolution.fields.firstOrNull()?.name ?: "edits",
                "The review is not complete yet.",
            )
        }
        val fields = (resolution as ReviewFieldResolution.Resolved).fields
        if (confirmationBinding(draft, fields) != request.expectedBinding) {
            return@runCommand WorkflowCommandOutcome.Conflict(
                "The preview is no longer current; the review changed since it was made.",
            )
        }
        val now = clock.nowEpochMillis()
        val transaction = TransactionEntity(
            type = fields.type,
            amountVnd = fields.amountVnd,
            occurredAtEpochMillis = fields.occurredAtEpochMillis,
            description = fields.description,
            categoryId = fields.categoryId,
            confirmation = ConfirmationStatus.CONFIRMED,
            source = RecordSource.BANK_NOTIFICATION,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        when (
            val result = reviewDao.confirm(
                id = request.draftId,
                expectedRevision = request.expectedRevision,
                edits = request.edits,
                transaction = transaction,
                categoryOrigin = if (request.edits.categoryResolved) {
                    ReviewCategoryOrigin.USER
                } else {
                    ReviewCategoryOrigin.NONE
                },
            )
        ) {
            is BankDraftReviewResult.Confirmed -> WorkflowCommandOutcome.Applied(
                WorkflowChange.DraftConfirmed(request.draftId, result.transactionId),
            )
            BankDraftReviewResult.AlreadyReviewed -> {
                // The draft became terminal under this request: the answer names the state
                // it is really in, not the state the request asked for.
                val terminal = draftDao.findById(request.draftId) ?: draft
                WorkflowCommandOutcome.NoChange(
                    change = terminalDraftChange(terminal),
                    detail = terminalDraftDetail(terminal, "no additional transaction was added"),
                )
            }
            BankDraftReviewResult.CategoryUnavailable -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.CATEGORY_UNAVAILABLE,
            )
            BankDraftReviewResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The draft changed under the caller.",
            )
            BankDraftReviewResult.NotFound -> WorkflowCommandOutcome.NotFound
            is BankDraftReviewResult.Saved,
            BankDraftReviewResult.Dismissed,
            is BankDraftReviewResult.NeedsCorrection,
            -> WorkflowCommandOutcome.Failed("The draft could not be confirmed.")
        }
    }

    /**
     * Dismisses a still-pending bank draft.
     *
     * The dismissal is the user's own decision — the notification stays out of the
     * ledger — so only the user may make it. A dismissed draft is terminal: it is never
     * reopened, and a repeated request is a no-change.
     */
    suspend fun dismissDraft(
        scope: WorkflowCallerScope,
        request: DismissDraftRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_DISMISS_DRAFT,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_DISMISS_DRAFT,
            request.draftId.toString(),
            request.expectedRevision.toString(),
        ),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val draft = draftDao.findById(request.draftId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
            return@runCommand WorkflowCommandOutcome.NoChange(
                change = terminalDraftChange(draft),
                detail = terminalDraftDetail(draft, "nothing was written"),
            )
        }
        if (draft.reviewRevision != request.expectedRevision) {
            return@runCommand WorkflowCommandOutcome.Conflict(
                "The draft changed under the caller.",
            )
        }
        when (val result = reviewDao.dismiss(request.draftId)) {
            BankDraftReviewResult.Dismissed -> WorkflowCommandOutcome.Applied(
                WorkflowChange.DraftDismissed(request.draftId),
            )
            BankDraftReviewResult.AlreadyReviewed -> {
                // The draft became terminal under this request: the answer names the state
                // it is really in, not the state the request asked for.
                val terminal = draftDao.findById(request.draftId) ?: draft
                WorkflowCommandOutcome.NoChange(
                    change = terminalDraftChange(terminal),
                    detail = terminalDraftDetail(terminal, "nothing was written"),
                )
            }
            BankDraftReviewResult.NotFound -> WorkflowCommandOutcome.NotFound
            is BankDraftReviewResult.Saved,
            is BankDraftReviewResult.Confirmed,
            is BankDraftReviewResult.NeedsCorrection,
            BankDraftReviewResult.CategoryUnavailable,
            BankDraftReviewResult.Changed,
            -> WorkflowCommandOutcome.Failed("The draft could not be dismissed.")
        }
    }

    // --- Category and rule commands -------------------------------------------

    /**
     * Adds a category to the catalog.
     *
     * The category is the user's own decision, so only the user may make this request: a
     * category names the pool every later record is filed under, and no other caller may
     * widen it. A name that another active category already carries is a conflict rather
     * than a second category, and a name that is empty asks the caller to state one.
     */
    suspend fun addCategory(
        scope: WorkflowCallerScope,
        request: AddCategoryRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_ADD_CATEGORY,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(COMMAND_ADD_CATEGORY, request.name),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val name = request.name.trim()
        if (name.isEmpty()) {
            return@runCommand WorkflowCommandOutcome.NeedsClarification(
                field = "name",
                reason = "A category needs a name.",
            )
        }
        val existing = categoryDao.findActiveIdByName(name)
        if (existing != null) {
            return@runCommand WorkflowCommandOutcome.Conflict(
                "Another active category is already called \"$name\".",
            )
        }
        val categoryId = ledger.addCategory(name)
        WorkflowCommandOutcome.Applied(WorkflowChange.CategoryAdded(categoryId, name))
    }

    /**
     * Renames a category.
     *
     * The id and every record filed under the category are untouched, so the history stays
     * attributed to the same category under its new name. A name that another active
     * category already carries is a conflict, and an empty name asks the caller to state
     * one.
     */
    suspend fun renameCategory(
        scope: WorkflowCallerScope,
        request: RenameCategoryRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_RENAME_CATEGORY,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(COMMAND_RENAME_CATEGORY, request.categoryId.toString(), request.name),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val category = categoryDao.findById(request.categoryId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        val name = request.name.trim()
        if (name.isEmpty()) {
            return@runCommand WorkflowCommandOutcome.NeedsClarification(
                field = "name",
                reason = "A category needs a name.",
            )
        }
        val duplicate = categoryDao.findActiveIdByName(name)
        if (duplicate != null && duplicate != category.id) {
            return@runCommand WorkflowCommandOutcome.Conflict(
                "Another active category is already called \"$name\".",
            )
        }
        ledger.renameCategory(category.id, name)
        WorkflowCommandOutcome.Applied(WorkflowChange.CategoryRenamed(category.id, name))
    }

    /**
     * Archives a category.
     *
     * Archiving takes the category out of the pool of assignable categories while the
     * records already filed under it keep pointing at it: the history is kept, and only
     * the choice of it is closed. A category that is already archived is answered with
     * the state it is really in.
     */
    suspend fun archiveCategory(
        scope: WorkflowCallerScope,
        request: ArchiveCategoryRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_ARCHIVE_CATEGORY,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(COMMAND_ARCHIVE_CATEGORY, request.categoryId.toString()),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val category = categoryDao.findById(request.categoryId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (category.archived) {
            return@runCommand WorkflowCommandOutcome.NoChange(
                change = WorkflowChange.CategoryArchived(category.id),
                detail = "The category was already archived; nothing was written.",
            )
        }
        ledger.archiveCategory(category.id)
        WorkflowCommandOutcome.Applied(WorkflowChange.CategoryArchived(category.id))
    }

    /**
     * Removes a category for good.
     *
     * The removal keeps every stored record — amount, type, moment, description, and the
     * totals computed from them — and only clears the links to the category: the records
     * that were filed under it become uncategorized, the saved rules that named it go
     * with it, and a still-pending draft's pre-set category is cleared so the reviewer
     * chooses again. The counts the change reports are the counts the removal applied.
     */
    suspend fun deleteCategory(
        scope: WorkflowCallerScope,
        request: DeleteCategoryRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_DELETE_CATEGORY,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(COMMAND_DELETE_CATEGORY, request.categoryId.toString()),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val category = categoryDao.findById(request.categoryId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        val impact = ledger.deleteCategory(category.id)
        WorkflowCommandOutcome.Applied(
            WorkflowChange.CategoryDeleted(category.id, impact),
        )
    }

    /**
     * Saves the user's own rule for one exact description.
     *
     * The rule is a deliberate standing decision of the user, so only the user may make
     * it, and it names an active category: a rule never points at a category that cannot
     * be chosen. A rule that already stands for that exact description is answered with
     * the state that is really stored, and it is never silently replaced by a later
     * choice.
     */
    suspend fun rememberCategoryRule(
        scope: WorkflowCallerScope,
        request: RememberCategoryRuleRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_REMEMBER_CATEGORY_RULE,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_REMEMBER_CATEGORY_RULE,
            request.description,
            request.categoryId.toString(),
        ),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val patternKey = rememberedCategoryRuleKey(request.description)
            ?: return@runCommand WorkflowCommandOutcome.NeedsClarification(
                field = "description",
                reason = "Enter a description before remembering a rule.",
            )
        val category = categoryDao.findById(request.categoryId)
            ?: return@runCommand WorkflowCommandOutcome.NotFound
        if (category.archived) {
            return@runCommand WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.CATEGORY_UNAVAILABLE,
            )
        }
        val remembered = ledger.rememberCategoryForDescription(request.description, category.id)
        val change = WorkflowChange.CategoryRuleRemembered(patternKey, category.id)
        if (remembered) {
            WorkflowCommandOutcome.Applied(change)
        } else {
            WorkflowCommandOutcome.NoChange(
                change = change,
                detail = "A saved rule already applies to this exact description.",
            )
        }
    }

    /**
     * Removes the saved rule of one exact description.
     *
     * Only that one rule is removed; nothing else about the description is touched, and a
     * description that has no rule is answered with the state that is really stored.
     */
    suspend fun removeCategoryRule(
        scope: WorkflowCallerScope,
        request: RemoveCategoryRuleRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_REMOVE_CATEGORY_RULE,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(COMMAND_REMOVE_CATEGORY_RULE, request.description),
        authorize = { authorizeUserDecision(scope) },
    ) {
        val patternKey = rememberedCategoryRuleKey(request.description)
            ?: return@runCommand WorkflowCommandOutcome.NeedsClarification(
                field = "description",
                reason = "Enter a description before remembering a rule.",
            )
        val removed = ledger.removeRememberedCategoryRule(request.description)
        if (removed) {
            WorkflowCommandOutcome.Applied(WorkflowChange.CategoryRuleRemoved(patternKey))
        } else {
            WorkflowCommandOutcome.NoChange(
                detail = "There is no saved rule for this exact description.",
            )
        }
    }

    /** The zone the wall-clock text of a review is read in. */
    private fun zone(): TimeZone = TimeZone.getTimeZone(timezoneId())

    /**
     * The change that names the state a terminal draft is really in.
     *
     * A draft is terminal once it was confirmed or dismissed, and an answer that arrives
     * after that must describe the state that is stored — not the state the request asked
     * for. A confirmed draft names the record it made; a dismissed draft names the
     * dismissal. A draft that names neither is not described as one of them: the answer
     * then carries no change at all rather than a state that was never stored.
     */
    private fun terminalDraftChange(draft: PendingBankDraftEntity): WorkflowChange? {
        val transactionId = draft.confirmedTransactionId
        return when {
            transactionId != null -> WorkflowChange.DraftConfirmed(draft.id, transactionId)
            draft.reviewStatus == IntakeReviewStatus.DISMISSED -> WorkflowChange.DraftDismissed(draft.id)
            else -> null
        }
    }

    /** The state a terminal draft is really in, in the words the user reads. */
    private fun terminalDraftState(draft: PendingBankDraftEntity): String? = when {
        draft.confirmedTransactionId != null -> "confirmed"
        draft.reviewStatus == IntakeReviewStatus.DISMISSED -> "dismissed"
        else -> null
    }

    /**
     * The words one terminal answer carries.
     *
     * The answer names the state the draft is really in and what that state means for the
     * request that arrived, so the reader is never told that a draft was "confirmed or
     * dismissed" when only one of the two is true.
     */
    private fun terminalDraftDetail(draft: PendingBankDraftEntity, what: String): String {
        val state = terminalDraftState(draft) ?: return "The draft is already decided; $what."
        return "The draft was already $state; $what."
    }

    /**
     * The binding of one confirmation.
     *
     * It names the exact fields a confirmation would write, the revision of the draft, the
     * state of the chosen category, and the source the draft was read against. A
     * confirmation whose binding does not match what the app would write now is refused
     * as a conflict, so a preview that has gone stale cannot overwrite newer data.
     */
    private suspend fun confirmationBinding(
        draft: PendingBankDraftEntity,
        fields: ConfirmedTransactionFields,
    ): String {
        val categoryState = fields.categoryId?.let { categoryId ->
            val category = categoryDao.findById(categoryId)
            if (category == null) {
                "gone"
            } else {
                "${category.id}:${category.archived}:${category.updatedAtEpochMillis}"
            }
        } ?: "none"
        val accepted = linkDao.findForDraft(draft.id)
            .filter { it.state == ContextLinkState.ACCEPTED }
        val sourceState = accepted.map { link ->
            val event = eventDao.findById(link.eventId)
            "${link.eventId}:${link.revision}:${event?.resolvedTransactionId ?: "-"}"
        }.joinToString(",").ifEmpty { "none" }
        return canonicalDependencyDigest(
            listOf(
                "DRAFT_CONFIRMATION/1",
                draft.id.toString(),
                draft.reviewRevision.toString(),
                draft.reviewStatus.name,
                fields.type.name,
                fields.amountVnd.toString(),
                fields.occurredAtEpochMillis.toString(),
                fields.description,
                fields.categoryId?.toString(),
                categoryState,
                sourceState,
            ),
        )
    }

    /**
     * The stored record that may be the same money as the fields a confirmation would
     * write.
     *
     * The risk is reported and never acted on: the record stays where it is, the draft
     * stays pending, and the user decides. The app never merges the two and never adds a
     * record of its own — a bank notification that arrives after the user recorded the
     * same money by hand is *evidence*, and it is the user's to weigh.
     */
    private suspend fun duplicateRiskOf(fields: ConfirmedTransactionFields): DuplicateRisk? {
        val zone = zone()
        val day = ManualDateTime.dateFieldText(fields.occurredAtEpochMillis, zone)
        val candidates = transactionDao.findConfirmedWithAmountInRange(
            amountVnd = fields.amountVnd,
            fromEpochMillis = ManualDateTime.dayStartEpochMillis(day, zone),
            throughExclusiveEpochMillis = ManualDateTime.dayAfterStartEpochMillis(day, zone),
            limit = DUPLICATE_RISK_LIMIT,
        )
        val candidate = candidates.firstOrNull() ?: return null
        return DuplicateRisk(
            transactionId = candidate.id,
            amountVnd = candidate.amountVnd,
            occurredAtEpochMillis = candidate.occurredAtEpochMillis,
            description = candidate.description,
            recordedFromNote = eventDao.findByResolvedTransaction(candidate.id) != null,
        )
    }

    // --- The authorization of a request ---------------------------------------

    /**
     * The authorization of a request that acts on one capture.
     *
     * The scope of a capture is a value the trusted application code supplied, so the
     * check needs no read at all: a capture outside the scope is refused before anything
     * protected is looked up.
     */
    private fun authorizeCapture(
        scope: WorkflowCallerScope,
        captureId: Long,
    ): WorkflowCommandOutcome? = if (scope.mayTouchCapture(captureId)) {
        null
    } else {
        WorkflowCommandOutcome.Denied(WorkflowDenialReason.CALLER_SCOPE)
    }

    /**
     * The authorization of a request that acts on one bank draft.
     *
     * The scope of a draft is a value the trusted application code supplied, so the check
     * needs no read at all.
     */
    private fun authorizeDraft(
        scope: WorkflowCallerScope,
        draftId: Long,
    ): WorkflowCommandOutcome? = if (scope.mayTouchDraft(draftId)) {
        null
    } else {
        WorkflowCommandOutcome.Denied(WorkflowDenialReason.CALLER_SCOPE)
    }

    /**
     * The authorization of a decision that is the user's own.
     *
     * A refusal of a link is the user's decision, so another caller is refused before
     * anything is read: it learns neither whether the pair exists nor what state it is in.
     */
    private fun authorizeUserDecision(
        scope: WorkflowCallerScope,
    ): WorkflowCommandOutcome? = if (scope is WorkflowCallerScope.User) {
        null
    } else {
        WorkflowCommandOutcome.Denied(WorkflowDenialReason.USER_DECISION_REQUIRED)
    }

    /**
     * The authorization of a request that acts on one link.
     *
     * The draft is checked against the scope without a read, and the reading the request
     * names is resolved to the capture it belongs to. A caller that may not act on that
     * capture is refused, and so is a reading that does not exist: to a caller outside
     * its scope the two are the same answer, so the existence of a row the caller may not
     * touch is not observable.
     */
    private suspend fun authorizeLink(
        scope: WorkflowCallerScope,
        eventId: Long,
        draftId: Long,
    ): WorkflowCommandOutcome? {
        authorizeDraft(scope, draftId)?.let { refusal -> return refusal }
        if (scope is WorkflowCallerScope.User) return null
        val event = eventDao.findById(eventId)
            ?: return WorkflowCommandOutcome.Denied(WorkflowDenialReason.CALLER_SCOPE)
        if (!scope.mayTouchCapture(event.captureId)) {
            return WorkflowCommandOutcome.Denied(WorkflowDenialReason.CALLER_SCOPE)
        }
        return null
    }

    /**
     * Why another caller may not attach this link, or null when the app's own policy
     * accepts it.
     *
     * The proposal must carry trusted evidence this app computed, the exact pair evidence
     * must still support one association, and the app's current bounded search must confirm
     * the complete pending-draft scope. Retrieval ranking and merchant similarity do not
     * replace the narrower local attachment policy. In particular, an INCOMPLETE scope
     * never authorizes automatic attachment, even if one visible pair otherwise looks unique.
     */
    private suspend fun attachPolicyRefusal(
        scope: WorkflowCallerScope.AgentSession,
        link: ContextLinkEntity,
    ): WorkflowCommandOutcome? {
        val current = automaticAttachmentPolicy.trustedEvidence(
            eventId = link.eventId,
            draftId = link.draftId,
            authorizedDraftIds = scope.draftIds,
            pendingDatabaseComplete = scope.draftScopeComplete,
        ) ?: return WorkflowCommandOutcome.Denied(WorkflowDenialReason.ATTACH_POLICY)
        if (!isTrustedLinkEvidence(link.evidence)) {
            return WorkflowCommandOutcome.Denied(WorkflowDenialReason.ATTACH_POLICY)
        }
        if (link.evidence != current.canonical) {
            return WorkflowCommandOutcome.Conflict(
                "The evidence the proposal was based on has changed.",
            )
        }
        return null
    }

    /** The stored reading of the span the request names, when there is one. */
    private suspend fun storedEventOf(request: UpdateEventRequest): NoteEventEntity? {
        val capture = captureDao.findById(request.captureId) ?: return null
        val identity = noteEventIdentity(
            sourceKind = capture.sourceKind,
            sourceIdentity = capture.sourceIdentity,
            spanStart = request.interpretation.spanStart,
            spanEnd = request.interpretation.spanEnd,
        )
        return eventDao.findByIdentity(request.captureId, identity)
    }

    internal companion object {
        const val COMMAND_SAVE_CAPTURE = "SAVE_CAPTURE"
        const val COMMAND_REVISE_CAPTURE = "REVISE_CAPTURE"
        const val COMMAND_DELETE_CAPTURE = "DELETE_CAPTURE"

        const val COMMAND_UPDATE_EVENT = "UPDATE_EVENT"
        const val COMMAND_PROPOSE_LINK = "PROPOSE_LINK"
        const val COMMAND_ATTACH_LINK = "ATTACH_LINK"
        const val COMMAND_REJECT_LINK = "REJECT_LINK"
        const val COMMAND_UNLINK_LINK = "UNLINK_LINK"
        const val COMMAND_SELECT_DRAFT_CATEGORY = "SELECT_DRAFT_CATEGORY"
        const val COMMAND_SAVE_MANUAL_TRANSACTION = "SAVE_MANUAL_TRANSACTION"
        const val COMMAND_CORRECT_TRANSACTION = "CORRECT_TRANSACTION"
        const val COMMAND_DELETE_TRANSACTION = "DELETE_TRANSACTION"
        const val COMMAND_SAVE_DRAFT_REVIEW = "SAVE_DRAFT_REVIEW"
        const val COMMAND_CONFIRM_DRAFT = "CONFIRM_DRAFT"
        const val COMMAND_DISMISS_DRAFT = "DISMISS_DRAFT"
        const val COMMAND_ADD_CATEGORY = "ADD_CATEGORY"
        const val COMMAND_RENAME_CATEGORY = "RENAME_CATEGORY"
        const val COMMAND_ARCHIVE_CATEGORY = "ARCHIVE_CATEGORY"
        const val COMMAND_DELETE_CATEGORY = "DELETE_CATEGORY"
        const val COMMAND_REMEMBER_CATEGORY_RULE = "REMEMBER_CATEGORY_RULE"
        const val COMMAND_REMOVE_CATEGORY_RULE = "REMOVE_CATEGORY_RULE"

        /**
         * How many stored records one duplication check looks at.
         *
         * The check reports that a record may be the same money; it never merges anything,
         * so one record is enough to say it, and the read stays bounded.
         */
        const val DUPLICATE_RISK_LIMIT = 5

        /** What a caller is told when the request under its key is still running. */
        const val IN_PROGRESS_REASON =
            "A request with this idempotency key is already running; " +
                "its result is recorded under that key."

        /**
         * What a caller is told when its key names another request.
         *
         * The same answer is given when the key names a request of another caller and
         * when it names another request of the caller's own, so a caller cannot learn
         * from the answer whose record holds the key. Nothing about that record — its
         * caller, its change, or whether it was applied — is ever named.
         */
        const val CONFLICT_KEY_IN_USE = "The idempotency key names a different request."
    }
}

/** How a refusal of a capture write is reported by the command boundary. */
private fun CaptureMessageWriteResult.toOutcome(): WorkflowCommandOutcome = when (this) {
    CaptureMessageWriteResult.NotFound -> WorkflowCommandOutcome.NotFound
    CaptureMessageWriteResult.AlreadyDeleted -> WorkflowCommandOutcome.Denied(
        WorkflowDenialReason.ALREADY_DELETED,
    )
    CaptureMessageWriteResult.ImmutableSource -> WorkflowCommandOutcome.Denied(
        WorkflowDenialReason.IMMUTABLE_SOURCE,
    )
    is CaptureMessageWriteResult.Saved, is CaptureMessageWriteResult.Deleted ->
        WorkflowCommandOutcome.Conflict("The capture changed under the caller.")
    CaptureMessageWriteResult.Changed -> WorkflowCommandOutcome.Conflict(
        "The capture changed under the caller.",
    )
}

/**
 * How a refusal of a financial field is reported by the command boundary.
 *
 * The rule is the app's own and it is the one the ledger repository applies; only the
 * shape of the answer differs. An unusable amount and a category that may not be
 * attached are told apart, so a screen can put the reason under the field it belongs to.
 */
private fun FinancialFieldRejection.toOutcome(): WorkflowCommandOutcome = when (this) {
    is FinancialFieldRejection.Amount -> WorkflowCommandOutcome.NeedsClarification(
        "amountVnd",
        reason,
    )
    is FinancialFieldRejection.Category -> WorkflowCommandOutcome.Denied(
        WorkflowDenialReason.CATEGORY_UNAVAILABLE,
    )
    is FinancialFieldRejection.TransferCategory -> WorkflowCommandOutcome.NeedsClarification(
        "categoryId",
        reason,
    )
}
