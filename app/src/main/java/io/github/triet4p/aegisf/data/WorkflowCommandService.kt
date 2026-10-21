package io.github.triet4p.aegisf.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import io.github.triet4p.aegisf.data.laya.LayaContextDecision
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's one write surface for the reversible preparation commands.
 *
 * Every preparation the app can make — saving a capture, revising its text, reading a
 * span of it, proposing, attaching, refusing, or removing the link between an event and a
 * bank draft, and asking for a classification of a draft — goes through this service.
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
 * - **The model is asked once.** A request that runs the model claims its idempotency key
 *   durably before the model is asked, so a second attempt under the same key finds the
 *   claim instead of repeating the work, and a claim whose attempt was interrupted may be
 *   taken over instead of reserving the key forever. The claim is held by two facts that
 *   fail independently: the durable row, which fences the writes, and the in-process
 *   guard, which proves that the attempt holding the key is still alive even when the
 *   database cannot be written. The two are taken together and never apart: the key is
 *   taken in the process before the durable row is written, a takeover takes the key
 *   before it updates the row, and a key is never taken away from the attempt that holds
 *   it — so there is no moment in which the row and the guard name two different owners,
 *   and an attempt that comes back after its claim was taken over does not ask the model
 *   a second time. The reservation of a held key says which caller holds it and which
 *   request it is running, so a request that arrives before the durable row exists is
 *   answered the way the row itself would answer it: the same request is running, and a
 *   different request or a different caller is a conflict. The claim is kept alive while
 *   the model works, and it is given back only when the model work has really ended, so
 *   neither a slow answer nor a model that cannot be interrupted lets a second attempt
 *   ask the model again.
 * - **Preparation only.** No command here confirms a draft, dismisses one, writes a
 *   ledger record, or edits a confirmed one. Those are the user's own financial
 *   decisions and belong to the user-only boundary of a later task.
 */
