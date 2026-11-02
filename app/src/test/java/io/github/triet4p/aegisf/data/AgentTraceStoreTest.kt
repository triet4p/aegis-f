package io.github.triet4p.aegisf.data

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The honest failure semantics of the local production trace store.
 *
 * The DAO fake makes storage failure controllable, so the test can prove what the owner's
 * no-silent-loss criterion needs: a failed append never fails the caller, the loss is recorded as
 * an explicit gap as soon as storage answers, the marker keeps the original run/attempt identity,
 * and a loss that cannot even be marked stays pending rather than being reported as complete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentTraceStoreTest {

    @Test
    fun `a loss that could not be marked is recorded when storage returns`() = runTest {
        val dao = FlakyTraceDao()
        val store = AgentTraceStore(dao, clock = { 1_000L })

        // The event write and its immediate marker write both fail.
        dao.failNext = 2
        store.append(
            runId = "run-loss",
            scopeId = "scope-loss",
            attempt = 1,
            kind = AGENT_TRACE_KIND_PROVIDER_REQUEST,
            payloadJson = JSONObject().put("body", "{\"lost\":true}").toString(),
            direction = AGENT_TRACE_DIRECTION_OUTBOUND,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            turn = 1,
        )
        assertTrue("nothing can be stored while the database is down", dao.events.isEmpty())

        // The next successful write records the pending loss before its own event.
        dao.failNext = 0
        store.append(
            runId = "run-loss",
            scopeId = "scope-loss",
            attempt = 1,
            kind = AGENT_TRACE_KIND_PROVIDER_RESPONSE,
            payloadJson = JSONObject().put("body", "{\"kept\":true}").toString(),
            direction = AGENT_TRACE_DIRECTION_INBOUND,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            turn = 1,
        )

        assertEquals(2, dao.events.size)
        val marker = dao.events[0]
        assertEquals(AGENT_TRACE_KIND_EVIDENCE_GAP, marker.kind)
        assertEquals(AGENT_TRACE_COMPLETION_UNAVAILABLE, marker.completion)
        assertEquals("the marker belongs to the run that lost evidence", "run-loss", marker.runId)
        val payload = JSONObject(marker.payloadJson)
        assertEquals("TRACE_WRITE_FAILED_EARLIER", payload.getString("reason"))
        assertEquals(1, payload.getInt("failedEvents"))
        assertEquals(
            listOf(AGENT_TRACE_KIND_PROVIDER_REQUEST),
            (0 until payload.getJSONArray("failedKinds").length()).map { index ->
                payload.getJSONArray("failedKinds").getString(index)
            },
        )
        assertEquals(AGENT_TRACE_KIND_PROVIDER_RESPONSE, dao.events[1].kind)
    }

    @Test
    fun `a transient failure records the gap immediately and never fails the caller`() = runTest {
        val dao = FlakyTraceDao()
        val store = AgentTraceStore(dao, clock = { 1L })
        dao.failNext = 1

        store.append(
            runId = "run-transient",
            scopeId = "scope-transient",
            attempt = 2,
            kind = AGENT_TRACE_KIND_TOOL_RESULT,
            payloadJson = JSONObject().put("name", "list_drafts").toString(),
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            turn = 1,
            callId = "call-1",
        )

        assertEquals(1, dao.events.size)
        val gap = dao.events.single()
        assertEquals(AGENT_TRACE_KIND_EVIDENCE_GAP, gap.kind)
        assertEquals(2, gap.attempt)
        assertEquals("call-1", gap.callId)
        val payload = JSONObject(gap.payloadJson)
        assertEquals("TRACE_WRITE_FAILED", payload.getString("reason"))
        assertEquals(AGENT_TRACE_KIND_TOOL_RESULT, payload.getJSONArray("failedKinds").getString(0))
        assertEquals(1, payload.getInt("failedEvents"))
    }

    @Test
    fun `a pending loss is never attributed to a different run`() = runTest {
        val dao = FlakyTraceDao()
        val store = AgentTraceStore(dao, clock = { 2L })

        dao.failNext = 2
        store.append("run-a", "scope-a", 1, AGENT_TRACE_KIND_PROVIDER_REQUEST, "{}", turn = 1)
        dao.failNext = 0
        store.append("run-b", "scope-b", 1, AGENT_TRACE_KIND_PROVIDER_REQUEST, "{}", turn = 1)

        assertEquals(2, dao.events.size)
        val marker = dao.events[0]
        assertEquals(AGENT_TRACE_KIND_EVIDENCE_GAP, marker.kind)
        assertEquals("run-a", marker.runId)
        assertEquals("scope-a", marker.scopeId)
        assertEquals("run-b", dao.events[1].runId)
    }

    /** A controllable stand-in for the Room DAO; the production store is used unchanged. */
    private class FlakyTraceDao : AgentTraceDao() {
        var failNext: Int = 0
        val events: MutableList<AgentTraceEventEntity> = mutableListOf()

        override suspend fun insert(event: AgentTraceEventEntity): Long {
            if (failNext > 0) {
                failNext -= 1
                throw IllegalStateException("local storage unavailable")
            }
            events += event
            return events.size.toLong()
        }

        override suspend fun findForRun(runId: String): List<AgentTraceEventEntity> =
            events.filter { it.runId == runId }

        override suspend fun findForAttempt(runId: String, attempt: Int): List<AgentTraceEventEntity> =
            events.filter { it.runId == runId && it.attempt == attempt }

        override suspend fun countForRun(runId: String): Long =
            events.count { it.runId == runId }.toLong()
    }
}
