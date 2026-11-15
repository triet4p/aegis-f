package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.data.AgentConversationEntity
import io.github.triet4p.aegisf.data.AgentRunAttemptEntity
import io.github.triet4p.aegisf.data.AgentRunEntity
import io.github.triet4p.aegisf.data.AgentTraceEventEntity
import io.github.triet4p.aegisf.data.AgentTraceStore
import io.github.triet4p.aegisf.data.CanonicalAgentRetryPolicy
import io.github.triet4p.aegisf.data.AGENT_TRACE_COMPLETION_COMPLETE
import io.github.triet4p.aegisf.data.AGENT_TRACE_COMPLETION_INTERRUPTED
import io.github.triet4p.aegisf.data.AGENT_TRACE_COMPLETION_NOT_APPLICABLE
import io.github.triet4p.aegisf.data.AGENT_TRACE_DIRECTION_INTERNAL
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_ATTEMPT_OUTCOME
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_ATTEMPT_STARTED
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_FAILURE
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_REQUEST
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_RESPONSE
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_STREAM_CHUNK
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_STREAM_END
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_RUN_ADMITTED
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_RUN_CANCELLED
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_TOOL_CALL
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_TOOL_ERROR
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_TOOL_RESULT
import io.github.triet4p.aegisf.data.AGENT_TRACE_RUN_SCOPE_ATTEMPT
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowOperation
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The durable S8.10 history of one caller session's agent runs, plus its local production trace.
 *
 * Four stores stay separate:
 *
 * (a) the user-visible conversation ([AgentConversationEntity]: request, status, and answer
 * lines only);
 * (b) the business captures and effect journal (owned by the existing command services, never
 * here);
 * (c) the run/attempt journal ([AgentRunEntity] / [AgentRunAttemptEntity]: run identity, retry
 * attempts, allowed effect references, pending/completed/unknown states, and privacy-safe
 * observations);
 * (d) the local production trace ([io.github.triet4p.aegisf.data.AgentTraceEventEntity] through
 * [io.github.triet4p.aegisf.data.AgentTraceStore]): ordered session/run/attempt events with the
 * complete provider request/response bodies, tool arguments/results/errors, clarification
 * questions and replies, cancellation and terminal outcomes.
 *
 * The trace exists because the journal above deliberately keeps aggregates and effect references
 * only. It is private local diagnostics: it never stores authentication headers, credentials or
 * transport secrets; it is never sent anywhere; and it is never part of [safeExport], which stays
 * exactly the conversation plus the run rows. A trace row describes evidence and authorizes
 * nothing. Missing evidence — a pre-trace run, an interrupted exchange, a failed trace write — is
 * recorded as an explicit `EVIDENCE_GAP` instead of an invented body.
 *
 * Vault mappings, protocol continuation items and provider reasoning are still not columns of the
 * conversation or the journal. Where a provider returned reasoning/continuation items, they are
 * retained only inside the local trace's complete provider response body.
 */
