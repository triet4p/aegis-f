package io.github.triet4p.aegisf.notification

import android.app.Notification
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.IntakeRepository
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import io.github.triet4p.aegisf.data.laya.LayaCategoryCorrectionRule
import io.github.triet4p.aegisf.data.laya.LayaContextDecision
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaEffectiveInput
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import io.github.triet4p.aegisf.data.laya.LayaInferenceOutcome
import io.github.triet4p.aegisf.data.laya.LayaInferenceRuntimeFactory
import io.github.triet4p.aegisf.data.laya.LayaInferenceRuntimeOpen
import io.github.triet4p.aegisf.data.laya.LayaInferenceSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InferenceIntakeIndependenceTest {

    private class SignalingIntakeRepository(database: LedgerDatabase) :
        IntakeRepository(database) {
        val storedDraft = CompletableDeferred<Long>()

        override suspend fun storePermitted(
            sourcePackage: String,
            notificationKey: String?,
            postedAtEpochMillis: Long,
            content: PermittedNotificationContent,
        ): Long {
            val id = super.storePermitted(
                sourcePackage,
                notificationKey,
                postedAtEpochMillis,
                content,
            )
            storedDraft.complete(id)
            return id
        }
    }

    private class BlockingSession(
        private val inferenceStarted: CountDownLatch,
        private val allowInferenceToFinish: CountDownLatch,
    ) : LayaInferenceSession {
        private val activeCalls = AtomicInteger()
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        val closeFinished = CountDownLatch(1)

        override fun suggest(
            context: LayaEffectiveContext,
            categories: List<CategoryEntity>,
            correctionRules: List<LayaCategoryCorrectionRule>,
        ): LayaContextDecision {
            check(!closed.get()) { "session used after close" }
            activeCalls.incrementAndGet()
            try {
                inferenceStarted.countDown()
                check(allowInferenceToFinish.await(5, TimeUnit.SECONDS))
                return LayaContextDecision(
                    decision = LayaCandidatePlanner.Decision.Unknown(
                        LayaCandidatePlanner.ManualReason.NO_ACTIVE_CATEGORIES,
                    ),
                    effectiveInput = LayaEffectiveInput(
                        text = context.primaryText,
                        digest = "digest",
                        includedReadings = emptyList(),
                        droppedReadings = emptyList(),
                        tokenCount = 0,
                        roomTokens = 0,
                    ),
                    evaluation = null,
                )
            } finally {
                activeCalls.decrementAndGet()
            }
        }


        override fun close() {
            check(activeCalls.get() == 0) { "closed while native decision work was active" }
            closed.set(true)
            closeFinished.countDown()
        }
    }

    @Test
    fun `slow inference leaves manual work and notification persistence independent`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            LedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
        val preferences = context.getSharedPreferences(
            NotificationAccessStore.FILE_NAME,
            Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        val accessStore = NotificationAccessStore(context)
        accessStore.addPackage(TEST_PACKAGE)
        val repository = SignalingIntakeRepository(database)
        val inferenceStarted = CountDownLatch(1)
        val allowInferenceToFinish = CountDownLatch(1)
        val session = BlockingSession(inferenceStarted, allowInferenceToFinish)
        val lifecycle = LayaInferenceLifecycle(
            modelDirectory = File(context.cacheDir, "unused-laya-model"),
            idleTimeoutMillis = 60_000L,
            runtimeFactory = LayaInferenceRuntimeFactory {
                LayaInferenceRuntimeOpen.Opened(session)
            },
        )
        val listener = Robolectric.buildService(BankNotificationListener::class.java)
            .create()
            .get()
        listener.ioDispatcher = Dispatchers.IO
        listener.activeNotificationsForTest = { emptyArray() }
        BankNotificationListener.updateIntakeRepository(repository)
        BankNotificationListener.updateAllowedPackages(emptySet())
        listener.onListenerDisconnected()

        try {
            listener.onListenerConnected()
            withTimeout(5_000L) {
                BankNotificationListener.connection.first { it is ListenerConnection.Connected }
            }

            val inference = async(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.suggest(
                    LayaEffectiveContext.of("non-financial lifecycle fixture"),
                    emptyList(),
                )
            }
            assertTrue(inferenceStarted.await(5, TimeUnit.SECONDS))

            var editorText = ""
            editorText = "manual form remains editable"
            var destination = "entry"
            destination = "categories"
            destination = "entry"
            assertEquals("manual form remains editable", editorText)
            assertEquals("entry", destination)
            assertFalse("the request suspends rather than blocking its caller", inference.isCompleted)

            listener.onNotificationPosted(
                ContentReadProbe(TEST_PACKAGE, 17, notification(text = "background intake smoke")),
            )
            val draftId = withTimeout(5_000L) { repository.storedDraft.await() }
            assertTrue(draftId > 0L)
            assertEquals(1, database.pendingBankDraftDao().findAll().size)
            assertEquals(
                "the inference remains blocked until the test releases it",
                1L,
                allowInferenceToFinish.count,
            )

            allowInferenceToFinish.countDown()
            val completed = inference.await() as LayaInferenceOutcome.Completed
            assertEquals(
                LayaCandidatePlanner.Decision.Unknown(
                    LayaCandidatePlanner.ManualReason.NO_ACTIVE_CATEGORIES,
                ),
                completed.value.decision,
            )
        } finally {
            allowInferenceToFinish.countDown()
            lifecycle.close()
            listener.onListenerDisconnected()
            listener.onDestroy()
            BankNotificationListener.updateIntakeRepository(null)
            BankNotificationListener.updateAllowedPackages(emptySet())
            withTimeout(5_000L) {
                while (session.closeFinished.count != 0L) kotlinx.coroutines.delay(1L)
            }
            database.close()
            preferences.edit().clear().commit()
        }
    }

    private fun notification(text: String) = Notification().apply {
        extras.putCharSequence(Notification.EXTRA_TEXT, text)
    }

    private companion object {
        const val TEST_PACKAGE = "com.example.bank"
    }
}
