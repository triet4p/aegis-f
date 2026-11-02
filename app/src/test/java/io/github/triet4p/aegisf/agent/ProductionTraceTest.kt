package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.AGENT_TRACE_COMPLETION_INTERRUPTED
import io.github.triet4p.aegisf.data.AGENT_TRACE_COMPLETION_UNAVAILABLE
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_ATTEMPT_OUTCOME
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_ATTEMPT_STARTED
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_CLARIFICATION_REPLY
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_CLARIFICATION_REQUEST
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_REQUEST
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_RESPONSE
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_RUN_ADMITTED
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_RUN_CANCELLED
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_TOOL_CALL
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_TOOL_RESULT
import io.github.triet4p.aegisf.data.AgentTraceStore
import io.github.triet4p.aegisf.data.AppContainer
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedAuthentication
import io.github.triet4p.aegisf.provider.HostedGenerationBudget
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HostedProviderCapabilities
import io.github.triet4p.aegisf.provider.HostedProviderConfig
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HttpTransport
import io.github.triet4p.aegisf.provider.OpenAiChatCompletionsAdapter
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PT1 — the local production trace of a real run.
 *
 * The first test drives the app's own container: its database, its run history, its trace store,
 * the production chat-completions adapter, and the production transport decorator over a
 * deterministic endpoint. It asserts the ordered evidence a run must leave behind — run
 * admission, attempt start, both complete provider exchanges (including the provider's returned
 * reasoning), the tool call with its exact arguments and the application's complete answer — and
 * that the credential never appears anywhere. It then reopens the app container to prove the
 * trace is persisted in the production Room file, not only in memory.
 *
 * The remaining tests pin the two honesty rules: an exchange that produced no response (failure)
 * or was cancelled mid-flight is recorded as an explicit gap, and a clarification question plus the
 * user's reply stay on the same run trace while the raw reply is never sent to the provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionTraceTest {
    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var store: AgentTraceStore
    private lateinit var history: AgentRunHistory
    private lateinit var declarations: List<HostedToolDeclaration>
    private lateinit var skills: WorkflowSkillCatalog
    private val sequence = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        commands = WorkflowCommandService(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
        )
        queries = WorkflowQueryService(database)
        store = AgentTraceStore(database.agentTraceDao())
        history = AgentRunHistory(database, traces = store)
        declarations = AgentToolDeclarations.load(context.assets::open)
        skills = WorkflowSkillCatalog.load(context.assets::open)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    @Test
    fun `production container records complete ordered evidence that survives reopen`() = runBlocking {
        val transport = ScriptedTransport(
            mutableListOf(
                { _: HostedHttpRequest, _: Int -> jsonResponse(TOOL_TURN_BODY) },
                { _: HostedHttpRequest, _: Int -> jsonResponse(ANSWER_TURN_BODY) },
            ),
        )
        val container = AppContainer(context)
        container.hostedHttpTransport = transport
        val scope = WorkflowCallerScope.AgentSession("pt1-production-session", emptySet(), emptySet())
        val host = container.createHostedWorkflowHost(scope, providerConfig())
        assertTrue("the production host must carry an enabled trace recorder", host.trace.enabled)

        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "List the pending drafts.",
        )
        val runHistory = container.agentRunHistory
        val admitted = runHistory.admitRun(scope, request, host.boundary, 1024)
        val outcome = BoundedWorkflowExecutor(host).startTracked(
            request = request,
            history = runHistory,
            attemptKey = admitted.attemptKey,
            maxOutputTokens = 1024,
        )
        assertTrue("expected a completed run, got $outcome", outcome is WorkflowRunOutcome.Completed)

        val events = runHistory.traceForRun(admitted.runId)
        assertEquals(
            listOf(
                AGENT_TRACE_KIND_RUN_ADMITTED,
                AGENT_TRACE_KIND_ATTEMPT_STARTED,
                AGENT_TRACE_KIND_PROVIDER_REQUEST,
                AGENT_TRACE_KIND_PROVIDER_RESPONSE,
                AGENT_TRACE_KIND_TOOL_CALL,
                AGENT_TRACE_KIND_TOOL_RESULT,
                AGENT_TRACE_KIND_PROVIDER_REQUEST,
                AGENT_TRACE_KIND_PROVIDER_RESPONSE,
                AGENT_TRACE_KIND_ATTEMPT_OUTCOME,
            ),
            events.map { it.kind },
        )
        assertTrue("event identities must be strictly ordered", events.zipWithNext().all { (a, b) -> a.id < b.id })
        assertTrue(events.all { it.scopeId == scope.scopeId })
        assertTrue(events.all { it.runId == admitted.runId })

        // The exact request that left the app: instructions, the offered tools and the context.
        val firstRequestEvent = events.first { it.kind == AGENT_TRACE_KIND_PROVIDER_REQUEST && it.turn == 1 }
        val firstRequest = JSONObject(firstRequestEvent.payloadJson)
        val body = firstRequest.getString("body")
        assertTrue("the system instruction must be recorded", body.contains("skill_id: draft-search"))
        assertTrue("the actual context must be recorded", body.contains("List the pending drafts."))
        val wire = JSONObject(body)
        assertEquals(MODEL, wire.getString("model"))
        assertTrue("the offered tools must be recorded", wire.getJSONArray("tools").length() > 0)
        assertTrue(
            "the tool schema must be recorded",
            wire.getJSONArray("tools").getJSONObject(0).getJSONObject("function").has("parameters"),
        )
        assertFalse("a credential header must never be stored", firstRequest.getJSONObject("headers").has("Authorization"))
        assertTrue(
            "the dropped credential header must be named, not valued",
            (0 until firstRequest.getJSONArray("redactedHeaders").length()).any { index ->
                firstRequest.getJSONArray("redactedHeaders").optString(index) == "Authorization"
            },
        )

        // The exact provider answer, including the reasoning item the adapter does not keep.
        val firstResponseEvent = events.first { it.kind == AGENT_TRACE_KIND_PROVIDER_RESPONSE && it.turn == 1 }
        val firstResponse = JSONObject(firstResponseEvent.payloadJson)
        assertEquals(200, firstResponse.getInt("status"))
        assertEquals("the provider body must be stored verbatim", TOOL_TURN_BODY, firstResponse.getString("body"))
        assertTrue(firstResponse.getString("body").contains("hidden provider reasoning"))

        // The tool call and its complete answer.
        val toolCall = JSONObject(events.first { it.kind == AGENT_TRACE_KIND_TOOL_CALL && it.turn == 1 }.payloadJson)
        assertEquals("list_drafts", toolCall.getString("name"))
        val recordedArguments = JSONObject(toolCall.getString("argumentsJson"))
        assertEquals("PENDING", recordedArguments.getString("review_status"))
        assertFalse(recordedArguments.getBoolean("duplicates_only"))
        assertTrue(recordedArguments.isNull("cursor"))
        assertEquals("READY", toolCall.getString("disposition"))
        val toolResult = JSONObject(events.first { it.kind == AGENT_TRACE_KIND_TOOL_RESULT }.payloadJson)
        val envelope = JSONObject(toolResult.getString("resultJson"))
        assertEquals("list_drafts", envelope.getString("tool"))
        assertEquals("none", envelope.getString("effect"))
        assertEquals("each call is answered exactly once", 1, events.count { it.kind == AGENT_TRACE_KIND_TOOL_CALL })
        assertEquals(1, events.count { it.kind == AGENT_TRACE_KIND_TOOL_RESULT })
        assertEquals("one provider request per turn, never a retried tool call", 2, transport.requests.size)

        // The terminal outcome with the answer, counts and usage.
        val outcomeEvent = JSONObject(events.first { it.kind == AGENT_TRACE_KIND_ATTEMPT_OUTCOME }.payloadJson)
        assertEquals("COMPLETED", outcomeEvent.getString("attemptState"))
        assertEquals("SUCCEEDED", outcomeEvent.getString("runState"))
        assertEquals(2, outcomeEvent.getInt("turns"))
        assertEquals(1, outcomeEvent.getInt("toolCalls"))
        assertTrue(outcomeEvent.getString("answer").contains("No pending drafts"))
        assertEquals(2, outcomeEvent.getJSONArray("usage").length())

        // No credential or transport secret anywhere in the trace.
        assertTrue(events.none { it.payloadJson.contains(TOKEN) })
        assertTrue(events.none { it.payloadJson.contains("Bearer") })

        // The privacy-safe export stays exactly the conversation: no trace evidence at all.
        val exported = runHistory.safeExport(scope.scopeId, 50, 0).toString()
        assertFalse(exported.contains("hidden provider reasoning"))
        assertFalse(exported.contains("call-trace-1"))
        assertFalse(exported.contains(TOKEN))

        // Reopen the production container: the trace is on disk, not only in memory.
        container.close()
        val reopened = AppContainer(context)
        try {
            val reread = reopened.agentRunHistory.traceForRun(admitted.runId)
            assertEquals(events.map { it.kind }, reread.map { it.kind })
            assertEquals(events.map { it.payloadJson }, reread.map { it.payloadJson })
            assertTrue(reread.none { it.payloadJson.contains(TOKEN) })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `failed and cancelled exchanges are recorded as explicit gaps not invented bodies`() = runBlocking {
        val scope = WorkflowCallerScope.AgentSession("pt1-interruption", emptySet(), emptySet())
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val recorder = AgentTraceRecorder.enabled(store)

        val failedRun = history.admitRun(
            scope,
            WorkflowRunRequest(setOf(WorkflowIntent.SEARCH_DRAFTS), "Interruption probe."),
            boundary,
            1024,
        )
        recorder.bind(failedRun.runId, scope.scopeId, 1)
        val failed = TracingHttpTransport(FailingTransport(IOException("connection reset")), recorder)
        var failedThrown = false
        try {
            failed.post(
                HostedHttpRequest(
                    url = "https://provider.example/v1/chat/completions?api_key=leaked-query-value",
                    headers = mapOf("Authorization" to "Bearer $TOKEN", "Content-Type" to "application/json"),
                    body = "{\"probe\":true}",
                ),
            )
        } catch (_: IOException) {
            failedThrown = true
        }
        assertTrue("the transport failure must propagate unchanged", failedThrown)

        val afterFailure = history.traceForRun(failedRun.runId)
        val requestRow = afterFailure.first { it.kind == AGENT_TRACE_KIND_PROVIDER_REQUEST }
        val request = JSONObject(requestRow.payloadJson)
        assertFalse(request.getJSONObject("headers").has("Authorization"))
        assertTrue((0 until request.getJSONArray("redactedHeaders").length()).any { index ->
            request.getJSONArray("redactedHeaders").optString(index) == "Authorization"
        })
        assertFalse("a credential in the URL query is never recorded", requestRow.payloadJson.contains("leaked-query-value"))
        assertTrue(request.getString("url").contains("api_key=[redacted]"))
        val responseRow = afterFailure.first { it.kind == AGENT_TRACE_KIND_PROVIDER_RESPONSE }
        assertEquals(AGENT_TRACE_COMPLETION_UNAVAILABLE, responseRow.completion)
        val response = JSONObject(responseRow.payloadJson)
        assertEquals("NO_RESPONSE", response.getString("reason"))
        assertEquals("IOException", response.getString("error"))
        assertFalse("no response body may be invented", response.has("body"))
        assertTrue(afterFailure.none { it.payloadJson.contains(TOKEN) })

        val cancelledRun = history.admitRun(
            scope,
            WorkflowRunRequest(setOf(WorkflowIntent.SEARCH_DRAFTS), "Cancellation probe."),
            boundary,
            1024,
        )
        recorder.bind(cancelledRun.runId, scope.scopeId, 1)
        val interrupted = TracingHttpTransport(FailingTransport(CancellationException("cancelled")), recorder)
        try {
            interrupted.post(
                HostedHttpRequest(
                    url = "https://provider.example/v1/chat/completions",
                    headers = mapOf("Authorization" to "Bearer $TOKEN"),
                    body = "{}",
                ),
            )
        } catch (_: CancellationException) {
        }
        val afterCancel = history.traceForRun(cancelledRun.runId)
        val interruptedRow = afterCancel.first { it.kind == AGENT_TRACE_KIND_PROVIDER_RESPONSE }
        assertEquals(AGENT_TRACE_COMPLETION_INTERRUPTED, interruptedRow.completion)
        assertEquals("INTERRUPTED", JSONObject(interruptedRow.payloadJson).getString("reason"))
        assertTrue(afterCancel.none { it.payloadJson.contains(TOKEN) })
    }

    @Test
    fun `clarification question and user reply are recorded while the raw reply stays local`() = runBlocking {
        val captureOutcome = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("pt1-capture-${sequence.incrementAndGet()}", SOURCE),
        )
        check(captureOutcome is WorkflowCommandOutcome.Applied) { "capture refused: $captureOutcome" }
        val captureId = (captureOutcome.change as WorkflowChange.CaptureSaved).captureId
        val scope = WorkflowCallerScope.AgentSession("pt1-clarification", setOf(captureId), emptySet())
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val recorder = AgentTraceRecorder.enabled(store)
        val transport = ScriptedTransport(
            mutableListOf(
                { request: HostedHttpRequest, _: Int ->
                    jsonResponse(
                        toolTurnBody(
                            "call-select",
                            "select_source",
                            selectSourceArguments(request.body),
                        ),
                    )
                },
                { request: HostedHttpRequest, _: Int ->
                    jsonResponse(
                        toolTurnBody(
                            "call-ask",
                            "ask_user",
                            askUserArguments(request.body),
                        ),
                    )
                },
                { _: HostedHttpRequest, _: Int -> jsonResponse(ANSWER_TURN_BODY) },
            ),
        )
        val adapter = OpenAiChatCompletionsAdapter(providerConfig(), TracingHttpTransport(transport, recorder))
        val host = WorkflowRunHost(
            scope = scope,
            boundary = boundary,
            gateway = MaskedHostedGateway(adapter, boundary),
            declarations = declarations,
            skills = skills,
            commands = commands,
            queries = queries,
            operationKeys = WorkflowOperationKeySource { "pt1-key-${sequence.incrementAndGet()}" },
            trace = recorder,
        )
        val request = WorkflowRunRequest(setOf(WorkflowIntent.INTERPRET_EVENTS), SOURCE)
        val admitted = history.admitRun(scope, request, boundary, 1024)
        val executor = BoundedWorkflowExecutor(host)
        val first = executor.startTracked(request, history, admitted.attemptKey, 1024)
        assertTrue("expected a clarification, got $first", first is WorkflowRunOutcome.Clarifying)
        val clarifying = first as WorkflowRunOutcome.Clarifying
        assertEquals("Which amount should this note record?", clarifying.question)

        val afterClarification = history.traceForRun(admitted.runId)
        val asked = JSONObject(
            afterClarification.first { it.kind == AGENT_TRACE_KIND_CLARIFICATION_REQUEST }.payloadJson,
        )
        assertEquals("amount", asked.getString("field"))
        assertEquals("Which amount should this note record?", asked.getString("question"))
        assertEquals("CLARIFYING", JSONObject(afterClarification.first {
            it.kind == AGENT_TRACE_KIND_ATTEMPT_OUTCOME
        }.payloadJson).getString("runState"))

        val resumed = executor.resumeTracked(
            history = history,
            runId = admitted.runId,
            continuation = clarifying.continuation,
            userAnswer = "25000",
        )
        assertTrue("expected the resumed attempt to settle, got $resumed", resumed != null)

        val afterResume = history.traceForRun(admitted.runId)
        val replyEvent = afterResume.first { it.kind == AGENT_TRACE_KIND_CLARIFICATION_REPLY }
        val reply = JSONObject(replyEvent.payloadJson)
        assertEquals("amount", reply.getString("field"))
        assertEquals("Which amount should this note record?", reply.getString("question"))
        assertEquals("25000", reply.getString("userAnswer"))
        assertEquals("the reply belongs to the reopened attempt", 2, replyEvent.attempt)
        assertEquals(
            listOf(1, 2),
            afterResume.filter { it.kind == AGENT_TRACE_KIND_ATTEMPT_OUTCOME }.map { it.attempt },
        )
        // The resumed attempt is honestly recorded as an unsettled failure, not as success.
        val settledOutcome = JSONObject(
            afterResume.filter { it.kind == AGENT_TRACE_KIND_ATTEMPT_OUTCOME }.last().payloadJson,
        )
        assertEquals("UNKNOWN", settledOutcome.getString("attemptState"))
        assertEquals("REQUIRED_EFFECT_MISSING", settledOutcome.getString("stopReason"))
        assertEquals("FAILED", settledOutcome.getString("runState"))
        // Three provider exchanges: the first phase's two turns and the resumed turn.
        assertEquals(3, afterResume.count { it.kind == AGENT_TRACE_KIND_PROVIDER_REQUEST })
        assertEquals(2, afterResume.count { it.kind == AGENT_TRACE_KIND_TOOL_CALL })
        // The user's raw answer stayed local: the provider only saw its masked form.
        val resumedRequestBody = JSONObject(
            afterResume.filter { it.kind == AGENT_TRACE_KIND_PROVIDER_REQUEST }.last().payloadJson,
        ).getString("body")
        assertFalse("the raw clarification answer must not be sent", resumedRequestBody.contains("25000"))
        assertTrue(afterResume.none { it.payloadJson.contains(TOKEN) })
    }

    @Test
    fun `cancellation is recorded once with the attempts it stopped`() = runBlocking {
        val scope = WorkflowCallerScope.AgentSession("pt1-cancel", emptySet(), emptySet())
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val admitted = history.admitRun(
            scope,
            WorkflowRunRequest(setOf(WorkflowIntent.SEARCH_DRAFTS), "Cancellation trace probe."),
            boundary,
            1024,
        )
        assertTrue(history.claimForExecution(admitted.runId, 1))
        assertTrue(history.cancelRun(admitted.runId, "USER_CANCELLED"))

        val events = history.traceForRun(admitted.runId)
        val cancelEvent = events.single { it.kind == AGENT_TRACE_KIND_RUN_CANCELLED }
        assertEquals(0, cancelEvent.attempt)
        val payload = JSONObject(cancelEvent.payloadJson)
        assertEquals("USER_CANCELLED", payload.getString("reason"))
        assertEquals("CANCELLED", payload.getString("state"))
        assertEquals(listOf(1), payload.getJSONArray("attempts").let { attempts ->
            (0 until attempts.length()).map { index -> attempts.getInt(index) }
        })

        // A second cancel changes nothing and must not invent a second cancellation event.
        assertFalse(history.cancelRun(admitted.runId, "USER_CANCELLED"))
        assertEquals(
            1,
            history.traceForRun(admitted.runId).count { it.kind == AGENT_TRACE_KIND_RUN_CANCELLED },
        )
    }

    // ---- Harness -------------------------------------------------------------------------

    private fun providerConfig(): HostedProviderConfig = HostedProviderConfig(
        protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        endpoint = "https://provider.example/v1/chat/completions",
        model = MODEL,
        authentication = HostedAuthentication.Bearer(TOKEN),
        budget = HostedGenerationBudget(maxOutputTokens = 1024),
        capabilities = HostedProviderCapabilities(
            supportsToolCalls = true,
            supportsParallelToolCalls = false,
            supportsReasoningBudget = false,
            supportsTemperature = false,
        ),
    )

    private fun jsonResponse(body: String): HostedHttpResponse =
        HostedHttpResponse(status = 200, headers = mapOf("content-type" to "application/json"), body = body)

    private fun toolTurnBody(callId: String, name: String, arguments: JSONObject): String =
        JSONObject()
            .put("id", "chatcmpl-${sequence.incrementAndGet()}")
            .put("model", MODEL)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", JSONObject.NULL)
                                .put(
                                    "tool_calls",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("id", callId)
                                            .put("type", "function")
                                            .put(
                                                "function",
                                                JSONObject()
                                                    .put("name", name)
                                                    .put("arguments", arguments.toString()),
                                            ),
                                    ),
                                ),
                        )
                        .put("finish_reason", "tool_calls"),
                ),
            )
            .put("usage", JSONObject().put("prompt_tokens", 90).put("completion_tokens", 15))
            .toString()

    /** The initial observation array the executor rendered into the run's first user turn. */
    private fun observations(body: String): JSONArray {
        val messages = JSONObject(body).getJSONArray("messages")
        val userContent = messages.getJSONObject(1).getString("content")
        val line = userContent.lines().first { it.trimStart().startsWith("[") }
        return JSONArray(line.trim())
    }

    private fun selectSourceArguments(body: String): JSONObject {
        val observations = observations(body)
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") == "capture") {
                return JSONObject()
                    .put("capture_ref", item.getString("ref"))
                    .put("quote", item.getString("source_text"))
                    .put("occurrence", 1)
            }
        }
        error("the initial observations carried no capture alias")
    }

    private fun askUserArguments(body: String): JSONObject {
        val messages = JSONObject(body).getJSONArray("messages")
        val toolMessage = (messages.length() - 1 downTo 0)
            .map { index -> messages.getJSONObject(index) }
            .first { message -> message.optString("role") == "tool" }
        val envelope = JSONObject(toolMessage.getString("content"))
        return JSONObject()
            .put("subject_ref", envelope.getJSONObject("data").getString("source_ref"))
            .put("field", "amount")
            .put("question", "Which amount should this note record?")
    }

    /** A transport that answers with the queue and records the exact request bodies it saw. */
    private class ScriptedTransport(
        private val handlers: MutableList<(HostedHttpRequest, Int) -> HostedHttpResponse>,
    ) : HttpTransport {
        val requests: MutableList<String> = mutableListOf()
        private var index = 0

        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
            requests += request.body
            val handler = handlers.getOrNull(index)
                ?: error("the provider was called more times than the script allows")
            index += 1
            return handler(request, index)
        }
    }

    /** A transport that fails before any response exists. */
    private class FailingTransport(private val failure: Exception) : HttpTransport {
        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse = throw failure
    }

    private companion object {
        const val MODEL = "pt1-trace-model"
        const val TOKEN = "pt1-synthetic-credential-value"
        const val SOURCE = "Coffee 25,000 VND on 2026-10-22 at 09:15. Synthetic fixture only."

        const val TOOL_TURN_BODY =
            "{\"id\":\"chatcmpl-trace-1\",\"model\":\"pt1-trace-model\",\"choices\":[{\"index\":0," +
                "\"message\":{\"role\":\"assistant\",\"content\":null," +
                "\"reasoning_content\":\"hidden provider reasoning\"," +
                "\"tool_calls\":[{\"id\":\"call-trace-1\",\"type\":\"function\",\"function\":{" +
                "\"name\":\"list_drafts\",\"arguments\":\"{\\\"review_status\\\":\\\"PENDING\\\"," +
                "\\\"duplicates_only\\\":false,\\\"cursor\\\":null}\"}}]}," +
                "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":120," +
                "\"completion_tokens\":20}}"

        const val ANSWER_TURN_BODY =
            "{\"id\":\"chatcmpl-trace-2\",\"model\":\"pt1-trace-model\",\"choices\":[{\"index\":0," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"No pending drafts are in this scope.\"}," +
                "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":150,\"completion_tokens\":12}}"
    }
}
