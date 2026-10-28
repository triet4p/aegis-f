package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.laya.LayaClassificationEngine
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedAuthentication
import io.github.triet4p.aegisf.provider.HostedGenerationBudget
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HttpTransport
import io.github.triet4p.aegisf.provider.UrlConnectionHttpTransport
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One bounded, owner-authorized real-phone smoke of the S8.13-pre interface.
 *
 * The provider credential is never an instrumentation argument, file on the device, or log
 * line: the host opens a one-shot loopback socket through `adb reverse`, writes the key once,
 * and this process reads it into memory only. Requests are recorded with the authorization
 * header removed so the evidence never carries a credential or a raw sensitive literal.
 *
 * Two sequential workflows are exercised against the product Go/Zen DeepSeek endpoint:
 * 1. a complete no-merchant reading, then an exact journal replay of that operation;
 * 2. a grounded partial reading with a missing amount, one clarification suspension, and a
 *    resumed verified correction.
 *
 * This is not a qualification batch and it does not score historical cases.
 */
@RunWith(AndroidJUnit4::class)
class S813HostedPhoneSmokeInstrumentedTest {
    @Test
    fun normalReplayAndClarificationResumeOnProductionPhone() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val credentialPort = requireNotNull(args.getString("S813_CREDENTIAL_PORT")) {
            "the bounded smoke needs the one-shot credential channel"
        }.toInt()
        val outputCap = args.getString("S813_OUTPUT_CAP")?.toIntOrNull() ?: 8192
        val credential = readCredential(credentialPort)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val transport = RecordingTransport(UrlConnectionHttpTransport())
            val config = HostedAdapters.create(goProviderConfig(credential, outputCap), transport)
            val declarations = AgentToolDeclarations.load(context.assets::open)
            val skills = WorkflowSkillCatalog.load(context.assets::open)
            val evidence = JSONObject()
                .put("task_id", "S8.13-pre")
                .put("attempt_id", "S8.13-pre-impl-a3")
                .put("contract", AgentInterface.CONTRACT)
                .put("protocol", "OPENAI_CHAT_COMPLETIONS")
                .put("endpoint_host", "opencode.ai")
                .put("model_role", "product-deepseek")
                .put("output_cap", outputCap)
                .put("hosted_provider_calls", JSONArray())
                .put("credential_recorded", false)
            val cases = JSONArray()
            cases.put(normalThenExactReplay(context, declarations, skills, transport, config, outputCap))
            cases.put(partialThenClarificationResume(context, declarations, skills, transport, config, outputCap))
            evidence.put("cases", cases)
            evidence.put("wire_attempts", transport.evidence())
            evidence.put("raw_sensitive_absent_from_egress", transport.rawSensitiveAbsent)
            evidence.put("authorization_recorded", transport.authorizationSeen)
            val output = File(context.filesDir, "S8.13-pre-impl-a3/hosted-phone-smoke.json")
            output.parentFile?.mkdirs()
            output.writeText(evidence.toString(2))
        } finally {
            // The credential is not retained beyond this test process.
        }
    }

    private suspend fun normalThenExactReplay(
        context: Context,
        declarations: List<io.github.triet4p.aegisf.provider.HostedToolDeclaration>,
        skills: WorkflowSkillCatalog,
        transport: RecordingTransport,
        config: io.github.triet4p.aegisf.provider.HostedGenerationAdapter,
        outputCap: Int,
    ): JSONObject {
        val fixture = fixture(context, "Lunch 28,000 VND on 2026-10-21 at 12:30", "Lunch")
        val run = runOnce(
            fixture = fixture,
            declarations = declarations,
            skills = skills,
            transport = transport,
            config = config,
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS, WorkflowIntent.REPLAY_OPERATION),
            userRequest = "Interpret the synthetic note. The merchant is not stated, so leave it " +
                "null. After the reading is stored, confirm the exact recorded operation by " +
                "calling replay_operation with the returned operation_ref, then report the result.",
            outputCap = outputCap,
        )
        val outcome = run.outcome
        val event = fixture.database.noteEventDao().findForCapture(fixture.captureId).singleOrNull()
        val result = JSONObject()
            .put("case", "normal_no_merchant_then_exact_replay")
            .put("outcome", outcomeName(outcome))
            .put("reason", outcomeReason(outcome))
            .put("turns", outcomeTurns(outcome))
            .put("tool_calls", outcomeCalls(outcome))
            .put("verified_effects", outcomeVerifiedEffects(outcome))
            .put("event_amount_vnd", event?.amountVnd ?: JSONObject.NULL)
            .put("event_merchant", event?.merchant ?: JSONObject.NULL)
            .put("event_unresolved_reason", event?.unresolvedReason ?: JSONObject.NULL)
            .put("event_status", event?.status?.name ?: JSONObject.NULL)
            .put("event_anchor_date", event?.anchorDate ?: JSONObject.NULL)
            .put("event_anchor_time", event?.anchorTime ?: JSONObject.NULL)
            .put("update_event_operations", fixture.database.workflowOperationDao().countOf("UPDATE_EVENT"))
            .put("all_operation_rows", fixture.database.workflowOperationDao().countOf("UPDATE_EVENT"))
            .put("ledger_rows", fixture.database.transactionDao().findAll().size)
        try {
            assertTrue("the normal+replay run did not complete: $result", outcome is WorkflowRunOutcome.Completed)
            outcome as WorkflowRunOutcome.Completed
            assertNotNull("the reading was not stored", event)
            assertEquals(28_000L, event!!.amountVnd)
            assertNull(event.merchant)
            assertNull(event.unresolvedReason)
            assertEquals("2026-10-21", event.anchorDate)
            assertEquals("12:30", event.anchorTime)
            assertEquals(1L, fixture.database.workflowOperationDao().countOf("UPDATE_EVENT"))
            assertEquals(0, fixture.database.transactionDao().findAll().size)
            assertTrue(
                "no verified event reading",
                outcome.verifiedEffects.any { it is WorkflowVerifiedEffect.EventReading },
            )
            val replay = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.ReplayIdentity>()
            assertTrue("no verified exact replay", replay.isNotEmpty())
            assertTrue("the replayed effect was not current", replay.all { it.currentEffectMatches })
        } catch (failure: AssertionError) {
            result.put("failure", failure.message ?: "assertion failed")
            throw failure
        } finally {
            fixture.database.close()
            fixture.laya.close()
        }
        return result
    }

    private suspend fun partialThenClarificationResume(
        context: Context,
        declarations: List<io.github.triet4p.aegisf.provider.HostedToolDeclaration>,
        skills: WorkflowSkillCatalog,
        transport: RecordingTransport,
        config: io.github.triet4p.aegisf.provider.HostedGenerationAdapter,
        outputCap: Int,
    ): JSONObject {
        val fixture = fixture(context, "Taxi ride on 2026-10-21", "Taxi ride")
        val executor = boundaryExecutor(fixture, declarations, skills, transport, config)
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the synthetic taxi note. Its amount is not stated: store the " +
                "grounded partial reading, then call ask_user with field amount and one focused " +
                "question. Do not invent an amount.",
        )
        val first = executor.start(request)
        val partial = fixture.database.noteEventDao().findForCapture(fixture.captureId).singleOrNull()
        val result = JSONObject()
            .put("case", "partial_then_clarification_resume")
            .put("first_outcome", outcomeName(first))
            .put("first_reason", outcomeReason(first))
            .put("clarification_field", (first as? WorkflowRunOutcome.Clarifying)?.field ?: JSONObject.NULL)
            .put("partial_amount_vnd", partial?.amountVnd ?: JSONObject.NULL)
            .put("partial_unresolved_reason", partial?.unresolvedReason ?: JSONObject.NULL)
            .put("partial_status", partial?.status?.name ?: JSONObject.NULL)
        try {
            assertTrue("the partial run did not suspend for clarification: $result", first is WorkflowRunOutcome.Clarifying)
            first as WorkflowRunOutcome.Clarifying
            assertEquals("amount", first.field)
            assertNotNull("the partial reading was not stored", partial)
            assertNull(partial!!.amountVnd)
            assertEquals("UNRESOLVED", partial.status.name)
            assertNotNull(partial.unresolvedReason)

            val resumed = executor.resume(first.continuation, "28,000")
            val corrected = fixture.database.noteEventDao().findForCapture(fixture.captureId).singleOrNull()
            result.put("resumed_outcome", outcomeName(resumed))
            result.put("resumed_reason", outcomeReason(resumed))
            result.put("corrected_amount_vnd", corrected?.amountVnd ?: JSONObject.NULL)
            result.put("corrected_unresolved_reason", corrected?.unresolvedReason ?: JSONObject.NULL)
            result.put("corrected_status", corrected?.status?.name ?: JSONObject.NULL)
            result.put("verified_effects", outcomeVerifiedEffects(resumed))
            result.put("update_event_operations", fixture.database.workflowOperationDao().countOf("UPDATE_EVENT"))
            result.put("ledger_rows", fixture.database.transactionDao().findAll().size)
            assertTrue("the resumed run did not complete: $result", resumed is WorkflowRunOutcome.Completed)
            assertNotNull("the corrected reading was not stored", corrected)
            assertEquals(28_000L, corrected!!.amountVnd)
            assertNull(corrected.unresolvedReason)
            assertEquals("INTERPRETED", corrected.status.name)
            assertEquals(0, fixture.database.transactionDao().findAll().size)
        } catch (failure: AssertionError) {
            result.put("failure", failure.message ?: "assertion failed")
            throw failure
        } finally {
            fixture.database.close()
            fixture.laya.close()
        }
        return result
    }

    private suspend fun runOnce(
        fixture: Fixture,
        declarations: List<io.github.triet4p.aegisf.provider.HostedToolDeclaration>,
        skills: WorkflowSkillCatalog,
        transport: RecordingTransport,
        config: io.github.triet4p.aegisf.provider.HostedGenerationAdapter,
        intents: Set<WorkflowIntent>,
        userRequest: String,
        outputCap: Int,
    ): RunResult {
        val executor = boundaryExecutor(fixture, declarations, skills, transport, config)
        val startedAt = System.nanoTime()
        val outcome = executor.start(
            WorkflowRunRequest(
                intents = intents,
                userRequest = userRequest,
                limits = WorkflowRunLimits(
                    maxTurns = 10,
                    maxToolCalls = 12,
                    maxContextCharacters = 160_000,
                    maxWallClockMillis = 300_000,
                ),
            ),
        )
        return RunResult(outcome, (System.nanoTime() - startedAt) / 1_000_000)
    }

    private fun boundaryExecutor(
        fixture: Fixture,
        declarations: List<io.github.triet4p.aegisf.provider.HostedToolDeclaration>,
        skills: WorkflowSkillCatalog,
        transport: RecordingTransport,
        config: io.github.triet4p.aegisf.provider.HostedGenerationAdapter,
    ): BoundedWorkflowExecutor {
        val gateway = MaskedHostedGateway(config, fixture.boundary)
        val host = WorkflowRunHost(
            scope = fixture.scope,
            boundary = fixture.boundary,
            gateway = gateway,
            declarations = declarations,
            skills = skills,
            commands = fixture.commands,
            queries = fixture.queries,
            operationKeys = WorkflowOperationKeySource { "s813-pre-a3-${UUID.randomUUID()}" },
        )
        return BoundedWorkflowExecutor(host)
    }

    private suspend fun fixture(
        context: Context,
        source: String,
        draftMemo: String,
    ): Fixture {
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val laya = LayaInferenceLifecycle(File(context.filesDir, "laya/model"))
        val commands = WorkflowCommandService(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
            engine = LayaClassificationEngine(laya),
        )
        val queries = WorkflowQueryService(database)
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s8.13-pre-a3-${UUID.randomUUID()}", source),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
        val now = System.currentTimeMillis()
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s8.13-pre-a3-draft-${UUID.randomUUID()}",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = now,
                bank = "Synthetic Bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = 28_000L,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-10-21",
                statedTime = "12:30",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = draftMemo,
                memoUnresolvedReason = null,
                createdAtEpochMillis = now,
            ),
        )
        val scope = WorkflowCallerScope.AgentSession(
            "s8.13-pre-a3-${UUID.randomUUID()}",
            setOf(captureId),
            setOf(draftId),
        )
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        return Fixture(database, commands, queries, captureId, draftId, scope, boundary, laya)
    }

    private fun goProviderConfig(credential: String, outputCap: Int) =
        io.github.triet4p.aegisf.provider.HostedProviderConfig(
            protocol = io.github.triet4p.aegisf.provider.HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "https://opencode.ai/zen/go/v1/chat/completions",
            model = "deepseek-v4.1-flash",
            authentication = HostedAuthentication.Bearer(credential),
            budget = HostedGenerationBudget(outputCap, temperature = 0.1, parallelToolCalls = false),
            capabilities = io.github.triet4p.aegisf.provider.HostedProviderCapabilities(
                supportsToolCalls = true,
                supportsParallelToolCalls = true,
                supportsReasoningBudget = false,
                supportsTemperature = true,
                maxOutputTokensLimit = 8192,
            ),
            clientHeaders = mapOf(
                "x-opencode-session" to "s8.13-pre-a3-phone-smoke",
                "User-Agent" to "aegis-f-s8.13-pre-a3/1.0",
            ),
        )

    /** Reads the one-shot credential from the host's loopback channel; never logs it. */
    private fun readCredential(port: Int): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 10_000)
            val line = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
            require(!line.isNullOrBlank()) { "the credential channel supplied no value" }
            return line
        }
    }

    private fun outcomeName(value: WorkflowRunOutcome): String = when (value) {
        is WorkflowRunOutcome.Completed -> "Completed"
        is WorkflowRunOutcome.Clarifying -> "Clarifying"
        is WorkflowRunOutcome.Refused -> "Refused"
        is WorkflowRunOutcome.Incomplete -> "Incomplete"
    }

    private fun outcomeReason(value: WorkflowRunOutcome): Any = when (value) {
        is WorkflowRunOutcome.Completed -> JSONObject.NULL
        is WorkflowRunOutcome.Clarifying -> value.field ?: JSONObject.NULL
        is WorkflowRunOutcome.Refused -> value.reason
        is WorkflowRunOutcome.Incomplete -> value.reason.name + (value.detail?.let { ":$it" } ?: "")
    }

    private fun outcomeTurns(value: WorkflowRunOutcome): Int = when (value) {
        is WorkflowRunOutcome.Completed -> value.turns
        is WorkflowRunOutcome.Incomplete -> value.turns
        else -> 0
    }

    private fun outcomeCalls(value: WorkflowRunOutcome): Int = when (value) {
        is WorkflowRunOutcome.Completed -> value.toolCalls
        is WorkflowRunOutcome.Incomplete -> value.toolCalls
        else -> 0
    }

    private fun outcomeVerifiedEffects(value: WorkflowRunOutcome): JSONArray {
        val effects = (value as? WorkflowRunOutcome.Completed)?.verifiedEffects.orEmpty()
        return JSONArray().also { out ->
            effects.forEach { effect ->
                out.put(
                    when (effect) {
                        is WorkflowVerifiedEffect.EventReading -> JSONObject()
                            .put("kind", "event_reading").put("event_id", effect.eventId)
                            .put("capture_id", effect.captureId).put("revision", effect.revision)
                        is WorkflowVerifiedEffect.LinkState -> JSONObject()
                            .put("kind", "link_state").put("link_id", effect.linkId)
                        is WorkflowVerifiedEffect.Classification -> JSONObject()
                            .put("kind", "classification").put("draft_id", effect.draftId)
                        is WorkflowVerifiedEffect.ClassificationUnavailable -> JSONObject()
                            .put("kind", "classification_unavailable").put("draft_id", effect.draftId)
                            .put("reason", effect.reasonCode)
                        is WorkflowVerifiedEffect.ReplayIdentity -> JSONObject()
                            .put("kind", "replay_identity").put("operation_key", effect.operationKey)
                            .put("current_effect_matches", effect.currentEffectMatches)
                    },
                )
            }
        }
    }

    private data class Fixture(
        val database: LedgerDatabase,
        val commands: WorkflowCommandService,
        val queries: WorkflowQueryService,
        val captureId: Long,
        val draftId: Long,
        val scope: WorkflowCallerScope.AgentSession,
        val boundary: MaskedPromptBoundary,
        val laya: LayaInferenceLifecycle,
    )

    private data class RunResult(val outcome: WorkflowRunOutcome, val latencyMillis: Long)

    /**
     * Records the masked wire for evidence. The authorization header is dropped before storage;
     * a request that carries a raw mapped sensitive surface is reported separately.
     */
    private class RecordingTransport(private val delegate: HttpTransport) : HttpTransport {
        private val attempts = JSONArray()
        var rawSensitiveAbsent = true
        var authorizationSeen = false

        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
            val response = delegate.post(request)
            authorizationSeen = authorizationSeen || request.headers.keys.any { it.equals("Authorization", true) }
            val safeHeaders = JSONObject()
            request.headers.forEach { (name, value) ->
                if (!name.equals("Authorization", true)) safeHeaders.put(name, value)
            }
            if (request.body.contains("⟦UNMAPPED⟧")) rawSensitiveAbsent = false
            attempts.put(
                JSONObject()
                    .put("url", request.url)
                    .put("headers", safeHeaders)
                    .put("body_sha256", sha256(request.body))
                    .put("body_characters", request.body.length)
                    .put("status", response.status),
            )
            return response
        }

        fun evidence(): JSONArray = JSONArray(attempts.toString())
    }

}

private fun sha256(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
