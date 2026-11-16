package io.github.triet4p.aegisf.agent

import android.os.Debug
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LedgerRecordQuery
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.ReviewQueueQuery
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.privacy.InboundScan
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedAuthentication
import io.github.triet4p.aegisf.provider.HostedGenerationBudget
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HostedProviderCapabilities
import io.github.triet4p.aegisf.provider.HostedProviderConfig
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedSseEvent
import io.github.triet4p.aegisf.provider.HostedStreamFrame
import io.github.triet4p.aegisf.provider.HttpStreamTransport
import io.github.triet4p.aegisf.provider.HttpTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Fixed synthetic workload measurements on the isolated app runtime; no socket or stored user data. */
@RunWith(AndroidJUnit4::class)
class S116PerformanceInstrumentedTest {
    private class SyntheticStreamTransport : HttpTransport, HttpStreamTransport {
        var requests = 0
            private set
        var serializedRequestBytes = 0L
            private set
        var externalNetworkBytes = 0L
            private set

        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse =
            error("the synthetic streaming path must not use complete-body transport")

        override fun postStream(request: HostedHttpRequest): Flow<HostedStreamFrame> = flow {
            requests += 1
            serializedRequestBytes += request.body.toByteArray(Charsets.UTF_8).size
            emit(
                HostedStreamFrame.Start(
                    status = 200,
                    headers = mapOf("content-type" to "text/event-stream"),
                    errorBody = null,
                ),
            )
            delay(FIRST_TEXT_DELAY_MILLIS)
            emit(
                HostedStreamFrame.Event(
                    HostedSseEvent(
                        event = null,
                        data = """{"model":"s116-synthetic-model","choices":[{"index":0,"delta":{"role":"assistant","content":"Synthetic response."}}]}""",
                    ),
                ),
            )
            delay(FINAL_RESPONSE_DELAY_MILLIS)
            emit(
                HostedStreamFrame.Event(
                    HostedSseEvent(
                        event = null,
                        data = """{"model":"s116-synthetic-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":123,"completion_tokens":8,"total_tokens":131,"prompt_tokens_details":{"cached_tokens":64}}}""",
                    ),
                ),
            )
            emit(HostedStreamFrame.Event(HostedSseEvent(event = null, data = "[DONE]")))
        }
    }

    @Test
    fun fixedWorkloadReportsLocalAndSyntheticProviderMetrics() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("s116_stage", "baseline")
        require(stage == "baseline" || stage == "after")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lines = mutableListOf(
            "stage=$stage",
            "device_runtime=Android-instrumentation",
            "provider_mode=synthetic_in-process-no-socket",
        )
        val pssBeforeKib = processPssKib()
        val workload = maskingWorkload()
        val coldStartNanos = SystemClock.elapsedRealtimeNanos()
        val coldScope = "s116-cold-mask-$stage"
        val coldBoundary = MaskedPromptBoundary(SensitiveVault(coldScope))
        val coldMasked = coldBoundary.maskOutbound(workload, MaskedPath.CONTEXT)
        val coldMaskNanos = SystemClock.elapsedRealtimeNanos() - coldStartNanos
        assertMasked(coldMasked)
        lines += "mask_input_chars=${workload.length} mask_cold_ms=${millis(coldMaskNanos)}"

        val warmBoundary = MaskedPromptBoundary(SensitiveVault("s116-warm-mask-$stage"))
        val expectedMasked = warmBoundary.maskOutbound(workload, MaskedPath.CONTEXT)
        assertMasked(expectedMasked)
        repeat(5) {
            assertEquals(expectedMasked, warmBoundary.maskOutbound(workload, MaskedPath.CONTEXT))
        }
        val warmMaskTimes = LongArray(WARM_SAMPLES)
        val allocationBefore = allocatedBytes()
        var maskOutputsMatch = true
        repeat(WARM_SAMPLES) { index ->
            val started = SystemClock.elapsedRealtimeNanos()
            val masked = warmBoundary.maskOutbound(workload, MaskedPath.CONTEXT)
            warmMaskTimes[index] = SystemClock.elapsedRealtimeNanos() - started
            if (masked != expectedMasked) maskOutputsMatch = false
        }
        val allocationAfter = allocatedBytes()
        check(maskOutputsMatch)
        lines += "mask_warm_${summary(warmMaskTimes)}"
        lines += "mask_warm_allocated_bytes_delta=${delta(allocationBefore, allocationAfter)}"
        lines += "mask_vault_entries=${warmBoundary.vault.entryCount()} mask_output_chars=${expectedMasked.length}"

