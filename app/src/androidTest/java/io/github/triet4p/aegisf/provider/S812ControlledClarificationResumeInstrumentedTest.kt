package io.github.triet4p.aegisf.provider

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.AegisApplication
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.agent.BoundedWorkflowToolAdapter
import io.github.triet4p.aegisf.agent.S8AgentToolDeclarations
import io.github.triet4p.aegisf.agent.WorkflowIntent
import io.github.triet4p.aegisf.agent.WorkflowOperationKeySource
import io.github.triet4p.aegisf.agent.WorkflowRunHost
import io.github.triet4p.aegisf.agent.WorkflowRunStopReason
import io.github.triet4p.aegisf.agent.WorkflowSkillCatalog
import io.github.triet4p.aegisf.data.CaptureEventQuery
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.ui.QuickCaptureScreen
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the production Chat clarification/resume UI and real app tools with only the model
 * adapter scripted. This is deterministic UI evidence, not hosted-provider evidence: no network
 * transport is constructed, while masking, tool dispatch, durable history, commands, queries,
 * effect verification and the displayed outcome all use their production implementations.
 */
@RunWith(AndroidJUnit4::class)
class S812ControlledClarificationResumeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (compose.activity.application as AegisApplication).container

    @After
    fun removeSyntheticProfile() {
        runCatching { container.hostedProviderSettingsStore.deleteAll() }
    }

    @Test
    fun controlledAdapterClarificationResumesThroughProductionChatUi() = runBlocking {
        val profile = SavedHostedProvider(
            id = "s812-controlled-${UUID.randomUUID()}",
            name = "S8.12 controlled local UI adapter",
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "https://opencode.ai/zen/go/v1/chat/completions",
            model = ControlledAdapter.MODEL,
            credential = "synthetic-controlled-ui-only",
            maxOutputTokens = 8_192,
            reasoningEffort = null,
            temperature = null,
            parallelToolCalls = false,
            capabilities = HostedProviderCapabilities(
                supportsToolCalls = true,
                supportsParallelToolCalls = true,
                supportsReasoningBudget = false,
                supportsTemperature = true,
            ),
        )
        container.hostedProviderSettingsStore.save(listOf(profile))
        container.hostedProviderSettingsStore.activate(profile.id)

        val source = "Tea at the cafe on 2026-10-22 at 18:00."
        val capture = container.workflowCommandService.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s812-controlled-capture-${UUID.randomUUID()}", source),
        )
        assertTrue(capture is WorkflowCommandOutcome.Applied)
        val captureId = ((capture as WorkflowCommandOutcome.Applied).change as WorkflowChange.CaptureSaved)
            .captureId
        val captureRevision = requireNotNull(container.workflowQueryService.sourceExcerpt(captureId)).revision
        val adapterReference = AtomicReference<ControlledAdapter?>()
        val assets: (String) -> InputStream = compose.activity.assets::open

        compose.activity.setContent {
            MaterialTheme {
                QuickCaptureScreen(
                    queryService = container.workflowQueryService,
                    commandService = container.workflowCommandService,
                    hostedProviderSettingsStore = container.hostedProviderSettingsStore,
                    createHostedWorkflowHost = { scope, _ ->
                        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
                        val adapter = ControlledAdapter(
                            captureId = captureId,
                            captureRevision = captureRevision,
                            source = source,
                            boundary = boundary,
                        )
                        adapterReference.set(adapter)
                        WorkflowRunHost(
                            scope = scope,
                            boundary = boundary,
                            gateway = MaskedHostedGateway(adapter, boundary),
                            declarations = S8AgentToolDeclarations.load(assets),
                            skills = WorkflowSkillCatalog.load(assets),
                            commands = container.workflowCommandService,
                            queries = container.workflowQueryService,
                            operationKeys = WorkflowOperationKeySource { UUID.randomUUID().toString() },
                        )
                    },
                    agentRunHistory = container.agentRunHistory,
                    onOpenManual = {},
                    onRecordTransaction = {},
                    onRecordEvent = {},
                )
            }
        }

        compose.onNodeWithText("Ask about your notes or pending drafts")
            .performScrollTo()
            .performTextInput("Interpret the new synthetic Tea note and ask me if a decision is needed.")
        compose.onNodeWithText("Send").performScrollTo().performClick()
        waitForText("Clarification needed:", substring = true)
        compose.onNodeWithText("Your answer")
            .performScrollTo()
            .performTextInput("The amount is 18,000 VND; keep the recorded time.")
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        var resumedVerified = false
        var resumedIncompleteReason: WorkflowRunStopReason? = null
        var resumedClarification = false
        var resumedError = false
        compose.waitUntil(timeoutMillis = 30_000) {
            val stillBusy = compose.onAllNodesWithText("Cancel")
                .fetchSemanticsNodes().isNotEmpty()
            resumedVerified = compose.onAllNodesWithText(
                "Verified application changes:",
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty()
            resumedIncompleteReason = WorkflowRunStopReason.values().firstOrNull { reason ->
                compose.onAllNodesWithText("Run incomplete: $reason.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            resumedClarification = compose.onAllNodesWithText(
                "Clarification needed:",
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty()
            resumedError = compose.onAllNodesWithText(
                "The clarification could not be resumed.",
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty()
            !stillBusy && (
                resumedVerified ||
                    resumedIncompleteReason != null ||
                    resumedClarification ||
                    resumedError
            )
        }

        val events = container.workflowQueryService.captureEvents(
            CaptureEventQuery(captureId, QueryPageRequest(limit = 10, offset = 0)),
        ).items
        val event = events.singleOrNull()
        val eventMatchesExpected = event?.amountVnd == 18_000L &&
            event.anchorDate == "2026-10-22" &&
            event.anchorTime == "18:00"
        val ledgerCount = database().openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM `transactions`").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        val terminalState = when {
            resumedVerified -> "verified"
            resumedIncompleteReason != null -> "incomplete:${resumedIncompleteReason!!.name}"
            resumedClarification -> "clarifying-again"
            resumedError -> "resume-error"
            else -> "unclassified"
        }
        val adapter = requireNotNull(adapterReference.get())
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("S8_12_CONTROLLED_UI", terminalState)
                putInt("S8_12_CONTROLLED_GENERATED_TURNS", adapter.generatedTurns)
                putInt("S8_12_CONTROLLED_EVENT_COUNT", events.size)
                putBoolean("S8_12_CONTROLLED_EVENT_MATCH", eventMatchesExpected)
                putLong("S8_12_CONTROLLED_LEDGER_ROWS", ledgerCount)
                putBoolean("S8_12_CONTROLLED_OUTBOUND_MASKED", adapter.outbound.none {
                    it.contains("18,000") || it.contains("18:00")
                })
            },
        )
        assertTrue("The resumed controlled run must finish with a verified effect, got $terminalState", resumedVerified)
        assertTrue(eventMatchesExpected)
        assertEquals(3, adapter.generatedTurns)
        assertEquals(0L, ledgerCount)
        assertTrue(adapter.outbound.none { it.contains("18,000") })
        assertTrue(adapter.outbound.none { it.contains("18:00") })
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun database(): LedgerDatabase {
        val getter = container.javaClass.getDeclaredMethod("getDatabase")
        getter.isAccessible = true
        return getter.invoke(container) as LedgerDatabase
    }

    private class ControlledConversation(
        override val protocol: HostedProtocol,
        override val pendingCallIds: List<String>,
    ) : HostedConversation

    private class ControlledAdapter(
        private val captureId: Long,
        private val captureRevision: Int,
        private val source: String,
        private val boundary: MaskedPromptBoundary,
    ) : HostedGenerationAdapter {
        override val protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS
        private var nextTurn = 0
        private var resumedInstruction = ""
        val outbound = mutableListOf<String>()
        val generatedTurns: Int get() = nextTurn

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
            outbound += systemInstruction
            outbound += userInstruction
            resumedInstruction = userInstruction
            return ControlledConversation(protocol, emptyList())
        }

        override suspend fun generate(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
        ): HostedGenerationResult = when (nextTurn++) {
            0 -> toolTurn(updateArguments(amountToken = null))
            1 -> {
                val answerLine = resumedInstruction.lineSequence()
                    .firstOrNull { it.startsWith("User answer: ") }
                    ?: error("the controlled resume did not receive its user answer")
                val amountToken = Regex("⟦AMT-[^⟧]+⟧").find(answerLine)?.value
                    ?: error("the controlled resume answer was not locally masked")
                toolTurn(updateArguments(amountToken))
            }
            2 -> HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = MODEL,
                    reportedModel = MODEL,
                    assistantText = "The synthetic event was updated.",
                    toolCalls = emptyList(),
                    finish = HostedTurnFinish.Complete,
                    usage = null,
                    conversation = null,
                ),
            )
            else -> error("the controlled adapter received an unexpected generation turn")
        }

        override fun continueWithToolResults(
            conversation: HostedConversation,
            results: List<HostedToolResult>,
        ): HostedConversation {
            val pending = (conversation as ControlledConversation).pendingCallIds
            require(results.map(HostedToolResult::callId) == pending)
            outbound += results.map(HostedToolResult::outputJson)
            return ControlledConversation(protocol, emptyList())
        }

        private fun toolTurn(arguments: JSONObject): HostedGenerationResult {
            val callId = "controlled-${nextTurn}"
            val call = HostedToolCall(
                callId = callId,
                name = BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION,
                argumentsJson = arguments.toString(),
                arguments = null,
                disposition = ToolCallDisposition.READY,
            )
            return HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = MODEL,
                    reportedModel = MODEL,
                    assistantText = null,
                    toolCalls = listOf(call),
                    finish = HostedTurnFinish.ToolCalls,
                    usage = null,
                    conversation = ControlledConversation(protocol, listOf(callId)),
                ),
            )
        }

        private fun updateArguments(amountToken: String?): JSONObject {
            val masked = boundary.maskOutbound(source, MaskedPath.CONTEXT)
            val dateToken = Regex("⟦DATE-[^⟧]+⟧").find(masked)?.value
                ?: error("the controlled source date was not locally masked")
            val timeToken = Regex("⟦TIME-[^⟧]+⟧").find(masked)?.value
                ?: error("the controlled source time was not locally masked")
            val timeAnchor = JSONObject()
                .put("date", dateToken)
                .put("time", timeToken)
                .put("timezoneId", JSONObject.NULL)
                .put("intervalEndDate", JSONObject.NULL)
                .put("intervalEndTime", JSONObject.NULL)
            return JSONObject()
                .put("captureId", captureId)
                .put("expectedCaptureRevision", captureRevision)
                .put("expectedEventRevision", JSONObject.NULL)
                .put("extractionVersion", "s8.12-controlled-ui-test")
                .put(
                    "interpretation",
                    JSONObject()
                        .put("spanStart", 0)
                        .put("spanEnd", masked.length)
                        .put("normalizedText", masked)
                        .put("maskedSpanText", masked)
                        .put("amountVnd", amountToken ?: JSONObject.NULL)
                        .put("merchant", JSONObject.NULL)
                        .put("unresolvedReason", JSONObject.NULL)
                        .put("timeAnchor", timeAnchor),
                )
        }

        companion object {
            const val MODEL = "s8.12-controlled-test-model"
        }
    }
}
