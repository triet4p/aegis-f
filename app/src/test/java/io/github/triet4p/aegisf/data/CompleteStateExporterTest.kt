package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedProviderCapabilities
import io.github.triet4p.aegisf.provider.HostedProviderExportProfile
import io.github.triet4p.aegisf.provider.HostedProviderExportSnapshot
import io.github.triet4p.aegisf.provider.HostedReasoningEffort
import io.github.triet4p.aegisf.provider.sanitizedProviderEndpointOrigin
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompleteStateExporterTest {
    private lateinit var database: LedgerDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `export preserves populated relational state and excludes private runtime history`() = runBlocking {
        val now = 1_800_000_000_000L
        val categoryId = database.categoryDao().insert(
            CategoryEntity(name = "Meals", createdAtEpochMillis = now, updatedAtEpochMillis = now),
        )
        val historicalCategoryId = database.categoryDao().insert(
            CategoryEntity(name = "Travel", createdAtEpochMillis = now, updatedAtEpochMillis = now),
        )
        assertTrue(
            database.rememberedCategoryRuleDao().rememberIfActive(
                RememberedCategoryRuleEntity(patternKey = "coffee shop", categoryId = categoryId),
            ),
        )
        val transactionId = database.transactionDao().insert(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountVnd = 9_876_543_210_123L,
                occurredAtEpochMillis = now - 1_000,
                description = "Coffee",
                categoryId = categoryId,
                confirmation = ConfirmationStatus.CONFIRMED,
                source = RecordSource.MANUAL,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                revision = 3,
            ),
        )
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "delivery-identity-1",
                sourcePackage = "bank.example",
                notificationKey = "notification-identity-1",
                postedAtEpochMillis = now - 10_000,
                bank = "Example Bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = 87_500L,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-11-12",
                statedTime = "10:30",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = "Coffee",
                memoUnresolvedReason = null,
                reviewStatus = IntakeReviewStatus.PENDING,
                confirmedTransactionId = null,
                possibleDuplicateOfDraftId = null,
                createdAtEpochMillis = now,
                reviewAmountText = "90000",
                reviewDateText = "2026-11-13",
                reviewTimeText = "11:45",
                reviewMemo = "Corrected coffee",
                reviewMemoEdited = true,
                reviewType = TransactionType.EXPENSE,
                reviewCategoryId = categoryId,
                reviewCategoryResolved = true,
                reviewCategoryOrigin = ReviewCategoryOrigin.USER,
                reviewRevision = 5,
            ),
        )
        val captureId = database.captureMessageDao().insert(
            CaptureMessageEntity(
                captureKind = CaptureKind.USER_NOTE,
                sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                sourceIdentity = "saved-note-identity-1",
                bodyText = "Saved private note text",
                capturedAtEpochMillis = now,
                captureTimezone = "Asia/Ho_Chi_Minh",
                revision = 2,
                originRef = null,
            ),
        )
        val eventId = database.noteEventDao().insert(
            NoteEventEntity(
                captureId = captureId,
                eventIdentity = "saved-note-event-1",
                spanStart = 0,
                spanEnd = 5,
                normalizedText = "Coffee",
                amountVnd = 87_500L,
                merchant = "Coffee shop",
                anchorDate = "2026-11-12",
                anchorTime = "10:30",
                anchorTimezone = "Asia/Ho_Chi_Minh",
                intervalEndDate = null,
                intervalEndTime = null,
                extractionVersion = "fixture-v1",
                unresolvedReason = null,
                status = NoteEventStatus.INTERPRETED,
                revision = 2,
                sourceRevision = 2,
                createdAtEpochMillis = now,
                resolvedTransactionId = transactionId,
                userEdited = true,
                resolvedAtEpochMillis = now,
            ),
        )
        database.contextLinkDao().insert(
            ContextLinkEntity(
                eventId = eventId,
                draftId = draftId,
                state = ContextLinkState.ACCEPTED,
                evidence = "same stated day and amount",
                revision = 4,
                userOverride = "user accepted",
                activeEventId = eventId,
                createdAtEpochMillis = now,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO `classification_snapshots` (" +
                "`draftId`, `contextRevision`, `categoryRevision`, `ruleRevision`, `modelRevision`, " +
                "`candidateSetVersion`, `decisionSource`, `suggestedCategoryId`, `candidateIds`, " +
                "`probabilities`, `unknownShare`, `confidence`, `effectiveInputDigest`, " +
                "`classificationInputKind`, `classificationInputIdentity`, `translationSourceRevision`, " +
                "`eventIds`, `predictionTime`, `staleReason`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                draftId,
                "context-r1",
                "category-r1",
                "rules-r1",
                "old-classifier-v0",
                "candidate-v1",
                "MODEL",
                historicalCategoryId,
                "[$historicalCategoryId]",
                "[0.9]",
                0.1,
                0.9,
                "input-digest",
                "STORED_SOURCE",
                "input-identity",
                null,
                "[$eventId]",
                now - 2_000,
                "historical-only",
            ),
        )

        val output = ByteArrayOutputStream()
        CompleteStateExporter(database) { profileSnapshot() }.writeTo(output, exportedAtEpochMillis = now)
        val exported = JSONObject(output.toString(Charsets.UTF_8.name()))
        val tables = exported.getJSONObject("tables")

        assertEquals("aegis-f-complete-state", exported.getString("format"))
        assertEquals(1, exported.getInt("format_version"))
        assertEquals("VND", exported.getString("money_unit"))
        assertEquals("none", exported.getString("encryption"))
        assertEquals(2, tables.getJSONArray("categories").length())
        assertEquals(1, tables.getJSONArray("remembered_category_rules").length())
        val ledger = tables.getJSONArray("transactions").getJSONObject(0)
        assertEquals(9_876_543_210_123L, ledger.getLong("amountVnd"))
        assertEquals(categoryId, ledger.getLong("categoryId"))
        val draft = tables.getJSONArray("pending_bank_drafts").getJSONObject(0)
        assertEquals("90000", draft.getString("reviewAmountText"))
        assertTrue(draft.has("confirmedTransactionId"))
        assertTrue(draft.isNull("confirmedTransactionId"))
        assertEquals("Corrected coffee", draft.getString("reviewMemo"))
        assertEquals(categoryId, draft.getLong("reviewCategoryId"))
        assertEquals("USER", draft.getString("reviewCategoryOrigin"))
        assertEquals(5, draft.getInt("reviewRevision"))
        assertEquals("Saved private note text", tables.getJSONArray("capture_messages")
            .getJSONObject(0).getString("bodyText"))
        assertEquals(1, tables.getJSONArray("note_events").length())
        assertEquals(1, tables.getJSONArray("context_links").length())
        val historicalSnapshot = tables.getJSONArray("classification_snapshots").getJSONObject(0)
        assertEquals("historical-only", historicalSnapshot.getString("staleReason"))
        assertEquals(historicalCategoryId, historicalSnapshot.getLong("suggestedCategoryId"))
        assertTrue(historicalSnapshot.has("translationSourceRevision"))
        assertTrue(historicalSnapshot.isNull("translationSourceRevision"))
        assertFalse(historicalSnapshot.getLong("suggestedCategoryId") == draft.getLong("reviewCategoryId"))
        assertTrue(exported.getString("historical_classification_policy").contains("historical provenance only"))
        assertEquals("active-profile", exported.getJSONObject("hosted_profiles").getString("active_profile_id"))
        val profile = exported.getJSONObject("hosted_profiles").getJSONArray("profiles").getJSONObject(0)
        assertEquals("https://provider.example", profile.getString("endpoint_origin"))
        assertFalse(profile.has("credential"))
        assertFalse(tables.has("agent_trace_events"))
        assertFalse(tables.has("agent_conversation"))
        assertFalse(exported.toString().contains("credential-value"))
    }

    @Test
    fun `hosted endpoint descriptor drops path and uri credentials`() {
        assertEquals(
            "https://provider.example:8443",
            sanitizedProviderEndpointOrigin(
                "https://user:credential-value@provider.example:8443/private/path?key=query-secret#fragment",
            ),
        )
        assertEquals(
            "https://provider.example",
            sanitizedProviderEndpointOrigin("https://provider.example/v1/{model}?key=query-secret"),
        )
    }

    private fun profileSnapshot() = HostedProviderExportSnapshot(
        activeProfileId = "active-profile",
        profiles = listOf(
            HostedProviderExportProfile(
                id = "active-profile",
                name = "Private provider",
                protocol = HostedProtocol.OPENAI_RESPONSES,
                endpointOrigin = "https://provider.example",
                model = "model-stub",
                maxOutputTokens = 32_768,
                reasoningEffort = HostedReasoningEffort.LOW,
                temperature = 0.2,
                parallelToolCalls = false,
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = true,
                    supportsReasoningBudget = true,
                    supportsTemperature = true,
                    maxOutputTokensLimit = 65_536,
                ),
            ),
        ),
    )
}