        val scanTimes = LongArray(WARM_SAMPLES)
        repeat(5) { assertEquals(InboundScan.Accepted, warmBoundary.scanInbound(expectedMasked)) }
        repeat(WARM_SAMPLES) { index ->
            val started = SystemClock.elapsedRealtimeNanos()
            val result = warmBoundary.scanInbound(expectedMasked)
            scanTimes[index] = SystemClock.elapsedRealtimeNanos() - started
            assertEquals(InboundScan.Accepted, result)
        }
        lines += "inbound_scan_${summary(scanTimes)}"

        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            seedDataset(database)
            val queries = WorkflowQueryService(database)
            val ledgerQuery = LedgerRecordQuery(bounds = QueryPageRequest(limit = PAGE_SIZE))
            val queueQuery = ReviewQueueQuery(bounds = QueryPageRequest(limit = PAGE_SIZE))
            val ledgerFirstNanos = measureOne {
                queries.ledgerRecords(ledgerQuery).items.size == PAGE_SIZE
            }
            val ledgerTimes = measure(WARM_SAMPLES, warmups = 5) {
                queries.ledgerRecords(ledgerQuery).items.size == PAGE_SIZE
            }
            val queueFirstNanos = measureOne {
                queries.reviewQueue(queueQuery).items.size == PAGE_SIZE
            }
            val queueTimes = measure(WARM_SAMPLES, warmups = 5) {
                queries.reviewQueue(queueQuery).items.size == PAGE_SIZE
            }
            lines += "ledger_dataset_rows=$LEDGER_ROWS query_page=$PAGE_SIZE first_after_seed_ms=${millis(ledgerFirstNanos)} warm_${summary(ledgerTimes)}"
            lines += "review_queue_dataset_rows=$QUEUE_ROWS queue_page=$PAGE_SIZE first_after_seed_ms=${millis(queueFirstNanos)} warm_${summary(queueTimes)}"

            val commands = WorkflowCommandService(database, timezoneId = { "UTC" })
            val runtimeScope = WorkflowCallerScope.AgentSession(
                scopeId = "s116-tool-validation-$stage",
                captureIds = emptySet(),
                draftIds = emptySet(),
            )
            val runtimeBoundary = MaskedPromptBoundary(SensitiveVault(runtimeScope.scopeId))
            val declarations = AgentToolDeclarations.load(context.assets::open)
            val runtime = AgentToolRuntime(
                commands = commands,
                queries = queries,
                session = AgentToolSession(
                    scope = runtimeScope,
                    boundary = runtimeBoundary,
                    registry = AgentReferenceRegistry(runtimeScope.scopeId),
                ),
                declarations = declarations,
            )
            val invalidArguments = JSONObject().put("unexpected", "synthetic")
            val invalidAllocationBefore = allocatedBytes()
            val invalidFirstNanos = measureOne {
                val result = runtime.execute("get_current_time", invalidArguments, "s116-invalid")
                result.envelope.getString("outcome") == "error" &&
                    result.envelope.getString("dispatch") == "not_dispatched"
            }
            val invalidTimes = measure(WARM_SAMPLES, warmups = 5) {
                val result = runtime.execute("get_current_time", invalidArguments, "s116-invalid")
                result.envelope.getString("outcome") == "error" &&
                    result.envelope.getString("dispatch") == "not_dispatched"
            }
            val invalidAllocationAfter = allocatedBytes()
            val validArguments = JSONObject()
            val validAllocationBefore = allocatedBytes()
            val validFirstNanos = measureOne {
                runtime.execute("get_current_time", validArguments, "s116-valid")
                    .envelope.getString("outcome") == "ok"
            }
            val validTimes = measure(WARM_SAMPLES, warmups = 5) {
                runtime.execute("get_current_time", validArguments, "s116-valid")
                    .envelope.getString("outcome") == "ok"
            }
            val validAllocationAfter = allocatedBytes()
            assertEquals(LEDGER_ROWS, database.transactionDao().findAll().size)
            lines += "tool_validation_invalid_first_ms=${millis(invalidFirstNanos)} warm_${summary(invalidTimes)} allocated_bytes_1cold_5warmup_20sample=${delta(invalidAllocationBefore, invalidAllocationAfter)}"
            lines += "tool_validation_valid_readonly_first_ms=${millis(validFirstNanos)} warm_${summary(validTimes)} allocated_bytes_1cold_5warmup_20sample=${delta(validAllocationBefore, validAllocationAfter)}"
            lines += "manual_intake_isolation=0 ledger writes from local validation workload"

