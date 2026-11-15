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

    @Test
    fun authenticationFailureIsTerminalWhileRateLimitRemainsRetryable() {
        listOf(401, 403).forEach { status ->
            val authentication = AgentRunFailure.ofProvider(
                HostedProviderFailure.Authentication(status, "synthetic key rejected"),
            )
            assertEquals("authentication:$status", authentication.code)
            assertFalse("a rejected provider key is not retryable", authentication.retryable)
        }

        val rateLimited = AgentRunFailure.ofProvider(
            HostedProviderFailure.RateLimited(429, retryAfterSeconds = 17, detail = "synthetic limit"),
        )
        assertTrue(rateLimited is AgentRunFailure.RateLimited)
        assertTrue(rateLimited.retryable)
    }
}