class AgentRunHistory(
    private val database: LedgerDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val traces: AgentTraceStore = AgentTraceStore(database.agentTraceDao(), clock),
) {
    private val runs = database.agentRunDao()
    private val attempts = database.agentRunAttemptDao()
    private val conversation = database.agentConversationDao()
    private val queryService = WorkflowQueryService(database)

    /**
     * Admits one run for a trusted request, or replays the run an identical request
     * already admitted. The request fingerprint is the one-way digest of the masked
     * request (intents, masked text, limits, vault scope tag), so a repeated user send
     * replays the same run without storing the request twice.
     */
    suspend fun admitRun(
        scope: WorkflowCallerScope.AgentSession,
        request: WorkflowRunRequest,
        boundary: MaskedPromptBoundary,
        maxOutputTokens: Int?,
    ): AdmittedRun {
        require(scope.scopeId.isNotBlank()) { "A run needs a trusted session identity." }
        val maskedRequest = boundary.maskOutbound(request.userRequest, MaskedPath.HISTORY)
        val limits = request.limits
        val limitsFingerprint = "${limits.maxToolCalls}/" +
            "${limits.maxContextCharacters}/${limits.maxWallClockMillis}/" +
            "${limits.maxRepeatedIdenticalCalls}/${limits.maxCaptureObservations}"
        val fingerprint = CanonicalAgentRetryPolicy.requestFingerprint(
            scopeId = scope.scopeId,
            intents = request.intents.map { it.name },
            maskedRequest = maskedRequest,
            limitsFingerprint = limitsFingerprint,
            vaultScopeTag = boundary.vault.scopeTag,
        )
        val runId = CanonicalAgentRetryPolicy.runIdentity(scope.scopeId, fingerprint)
        val now = clock()
        val row = AgentRunEntity(
            runId = runId,
            scopeId = scope.scopeId,
            intentNames = request.intents.map { it.name }.sorted().joinToString(","),
            requestFingerprint = fingerprint,
            attempt = 1,
            state = "PENDING",
            stopReason = null,
            maxOutputTokens = maxOutputTokens,
            answerDigest = null,
            requestText = maskedRequest.take(MAX_REQUEST_CHARS),
            answerText = null,
            revisionEpochMillis = now,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val firstAttempt = AgentRunAttemptEntity(
            runId = runId,
            attempt = 1,
            idempotencyKey = CanonicalAgentRetryPolicy.attemptKey(runId, 1),
            state = "PENDING",
            stopReason = null,
            effectKind = null,
            eventId = null,
            eventRevision = null,
            linkId = null,
            linkState = null,
            linkRevision = null,
            draftId = null,
            decisionSource = null,
            selectedCategoryId = null,
            observationJson = JSONObject().put("admitted", true).toString(),
            ownerToken = UUID.randomUUID().toString(),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val firstInsert = runs.insert(row)
        val stored = runs.findByRunId(runId)!!
        val replayed = firstInsert == -1L
        if (!replayed) {
            attempts.insert(firstAttempt)
            appendLine(runId, "USER", maskedRequest.take(MAX_REQUEST_CHARS), now)
            appendLine(runId, "STATUS", "Run admitted.", now)
            traces.append(
                runId = runId,
                scopeId = scope.scopeId,
                attempt = 1,
                kind = AGENT_TRACE_KIND_RUN_ADMITTED,
                payloadJson = JSONObject()
                    .put("intents", JSONArray(request.intents.map { it.name }.sorted()))
                    .put("maskedRequest", maskedRequest)
                    .put("limits", JSONObject()
                        .put("maxToolCalls", limits.maxToolCalls)
                        .put("maxContextCharacters", limits.maxContextCharacters)
                        .put("maxWallClockMillis", limits.maxWallClockMillis)
                        .put("maxRepeatedIdenticalCalls", limits.maxRepeatedIdenticalCalls)
                        .put("maxCaptureObservations", limits.maxCaptureObservations))
                    .put("maxOutputTokens", maxOutputTokens ?: JSONObject.NULL)
                    .put("attemptKey", CanonicalAgentRetryPolicy.attemptKey(runId, 1))
                    .toString(),
            )
            return AdmittedRun(
                runId = runId,
                scopeId = scope.scopeId,
                attempt = stored.attempt,
                attemptKey = CanonicalAgentRetryPolicy.attemptKey(runId, 1),
                ownerToken = attempts.findForRun(runId).first { it.attempt == 1 }.ownerToken,
                replayed = false,
            )
        }
        val priorAttempts = attempts.findForRun(runId)
        return AdmittedRun(
            runId = runId,
            scopeId = scope.scopeId,
            attempt = stored.attempt,
            attemptKey = priorAttempts.first { it.attempt == 1 }.idempotencyKey,
            ownerToken = priorAttempts.first { it.attempt == 1 }.ownerToken,
            replayed = true,
        )
    }

    /**
     * Claims the admitted run for execution. Returns false when another opener already
     * holds it, so a repeated send never starts a second workflow for one run.
     */
    suspend fun claimForExecution(runId: String, attempt: Int): Boolean {
        val claimed = runs.claimPending(runId, attempt, clock()) == 1
        if (claimed) {
            val stored = runs.findByRunId(runId)
            if (stored != null) {
                traces.append(
                    runId = runId,
                    scopeId = stored.scopeId,
                    attempt = attempt,
                    kind = AGENT_TRACE_KIND_ATTEMPT_STARTED,
                    completion = AGENT_TRACE_COMPLETION_NOT_APPLICABLE,
                    payloadJson = JSONObject()
                        .put("attempt", attempt)
                        .put("reason", "claim")
                        .put("state", "RUNNING")
                        .toString(),
                )
            }
        }
        return claimed
    }

    /**
     * Records the durable end of one attempt: settles the attempt row and advances the
     * run row to its terminal (or clarifying) state. The attempt token gates the
     * settle, so a late reply cannot overwrite the run's current attempt.
     */
    suspend fun recordAttemptOutcome(
        runId: String,
        attempt: Int,
        outcome: WorkflowRunOutcome,
        boundary: MaskedPromptBoundary,
    ) {
        val now = clock()
        val rows = attempts.findForRun(runId)
        val row = rows.firstOrNull { it.attempt == attempt } ?: return
        val token = row.ownerToken ?: return
        val safeObservation = retryAwareObservation(row, outcome, boundary)
        val (attemptState, reason, effect) = attemptOutcomeOf(outcome)
        attempts.settle(
            id = row.id,
            ownerToken = token,
            state = attemptState,
            stopReason = reason,
            effectKind = effect?.kind,
            eventId = effect?.eventId,
            eventRevision = effect?.eventRevision,
            linkId = effect?.linkId,
            linkState = effect?.linkState,
            linkRevision = effect?.linkRevision,
            draftId = effect?.draftId,
            decisionSource = null,
            selectedCategoryId = effect?.categoryId,
            observationJson = safeObservation,
            now = now,
        )
        when (outcome) {
            is WorkflowRunOutcome.Completed -> {
                val answer = outcome.answer.take(MAX_ANSWER_CHARS)
                val maskedAnswer = boundary.maskOutbound(answer, MaskedPath.HISTORY)
                appendLine(runId, "AGENT", maskedAnswer, now)
                runs.finish(
                    runId,
                    "SUCCEEDED",
                    CanonicalAgentRetryPolicy.RETRY_CODE_NONE,
                    maskedAnswer,
                    maskedAnswer.take(MAX_ANSWER_CHARS),
                    now,
                )
            }
            is WorkflowRunOutcome.Clarifying -> {
                // The question the user actually read belongs to the durable conversation, in
                // order before the answer they give next. The trace keeps the structured event;
                // this line is the transcript a restored screen shows.
                val maskedQuestion = boundary.maskOutbound(outcome.question, MaskedPath.HISTORY)
                appendLine(runId, "AGENT", maskedQuestion, now)
                runs.markClarifying(runId, CanonicalAgentRetryPolicy.RETRY_CODE_CLARIFY, now)
            }
            is WorkflowRunOutcome.Refused -> {
                appendLine(runId, "STATUS", "The request was refused: ${outcome.reason}.", now)
                runs.finish(runId, "FAILED", outcome.reason, null, null, now)
            }
            is WorkflowRunOutcome.Incomplete -> {
                appendLine(
                    runId,
                    "STATUS",
                    "The attempt ended: ${outcome.reason.name.lowercase()}.",
                    now,
                )
                runs.finish(runId, "FAILED", outcome.reason.name, null, null, now)
            }
        }
        val stored = runs.findByRunId(runId)
        if (stored != null) {
            traces.append(
                runId = runId,
                scopeId = stored.scopeId,
                attempt = attempt,
                kind = AGENT_TRACE_KIND_ATTEMPT_OUTCOME,
                completion = AGENT_TRACE_COMPLETION_COMPLETE,
                payloadJson = boundary.redactTracePayload(
                    traceOutcomePayload(outcome, attemptState, reason, stored.state),
                ),
            )
        }
    }

    /**
     * Reconciles an uncertain write before any replay: re-reads the durable operation journal
     * under the keys the attempt actually used and reports whether it applied anything.
     *
     * The attempt's own per-tool keys come from its recorded tool results, with the run's session
     * journal as the fallback when the attempt's calls were not recorded. `Applied` and `Unknown`
     * both mean the caller must not replay this attempt; `NotApplied` is the only verdict that
     * permits a fresh attempt.
     */
    suspend fun reconcileUncertainWrite(
        runId: String,
        attempt: Int,
    ): UncertainWriteVerdict {
        val rows = attempts.findForRun(runId)
        val row = rows.firstOrNull { it.attempt == attempt }
            ?: return UncertainWriteVerdict.Unknown("no such attempt")
        if (row.state == "COMPLETED") {
            return UncertainWriteVerdict.Applied("the attempt already verified")
        }
        if (row.state == "CANCELLED") {
            return UncertainWriteVerdict.NotApplied("the attempt was cancelled")
        }
        val evidence = attemptJournalEvidence(runId, attempt)
        return when {
            evidence.possiblyApplied -> UncertainWriteVerdict.Applied(evidence.detail)
            evidence.undecided -> UncertainWriteVerdict.Unknown(evidence.detail)
            evidence.rows.any { it.outcome == "PENDING" } ->
                UncertainWriteVerdict.Unknown("the journal claim is still pending")

            else -> UncertainWriteVerdict.NotApplied(evidence.detail)
        }
    }

    /**
     * The durable journal evidence of one attempt, keyed by the keys that attempt actually used.
     *
     * A mutation is journaled under a per-tool key (`attemptKey:record`, `attemptKey:propose`,
     * `attemptKey:attach`, `attemptKey:draft-review`, or a host-minted key for a later distinct
     * call), never under the attempt row's nominal key. The attempt's own tool results record
     * that exact key, so a retry reconciles the real journal rows instead of looking up a key
     * that was never written.
     *
     * When the host ran without its trace recorder (or the trace write failed), the attempt's
     * per-call keys are unknown. The run's session scope is then read instead: one session owns
     * one run, so an applied row there may be this request's already-applied effect, and a replay
     * must not repeat it.
     */
    private suspend fun attemptJournalEvidence(
        runId: String,
        attempt: Int,
    ): AttemptJournalEvidence {
        val events = traces.findForAttempt(runId, attempt)
        val results = events.filter { it.kind == AGENT_TRACE_KIND_TOOL_RESULT }
        if (results.isNotEmpty()) {
            val keys = results.mapNotNull { event ->
                val payload = parsePayload(event.payloadJson) ?: return@mapNotNull null
                if (payload.optString("dispatch") != "dispatched") return@mapNotNull null
                payload.optString("operationKey").takeIf { it.isNotBlank() }
            }.distinct()
            val rows = keys.mapNotNull { key -> queryService.workflowOperation(key) }
            val answered = results.mapNotNull { it.callId }.toSet()
            val unanswered = events
                .filter { it.kind == AGENT_TRACE_KIND_TOOL_CALL }
                .mapNotNull { it.callId }
                .filterNot { it in answered }
            val raised = events.any { it.kind == AGENT_TRACE_KIND_TOOL_ERROR }
            return AttemptJournalEvidence(
                rows = rows,
                possiblyApplied = rows.any { it.outcome == "APPLIED" },
                undecided = (unanswered.isNotEmpty() || raised) &&
                    rows.none { it.outcome == "APPLIED" || it.outcome == "PENDING" },
                detail = when {
                    rows.isNotEmpty() -> "the attempt's own journal keys were re-read"
                    unanswered.isNotEmpty() || raised ->
                        "a dispatched call has no recorded answer; its effect cannot be ruled out"

                    else -> "the attempt's recorded tool calls wrote no journal row"
                },
            )
        }
        if (events.any { it.kind == AGENT_TRACE_KIND_TOOL_CALL || it.kind == AGENT_TRACE_KIND_TOOL_ERROR }) {
            // A call began but produced no recorded answer (a crash or an interrupted write):
            // whether it applied cannot be proved, so nothing may replay it.
            return AttemptJournalEvidence(
                rows = emptyList(),
                possiblyApplied = false,
                undecided = true,
                detail = "a tool call began but its result was not recorded",
            )
        }
        if (events.any { it.kind in RECORDER_OBSERVED_KINDS }) {
            // The recorder was live and the attempt produced no tool call at all.
            return AttemptJournalEvidence(
                rows = emptyList(),
                possiblyApplied = false,
                undecided = false,
                detail = "the recorded attempt dispatched no mutation",
            )
        }
        val stored = runs.findByRunId(runId)
            ?: return AttemptJournalEvidence(
                rows = emptyList(),
                possiblyApplied = false,
                undecided = true,
                detail = "the attempt has no journal evidence and no run scope",
            )
        val scoped = queryService.workflowOperationsForScope(stored.scopeId)
        return AttemptJournalEvidence(
            rows = scoped,
            possiblyApplied = scoped.any { it.outcome == "APPLIED" },
            undecided = false,
            detail = if (scoped.isEmpty()) {
                "the run's session recorded no journal row"
            } else {
                "the run's session journal was re-read because the attempt's calls were not recorded"
            },
        )
    }

    private data class AttemptJournalEvidence(
        val rows: List<WorkflowOperation>,
        val possiblyApplied: Boolean,
        val undecided: Boolean,
        val detail: String,
    )

    private fun parsePayload(json: String): JSONObject? = try {
        JSONObject(json)
    } catch (_: Exception) {
        null
    }

    /**
     * Cancels one run without claiming rollback. A stored effect stays stored and
     * stays verified; cancellation only ends the conversation and frees the keys for
     * a corrected request.
     */
    suspend fun cancelRun(runId: String, reason: String): Boolean {
        val now = clock()
        val stored = runs.findByRunId(runId)
        val rows = attempts.findForRun(runId)
        val cancelledAttempts = rows.filter { it.state == "PENDING" && it.ownerToken != null }
        cancelledAttempts.forEach { row ->
            try {
                attempts.cancel(row.id, row.ownerToken!!, reason, now)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        appendLine(runId, "STATUS", "The run was cancelled.", now)
        val cancelled = runs.cancel(runId, reason, now) == 1
        if (cancelled && stored != null) {
            traces.append(
                runId = runId,
                scopeId = stored.scopeId,
                attempt = AGENT_TRACE_RUN_SCOPE_ATTEMPT,
                kind = AGENT_TRACE_KIND_RUN_CANCELLED,
                direction = AGENT_TRACE_DIRECTION_INTERNAL,
                completion = AGENT_TRACE_COMPLETION_INTERRUPTED,
                payloadJson = JSONObject()
                    .put("reason", reason)
                    .put("attempts", JSONArray().also { out ->
                        cancelledAttempts.forEach { row -> out.put(row.attempt) }
                    })
                    .put("state", "CANCELLED")
                    .toString(),
            )
        }
        return cancelled
    }

    /** The durable user-visible conversation of one run, in display order. */
    suspend fun conversationForRun(runId: String): List<AgentConversationEntity> =
        conversation.findForRun(runId)

    /**
     * Appends the user's own answer to a clarification, masked for history.
     *
     * The call happens between reopening the clarifying run and resuming it, so a restored
     * transcript reads the question, the truthful continuation line and the answer in order. An
     * answer the masking boundary refuses is not persisted: the resume itself then settles as a
     * masking stop, and no raw or partially mapped text reaches the conversation.
     */
    suspend fun recordClarificationAnswer(
        runId: String,
        userAnswer: String,
        boundary: MaskedPromptBoundary,
    ) {
        val masked = try {
            boundary.maskOutbound(userAnswer, MaskedPath.HISTORY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        appendLine(runId, "USER", masked, clock())
    }

    /**
     * The newest durable runs across the app's conversations, newest first.
     *
     * The Chat destination reads this bounded list for its history sheet and for the one
     * conversation it shows after a process recreation. It is a read of the same run rows the
     * executor writes: nothing is resumed, claimed or inferred from it.
     */
    suspend fun recentRuns(limit: Int): List<AgentRunEntity> {
        require(limit in 1..MAX_RECENT_RUNS) { "A history page is bounded to $MAX_RECENT_RUNS runs." }
        return runs.findRecent(limit)
    }

    /** One durable run row by identity, for a history row or an interrupted transcript. */
    suspend fun runForId(runId: String): AgentRunEntity? = runs.findByRunId(runId)

    /**
     * The complete local production trace of one run, in append order: the run admission, each
     * attempt start, every provider request/response body, every tool call and result, the
     * clarification question and reply, and the attempt/run outcomes.
     *
     * This is private local diagnostics. It is deliberately not part of [safeExport]: callers are
     * the app's own evidence paths, never the hosted prompt or an export.
     */
    suspend fun traceForRun(runId: String): List<AgentTraceEventEntity> = traces.findForRun(runId)

    /** The trace events of one attempt of a run, in append order. */
    suspend fun traceForAttempt(runId: String, attempt: Int): List<AgentTraceEventEntity> =
        traces.findForAttempt(runId, attempt)

    /**
     * Reopens one failed run for its next bounded attempt. The returned attempt key is fresh
     * (attempt + 1) and the prior attempt row stays as history. A `CLARIFYING` run is not a
     * failed request: it is reopened by [reopenClarifying], so a retry can never replay an
     * original request over a question the user has not answered yet.
     */
    suspend fun reopenForRetry(
        scope: WorkflowCallerScope.AgentSession,
        runId: String,
    ): AdmittedRun? = reopenFrom(hostScope = scope, runId = runId, fromStates = setOf("FAILED"))

    /**
     * Decides whether a failed run may retry, before the caller appends any transcript entry.
     *
     * The retried attempt is the run's current durable attempt, never a remembered UI number.
     * [RetryBlockReason.ATTEMPTS_EXHAUSTED] and [RetryBlockReason.TERMINAL] are truthful dead
     * ends: no Retry action may be offered for them. [RetryBlockReason.POSSIBLY_APPLIED] means
     * the attempt's own journal keys recorded an applied effect (or cannot prove that none was
     * applied), so replaying its request could duplicate it.
     *
     * A failed clarification continuation is reported as [RetryTarget.CLARIFICATION]: the caller
     * re-issues the same question and answer, never the original request.
     */
    suspend fun retryAdmission(
        scope: WorkflowCallerScope.AgentSession,
        runId: String,
    ): RetryAdmission {
        val stored = runs.findByRunId(runId)
            ?: return RetryAdmission.Blocked(RetryBlockReason.UNAVAILABLE)
        if (stored.scopeId != scope.scopeId) {
            return RetryAdmission.Blocked(RetryBlockReason.UNAVAILABLE)
        }
        when (stored.state) {
            "RUNNING", "PENDING" -> return RetryAdmission.Blocked(RetryBlockReason.STILL_PROCESSING)
            "FAILED" -> Unit
            else -> return RetryAdmission.Blocked(RetryBlockReason.NOT_FAILED)
        }
        val current = stored.attempt
        if (current + 1 > CanonicalAgentRetryPolicy.MAX_ATTEMPTS) {
            return RetryAdmission.Blocked(RetryBlockReason.ATTEMPTS_EXHAUSTED)
        }
        val last = attempts.findForRun(runId).firstOrNull { it.attempt == current }
            ?: return RetryAdmission.Blocked(RetryBlockReason.UNAVAILABLE)
        if (last.state == "PENDING") {
            return RetryAdmission.Blocked(RetryBlockReason.STILL_PROCESSING)
        }
        val observation = parsePayload(last.observationJson)
        val retryable = if (observation != null && observation.has("retryable")) {
            observation.optBoolean("retryable")
        } else {
            // A row written before the retry classification was persisted: fall back to the
            // stored stop reason's own bounded vocabulary.
            CanonicalAgentRetryPolicy.isRetryableStop(last.stopReason ?: stored.stopReason.orEmpty())
        }
        if (!retryable) return RetryAdmission.Blocked(RetryBlockReason.TERMINAL)
        return when (reconcileUncertainWrite(runId, current)) {
            is UncertainWriteVerdict.Applied,
            is UncertainWriteVerdict.Unknown,
            -> RetryAdmission.Blocked(RetryBlockReason.POSSIBLY_APPLIED)

            is UncertainWriteVerdict.NotApplied -> RetryAdmission.Ready(
                currentAttempt = current,
                nextAttempt = current + 1,
                target = if (observation?.optString("mode") == "clarification") {
                    RetryTarget.CLARIFICATION
                } else {
                    RetryTarget.REQUEST
                },
            )
        }
    }

    /**
     * Reopens a suspended clarification for its next resume turn. Only a run still in
     * `CLARIFYING` moves; a FAILED, RUNNING, or terminal run returns null so a stale, foreign,
     * or blank continuation can never strand or rerun a failed workflow. A clarification answer
     * is a conversation turn, not a failure retry, so its attempt number is not capped by the
     * failure-retry policy.
     */
    suspend fun reopenClarifying(
        scope: WorkflowCallerScope.AgentSession,
        runId: String,
    ): AdmittedRun? = reopenFrom(hostScope = scope, runId = runId, fromStates = setOf("CLARIFYING"))

    private suspend fun reopenFrom(
        hostScope: WorkflowCallerScope.AgentSession,
        runId: String,
        fromStates: Set<String>,
    ): AdmittedRun? {
        val stored = runs.findByRunId(runId) ?: return null
        if (stored.scopeId != hostScope.scopeId) return null
        val next = stored.attempt + 1
        // Failure retries keep the bounded attempt policy. A clarification answer is a new
        // conversation turn, not a retried failure, so a session may continue clarifying without
        // a conversation-turn cap.
        if (stored.state != "CLARIFYING" && next > CanonicalAgentRetryPolicy.MAX_ATTEMPTS) return null
        val now = clock()
        val moved = when {
            stored.state !in fromStates -> 0
            stored.state == "FAILED" -> runs.reopenForRetry(runId, next, now, now)
            else -> runs.resumeClarifying(runId, next, now, now)
        }
        if (moved != 1) return null
        val key = CanonicalAgentRetryPolicy.attemptKey(runId, next)
        val token = UUID.randomUUID().toString()
        // A clarification lineage survives a failed answer turn: retrying that failure resumes
        // the same question and answer. Without this, a second retry of the same conversation
        // would be labelled as a request retry and would replay the original request instead.
        val continuing = stored.state == "CLARIFYING" ||
            attempts.findForRun(runId)
                .firstOrNull { it.attempt == stored.attempt }
                ?.let { prior -> parsePayload(prior.observationJson)?.optString("mode") }
                .orEmpty() == "clarification"
        attempts.insert(
            AgentRunAttemptEntity(
                runId = runId,
                attempt = next,
                idempotencyKey = key,
                state = "PENDING",
                stopReason = null,
                effectKind = null,
                eventId = null,
                eventRevision = null,
                linkId = null,
                linkState = null,
                linkRevision = null,
                draftId = null,
                decisionSource = null,
                // The mode survives the settle (see `retryAwareObservation`), so a later retry
                // knows whether this attempt replayed a request or continued a clarification.
                observationJson = JSONObject()
                    .put("retry", next)
                    .put("mode", if (continuing) "clarification" else "retry")
                    .toString(),
                ownerToken = token,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        appendLine(
            runId,
            "STATUS",
            if (continuing) {
                "The conversation continued after your answer (attempt $next)."
            } else {
                "The run was retried (attempt $next)."
            },
            now,
        )
        traces.append(
            runId = runId,
            scopeId = hostScope.scopeId,
            attempt = next,
            kind = AGENT_TRACE_KIND_ATTEMPT_STARTED,
            completion = AGENT_TRACE_COMPLETION_NOT_APPLICABLE,
            payloadJson = JSONObject()
                .put("attempt", next)
                .put("reason", if (stored.state == "FAILED") "retry" else "clarification")
                .put("state", "PENDING")
                .toString(),
        )
        return AdmittedRun(runId, hostScope.scopeId, next, key, token, replayed = false)
    }


    /** Appends the bounded retry summary line of one run (status only, no content). */
    suspend fun appendRetrySummary(runId: String) {
        appendLine(runId, "STATUS", "The run used a bounded retry.", clock())
    }

    /** Deletes every saved conversation, run and local trace; business stores stay untouched. */
    suspend fun deleteAllHistory(): Int = runs.deleteAll()

    /**
     * The privacy-safe export of one session: conversation lines plus run states only.
     * Operation keys, digests, attempt tokens, observations, vault mappings, protocol
     * continuation items, reasoning, and wire detail are never columns of the exported
     * rows and never leave through this path. The local production trace
     * (`agent_trace_events`) is a separate private store and is never included here.
     * Business captures and the effect journal are separate stores and are never
     * exported here either.
     */
    suspend fun safeExport(scopeId: String, limit: Int, offset: Int): org.json.JSONObject {
        val lines = conversation.findExportPage(scopeId, limit, offset)
        val out = org.json.JSONObject()
        val entries = org.json.JSONArray()
        lines.forEach { line ->
            entries.put(
                org.json.JSONObject()
                    .put("runId", line.runId)
                    .put("role", line.role)
                    .put("text", line.text)
                    .put("createdAt", line.createdAtEpochMillis),
            )
        }
        out.put("scopeId", scopeId)
        out.put("messages", entries)
        return out
    }

    private suspend fun appendLine(runId: String, role: String, text: String, now: Long) {
        return try {
            val existing = conversation.findForRun(runId)
            conversation.insert(
                AgentConversationEntity(
                    runId = runId,
                    sequence = existing.size,
                    role = role,
                    text = text.take(MAX_LINE_CHARS),
                    createdAtEpochMillis = now,
                ),
            )
            Unit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    /**
     * The privacy-safe attempt observation, extended with the durable retry facts a later
     * decision needs: whether this attempt's failure was retryable at all, the classified code,
     * and the mode the attempt was reopened in (a request retry or a clarification continuation).
     *
     * The facts are a boolean and fixed vocabulary codes, never content; a row written before
     * this extension keeps its prior JSON and is classified from its stop reason instead.
     */
    private fun retryAwareObservation(
        row: AgentRunAttemptEntity,
        outcome: WorkflowRunOutcome,
        boundary: MaskedPromptBoundary,
    ): String {
        val failure = AgentRunFailure.ofOutcome(outcome)
        val priorMode = parsePayload(row.observationJson)
            ?.optString("mode")
            ?.takeIf { it.isNotBlank() }
        return JSONObject(safeObservation(outcome, boundary))
            .put("retryable", failure?.retryable == true)
            .put("classification", failure?.code ?: CanonicalAgentRetryPolicy.RETRY_CODE_NONE)
            .apply { priorMode?.let { put("mode", it) } }
            .toString()
    }

    /**
     * Privacy-safe attempt observation, extended for S8.11 with actual usage and cache
     * facts. The masked line carries counts and identities only: turns, tool calls,
     * verified effects, total input/output tokens summed from reported per-turn usage,
     * cached input tokens summed the same way, the provider cache behavior, a stable
     * cache fingerprint prefix (when the run carries one), and the compaction record.
     * No raw text, no mappings, no keys, no credentials, no estimates: every number is
     * a sum of provider-reported usage fields, and unsupported routes record zeros
     * rather than invented savings.
     */
    private fun safeObservation(outcome: WorkflowRunOutcome, boundary: MaskedPromptBoundary): String {
        val usage = when (outcome) {
            is WorkflowRunOutcome.Completed -> outcome.usage
            is WorkflowRunOutcome.Clarifying -> outcome.usage
            is WorkflowRunOutcome.Incomplete -> outcome.usage
            else -> emptyList()
        }
        val support = when (outcome) {
            is WorkflowRunOutcome.Completed -> outcome.cacheSupport
            is WorkflowRunOutcome.Clarifying -> outcome.cacheSupport
            is WorkflowRunOutcome.Incomplete -> outcome.cacheSupport
            else -> null
        }
        val cacheFingerprint = when (outcome) {
            is WorkflowRunOutcome.Completed -> outcome.cacheKey?.fingerprint()
            is WorkflowRunOutcome.Clarifying -> outcome.cacheKey?.fingerprint()
            is WorkflowRunOutcome.Incomplete -> outcome.cacheKey?.fingerprint()
            else -> null
        }
        val compaction = when (outcome) {
            is WorkflowRunOutcome.Completed -> outcome.compaction
            is WorkflowRunOutcome.Incomplete -> outcome.compaction
            else -> null
        }
        val inTotal = usage.sumOf { it.inputTokens ?: 0 }
        val outTotal = usage.sumOf { it.outputTokens ?: 0 }
        val cachedTotal = usage.sumOf { it.cachedInputTokens ?: 0 }
        val masked = try {
            val summary = when (outcome) {
                is WorkflowRunOutcome.Completed ->
                    "completed turns=${outcome.turns} tools=${outcome.toolCalls} effects=${outcome.verifiedEffects.size}" +
                        (outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.NoEffect>()
                            .takeIf { it.isNotEmpty() }
                            ?.let { " no_effect=${it.joinToString(",") { effect -> effect.kindName }}" }
                            ?: "") +
                        (outcome.maskingRepairs.takeIf { it.isNotEmpty() }
                            ?.let { " masking_repairs=${it.size}" }
                            ?: "") +
                        " in=$inTotal out=$outTotal cached=$cachedTotal" +
                        " cache=${support?.name?.lowercase() ?: "na"}" +
                        (cacheFingerprint?.let { " key=${it.take(12)}" } ?: "") +
                        (compaction?.let { " compacted=${it.droppedToolResults}" } ?: "")
                is WorkflowRunOutcome.Clarifying -> "clarifying field=${outcome.field}" +
                    " turns=${outcome.turns} tools=${outcome.toolCalls}" +
                    (outcome.maskingRepairs.takeIf { it.isNotEmpty() }?.let { " masking_repairs=${it.size}" } ?: "") +
                    " in=$inTotal out=$outTotal cached=$cachedTotal" +
                    " cache=${support?.name?.lowercase() ?: "na"}" +
                    (cacheFingerprint?.let { " key=${it.take(12)}" } ?: "")
                is WorkflowRunOutcome.Refused -> "refused reason=${outcome.reason}" +
                    (outcome.maskingRepairs.takeIf { it.isNotEmpty() }?.let { " masking_repairs=${it.size}" } ?: "")
                is WorkflowRunOutcome.Incomplete ->
                    "incomplete reason=${outcome.reason.name} detail=${outcome.detail?.take(80)}" +
                        (outcome.maskingRepairs.takeIf { it.isNotEmpty() }
                            ?.let { " masking_repairs=${it.size} last=${it.last().outcome}" }
                            ?: "") +
                        " in=$inTotal out=$outTotal cached=$cachedTotal" +
                        " cache=${support?.name?.lowercase() ?: "na"}" +
                        (cacheFingerprint?.let { " key=${it.take(12)}" } ?: "") +
                        (compaction?.let { " compacted=${it.droppedToolResults}" } ?: "")
            }
            boundary.maskOutbound(summary, MaskedPath.HISTORY)
        } catch (_: Exception) {
            "observation withheld by the privacy boundary"
        }
        val detail = when (outcome) {
            is WorkflowRunOutcome.Completed ->
                JSONObject().put("turns", outcome.turns).put("toolCalls", outcome.toolCalls)
                    .put("effects", outcome.verifiedEffects.size)
                    .put(
                        "noEffect",
                        JSONArray().also { out ->
                            outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.NoEffect>()
                                .forEach { effect -> out.put(effect.kindName) }
                        },
                    )
                    .put("maskingRepairs", JSONArray().also { out ->
                        outcome.maskingRepairs.forEach { repair -> out.put(repairRecordJson(repair)) }
                    })
                    .put("inputTokens", inTotal).put("outputTokens", outTotal)
                    .put("cachedInputTokens", cachedTotal)
                    .put("cacheSupport", support?.name ?: JSONObject.NULL)
                    .put("cacheFingerprint", cacheFingerprint?.take(16) ?: JSONObject.NULL)
                    .put("compactedToolResults", compaction?.droppedToolResults ?: 0)
            is WorkflowRunOutcome.Clarifying -> JSONObject().put("field", outcome.field)
                .put("turns", outcome.turns).put("toolCalls", outcome.toolCalls)
                .put("maskingRepairs", JSONArray().also { out ->
                    outcome.maskingRepairs.forEach { repair -> out.put(repairRecordJson(repair)) }
                })
                .put("inputTokens", inTotal).put("outputTokens", outTotal)
                .put("cachedInputTokens", cachedTotal)
                .put("cacheSupport", support?.name ?: JSONObject.NULL)
                .put("cacheFingerprint", cacheFingerprint?.take(16) ?: JSONObject.NULL)
            is WorkflowRunOutcome.Refused -> JSONObject().put("reason", outcome.reason)
                .put("maskingRepairs", JSONArray().also { out ->
                    outcome.maskingRepairs.forEach { repair -> out.put(repairRecordJson(repair)) }
                })
            is WorkflowRunOutcome.Incomplete ->
                JSONObject().put("reason", outcome.reason.name)
                    .put("turns", outcome.turns).put("toolCalls", outcome.toolCalls)
                    .put("maskingRepairs", JSONArray().also { out ->
                        outcome.maskingRepairs.forEach { repair -> out.put(repairRecordJson(repair)) }
                    })
                    .put("inputTokens", inTotal).put("outputTokens", outTotal)
                    .put("cachedInputTokens", cachedTotal)
                    .put("cacheSupport", support?.name ?: JSONObject.NULL)
                    .put("cacheFingerprint", cacheFingerprint?.take(16) ?: JSONObject.NULL)
                    .put("compactedToolResults", compaction?.droppedToolResults ?: 0)
        }
        return JSONObject()
            .put("summary", masked)
            .put("detail", detail)
            .toString()
    }

    /**
     * The complete, untruncated payload of one attempt outcome.
     *
     * It repeats the same facts the journal observation summarizes (state, stop reason, counts,
     * usage, verified effects) and adds the parts the journal deliberately omits: the masked
     * answer the user saw, the clarification question, the ordered run steps, the compaction
     * record, and any privacy refusal. Provider bodies are already recorded by the transport
     * decorator, so nothing here is inferred from or substituted for them.
     */
    private fun traceOutcomePayload(
        outcome: WorkflowRunOutcome,
        attemptState: String,
        stopReason: String?,
        runState: String,
    ): String {
        val payload = JSONObject()
            .put("attemptState", attemptState)
            .put("runState", runState)
            .put("stopReason", stopReason ?: JSONObject.NULL)
        when (outcome) {
            is WorkflowRunOutcome.Completed -> payload
                .put("answer", outcome.answer)
                .put("turns", outcome.turns)
                .put("toolCalls", outcome.toolCalls)
                .put("trace", stepsJson(outcome.trace))
                .put("usage", usageJson(outcome.usage))
                .put("verifiedEffects", effectsJson(outcome.verifiedEffects))
                .put("maskingRepairs", repairsJson(outcome.maskingRepairs))
                .put("compaction", compactionJson(outcome.compaction))
            is WorkflowRunOutcome.Clarifying -> payload
                .put("question", outcome.question)
                .put("field", outcome.field ?: JSONObject.NULL)
                .put("turns", outcome.turns)
                .put("toolCalls", outcome.toolCalls)
                .put("trace", stepsJson(outcome.trace))
                .put("usage", usageJson(outcome.usage))
                .put("maskingRepairs", repairsJson(outcome.maskingRepairs))
            is WorkflowRunOutcome.Refused -> payload
                .put("reason", outcome.reason)
                .put("answer", outcome.answer ?: JSONObject.NULL)
                .put("trace", stepsJson(outcome.trace))
                .put("maskingRepairs", repairsJson(outcome.maskingRepairs))
            is WorkflowRunOutcome.Incomplete -> payload
                .put("reason", outcome.reason.name)
                .put("detail", outcome.detail ?: JSONObject.NULL)
                .put("turns", outcome.turns)
                .put("toolCalls", outcome.toolCalls)
                .put("trace", stepsJson(outcome.trace))
                .put("usage", usageJson(outcome.usage))
                .put("maskingRepairs", repairsJson(outcome.maskingRepairs))
                .put("compaction", compactionJson(outcome.compaction))
                .put(
                    "privacyRefusal",
                    outcome.privacyRefusal?.let { refusal ->
                        JSONObject()
                            .put("reason", refusal.reason.name)
                            .put("sensitiveShape", refusal.sensitiveShape ?: JSONObject.NULL)
                            .put("safeLocation", refusal.safeLocation)
                            .put("nodeType", refusal.nodeType)
                    } ?: JSONObject.NULL,
                )
        }
        return payload.toString()
    }

    private fun stepsJson(steps: List<WorkflowRunStep>): JSONArray = JSONArray().also { out ->
        steps.forEach { step ->
            out.put(JSONObject().put("state", step.state.name).put("detail", step.detail ?: JSONObject.NULL))
        }
    }

    private fun usageJson(usage: List<AgentRunUsage>): JSONArray = JSONArray().also { out ->
        usage.forEach { turn ->
            out.put(
                JSONObject()
                    .put("inputTokens", turn.inputTokens ?: JSONObject.NULL)
                    .put("outputTokens", turn.outputTokens ?: JSONObject.NULL)
                    .put("reasoningTokens", turn.reasoningTokens ?: JSONObject.NULL)
                    .put("cachedInputTokens", turn.cachedInputTokens ?: JSONObject.NULL),
            )
        }
    }

    private fun effectsJson(effects: List<WorkflowVerifiedEffect>): JSONArray = JSONArray().also { out ->
        effects.forEach { effect ->
            val item = JSONObject()
            when (effect) {
                is WorkflowVerifiedEffect.EventReading -> item
                    .put("kind", "event_reading")
                    .put("eventId", effect.eventId)
                    .put("captureId", effect.captureId)
                    .put("revision", effect.revision)
                is WorkflowVerifiedEffect.LinkState -> item
                    .put("kind", "link_state")
                    .put("linkId", effect.linkId)
                    .put("eventId", effect.eventId)
                    .put("draftId", effect.draftId)
                    .put("state", effect.state.name)
                    .put("revision", effect.revision)
                is WorkflowVerifiedEffect.DraftCategory -> item
                    .put("kind", "draft_category")
                    .put("draftId", effect.draftId)
                    .put("categoryId", effect.categoryId)
                    .put("reviewStatus", effect.reviewStatus.name)
                    .put("reviewRevision", effect.reviewRevision)
                is WorkflowVerifiedEffect.ReplayIdentity -> item
                    .put("kind", "replay_identity")
                    .put("operationKey", effect.operationKey)
                    .put("currentEffectMatches", effect.currentEffectMatches)
                is WorkflowVerifiedEffect.NoEffect -> item
                    .put("kind", effect.kindName)
                    .put("intent", effect.intent.name)
                    .put("summary", effect.toString())
            }
            out.put(item)
        }
    }

    private fun repairsJson(repairs: List<MaskingRepairRecord>): JSONArray = JSONArray().also { out ->
        repairs.forEach { repair -> out.put(repairRecordJson(repair)) }
    }

    private fun compactionJson(compaction: AgentContextCompaction?): Any = compaction?.let { record ->
        JSONObject()
            .put("compactedTurns", record.compactedTurns)
            .put("droppedToolResults", record.droppedToolResults)
            .put("carriedDispatches", record.carriedDispatches)
    } ?: JSONObject.NULL

    /** Content-free repair chronology for durable history; never refused text or a token. */
    private fun repairRecordJson(repair: MaskingRepairRecord): JSONObject = JSONObject()
        .put("attempt", repair.attempt)
        .put("reason", repair.reason)
        .put("outcome", repair.outcome)

    private fun attemptOutcomeOf(outcome: WorkflowRunOutcome): Triple<String, String?, AttemptEffect> {
        return when (outcome) {
            is WorkflowRunOutcome.Completed -> {
                val link = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.LinkState>().firstOrNull()
                val event = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.EventReading>().firstOrNull()
                val draftCategory = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.DraftCategory>().firstOrNull()
                val noEffect = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.NoEffect>().isNotEmpty()
                val effect = when {
                    link != null -> AttemptEffect("link", link.eventId, link.revision, link.linkId, link.state.name, link.revision, link.draftId, null)
                    event != null -> AttemptEffect("event", event.eventId, event.revision, null, null, null, null, null)
                    draftCategory != null -> AttemptEffect("draft_category", null, null, null, null, null, draftCategory.draftId, draftCategory.categoryId)
                    noEffect -> AttemptEffect("no_effect", null, null, null, null, null, null, null)
                    else -> AttemptEffect("query", null, null, null, null, null, null, null)
                }
                Triple("COMPLETED", CanonicalAgentRetryPolicy.RETRY_CODE_NONE, effect)
            }
            is WorkflowRunOutcome.Clarifying -> Triple("UNKNOWN", CanonicalAgentRetryPolicy.RETRY_CODE_CLARIFY, AttemptEffect("clarification", null, null, null, null, null, null, null))
            is WorkflowRunOutcome.Refused -> Triple("UNKNOWN", outcome.reason, AttemptEffect("refused", null, null, null, null, null, null, null))
            is WorkflowRunOutcome.Incomplete -> Triple("UNKNOWN", outcome.reason.name, AttemptEffect("unknown", null, null, null, null, null, null, null))
        }
    }

    private data class AttemptEffect(val kind: String?, val eventId: Long?, val eventRevision: Int?, val linkId: Long?, val linkState: String?, val linkRevision: Int?, val draftId: Long?, val categoryId: Long?)

    private companion object {
        const val MAX_REQUEST_CHARS = 2_000
        const val MAX_ANSWER_CHARS = 2_000
        const val MAX_LINE_CHARS = 2_000
        const val MAX_RECENT_RUNS = 100

        /**
         * The trace kinds only a bound host recorder writes. Their presence proves the recorder
         * was live for the attempt, so the absence of a tool result means no tool call ran.
         */
        val RECORDER_OBSERVED_KINDS = setOf(
            AGENT_TRACE_KIND_PROVIDER_REQUEST,
            AGENT_TRACE_KIND_PROVIDER_RESPONSE,
            AGENT_TRACE_KIND_PROVIDER_STREAM_CHUNK,
            AGENT_TRACE_KIND_PROVIDER_STREAM_END,
            AGENT_TRACE_KIND_PROVIDER_FAILURE,
            AGENT_TRACE_KIND_TOOL_CALL,
            AGENT_TRACE_KIND_TOOL_RESULT,
            AGENT_TRACE_KIND_TOOL_ERROR,
        )
    }
}

/** One admitted run: the durable identity a repeated send replays. */
data class AdmittedRun(
    val runId: String,
    val scopeId: String,
    val attempt: Int,
    val attemptKey: String,
    val ownerToken: String?,
    val replayed: Boolean,
)

/** What reconciliation of an uncertain write found before any replay. */
sealed interface UncertainWriteVerdict {
    /** The stored effect matches the attempt: replay is safe (idempotent no-change). */
    data class Applied(val detail: String) : UncertainWriteVerdict

    /** Nothing was applied: a corrected request may run under a fresh key. */
    data class NotApplied(val detail: String) : UncertainWriteVerdict

    /** Cannot be decided from durable state: do not replay; ask the user or retry bounded. */
    data class Unknown(val detail: String) : UncertainWriteVerdict
}

/**
 * The decision a retry needs before anything is appended to the visible conversation.
 *
 * A caller must not start a user-visible retry turn — not even the user's own repeated bubble —
 * until the durable decision says [Ready]; [Blocked] is reported in place instead.
 */
sealed interface RetryAdmission {
    /** The current attempt failed retryably, has an attempt left and applied nothing. */
    data class Ready(
        val currentAttempt: Int,
        val nextAttempt: Int,
        val target: RetryTarget,
    ) : RetryAdmission

    /** The run cannot retry, for the named reason; no Retry action may be offered. */
    data class Blocked(val reason: RetryBlockReason) : RetryAdmission
}

/** What a ready retry must replay: the original request or the clarification continuation. */
enum class RetryTarget {
    REQUEST,
    CLARIFICATION,
}

/** Why a failed run offers no retry. */
enum class RetryBlockReason {
    /** The run is missing, foreign, or has no attempt row. */
    UNAVAILABLE,

    /** The run is not in its failed state, so retry is not the applicable action. */
    NOT_FAILED,

    /** The bounded attempt allowance is spent; a later attempt is not permitted. */
    ATTEMPTS_EXHAUSTED,

    /** The failure classification is terminal: retrying would repeat a decided outcome. */
    TERMINAL,

    /** An attempt still holds the run, so retrying now would race it. */
    STILL_PROCESSING,

    /** The attempt's journal recorded an applied effect or cannot prove that none was applied. */
    POSSIBLY_APPLIED,
}
