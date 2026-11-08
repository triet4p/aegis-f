package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.provider.HostedProviderFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunFailureTest {
    @Test
    fun runWallClockBudgetIsTerminalNotRetryable() {
        // A spent run budget cannot be recovered by replaying the same exhausted budget; the
        // attempt loop re-ran a 300 s run three times before this classification was fixed.
        val failure = AgentRunFailure.ofStop(WorkflowRunStopReason.TIME_BUDGET, "time bound reached")
        assertFalse(failure!!.retryable)
        assertEquals("time_budget", failure.code)
    }

    @Test
    fun otherBudgetsStayTerminal() {
        listOf(
            WorkflowRunStopReason.TURN_BUDGET,
            WorkflowRunStopReason.TOOL_CALL_BUDGET,
            WorkflowRunStopReason.CONTEXT_BUDGET,
        ).forEach { reason ->
            assertFalse(reason.name, AgentRunFailure.ofStop(reason, null)!!.retryable)
        }
    }

    @Test
    fun providerRequestTimeoutRemainsRetryable() {
        val failure = AgentRunFailure.ofProvider(
            HostedProviderFailure.ProviderState("request_timeout", "socket timeout"),
        )
        assertTrue(failure is AgentRunFailure.Timeout)
        assertTrue(failure.retryable)
    }
}
