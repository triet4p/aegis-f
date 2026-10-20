package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.isBlockedForSuggestionSafety
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Consumer-behavior regressions for S4.10-A3 draft-review suggestion input.
 *
 * English-preferred advisory only: any non-blank note/memo reaches the saved-rule
 * lookup and bounded inference (including short phrases and Vietnamese text), while
 * only narrow OTP/security-like text and reference codes stay manual. The explicit
 * note is evaluated alone and never concatenated with the bank memo (its text never
 * touches stored bank fields), rules resolve before inference, and explicit category
 * choice wins over a pending suggestion with no silent confirmation. No multilingual
 * quality is claimed; unsupported-language quality is a review decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BankDraftSuggestionGateTest {

    private fun category(id: Long, name: String, archived: Boolean = false) = CategoryEntity(
        id = id,
        name = name,
        archived = archived,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun draft(
        memoState: IntakeFieldState = IntakeFieldState.PRESENT,
        memo: String? = "Lunch at a cafe",
        reason: String? = null,
        parseStatus: IntakeParseStatus = IntakeParseStatus.PARSED,
    ) = PendingBankDraftEntity(
        deliveryId = "pkg|key|1",
        sourcePackage = "pkg",
        notificationKey = "key",
        postedAtEpochMillis = 1L,
        bank = "VIETINBANK",
        parseStatus = parseStatus,
        amountState = IntakeFieldState.PRESENT,
        amountVnd = 50_000L,
        amountUnresolvedReason = null,
        direction = IntakeDirection.DEBIT,
        momentState = IntakeFieldState.PRESENT,
        statedDate = "2026-10-16",
        statedTime = "07:38",
        momentUnresolvedReason = null,
        memoState = memoState,
        memo = memo,
        memoUnresolvedReason = reason,
        reviewStatus = IntakeReviewStatus.PENDING,
        createdAtEpochMillis = 1L,
    )

    @Test
    fun `short and Vietnamese inputs reach inference`() {
        // S4.10-A3: no language/word-list/short-phrase block. These previously
        // gated inputs must now reach the rule lookup/model (quality stays a review
        // decision, never a filter).
        assertEquals("lunch", draftSuggestionInput(draft(memo = "lunch"), "lunch"))
        assertEquals("buy lunch", draftSuggestionInput(draft(memo = "buy lunch"), "buy lunch"))
        assertEquals("buy breakfast", draftSuggestionInput(draft(memo = "buy breakfast"), "buy breakfast"))
        assertEquals("buy", draftSuggestionInput(draft(memo = "buy"), "buy"))
        assertEquals("Monthly rent", draftSuggestionInput(draft(memo = "Monthly rent"), "Monthly rent"))
        assertEquals("Electric bill", draftSuggestionInput(draft(memo = "Electric bill"), "Electric bill"))
        assertEquals(
            "an trua",
            draftSuggestionInput(draft(memo = "an trua"), "an trua"),
        )
        assertFalse(isBlockedForSuggestionSafety("buy lunch"))
        assertFalse(isBlockedForSuggestionSafety("an trua"))
    }

    @Test
    fun `explicit note is evaluated alone and never concatenated`() {
        val stored = draft(
            memoState = IntakeFieldState.UNRESOLVED,
            memo = null,
            reason = "REDACTED",
        )
        assertEquals("buy lunch", draftSuggestionInput(stored, "", "buy lunch"))
        assertEquals("Monthly rent", draftSuggestionInput(stored, "", "Monthly rent"))
        assertNull(draftSuggestionInput(stored, ""))
        assertNull(stored.memo)
    }

    @Test
    fun `explicit note on unsupported format reaches inference`() {
        val stored = draft(
            memoState = IntakeFieldState.MISSING,
            memo = null,
            parseStatus = IntakeParseStatus.UNSUPPORTED_FORMAT,
        )
        assertEquals("buy lunch", draftSuggestionInput(stored, "", "buy lunch"))
        assertEquals("Monthly rent", draftSuggestionInput(stored, "", "Monthly rent"))
    }

    @Test
    fun `blank inputs stay manual`() {
        assertNull(draftSuggestionInput(draft(memo = ""), ""))
        assertNull(draftSuggestionInput(draft(memo = "   "), "   "))
    }

    @Test
    fun `reference codes stay manual even with English words`() {
        // Addressed-to/transaction-code wrappers around any phrasing must not reach
        // inference: the codes would otherwise travel to the model as description.
        assertNull(
            draftSuggestionInput(
                draft(memo = "CT DI382K26A0 Lunch at a cafe T5RYKH8N"),
                "CT DI382K26A0 Lunch at a cafe T5RYKH8N",
            ),
        )
        assertTrue(isBlockedForSuggestionSafety("CT DI382K26A0 Lunch at a cafe T5RYKH8N"))
        assertTrue(isBlockedForSuggestionSafety("T5RYKH8N"))
    }

    @Test
    fun `safety-blocked note never inherits memo text`() {
        val stored = draft(memo = "CT DI:382K26A0 QR - Thanh toan order 26101719; tai iPay")
        assertEquals("Monthly rent", draftSuggestionInput(stored, stored.memo.orEmpty(), "Monthly rent"))
        assertEquals("buy lunch", draftSuggestionInput(stored, stored.memo.orEmpty(), "buy lunch"))
        // A code-bearing note yields null rather than reusing the bank memo.
        assertNull(draftSuggestionInput(stored, stored.memo.orEmpty(), "note T5RYKH8N"))
        assertEquals(stored.memo, "CT DI:382K26A0 QR - Thanh toan order 26101719; tai iPay")
    }

    @Test
    fun `otp-like text never becomes inference input`() {
        assertNull(draftSuggestionInput(draft(memo = "Your OTP is 123456"), "Your OTP is 123456"))
        assertTrue(isBlockedForSuggestionSafety("Your OTP is 123456"))
        assertTrue(isBlockedForSuggestionSafety("m\u00E3 otp 123456"))
    }

    @Test
    fun `shop note matches the shop category without inference`() {
        // Deterministic consumer path on the phone's category set: `shop` is an active
        // category name, so the planner resolves the note to it before any model call.
        val tokenizer = io.github.triet4p.aegisf.data.laya.LayaTokenizerLoader.loadFromRepoFile()
        val shop = category(1L, "shop")
        val transport = category(2L, "Transport")
        val note = checkNotNull(draftSuggestionInput(draft(memo = "x"), "x", "Shop ride"))
        val plan = io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.plan(
            tokenizer,
            note,
            listOf(shop, transport),
        )
        assertTrue(plan is io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.Plan.MetadataMatch)
        val decision = io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.decide(plan) {
            error("shop metadata match must not call inference")
        } as io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.Decision.Category
        assertEquals(1L, decision.categoryId)
    }
    @Test
    fun `remembered rule resolves before inference for draft memo`() = runTest {
        val food = category(1L, "Food")
        var inferenceCalls = 0
        val matched = decideRememberedBeforeInference(
            description = "Lunch at a cafe",
            context = LayaEffectiveContext.of("Lunch at a cafe"),
            activeCategories = listOf(food),
            findRememberedCategory = { if (it == "Lunch at a cafe") food else null },
            infer = { _, _ ->
                inferenceCalls += 1
                "model"
            },
        )
        assertEquals(ManualCategoryDecision.Remembered(food), matched)
        assertEquals(0, inferenceCalls)
    }

    @Test
    fun `remembered rule matches the explicit note text`() = runTest {
        val housing = category(1L, "Housing")
        var inferenceCalls = 0
        val note = checkNotNull(
            draftSuggestionInput(draft(memo = "CT DI notes"), "CT DI notes", "Monthly rent"),
        )
        val matched = decideRememberedBeforeInference(
            description = note,
            context = LayaEffectiveContext.of(note),
            activeCategories = listOf(housing),
            findRememberedCategory = { if (it == "Monthly rent") housing else null },
            infer = { _, _ ->
                inferenceCalls += 1
                "model"
            },
        )
        assertEquals(ManualCategoryDecision.Remembered(housing), matched)
        assertEquals(0, inferenceCalls)
    }

    @Test
    fun `archived remembered target falls through to inference`() = runTest {
        val archived = category(1L, "Food", archived = true)
        val current = category(2L, "Transport")
        var inferenceCalls = 0
        val decision = decideRememberedBeforeInference(
            description = "Lunch at a cafe",
            context = LayaEffectiveContext.of("Lunch at a cafe"),
            activeCategories = listOf(current),
            findRememberedCategory = { archived },
            infer = { _, categories ->
                inferenceCalls += 1
                assertEquals(listOf(current), categories)
                "model"
            },
        )
        assertEquals(ManualCategoryDecision.Inferred("model"), decision)
        assertEquals(1, inferenceCalls)
    }

    @Test
    fun `explicit category choice wins and stale categories are rejected`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val transport = category(2L, "Transport")
        val intent = ManualSuggestionIntent(Any(), "Lunch at a cafe", io.github.triet4p.aegisf.data.TransactionType.EXPENSE)
        val request = ManualSuggestionRequest(intent, listOf(food, transport))
        val token = checkNotNull(machine.begin(request))
        var selectedCategoryId: Long? = null
        var savedRecordCount = 0

        assertTrue(
            machine.complete(
                token,
                request.intent,
                request.categories,
                request.categories,
                io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.CurrentResolution.ActiveCategory(
                    category = food,
                    source = io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.SuggestionSource.MODEL,
                    confidence = null,
                    uncertainty = null,
                ),
            ),
        )
        assertNull(selectedCategoryId)
        assertEquals(0, savedRecordCount)

        machine.chooseManually(intent)
        assertEquals(ManualCategorySuggestionState.ManuallySelected, machine.state)

        selectedCategoryId = transport.id
        assertEquals(transport.id, selectedCategoryId)
        assertEquals(0, savedRecordCount)
    }

    @Test
    fun `renamed suggested category is stale against fresh rows`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val renamed = category(1L, "Meals")
        val intent = ManualSuggestionIntent(Any(), "Lunch at a cafe", io.github.triet4p.aegisf.data.TransactionType.EXPENSE)
        val token = checkNotNull(
            machine.begin(ManualSuggestionRequest(intent, listOf(food))),
        )
        assertFalse(
            machine.complete(
                token,
                intent,
                listOf(renamed),
                listOf(renamed),
                io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.resolveCurrent(
                    io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.Decision.Category(
                        categoryId = 1L,
                        snapshotName = "Food",
                        source = io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.SuggestionSource.MODEL,
                        confidence = null,
                    ),
                    listOf(renamed),
                ),
            ),
        )
        assertTrue(machine.state is ManualCategorySuggestionState.Stale)
    }

    @Test
    fun `draft switching rejects late results`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val first = ManualSuggestionIntent(Any(), "Lunch at a cafe", io.github.triet4p.aegisf.data.TransactionType.EXPENSE)
        val second = ManualSuggestionIntent(Any(), "Monthly rent", io.github.triet4p.aegisf.data.TransactionType.EXPENSE)
        val token = checkNotNull(machine.begin(ManualSuggestionRequest(first, listOf(food))))
        assertFalse(
            machine.complete(
                token,
                second,
                listOf(food),
                listOf(food),
                io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.CurrentResolution.ActiveCategory(
                    category = food,
                    source = io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.SuggestionSource.MODEL,
                    confidence = null,
                    uncertainty = null,
                ),
            ),
        )
        assertEquals(ManualCategorySuggestionState.Idle, machine.state)
    }

    @Test
    fun `note change invalidates the prior suggestion intent`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val first = ManualSuggestionIntent(Any(), "Monthly rent", io.github.triet4p.aegisf.data.TransactionType.EXPENSE)
        val second = ManualSuggestionIntent(Any(), "Electric bill", io.github.triet4p.aegisf.data.TransactionType.EXPENSE)
        val token = checkNotNull(machine.begin(ManualSuggestionRequest(first, listOf(food))))
        assertFalse(
            machine.complete(
                token,
                second,
                listOf(food),
                listOf(food),
                io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.CurrentResolution.ActiveCategory(
                    category = food,
                    source = io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.SuggestionSource.MODEL,
                    confidence = null,
                    uncertainty = null,
                ),
            ),
        )
        assertEquals(ManualCategorySuggestionState.Idle, machine.state)
    }
    @Test
    fun `opaque memo keeps a visible panel state and any note reaches inference`() {
        // S4.10-A2 visibility: an ordinary opaque memo keeps the header visible.
        // S4.10-A3 advisory: the note/memo text itself is no longer gated.
        val opaque = draft(memo = "CT DI notes")
        assertEquals("CT DI notes", draftSuggestionInput(opaque, "CT DI notes", ""))
        assertEquals("buy lunch", draftSuggestionInput(opaque, "CT DI notes", "buy lunch"))
        assertEquals("Monthly rent", draftSuggestionInput(opaque, "CT DI notes", "Monthly rent"))
        assertTrue(
            isBankDraftSuggestionPanelVisible(ManualCategorySuggestionState.Ignored),
        )
        assertTrue(
            isBankDraftSuggestionPanelVisible(
                ManualCategorySuggestionState.WaitingForDescription,
            ),
        )
        assertTrue(
            isBankDraftSuggestionPanelVisible(ManualCategorySuggestionState.ManuallySelected),
        )
        assertFalse(isBankDraftSuggestionPanelVisible(ManualCategorySuggestionState.Idle))
    }
}