            val syntheticTransport = SyntheticStreamTransport()
            val config = HostedProviderConfig(
                protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://example.invalid/v1/chat/completions",
                model = "s116-synthetic-model",
                authentication = HostedAuthentication.Bearer("s116-synthetic-only-not-a-key"),
                budget = HostedGenerationBudget(),
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = false,
                    supportsTemperature = false,
                ),
            )
            val adapter = HostedAdapters.create(config, syntheticTransport)
            val providerTimes = LongArray(PROVIDER_WARM_SAMPLES + 1)
            val firstTextTimes = LongArray(PROVIDER_WARM_SAMPLES + 1)
            var finalUsage = "unavailable"
            var finalTurns = 0
            repeat(providerTimes.size) { index ->
                val scope = WorkflowCallerScope.AgentSession(
                    scopeId = "s116-provider-$stage-$index",
                    captureIds = emptySet(),
                    draftIds = emptySet(),
                )
                val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
                val gateway = MaskedHostedGateway(adapter, boundary)
                val skillCatalog = WorkflowSkillCatalog.load(context.assets::open)
                val host = WorkflowRunHost(
                    scope = scope,
                    boundary = boundary,
                    gateway = gateway,
                    declarations = declarations,
                    skills = skillCatalog,
                    commands = commands,
                    queries = queries,
                    operationKeys = WorkflowOperationKeySource { "s116-op-$index" },
                )
                val request = WorkflowRunRequest(
                    intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
                    userRequest = "Return the fixed synthetic status without changing data.",
                    requiredIntents = emptySet(),
                )
                var firstTextAt: Long? = null
                val started = SystemClock.elapsedRealtimeNanos()
                val outcome = BoundedWorkflowExecutor(host).startTracked(
                    request = request,
                    history = AgentRunHistory(database),
                    attemptKey = "s116-provider-attempt-$index",
                    progress = { progress ->
                        if (firstTextAt == null && progress is WorkflowRunProgress.PublicText) {
                            firstTextAt = SystemClock.elapsedRealtimeNanos()
                        }
                    },
                )
                val finished = SystemClock.elapsedRealtimeNanos()
                providerTimes[index] = finished - started
                firstTextTimes[index] = (firstTextAt ?: error("synthetic response did not stream public text")) - started
                val completed = outcome as? WorkflowRunOutcome.Completed
                    ?: error("synthetic request did not complete: ${outcome::class.simpleName}")
                assertEquals("Synthetic response.", completed.answer)
                assertEquals(0, completed.toolCalls)
                finalTurns = completed.turns
                finalUsage = completed.usage.joinToString(",") { usage ->
                    "input=${usage.inputTokens};output=${usage.outputTokens};reasoning=${usage.reasoningTokens};cached=${usage.cachedInputTokens}"
                }
                assertEquals(AgentCacheSupport.CACHED_PREFIX_REUSE, completed.cacheSupport)
            }
            assertEquals(providerTimes.size, syntheticTransport.requests)
            assertEquals(0L, syntheticTransport.externalNetworkBytes)
            lines += "synthetic_agent_turns=$finalTurns warmup_runs=1 measured_runs=$PROVIDER_WARM_SAMPLES"
            lines += "synthetic_whole_request_cold_ms=${millis(providerTimes.first())}"
            lines += "synthetic_first_result_cold_ms=${millis(firstTextTimes.first())}"
            lines += "synthetic_whole_request_warm_${summary(providerTimes.copyOfRange(1, providerTimes.size))}"
            lines += "synthetic_first_result_warm_${summary(firstTextTimes.copyOfRange(1, firstTextTimes.size))}"
            lines += "synthetic_usage=$finalUsage"
            lines += "synthetic_request_count=${syntheticTransport.requests} serialized_body_bytes=${syntheticTransport.serializedRequestBytes} external_network_bytes=${syntheticTransport.externalNetworkBytes}"
            lines += "synthetic_provider_cost=not exposed by app usage contract; no live charge"
        } finally {
            database.close()
        }

        lines += "process_pss_before_kib=${pssBeforeKib ?: "unavailable"} process_pss_after_kib=${processPssKib() ?: "unavailable"}"
        val resultFile = File(context.cacheDir, "s116-performance-$stage.txt")
        resultFile.writeText(lines.joinToString("\n", postfix = "\n"))
        android.util.Log.i("S116_PERF", "metrics_file=${resultFile.name} lines=${lines.size}")
        lines.forEach { android.util.Log.i("S116_PERF", it) }
        assertTrue("measurement artifact was not written", resultFile.length() > 0)
    }

    private fun maskingWorkload(): String = buildString {
        repeat(MASK_LINES) { index ->
            val amount = String.format(Locale.US, "%,d", 15_000_000L + index * 1_000L)
            append("Synthetic purchase ").append(index)
                .append(" paid ").append(amount).append(" VND on 2026-10-25 at 08:30 ")
                .append("account ending 4821 reference AB12CD. ")
        }
    }

    private fun assertMasked(masked: String) {
        repeat(MASK_LINES) { index ->
            val amount = String.format(Locale.US, "%,d", 15_000_000L + index * 1_000L)
            assertFalse("synthetic amount $index was not masked", masked.contains(amount))
        }
        for (literal in listOf("2026-10-25", "08:30", "4821", "AB12CD")) {
            assertFalse("synthetic raw literal was not masked: $literal", masked.contains(literal))
        }
    }

    private suspend fun seedDataset(database: LedgerDatabase) {
        val now = 1_799_000_000_000L
        repeat(LEDGER_ROWS) { index ->
            database.transactionDao().insert(
                TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amountVnd = 1_000L + index,
                    occurredAtEpochMillis = now + index * 60_000L,
                    description = "Synthetic performance row $index",
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
        }
        repeat(QUEUE_ROWS) { index ->
            database.pendingBankDraftDao().store(
                PendingBankDraftEntity(
                    deliveryId = "s116-performance-$index",
                    sourcePackage = "synthetic.s116",
                    notificationKey = "s116-$index",
                    postedAtEpochMillis = now + index * 1_000L,
                    bank = "SYNTHETIC",
                    parseStatus = IntakeParseStatus.PARSED,
                    amountState = IntakeFieldState.PRESENT,
                    amountVnd = 50_000L + index,
                    amountUnresolvedReason = null,
                    direction = IntakeDirection.DEBIT,
                    momentState = IntakeFieldState.PRESENT,
                    statedDate = "2026-10-25",
                    statedTime = "08:30",
                    momentUnresolvedReason = null,
                    memoState = IntakeFieldState.PRESENT,
                    memo = "Synthetic performance row $index",
                    memoUnresolvedReason = null,
                    createdAtEpochMillis = now + index * 1_000L,
                ),
            )
        }
    }

    private suspend fun measure(
        count: Int,
        warmups: Int,
        operation: suspend () -> Boolean,
    ): LongArray {
        repeat(warmups) { check(operation()) }
        return LongArray(count).also { timings ->
            repeat(count) { index ->
                val started = SystemClock.elapsedRealtimeNanos()
                val succeeded = operation()
                timings[index] = SystemClock.elapsedRealtimeNanos() - started
                check(succeeded)
            }
        }
    }
    private suspend fun measureOne(operation: suspend () -> Boolean): Long {
        val started = SystemClock.elapsedRealtimeNanos()
        val succeeded = operation()
        val elapsed = SystemClock.elapsedRealtimeNanos() - started
        check(succeeded)
        return elapsed
    }

    private fun summary(samples: LongArray): String {
        val ordered = samples.sortedArray()
        val p50 = ordered[((ordered.size - 1) * 50) / 100]
        val p90 = ordered[((ordered.size - 1) * 90) / 100]
        return "p50_ms=${millis(p50)} p90_ms=${millis(p90)} n=${ordered.size}"
    }

    private fun millis(nanos: Long): String = String.format(Locale.US, "%.3f", nanos / 1_000_000.0)

    private fun allocatedBytes(): Long? = runCatching {
        Debug.getRuntimeStats()["art.gc.bytes-allocated"]?.toLongOrNull()
    }.getOrNull()

    private fun delta(before: Long?, after: Long?): String =
        if (before != null && after != null) (after - before).toString() else "unavailable"

    private fun processPssKib(): Long? = runCatching {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        memoryInfo.totalPss.toLong()
    }.getOrNull()

    private companion object {
        const val MASK_LINES = 128
        const val WARM_SAMPLES = 20
        const val PROVIDER_WARM_SAMPLES = 5
        const val LEDGER_ROWS = 512
        const val QUEUE_ROWS = 128
        const val PAGE_SIZE = 50
        const val FIRST_TEXT_DELAY_MILLIS = 20L
        const val FINAL_RESPONSE_DELAY_MILLIS = 15L
    }
}
