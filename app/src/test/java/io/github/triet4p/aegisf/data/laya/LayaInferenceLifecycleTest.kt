package io.github.triet4p.aegisf.data.laya

import io.github.triet4p.aegisf.data.CategoryEntity
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayaInferenceLifecycleTest {

    private fun category() = CategoryEntity(
        id = 1L,
        name = "Everyday",
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
    )

    private fun decision(id: Long) = LayaCandidatePlanner.Decision.Category(
        categoryId = id,
        snapshotName = "Category $id",
        source = LayaCandidatePlanner.SuggestionSource.MODEL,
        confidence = 0.5,
    )

    private class ControlledSession(blockedDescriptions: Set<String>) : LayaInferenceSession {
        private val entered = blockedDescriptions.associateWith { CountDownLatch(1) }
        private val gates = blockedDescriptions.associateWith { CountDownLatch(1) }
        private val activeCalls = AtomicInteger()
        private val maximumConcurrentCalls = AtomicInteger()
        private val closeCount = AtomicInteger()
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        private val calls = CopyOnWriteArrayList<String>()
        val closeFinished = CountDownLatch(1)

        fun awaitEntered(description: String) {
            assertTrue(
                "worker did not enter $description",
                entered[description]?.await(5, TimeUnit.SECONDS) == true,
            )
        }

        fun release(description: String) {
            gates[description]?.countDown()
        }

        fun awaitClosed() {
            assertTrue("session was not closed", closeFinished.await(5, TimeUnit.SECONDS))
        }

        override fun suggest(
            description: String,
            categories: List<CategoryEntity>,
            correctionRules: List<LayaCategoryCorrectionRule>,
        ): LayaCandidatePlanner.Decision {
            run(description)
            return LayaCandidatePlanner.Decision.Category(
                categoryId = description.length.toLong(),
                snapshotName = "Category ${description.length}",
                source = LayaCandidatePlanner.SuggestionSource.MODEL,
                confidence = 0.5,
            )
        }


        private fun run(description: String) {
            check(!closed.get()) { "session used after close" }
            calls += description
            val active = activeCalls.incrementAndGet()
            maximumConcurrentCalls.updateAndGet { previous -> maxOf(previous, active) }
            try {
                entered[description]?.countDown()
                val gate = gates[description]
                if (gate != null) {
                    check(gate.await(5, TimeUnit.SECONDS)) { "timed out waiting to release $description" }
                }
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        override fun close() {
            check(activeCalls.get() == 0) { "session closed while native work was active" }
            closed.set(true)
            closeCount.incrementAndGet()
            closeFinished.countDown()
        }

        fun calls(): List<String> = calls.toList()
        fun maxConcurrentCalls(): Int = maximumConcurrentCalls.get()
        fun closeCount(): Int = closeCount.get()
        fun isClosed(): Boolean = closed.get()
    }

    private class Sessions(blockedDescriptions: Set<String>) {
        val opened = CopyOnWriteArrayList<ControlledSession>()
        val factory = LayaInferenceRuntimeFactory { _ ->
            val session = ControlledSession(blockedDescriptions)
            opened.add(session)
            LayaInferenceRuntimeOpen.Opened(session)
        }
    }

    @Test
    fun `only the latest pending request runs and a blocking request leaves its caller free`() =
        runBlocking {
            val sessions = Sessions(setOf("active"))
            val lifecycle = LayaInferenceLifecycle(File("unused"), 60_000L, sessions.factory)
            try {
                val active = async(start = CoroutineStart.UNDISPATCHED) {
                    lifecycle.suggest("active", listOf(category()))
                }
                val session = sessions.opened.firstOrNull() ?: run {
                    // The factory opens on the worker after the request has suspended.
                    awaitSession(sessions)
                }
                session.awaitEntered("active")

                var uiState = "before"
                uiState = "typed and navigated while inference was active"
                assertEquals("typed and navigated while inference was active", uiState)
                assertFalse("the suspending caller must not block on inference", active.isCompleted)

                val replaced = async(start = CoroutineStart.UNDISPATCHED) {
                    lifecycle.suggest("replaced", listOf(category()))
                }
                val newest = async(start = CoroutineStart.UNDISPATCHED) {
                    lifecycle.suggest("newest", listOf(category()))
                }
                assertEquals(LayaInferenceOutcome.Superseded, replaced.await())

                session.release("active")
                assertEquals(
                    LayaInferenceOutcome.Completed(decision("active".length.toLong())),
                    active.await(),
                )
                assertEquals(
                    LayaInferenceOutcome.Completed(decision("newest".length.toLong())),
                    newest.await(),
                )
                assertEquals(listOf("active", "newest"), session.calls())
                assertEquals(1, session.maxConcurrentCalls())
            } finally {
                sessions.opened.forEach { it.release("active") }
                lifecycle.close()
                sessions.opened.forEach { it.awaitClosed() }
            }
        }

    @Test
    fun `cancelled active and queued requests are not delivered and release permits reopen`() =
        runBlocking {
            val sessions = Sessions(setOf("active"))
            val lifecycle = LayaInferenceLifecycle(File("unused"), 60_000L, sessions.factory)
            try {
                val active = async(start = CoroutineStart.UNDISPATCHED) {
                    lifecycle.suggest("active", listOf(category()))
                }
                val firstSession = awaitSession(sessions)
                firstSession.awaitEntered("active")
                lifecycle.releaseSession()

                val cancelledPending = async(start = CoroutineStart.UNDISPATCHED) {
                    lifecycle.suggest("cancelled", listOf(category()))
                }
                cancelledPending.cancelAndJoin()
                val later = async(start = CoroutineStart.UNDISPATCHED) {
                    lifecycle.suggest("later", listOf(category()))
                }

                active.cancelAndJoin()
                assertFalse("cancellation must not close an in-use native session", firstSession.isClosed())
                firstSession.release("active")
                assertEquals(
                    LayaInferenceOutcome.Completed(decision("later".length.toLong())),
                    later.await(),
                )
                firstSession.awaitClosed()

                assertEquals(listOf("active"), firstSession.calls())
                assertEquals(2, sessions.opened.size)
                assertEquals(listOf("later"), sessions.opened.last().calls())
                assertEquals(1, firstSession.closeCount())
            } finally {
                sessions.opened.forEach { it.release("active") }
                lifecycle.close()
                sessions.opened.forEach { it.awaitClosed() }
            }
        }

    @Test
    fun `idle sessions close after their bounded retention window and can reopen`() = runBlocking {
        val sessions = Sessions(emptySet())
        val lifecycle = LayaInferenceLifecycle(File("unused"), 10L, sessions.factory)
        try {
            assertEquals(LayaInferenceOutcome.Completed(Unit), lifecycle.prepare())
            val firstSession = awaitSession(sessions)
            firstSession.awaitClosed()
            assertEquals(1, firstSession.closeCount())

            assertEquals(LayaInferenceOutcome.Completed(Unit), lifecycle.prepare())
            withTimeout(5_000L) {
                while (sessions.opened.size < 2) kotlinx.coroutines.delay(1L)
            }
            assertEquals(2, sessions.opened.size)
        } finally {
            lifecycle.close()
            sessions.opened.forEach { it.awaitClosed() }
        }
    }

    @Test
    fun `closing during native work drains pending work and closes only after execution returns`() =
        runBlocking {
            val sessions = Sessions(setOf("active"))
            val lifecycle = LayaInferenceLifecycle(File("unused"), 60_000L, sessions.factory)
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.suggest("active", listOf(category()))
            }
            val session = awaitSession(sessions)
            try {
                session.awaitEntered("active")
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    lifecycle.suggest("pending", listOf(category()))
                }

                lifecycle.close()
                assertEquals(LayaInferenceOutcome.Closed, pending.await())
                assertFalse(
                    "close must defer release while native execution is active",
                    session.isClosed(),
                )
                assertEquals(LayaInferenceOutcome.Closed, lifecycle.prepare())

                session.release("active")
                assertEquals(LayaInferenceOutcome.Closed, active.await())
                session.awaitClosed()
                assertEquals(listOf("active"), session.calls())
                assertEquals(1, session.closeCount())
            } finally {
                session.release("active")
                lifecycle.close()
                session.awaitClosed()
            }
        }

    private suspend fun awaitSession(sessions: Sessions): ControlledSession = withTimeout(5_000L) {
        while (sessions.opened.isEmpty()) kotlinx.coroutines.delay(1L)
        sessions.opened.first()
    }
}
