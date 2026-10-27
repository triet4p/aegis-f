package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.data.AgentConversationEntity
import io.github.triet4p.aegisf.data.AgentRunAttemptEntity
import io.github.triet4p.aegisf.data.AgentRunEntity
import io.github.triet4p.aegisf.data.CanonicalAgentRetryPolicy
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The durable S8.10 history of one caller session's agent runs.
 *
 * Three stores stay separate the way the contract requires: (a) the user-visible
 * conversation ([AgentConversationEntity]: request, status, and answer lines only);
 * (b) the business captures and effect journal (owned by the existing command
 * services, never here); (c) the run/attempt journal ([AgentRunEntity] /
 * [AgentRunAttemptEntity]: run identity, retry attempts, allowed effect references,
 * pending/completed/unknown states, and privacy-safe observations).
 *
 * The history never stores credentials, vault mappings, protocol continuation items,
 * transient reasoning, or provider wire detail — none of those are columns and none
 * leave through the conversation read. The safe export is exactly the conversation
 * plus run states; the operation journal (`workflow_operations`) is never exported.
 */
class AgentRunHistory(
    private val database: LedgerDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
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
        val limitsFingerprint = "${limits.maxTurns}/${limits.maxToolCalls}/" +
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
    suspend fun claimForExecution(runId: String, attempt: Int): Boolean =
        runs.claimPending(runId, attempt, clock()) == 1

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
        val safeObservation = safeObservation(outcome, boundary)
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
            decisionSource = effect?.decisionSource,
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
            is WorkflowRunOutcome.Clarifying ->
                runs.markClarifying(runId, CanonicalAgentRetryPolicy.RETRY_CODE_CLARIFY, now)
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
    }

    /**
     * Reconciles an uncertain write before any replay: re-reads the durable operation
     * journal and the live rows behind the attempt's claimed effects. Returns the
     * reconciliation verdict; a `false` verdict means the caller must not replay this
     * attempt and must instead open a fresh corrected request.
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
        val recorded = queryService.workflowOperation(row.idempotencyKey)
        if (recorded == null) {
            return UncertainWriteVerdict.NotApplied("no journal row for the attempt key")
        }
        if (recorded.outcome == "PENDING") {
            return UncertainWriteVerdict.Unknown("the journal claim is still pending")
        }
        val live = reconcileEffectReferences(row)
        return when {
            live == null -> UncertainWriteVerdict.Unknown("the claimed effect cannot be re-read")
            live -> UncertainWriteVerdict.Applied("the stored effect matches the attempt")
            else -> UncertainWriteVerdict.NotApplied("the stored effect differs; open a corrected request")
        }
    }

    /**
     * Cancels one run without claiming rollback. A stored effect stays stored and
     * stays verified; cancellation only ends the conversation and frees the keys for
     * a corrected request.
     */
    suspend fun cancelRun(runId: String, reason: String): Boolean {
        val now = clock()
        val rows = attempts.findForRun(runId)
        rows.filter { it.state == "PENDING" && it.ownerToken != null }.forEach { row ->
            try {
                attempts.cancel(row.id, row.ownerToken!!, reason, now)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        appendLine(runId, "STATUS", "The run was cancelled.", now)
        return runs.cancel(runId, reason, now) == 1
    }

    /** The durable user-visible conversation of one run, in display order. */
    suspend fun conversationForRun(runId: String): List<AgentConversationEntity> =
        conversation.findForRun(runId)

    /**
     * Reopens one failed/clarifying run for its next bounded attempt. The returned
     * attempt key is fresh (attempt + 1) and the prior attempt row stays as history.
     */
    suspend fun reopenForRetry(
        scope: WorkflowCallerScope.AgentSession,
        runId: String,
    ): AdmittedRun? = reopenFrom(hostScope = scope, runId = runId, fromStates = setOf("FAILED", "CLARIFYING"))

    /**
     * Reopens a suspended clarification for its next bounded resume attempt. Only a run still in
     * `CLARIFYING` moves; a FAILED, RUNNING, or terminal run returns null so a stale, foreign,
     * or blank continuation can never strand or rerun a failed workflow.
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
        if (next > CanonicalAgentRetryPolicy.MAX_ATTEMPTS) return null
        val now = clock()
        val moved = when {
            stored.state !in fromStates -> 0
            stored.state == "FAILED" -> runs.reopenForRetry(runId, next, now, now)
            else -> runs.resumeClarifying(runId, next, now, now)
        }
        if (moved != 1) return null
        val key = CanonicalAgentRetryPolicy.attemptKey(runId, next)
        val token = UUID.randomUUID().toString()
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
                observationJson = JSONObject().put("retry", next).toString(),
                ownerToken = token,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        appendLine(runId, "STATUS", "The run was retried (attempt $next).", now)
        return AdmittedRun(runId, hostScope.scopeId, next, key, token, replayed = false)
    }

    /**
     * Bounded retention: keeps at most [CanonicalAgentRetryPolicy.MAX_RUNS_PER_SCOPE]
     * newest runs of the session and prunes their attempts by cascade. Returns the
     * number of pruned runs.
     */
    suspend fun enforceRetention(scopeId: String): Int {
        val count = runs.countOf(scopeId)
        val excess = (count - CanonicalAgentRetryPolicy.MAX_RUNS_PER_SCOPE).toInt()
        if (excess <= 0) return 0
        val victims = runs.oldestIds(scopeId, excess)
        if (victims.isEmpty()) return 0
        return runs.deleteByIds(victims)
    }

    /** Appends the bounded retry summary line of one run (status only, no content). */
    suspend fun appendRetrySummary(runId: String) {
        appendLine(runId, "STATUS", "The run used a bounded retry.", clock())
    }

    /** Deletes the whole user-visible history of one session; business stores stay. */
    suspend fun deleteScopeHistory(scopeId: String): Int = runs.deleteScope(scopeId)

    /**
     * The privacy-safe export of one session: conversation lines plus run states only.
     * Operation keys, digests, attempt tokens, observations, vault mappings, protocol
     * continuation items, transient reasoning, and wire detail are never columns and
     * never leave through this path. Business captures and the effect journal are
     * separate stores and are never exported here.
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

    /**
     * Re-reads the live rows behind one attempt's claimed effects and reports whether
     * they still carry the attempted state. `null` means the effect cannot be re-read
     * from durable state (no claimed effect, or an unreadable row); the caller must
     * treat that as `Unknown`, not as success and not as proof of absence.
     */
    private suspend fun reconcileEffectReferences(row: AgentRunAttemptEntity): Boolean? {
        return try {
            when (row.effectKind) {
                null, "query", "clarification", "refused", "unknown" -> null
                "event" -> {
                    val expected = row.eventRevision ?: return null
                    val stored = queryService.eventOperations(
                        io.github.triet4p.aegisf.data.EventOperationQuery(
                            captureId = null,
                            bounds = QueryPageRequest(500, 0),
                        ),
                    ).items.firstOrNull { it.eventId == row.eventId } ?: return null
                    stored.revision == expected
                }
                "link" -> {
                    val pair = queryService.linkOperations(
                        io.github.triet4p.aegisf.data.LinkOperationQuery(
                            eventId = row.eventId,
                            draftId = row.draftId,
                            bounds = QueryPageRequest(1, 0),
                        ),
                    ).items.firstOrNull { it.linkId == row.linkId } ?: return null
                    pair.revision == (row.linkRevision ?: return null) &&
                        pair.state.name == (row.linkState ?: return null)
                }
                "classification" -> {
                    val stored = queryService.classificationOperations(
                        io.github.triet4p.aegisf.data.ClassificationOperationQuery(
                            staleOnly = false,
                            bounds = QueryPageRequest(500, 0),
                        ),
                    ).items.firstOrNull { it.draftId == row.draftId } ?: return null
                    stored.decisionSource.name == (row.decisionSource ?: return null)
                }
                else -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
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
            is WorkflowRunOutcome.Incomplete -> outcome.usage
            else -> emptyList()
        }
        val support = when (outcome) {
            is WorkflowRunOutcome.Completed -> outcome.cacheSupport
            is WorkflowRunOutcome.Incomplete -> outcome.cacheSupport
            else -> null
        }
        val cacheFingerprint = when (outcome) {
            is WorkflowRunOutcome.Completed -> outcome.cacheKey?.fingerprint()
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
                        " in=$inTotal out=$outTotal cached=$cachedTotal" +
                        " cache=${support?.name?.lowercase() ?: "na"}" +
                        (cacheFingerprint?.let { " key=${it.take(12)}" } ?: "") +
                        (compaction?.let { " compacted=${it.droppedToolResults}" } ?: "")
                is WorkflowRunOutcome.Clarifying -> "clarifying field=${outcome.field}"
                is WorkflowRunOutcome.Refused -> "refused reason=${outcome.reason}"
                is WorkflowRunOutcome.Incomplete ->
                    "incomplete reason=${outcome.reason.name} detail=${outcome.detail?.take(80)}" +
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
                    .put("inputTokens", inTotal).put("outputTokens", outTotal)
                    .put("cachedInputTokens", cachedTotal)
                    .put("cacheSupport", support?.name ?: JSONObject.NULL)
                    .put("cacheFingerprint", cacheFingerprint?.take(16) ?: JSONObject.NULL)
                    .put("compactedToolResults", compaction?.droppedToolResults ?: 0)
            is WorkflowRunOutcome.Clarifying -> JSONObject().put("field", outcome.field)
            is WorkflowRunOutcome.Refused -> JSONObject().put("reason", outcome.reason)
            is WorkflowRunOutcome.Incomplete ->
                JSONObject().put("reason", outcome.reason.name)
                    .put("turns", outcome.turns).put("toolCalls", outcome.toolCalls)
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

    private fun attemptOutcomeOf(outcome: WorkflowRunOutcome): Triple<String, String?, AttemptEffect> {
        return when (outcome) {
            is WorkflowRunOutcome.Completed -> {
                val link = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.LinkState>().firstOrNull()
                val event = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.EventReading>().firstOrNull()
                val classification = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.Classification>().firstOrNull()
                val effect = when {
                    link != null -> AttemptEffect("link", link.eventId, link.revision, link.linkId, link.state.name, link.revision, link.draftId, null)
                    event != null -> AttemptEffect("event", event.eventId, event.revision, null, null, null, null, null)
                    classification != null -> AttemptEffect("classification", null, null, null, null, null, classification.draftId, classification.source.name)
                    else -> AttemptEffect("query", null, null, null, null, null, null, null)
                }
                Triple("COMPLETED", CanonicalAgentRetryPolicy.RETRY_CODE_NONE, effect)
            }
            is WorkflowRunOutcome.Clarifying -> Triple("UNKNOWN", CanonicalAgentRetryPolicy.RETRY_CODE_CLARIFY, AttemptEffect("clarification", null, null, null, null, null, null, null))
            is WorkflowRunOutcome.Refused -> Triple("UNKNOWN", outcome.reason, AttemptEffect("refused", null, null, null, null, null, null, null))
            is WorkflowRunOutcome.Incomplete -> Triple("UNKNOWN", outcome.reason.name, AttemptEffect("unknown", null, null, null, null, null, null, null))
        }
    }

    private data class AttemptEffect(val kind: String?, val eventId: Long?, val eventRevision: Int?, val linkId: Long?, val linkState: String?, val linkRevision: Int?, val draftId: Long?, val decisionSource: String?)

    private companion object {
        const val MAX_REQUEST_CHARS = 2_000
        const val MAX_ANSWER_CHARS = 2_000
        const val MAX_LINE_CHARS = 2_000
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
