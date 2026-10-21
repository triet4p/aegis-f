package io.github.triet4p.aegisf.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import io.github.triet4p.aegisf.data.laya.LayaContextDecision
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract
import io.github.triet4p.aegisf.ui.ManualDateTime
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
    private val transactionDao = database.transactionDao()
    private val categoryDao = database.categoryDao()
    private val reviewDao = database.bankDraftReviewDao()
    private val query = WorkflowQueryService(database)
    private val captures = CaptureMessageRepository(database = database, clock = clock, timezoneId = timezoneId)
    private val events = EventContextRepository(database = database, clock = clock)
    /** The app's own evidence about a proposed association; never read from a request. */
    private val linkEvidence = LinkEvidencePolicy(database)
    private val snapshots = ClassificationSnapshotRepository(database = database, clock = clock)
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
            ContextLinkResult.EventAlreadyRecorded -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_ALREADY_RECORDED,
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
            ContextLinkResult.EventAlreadyRecorded -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_ALREADY_RECORDED,
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
            ContextLinkResult.EventAlreadyRecorded -> WorkflowCommandOutcome.Denied(
                WorkflowDenialReason.EVENT_ALREADY_RECORDED,
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
        when (val result = reviewDao.saveReview(request.draftId, request.expectedRevision, request.edits)) {
            is BankDraftReviewResult.Saved -> WorkflowCommandOutcome.Applied(
                WorkflowChange.DraftReviewSaved(request.draftId, result.revision),
            )
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