class WorkflowCommandService(
    private val database: LedgerDatabase,
    private val clock: LedgerClock = SystemLedgerClock,
    private val timezoneId: () -> String = { TimeZone.getDefault().id },
    /**
     * The bounded classifier the boundary asks for a decision.
     *
     * It is asked only after the request was found to be new, in scope, and answerable:
     * a repeated request and a refused request never reach the model.
     */
    private val engine: ClassificationEngine,
    /** The saved exact-description rule lookup; a valid rule answers before the model runs. */
    private val findRememberedCategory: suspend (String) -> CategoryEntity? = { null },
    /**
     * How long an attempt holds the claim on an idempotency key.
     *
     * The claim is a durable reservation of the key, not a lock: a request that is
     * interrupted — a process that died, a device that was switched off — leaves the key
     * reserved until the claim ends, and after that another attempt may take it over. The
     * time is long enough for one model call and short enough that an interrupted request
     * is never reserved forever.
     */
    private val claimLeaseMillis: Long = DEFAULT_CLAIM_LEASE_MILLIS,
) {
    /**
     * How often the claim of a running request is extended.
     *
     * The extension happens well inside the lease, so a request that is still working
     * keeps its key while it works, however long the work takes.
     */
    private val claimRenewIntervalMillis: Long = (claimLeaseMillis / 3).coerceAtLeast(50L)

    private val captureDao = database.captureMessageDao()
    private val eventDao = database.noteEventDao()
    private val linkDao = database.contextLinkDao()
    private val draftDao = database.pendingBankDraftDao()
    private val operations = database.workflowOperationDao()
    private val query = WorkflowQueryService(database)
    private val captures = CaptureMessageRepository(database = database, clock = clock, timezoneId = timezoneId)
    private val events = EventContextRepository(database = database, clock = clock)
    /** The app's own evidence about a proposed association; never read from a request. */
    private val linkEvidence = LinkEvidencePolicy(database)
    private val snapshots = ClassificationSnapshotRepository(database = database, clock = clock)

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

    // --- Event interpretations ----------------------------------------------

    /**
     * Stores or revises the reading of one span of one capture.
     *
     * The reading is taken from a named revision of a named capture. A span outside the
     * current text, a capture that changed under the caller, a deleted capture, an
     * unusable amount, and a malformed time are all refused, and a refused reading writes
     * nothing at all. One span of one capture holds one reading: a repeated extraction of
     * the same span addresses the same event instead of appending a second copy.
     */
    suspend fun updateEventInterpretation(
        scope: WorkflowCallerScope,
        request: UpdateEventRequest,
    ): WorkflowCommandOutcome = runCommand(
        scope = scope,
        command = COMMAND_UPDATE_EVENT,
        idempotencyKey = request.idempotencyKey,
        payload = listOf(
            COMMAND_UPDATE_EVENT,
            request.captureId.toString(),
            request.expectedCaptureRevision.toString(),
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
            )
        ) {
            is NoteEventWriteResult.Stored -> WorkflowCommandOutcome.Applied(
                WorkflowChange.EventStored(result.eventId, result.revision),
            )
            is NoteEventWriteResult.Revised -> WorkflowCommandOutcome.Applied(
                WorkflowChange.EventRevised(result.eventId, result.revision),
            )
            NoteEventWriteResult.NoChange -> WorkflowCommandOutcome.NoChange(
                change = storedEventOf(request)?.let { WorkflowChange.EventStored(it.id, it.revision) },
                detail = "The same reading is already stored for that span.",
            )
            NoteEventWriteResult.NotFound -> WorkflowCommandOutcome.NotFound
            NoteEventWriteResult.Changed -> WorkflowCommandOutcome.Conflict(
                "The capture changed under the caller.",
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
        }
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
        if (scope !is WorkflowCallerScope.User) {
            attachPolicyRefusal(link)?.let { refusal -> return@runCommand refusal }
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
            is ContextLinkResult.Unlinked -> WorkflowCommandOutcome.Conflict(
                "The link was removed under the caller.",
            )
        }
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
            is ContextLinkResult.Linked -> WorkflowCommandOutcome.Conflict(
                "The link changed under the caller.",
            )
        }
    }

    // --- Classification -----------------------------------------------------

    /**
     * Asks for a classification of one bank draft and records what it produced.
     *
     * The request is answered in this order, and the order matters:
     *
     * - a request outside the caller's scope is refused before anything protected is
     *   read, so a caller that may not act on a draft learns nothing about it;
     * - a request that was already made under the same key is answered from the durable
     *   record of it, and the model is not asked again;
     * - a request that is already running under the same key is answered as such, and
     *   the model is not asked a second time — while the same key with another request,
     *   or from another caller, is a conflict, in this window as well as after the
     *   durable record of the key exists;
     * - a request for a draft that does not exist, for a draft that moved on, or for text
     *   that may not be offered to the model, is refused before the model is asked;
     * - only then is the effective context composed and the decision asked for.
     *
     * The key is claimed durably *before* the model is asked, so two attempts under one
     * key cannot both run the model, whatever the number of command-service instances and
     * whatever the number of processes. The key is also claimed in the process before
     * the durable claim is written, and a takeover claims it before it updates the
     * durable claim, so the two never name different owners and no attempt can take a
     * claim over while the attempt that owns it is still alive. The claim is kept alive
     * while the model works, so a slow answer does not end it and let a second attempt
     * ask the model again. The claim is released again when the request is refused,
     * fails, or is cancelled, and a claim whose attempt was interrupted may be taken
     * over by a later attempt, so the key is never reserved forever.
     *
     * The prediction is stored against the state it was made in: a context that moved on
     * while the model was working is refused as a conflict, and a draft whose category the
     * user already chose keeps the user's choice. The stored prediction is review-only
     * evidence: no bank field is changed by any of this.
     */
    suspend fun requestClassification(
        scope: WorkflowCallerScope,
        request: ClassificationRequest,
    ): WorkflowCommandOutcome {
        if (request.idempotencyKey.isBlank()) {
            return WorkflowCommandOutcome.Failed("A command needs a non-blank idempotency key.")
        }
        // The caller's own authority is settled before anything protected is read.
        if (!scope.mayTouchDraft(request.draftId)) {
            return WorkflowCommandOutcome.Denied(WorkflowDenialReason.CALLER_SCOPE)
        }
        val digest = canonicalDependencyDigest(
            listOf(
                COMMAND_REQUEST_CLASSIFICATION,
                request.draftId.toString(),
                request.description,
                request.expectedReviewRevision?.toString(),
            ),
        )
        val claim = claimOperation(scope, request.idempotencyKey, digest)
        when (claim) {
            is OperationClaim.Refused -> return claim.outcome
            is OperationClaim.InProgress -> return WorkflowCommandOutcome.Failed(IN_PROGRESS_REASON)
            is OperationClaim.Replayed -> return replayOf(claim.recorded, scope, digest)
            is OperationClaim.Acquired -> Unit
        }
        val acquired = claim as OperationClaim.Acquired
        val hold = ClaimHold(acquired, request.idempotencyKey)
        try {
            return runClaimedClassification(scope, request, digest, acquired, hold)
        } catch (cancelled: CancellationException) {
            // A cancelled request leaves the key free again: the work never finished, so
            // nothing is recorded and a later attempt may use the key. The model work
            // itself is not cancelled — the local model is a native runtime whose
            // execution cannot be interrupted — so the key is given back when that work
            // has really ended, and not one moment earlier.
            hold.abandon()
            throw cancelled
        } catch (error: Exception) {
            hold.abandon()
            return WorkflowCommandOutcome.Failed(
                error.message ?: "The classification request could not be carried out.",
            )
        }
    }

    /**
     * The work of one classification request that holds the claim on its key.
     *
     * Every refusal on the way releases the claim again, so a refused request records
     * nothing and leaves the key free for a corrected request.
     */
    private suspend fun runClaimedClassification(
        scope: WorkflowCallerScope,
        request: ClassificationRequest,
        digest: String,
        claim: OperationClaim.Acquired,
        hold: ClaimHold,
    ): WorkflowCommandOutcome {
        val draft = draftDao.findById(request.draftId)
            ?: return releaseWith(hold, WorkflowCommandOutcome.NotFound)
        if (request.expectedReviewRevision != null &&
            draft.reviewRevision != request.expectedReviewRevision
        ) {
            return releaseWith(
                hold,
                WorkflowCommandOutcome.Conflict("The draft changed under the caller."),
            )
        }
        val description = request.description.trim()
        if (description.isEmpty()) {
            return releaseWith(
                hold,
                WorkflowCommandOutcome.NeedsClarification(
                    "description",
                    "A classification needs a description to classify.",
                ),
            )
        }
        if (isBlockedForSuggestionSafety(description)) {
            return releaseWith(
                hold,
                WorkflowCommandOutcome.NeedsClarification(
                    "description",
                    "The text is blocked for suggestion safety and is never offered to the model.",
                ),
            )
        }
        val effective = snapshots.effectiveContextFor(request.draftId, description)
            ?: return releaseWith(hold, WorkflowCommandOutcome.NotFound)
        val activeCategories = query.categoryCatalog().filterNot { it.archived }
        val produced = when (
            val decision = decideRememberedBeforeInference(
                description = description,
                context = effective.context,
                activeCategories = activeCategories,
                findRememberedCategory = findRememberedCategory,
                infer = { context, assignable ->
                    // The claim is looked at once more before the model is asked: the
                    // attempt that works on this request must still be the attempt the
                    // durable claim names. An attempt whose claim was taken over while
                    // it was starting up does not ask the model for a request another
                    // attempt already owns.
                    if (!stillHoldsTheClaim(request.idempotencyKey, claim)) {
                        throw ClaimLostException(CLAIM_NOT_KEPT_REASON)
                    }
                    // The claim is kept alive while the model works, and the work is
                    // held until it has really ended, so neither a slow answer nor a
                    // renewal that fails lets a second attempt ask the model again.
                    runModelWork(hold, context, assignable)
                },
            )
        ) {
            is ManualCategoryDecision.Remembered -> Produced.Remembered(decision.category)
            is ManualCategoryDecision.Inferred -> when (val result = decision.outcome) {
                is ClassificationEngineResult.Decided -> Produced.Decided(result.decision)
                is ClassificationEngineResult.Unavailable -> return releaseWith(
                    hold,
                    WorkflowCommandOutcome.Failed(
                        when (result.state) {
                            LayaRuntimeContract.State.Missing -> "The local model is not installed."
                            is LayaRuntimeContract.State.CorruptOrIncompatible ->
                                "The local model cannot be used on this device."
                            LayaRuntimeContract.State.Ready -> "The local model is not available."
                        },
                    ),
                )
                ClassificationEngineResult.Superseded -> return releaseWith(
                    hold,
                    WorkflowCommandOutcome.Cancelled,
                )
                is ClassificationEngineResult.Failed -> return releaseWith(
                    hold,
                    WorkflowCommandOutcome.Failed(result.reason),
                )
            }
        }
        val prediction = when (produced) {
            is Produced.Remembered -> ClassificationPrediction(
                source = PredictionSource.REMEMBERED_RULE,
                suggestedCategoryId = produced.category.id,
                candidateIds = emptyList(),
                probabilities = emptyList(),
                unknownShare = null,
                confidence = null,
                effectiveInputDigest = effectiveInputDigest(description),
            )
            is Produced.Decided -> produced.decision.classificationPrediction()
        }
        return completeClaim(scope, request, claim, prediction, produced, effective, hold)
    }

    /**
     * Finishes one classification request that holds the claim on its key.
     *
     * The stored prediction and the record of the request are written in one transaction,
     * and only by the attempt that still holds the claim: an attempt whose claim was taken
     * over writes nothing at all, so a late result can never replace the result of the
     * attempt that took it over, nor the user's own choice.
     */
    private suspend fun completeClaim(
        scope: WorkflowCallerScope,
        request: ClassificationRequest,
        claim: OperationClaim.Acquired,
        prediction: ClassificationPrediction,
        produced: Produced,
        effective: EffectiveDraftContext,
        hold: ClaimHold,
    ): WorkflowCommandOutcome {
        val outcome = try {
            database.withTransaction {
                val current = operations.findByKey(request.idempotencyKey)
                if (current == null ||
                    current.ownerToken != claim.ownerToken ||
                    current.outcome != OPERATION_OUTCOME_PENDING
                ) {
                    // The claim is no longer ours: another attempt owns this request.
                    null
                } else {
                    val result = when (
                        val stored = snapshots.recordPrediction(
                            draftId = request.draftId,
                            prediction = prediction,
                            expected = effective.dependencies,
                        )
                    ) {
                        is SnapshotWriteResult.Stored -> WorkflowCommandOutcome.Applied(
                            WorkflowChange.Classified(
                                draftId = request.draftId,
                                snapshotId = stored.snapshotId,
                                source = prediction.source,
                                suggestedCategoryId = prediction.suggestedCategoryId,
                                confidence = prediction.confidence,
                                decision = produced.decisionOrNull(),
                            ),
                        )
                        SnapshotWriteResult.Stale -> WorkflowCommandOutcome.Conflict(
                            "The context moved on while the prediction was made.",
                        )
                        SnapshotWriteResult.UserSelectionPreserved -> WorkflowCommandOutcome.NoChange(
                            change = WorkflowChange.Classified(
                                draftId = request.draftId,
                                snapshotId = 0L,
                                source = prediction.source,
                                suggestedCategoryId = prediction.suggestedCategoryId,
                                confidence = prediction.confidence,
                                decision = produced.decisionOrNull(),
                            ),
                            detail = "The user's own category choice stands.",
                        )
                        SnapshotWriteResult.NotFound -> WorkflowCommandOutcome.NotFound
                    }
                    when (result) {
                        is WorkflowCommandOutcome.Applied -> {
                            finishClaim(claim, OPERATION_OUTCOME_APPLIED, result.change)
                            result
                        }
                        is WorkflowCommandOutcome.NoChange -> {
                            finishClaim(claim, OPERATION_OUTCOME_NO_CHANGE, result.change)
                            result
                        }
                        else -> {
                            // A refusal records nothing: the key is free again.
                            operations.releaseClaim(
                                id = claim.operationId,
                                ownerToken = claim.ownerToken,
                                pendingOutcome = OPERATION_OUTCOME_PENDING,
                            )
                            result
                        }
                    }
                }
            }
        } catch (lost: ClaimLostException) {
            null
        }
        // The claim is settled: the row carries the outcome of the request, or it was
        // released, or it belongs to another attempt now. In every case this attempt is
        // done with the key, and the key is free for the next attempt.
        hold.finish()
        return outcome ?: WorkflowCommandOutcome.Failed(
            "Another attempt owns this request; its result was not written.",
        )
    }

    /**
     * Finishes the claim of one request and records what it reached.
     *
     * The row moves only when it still carries this attempt's own token, so an attempt
     * whose claim was taken over cannot write over the attempt that took it. The failure
     * rolls the whole transaction back, so a stored prediction is never left without the
     * record of the request that made it.
     */
    private suspend fun finishClaim(
        claim: OperationClaim.Acquired,
        outcome: String,
        change: WorkflowChange?,
    ) {
        val finished = operations.completeClaim(
            id = claim.operationId,
            ownerToken = claim.ownerToken,
            outcome = outcome,
            changeSummary = change?.let { encodeWorkflowChange(it) },
            pendingOutcome = OPERATION_OUTCOME_PENDING,
        )
        if (finished != 1) throw ClaimLostException()
    }

    /**
     * Runs the model for one request that holds the claim on its key.
     *
     * The work runs in a scope of its own, and that is the whole point of it. The local
     * model is a native runtime whose execution cannot be interrupted: cancelling a
     * caller withdraws the delivery of the answer, it does not stop the inference. The
     * boundary therefore never pretends that it can stop the work — the work is not
     * cancelled with the caller, the claim is held until the work has really ended, and
     * only then is the key given back. A request whose caller stopped waiting is failed
     * at once, and its model work finishes in the background, where its answer is
     * discarded.
     *
     * The claim is kept alive while the work runs, and the keeping-alive is watched. An
     * extension that fails, or one that finds no claim, is not ignored: the attempt can
     * no longer prove that the key is its own, so the request is failed instead of
     * being carried out under a claim it cannot vouch for. The work itself is not
     * interrupted — it cannot be — but the in-process guard keeps every other attempt
     * away from the key until the work has really ended, so the model is asked once for
     * one request.
     */
    private suspend fun runModelWork(
        hold: ClaimHold,
        context: LayaEffectiveContext,
        assignable: List<CategoryEntity>,
    ): ClassificationEngineResult {
        val lost = CompletableDeferred<Unit>()
        // The work is marked as running before it is started, so that the work can
        // never end before it was known to be running.
        hold.workStarted()
        val work = modelWorkScope.async {
            val heartbeat = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(claimRenewIntervalMillis)
                    if (!keepClaimAlive(hold.claim)) {
                        // The claim could not be kept: the attempt stops waiting for the
                        // model, and the request is failed.
                        lost.complete(Unit)
                        break
                    }
                }
            }
            try {
                engine.classify(context, assignable)
            } finally {
                heartbeat.cancel()
                hold.workEnded()
            }
        }
        return try {
            select {
                work.onAwait { it }
                lost.onAwait { throw ClaimLostException(CLAIM_NOT_KEPT_REASON) }
            }
        } catch (cancelled: CancellationException) {
            // The caller stopped waiting. The work keeps running, and the claim is given
            // back when the work has really ended.
            hold.abandon()
            throw cancelled
        }
    }

    /**
     * Extends the claim of one request that is still running.
     *
     * The extension is a write of its own, so no transaction is held while the model
     * works, and it moves the claim only while this attempt still holds it. A write that
     * fails, and an update that finds no claim, are both the same answer: this attempt
     * cannot prove that the key is its own any more, and it must not keep working as if
     * it could.
     */
    private suspend fun keepClaimAlive(claim: OperationClaim.Acquired): Boolean {
        val now = clock.nowEpochMillis()
        return try {
            operations.renewClaim(
                id = claim.operationId,
                ownerToken = claim.ownerToken,
                leaseExpiresAtEpochMillis = now + claimLeaseMillis,
                pendingOutcome = OPERATION_OUTCOME_PENDING,
            ) == 1
        } catch (error: Exception) {
            // The claim could not be extended. The failure is not swallowed: it is
            // reported to the request, which is failed rather than carried out under a
            // claim this attempt cannot vouch for.
            false
        }
    }

    /**
     * Whether the durable claim of one request is still the claim of this attempt.
     *
     * The row names the attempt that may finish the request, and the check is made
     * before the model is asked. An attempt whose claim was taken over — by another
     * attempt in this process, or by anything that wrote the row without the guard —
     * therefore does not ask the model for a request that another attempt owns.
     */
    private suspend fun stillHoldsTheClaim(
        idempotencyKey: String,
        claim: OperationClaim.Acquired,
    ): Boolean {
        val current = operations.findByKey(idempotencyKey) ?: return false
        return current.ownerToken == claim.ownerToken &&
            current.outcome == OPERATION_OUTCOME_PENDING
    }

    /**
     * Releases the claim of one request without a result.
     *
     * The release is not cancellable: a request that was cancelled must still give its
     * key back, or the key would stay reserved for a work that never finished.
     */
    private suspend fun releaseClaim(claim: OperationClaim.Acquired) {
        withContext(NonCancellable) {
            operations.releaseClaim(
                id = claim.operationId,
                ownerToken = claim.ownerToken,
                pendingOutcome = OPERATION_OUTCOME_PENDING,
            )
        }
    }

    /** Gives the claim of one request back and answers with the given outcome. */
    private suspend fun releaseWith(
        hold: ClaimHold,
        outcome: WorkflowCommandOutcome,
    ): WorkflowCommandOutcome {
        hold.giveBack()
        return outcome
    }

    /**
     * Claims the idempotency key of one request before its work starts.
     *
     * The claim is a durable row, not a lock: it is written and committed before the
     * expensive work begins, it is visible to every command-service instance and to every
     * process, and it is taken over only when the attempt that held it is gone and its
     * lease has ended.
     *
     * The claim is also held in the process, by the in-process guard: the row says what
     * a request may be done once, and the guard says which attempt is still alive to do
     * it. The two fail independently, and the request is safe only while both hold.
     *
     * They are also taken together, and never apart. The key is taken in the process
     * before the durable row is written, and a takeover takes the key before it updates
     * the row, so there is no moment in which the row names an attempt that the guard
     * does not name. A key is never taken away from the attempt that holds it, so an
     * attempt that comes back after its claim was taken over finds the key held by the
     * attempt that took it, and it does no work.
     *
     * Because the key is taken first, there is a window in which the key is held and the
     * durable row does not exist yet. The reservation of the held key says which caller
     * holds it and which request it is running, so a competitor that arrives in that
     * window is answered the way the durable row would answer it: the same request is
     * running, and a different request or a different caller is a conflict. The same
     * key and the same request therefore always get the same answer, whichever side of
     * the durable write the request arrives on.
     */
    private suspend fun claimOperation(
        scope: WorkflowCallerScope,
        idempotencyKey: String,
        digest: String,
    ): OperationClaim {
        val now = clock.nowEpochMillis()
        val ownerToken = UUID.randomUUID().toString()
        val existing = operations.findByKey(idempotencyKey)
        if (existing != null) {
            return resolveClaim(existing, scope, digest, now, ownerToken)
        }
        // No claim exists yet, so the key is taken in the process first. The key is
        // taken before the durable claim is written, so that there is no moment in
        // which the durable claim exists while the guard names nobody: a competitor
        // that found such a claim would take it over while its owner was still alive,
        // and the owner would then ask the model a second time for one request.
        when (
            val acquisition = WorkflowClaimGuard.acquire(
                idempotencyKey,
                ownerToken,
                scope.scopeId,
                digest,
            )
        ) {
            is WorkflowClaimGuard.Acquisition.Acquired -> Unit
            is WorkflowClaimGuard.Acquisition.Held -> {
                // Another attempt in this process is taking the key: the model is not
                // asked a second time for one request.
                val winner = operations.findByKey(idempotencyKey)
                if (winner != null) {
                    return resolveClaim(winner, scope, digest, now, ownerToken)
                }
                // The durable claim of the attempt that holds the key is not written
                // yet, so there is no record to compare this request with. The
                // reservation of that attempt says which caller it acts for and which
                // request it is running, and the request is answered the way the
                // durable claim would answer it: the same request is running, and a
                // different request or a different caller is a conflict. The two
                // windows answer alike, so the same key and the same request always
                // get the same answer.
                if (acquisition.scopeId != scope.scopeId ||
                    acquisition.payloadDigest != digest
                ) {
                    return OperationClaim.Refused(
                        WorkflowCommandOutcome.Conflict(
                            CONFLICT_KEY_IN_USE,
                        ),
                    )
                }
                return OperationClaim.InProgress
            }
        }
        val claim = try {
            claimTheNewKey(scope, idempotencyKey, digest, now, ownerToken)
        } catch (error: Throwable) {
            // The claim was not established: the key is given back, so that a later
            // attempt may take it.
            WorkflowClaimGuard.release(idempotencyKey, ownerToken)
            throw error
        }
        if (claim !is OperationClaim.Acquired) {
            // The key belongs to somebody else after all: it is given back, so that
            // the attempt that really owns it is the only one that holds it.
            WorkflowClaimGuard.release(idempotencyKey, ownerToken)
        }
        return claim
    }

    /**
     * Writes the durable claim of a key that no attempt has claimed yet.
     *
     * The key is already held in the process when this is called, so the claim that is
     * written here is the claim of the attempt that holds the key, and the two can
     * never name different owners.
     */
    private suspend fun claimTheNewKey(
        scope: WorkflowCallerScope,
        idempotencyKey: String,
        digest: String,
        now: Long,
        ownerToken: String,
    ): OperationClaim {
        WorkflowClaimGuard.holdAt(idempotencyKey, WorkflowClaimGuard.Point.KEY_TAKEN)
        val inserted = try {
            operations.insert(
                WorkflowOperationEntity(
                    idempotencyKey = idempotencyKey,
                    scopeId = scope.scopeId,
                    command = COMMAND_REQUEST_CLASSIFICATION,
                    payloadDigest = digest,
                    outcome = OPERATION_OUTCOME_PENDING,
                    changeSummary = null,
                    ownerToken = ownerToken,
                    leaseExpiresAtEpochMillis = now + claimLeaseMillis,
                    createdAtEpochMillis = now,
                ),
            )
        } catch (error: SQLiteConstraintException) {
            null
        }
        if (inserted != null) {
            WorkflowClaimGuard.holdAt(idempotencyKey, WorkflowClaimGuard.Point.CLAIM_WRITTEN)
            return OperationClaim.Acquired(inserted, ownerToken)
        }
        // Another attempt claimed the key first: its claim is the one that counts.
        val winner = operations.findByKey(idempotencyKey)
            ?: return OperationClaim.InProgress
        return resolveClaim(winner, scope, digest, now, ownerToken)
    }

    /**
     * What an existing row under the key means for this attempt.
     *
     * A row of another caller and a row of another request are both conflicts, and they
     * are answered alike: neither names the other row, and neither says whose record
     * holds the key. A finished row is the answer to this request. A pending row is a
     * claim: it is taken over only when its attempt is gone.
     *
     * An ended lease is not by itself the proof that the attempt is gone. The attempt
     * that holds the claim extends the lease with a write, and that write can fail or
     * arrive late; when it does, the attempt may still be working, and its model work
     * may still be running. The in-process guard is that proof: while a live attempt
     * holds the key in this process, the claim is not taken over, and the model is not
     * asked a second time for one request. When the process is gone, the guard is gone
     * with it, and the claim of the interrupted attempt may be taken over as before.
     *
     * The takeover is the one moment in which the claim moves, and it is taken in the
     * same order as every other claim: the key is taken in the process first, and the
     * row is updated second. A takeover therefore never leaves the row naming an
     * attempt that the guard does not name, and the attempt that took the claim over
     * keeps it — an attempt that comes back later finds the key held and does no work.
     */
    private suspend fun resolveClaim(
        existing: WorkflowOperationEntity,
        scope: WorkflowCallerScope,
        digest: String,
        now: Long,
        ownerToken: String,
    ): OperationClaim {
        if (existing.scopeId != scope.scopeId) {
            return OperationClaim.Refused(
                WorkflowCommandOutcome.Conflict(
                    CONFLICT_KEY_IN_USE,
                ),
            )
        }
        if (existing.payloadDigest != digest) {
            return OperationClaim.Refused(
                WorkflowCommandOutcome.Conflict(
                    CONFLICT_KEY_IN_USE,
                ),
            )
        }
        if (existing.outcome != OPERATION_OUTCOME_PENDING) {
            return OperationClaim.Replayed(existing)
        }
        val lease = existing.leaseExpiresAtEpochMillis
        val currentOwner = existing.ownerToken
        if (lease == null || currentOwner == null || now < lease) {
            return OperationClaim.InProgress
        }
        // The lease ended. The attempt that held the claim may still be alive in this
        // process: its work may still be running, so the claim is not taken over and
        // the model is not asked a second time. The guard is that proof, and it is
        // taken *before* the row is updated, so that the row and the guard can never
        // name two different owners — and so that the attempt that took the claim over
        // cannot be robbed of it by an attempt that comes back later.
        val acquisition = WorkflowClaimGuard.acquire(
            existing.idempotencyKey,
            ownerToken,
            scope.scopeId,
            digest,
        )
        if (acquisition !is WorkflowClaimGuard.Acquisition.Acquired) {
            // A live attempt holds the key: the claim is not taken over.
            return OperationClaim.InProgress
        }
        // The attempt that held the claim is gone: this attempt may take it over.
        val taken = try {
            operations.takeOverClaim(
                id = existing.id,
                expectedOwnerToken = currentOwner,
                ownerToken = ownerToken,
                leaseExpiresAtEpochMillis = now + claimLeaseMillis,
                pendingOutcome = OPERATION_OUTCOME_PENDING,
            )
        } catch (error: Throwable) {
            WorkflowClaimGuard.release(existing.idempotencyKey, ownerToken)
            throw error
        }
        if (taken != 1) {
            // The claim moved on while this attempt was taking it: the key is given
            // back, and the request is answered as running.
            WorkflowClaimGuard.release(existing.idempotencyKey, ownerToken)
            return OperationClaim.InProgress
        }
        WorkflowClaimGuard.holdAt(existing.idempotencyKey, WorkflowClaimGuard.Point.CLAIM_WRITTEN)
        return OperationClaim.Acquired(existing.id, ownerToken)
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
        return try {
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
     * The policy is the app's evidence, not the caller's word: the proposal must carry
     * evidence this app computed, the app's facts about the pair must still prove one
     * association, and that evidence must still be the evidence the proposal was made on.
     * A link whose evidence is a reason somebody stated, or one whose facts have moved on
     * since the proposal, is not attached by another caller: it stays proposed, and the
     * user decides.
     */
    private suspend fun attachPolicyRefusal(
        link: ContextLinkEntity,
    ): WorkflowCommandOutcome? {
        val event = eventDao.findById(link.eventId)
            ?: return WorkflowCommandOutcome.Denied(WorkflowDenialReason.ATTACH_POLICY)
        val draft = draftDao.findById(link.draftId)
            ?: return WorkflowCommandOutcome.Denied(WorkflowDenialReason.ATTACH_POLICY)
        val current = linkEvidence.evidenceFor(event, draft)
        if (current.verdict != LinkEvidenceVerdict.UNIQUE) {
            return WorkflowCommandOutcome.Denied(WorkflowDenialReason.ATTACH_POLICY)
        }
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

    /** What one classification request produced. */
    private sealed interface Produced {
        /** A saved exact-description rule answered; the model was never asked. */
        data class Remembered(val category: CategoryEntity) : Produced

        /** The model produced a decision. */
        data class Decided(val decision: LayaContextDecision) : Produced

        /** The decision this request produced, when it produced one. */
        fun decisionOrNull(): LayaContextDecision? = when (this) {
            is Remembered -> null
            is Decided -> decision
        }
    }

    /**
     * What the claim on an idempotency key means for this attempt.
     *
     * A claim is either held by this attempt, held by another attempt that is still
     * running, taken from an attempt that is gone, or already finished — and the finished
     * row is the answer to the request.
     */
    private sealed interface OperationClaim {
        /** This attempt holds the claim and may do the work. */
        data class Acquired(val operationId: Long, val ownerToken: String) : OperationClaim

        /** The request under this key already finished; its record is the answer. */
        data class Replayed(val recorded: WorkflowOperationEntity) : OperationClaim

        /** Another attempt is running under this key; the work is not repeated. */
        data object InProgress : OperationClaim

        /** The key names another request or belongs to another caller. */
        data class Refused(val outcome: WorkflowCommandOutcome) : OperationClaim
    }

    /**
     * The claim of one request while the request's work runs.
     *
     * The claim is held by two facts that fail independently: the durable row, which
     * fences the writes and lets a request whose attempt was interrupted be taken over,
     * and the in-process guard, which proves that the attempt holding the key is still
     * alive even when the database cannot be written. Either fact alone would let a
     * second attempt ask the model a second time for one request.
     *
     * The claim is given back exactly once, and never while the model work is still
     * running: the local model is a native runtime whose execution cannot be
     * interrupted, so a claim that was given back early would let a second attempt
     * start a second inference for the same request.
     */
    private inner class ClaimHold(
        val claim: OperationClaim.Acquired,
        private val key: String,
    ) {
        /** Whether the model work of this request is running right now. */
        private val workRunning = AtomicBoolean(false)

        /** Whether the request gave up before its work ended. */
        private val abandoned = AtomicBoolean(false)

        /** Whether the claim was already settled, so it is not given back twice. */
        private val settled = AtomicBoolean(false)

        /** The model work has started: the claim is held until the work really ends. */
        fun workStarted() {
            workRunning.set(true)
        }

        /** The model work has really ended: the claim may be given back now. */
        suspend fun workEnded() {
            workRunning.set(false)
            if (abandoned.get()) giveBack()
        }

        /** The request gave up: the claim is given back, unless the work still runs. */
        suspend fun abandon() {
            abandoned.set(true)
            if (!workRunning.get()) giveBack()
        }

        /** The request settled the claim itself: the row carries its outcome. */
        fun finish() {
            settled.set(true)
            releaseGuard()
        }

        /**
         * Gives the claim back: the row is deleted and the key is free again.
         *
         * A release that cannot be written leaves the row pending with an ended lease,
         * and a later attempt takes that row over; the key is therefore never reserved
         * forever, and the failure of the release is not fatal to anything else.
         */
        suspend fun giveBack() {
            if (!settled.compareAndSet(false, true)) return
            try {
                releaseClaim(claim)
            } catch (error: Exception) {
                // The row stays pending until its lease ends, and a later attempt takes
                // it over then. There is no caller left to tell about it.
            } finally {
                releaseGuard()
            }
        }

        /** Gives the in-process guard back, when this attempt is the one that holds it. */
        private fun releaseGuard() {
            WorkflowClaimGuard.release(key, claim.ownerToken)
        }
    }

    internal companion object {
        const val COMMAND_SAVE_CAPTURE = "SAVE_CAPTURE"
        const val COMMAND_REVISE_CAPTURE = "REVISE_CAPTURE"
        const val COMMAND_UPDATE_EVENT = "UPDATE_EVENT"
        const val COMMAND_PROPOSE_LINK = "PROPOSE_LINK"
        const val COMMAND_ATTACH_LINK = "ATTACH_LINK"
        const val COMMAND_REJECT_LINK = "REJECT_LINK"
        const val COMMAND_UNLINK_LINK = "UNLINK_LINK"
        const val COMMAND_REQUEST_CLASSIFICATION = "REQUEST_CLASSIFICATION"

        /** How long a claim on an idempotency key is held before it may be taken over. */
        const val DEFAULT_CLAIM_LEASE_MILLIS = 5 * 60 * 1_000L

        /**
         * The scope that owns the model work of a request.
         *
         * The work must not stop when the caller that asked for it stops waiting: the
         * local model is a native runtime whose execution cannot be interrupted, and the
         * claim of a request may only be given back when the work has really ended. The
         * scope belongs to the app, not to a caller, so no screen and no request can
         * cancel it; it holds nothing but the work that is running.
         */
        val modelWorkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** What a caller is told when the request under its key is still running. */
        const val IN_PROGRESS_REASON =
            "A request with this idempotency key is already running; " +
                "its result is recorded under that key."

        /**
         * What a caller is told when the claim on its key could not be kept.
         *
         * The claim is the fence that keeps one request to one model call. An attempt
         * that cannot extend its claim cannot prove that the key is its own, and it
         * does not carry the request out under a claim it cannot vouch for.
         */
        const val CLAIM_NOT_KEPT_REASON =
            "The claim on this request could not be kept, so the request was not carried out."

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

/**
 * The claim of a request was lost while the attempt was working on it.
 *
 * The attempt that loses its claim writes nothing at all: the stored prediction and the
 * record of the request are one transaction, and that transaction is rolled back whole.
 */
private class ClaimLostException(
    message: String = "The claim of the request was taken over.",
) : RuntimeException(message)

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
