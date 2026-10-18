package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
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
 * Consumer-behavior regressions for S4.8 draft-review suggestion gating.
 *
 * These pin the gates a reviewer would notice going wrong: meaningful parsed English is
 * eligible, opaque/Vietnamese/identifier-only memos stay manual, an explicit English note
 * in the separate suggestion field is evaluated alone and never concatenated with the
 * bank memo (its text never touches stored bank fields), OTP-like text never becomes
 * inference input, rules resolve before inference, and explicit category choice wins
 * over a pending suggestion with no silent confirmation.
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
    fun `meaningful parsed English memo is eligible`() {
        assertEquals("Lunch at a cafe", draftSuggestionInput(draft(), "Lunch at a cafe"))
    }

    @Test
    fun `everyday unseen English expenses are eligible`() {
        // R2 false negatives: none of these share fixture wording beyond general English,
        // yet each must reach the lookup/model so it can resolve or return unknown.
        assertTrue(isMeaningfulEnglishForSuggestion("Electric bill"))
        assertTrue(isMeaningfulEnglishForSuggestion("Water bill"))
        assertTrue(isMeaningfulEnglishForSuggestion("Doctor visit"))
        assertTrue(isMeaningfulEnglishForSuggestion("Dentist checkup"))
        assertTrue(isMeaningfulEnglishForSuggestion("Flight to Hanoi"))
        assertTrue(isMeaningfulEnglishForSuggestion("Gym membership"))
        assertTrue(isMeaningfulEnglishForSuggestion("Internet subscription"))
        assertTrue(isMeaningfulEnglishForSuggestion("Car repair bill"))
        assertTrue(isMeaningfulEnglishForSuggestion("Shop ride"))
        assertEquals("Electric bill", draftSuggestionInput(draft(memo = "Electric bill"), "Electric bill"))
        assertEquals("Doctor visit", draftSuggestionInput(draft(memo = "Doctor visit"), "Doctor visit"))
    }

    @Test
    fun `monthly rent and payment to Alex are eligible English`() {
        assertEquals("Monthly rent", draftSuggestionInput(draft(memo = "Monthly rent"), "Monthly rent"))
        assertEquals(
            "Payment to Alex",
            draftSuggestionInput(draft(memo = "Payment to Alex"), "Payment to Alex"),
        )
    }

    @Test
    fun `english note with amount stays eligible and codes stay manual`() {
        assertTrue(isMeaningfulEnglishForSuggestion("Lunch at a cafe, 45000 VND"))
        assertTrue(isMeaningfulEnglishForSuggestion("Monthly rent"))
        assertFalse(isMeaningfulEnglishForSuggestion("CT DI382K26A0"))
        assertFalse(isMeaningfulEnglishForSuggestion("T5RYKH8N"))
        assertNull(draftSuggestionInput(draft(memo = "CT DI382K26A0"), "CT DI382K26A0"))
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
    fun `opaque identifier-only memo stays manual`() {
        assertNull(draftSuggestionInput(draft(memo = "REF#101"), "REF#101"))
        assertNull(draftSuggestionInput(draft(memo = "AB 12"), "AB 12"))
        assertNull(draftSuggestionInput(draft(memo = ""), ""))
        assertNull(draftSuggestionInput(draft(memo = "   "), "   "))
    }

    @Test
    fun `Vietnamese memo stays manual`() {
        assertNull(draftSuggestionInput(draft(memo = "Chuy\u1EC3n ti\u1EC1n \u0103n tr\u01B0a"), "Chuy\u1EC3n ti\u1EC1n \u0103n tr\u01B0a"))
        assertNull(draftSuggestionInput(draft(memo = "Chuyen tien an trua"), "Chuyen tien an trua"))
        assertNull(draftSuggestionInput(draft(memo = "Thanh toan tien dien"), "Thanh toan tien dien"))
        assertFalse(isMeaningfulEnglishForSuggestion("Chuyen tien an trua"))
        assertFalse(isMeaningfulEnglishForSuggestion("Thanh toan tien dien"))
    }

    @Test
    fun `Vietnamese banking memo with a loanword stays manual`() {
        // R2 false positives: one recognizable English token inside a Vietnamese memo
        // is not enough to admit the whole memo to the English-first model.
        assertFalse(isMeaningfulEnglishForSuggestion("Thanh toan VNPay payment don hang 12345"))
        assertFalse(isMeaningfulEnglishForSuggestion("QR payment tai cua hang"))
        assertFalse(isMeaningfulEnglishForSuggestion("ShopeePay payment tien com"))
        assertFalse(isMeaningfulEnglishForSuggestion("Chuyen tien an trua cafe"))
        assertFalse(isMeaningfulEnglishForSuggestion("Thanh toan tien nha rent"))
        assertFalse(isMeaningfulEnglishForSuggestion("CK coffee Highlands"))
        assertNull(
            draftSuggestionInput(
                draft(memo = "Thanh toan VNPay payment don hang 12345"),
                "Thanh toan VNPay payment don hang 12345",
            ),
        )
        assertNull(
            draftSuggestionInput(
                draft(memo = "Chuyen tien an trua cafe"),
                "Chuyen tien an trua cafe",
            ),
        )
    }

    @Test
    fun `unsupported-language ASCII memo stays manual`() {
        assertNull(draftSuggestionInput(draft(memo = "Nap tien dien thoai"), "Nap tien dien thoai"))
        assertNull(draftSuggestionInput(draft(memo = "CK NHANH 123 THANH TOAN"), "CK NHANH 123 THANH TOAN"))
    }

    @Test
    fun `bank memo with reference codes stays manual even with English words`() {
        // Addressed-to/transaction-code wrappers around English phrasing must not reach
        // inference: the codes would otherwise travel to the model as description.
        assertNull(
            draftSuggestionInput(
                draft(memo = "CT DI382K26A0 Lunch at a cafe T5RYKH8N"),
                "CT DI382K26A0 Lunch at a cafe T5RYKH8N",
            ),
        )
        assertFalse(isMeaningfulEnglishForSuggestion("CT DI:382K26A0 Lunch at a cafe T5RYKH8N"))
    }

    @Test
    fun `explicit English note is evaluated alone and never concatenated`() {
        val stored = draft(
            memoState = IntakeFieldState.UNRESOLVED,
            memo = null,
            reason = "REDACTED",
        )
        assertEquals("Monthly rent", draftSuggestionInput(stored, "", "Monthly rent"))
        assertNull(draftSuggestionInput(stored, ""))
        assertNull(stored.memo)
    }

    @Test
    fun `explicit English note on unsupported format is eligible`() {
        val stored = draft(
            memoState = IntakeFieldState.MISSING,
            memo = null,
            parseStatus = IntakeParseStatus.UNSUPPORTED_FORMAT,
        )
        assertEquals("Monthly rent", draftSuggestionInput(stored, "", "Monthly rent"))
        assertNull(draftSuggestionInput(stored, "", "REF#101"))
    }

    @Test
    fun `note on stored bank memo never inherits memo text`() {
        // The R2 failure mode: note typed into a stored-memo draft must stand alone.
        val stored = draft(memo = "CT DI:382K26A0 QR - Thanh toan order 26101719; tai iPay")
        assertEquals("Monthly rent", draftSuggestionInput(stored, stored.memo.orEmpty(), "Monthly rent"))
        assertNull(draftSuggestionInput(stored, stored.memo.orEmpty(), ""))
        // Junk in the note field suppresses rather than reuses the bank memo.
        assertNull(draftSuggestionInput(stored, stored.memo.orEmpty(), "xyz 123"))
        assertFalse(isMeaningfulEnglishForSuggestion("Lunch at a c\u00E0e"))
        assertNull(draftSuggestionInput(stored, stored.memo.orEmpty(), "Lunch at a c\u00E0e"))
        assertEquals(stored.memo, "CT DI:382K26A0 QR - Thanh toan order 26101719; tai iPay")
    }

    @Test
    fun `autocorrect diacritic note stays manual`() {
        val stored = draft(memo = "CT DI:382K26A0 QR - Thanh toan order 26101719; tai iPay")
        assertNull(draftSuggestionInput(stored, stored.memo.orEmpty(), "Lunch at a c\u00E0e, 45000 VND"))
        assertEquals(stored.memo, "CT DI:382K26A0 QR - Thanh toan order 26101719; tai iPay")
    }

    @Test
    fun `narrow example-specific wording stays manual`() {
        // Words tied only to labeled examples (standup, colleagues) carry no general
        // evidence, so a note using only them stays reviewable, never forced.
        assertFalse(isMeaningfulEnglishForSuggestion("Standup celebration downtown"))
        assertNull(draftSuggestionInput(draft(memo = "Standup celebration downtown"), "Standup celebration downtown"))
    }

    @Test
    fun `otp-like text never becomes inference input`() {
        assertNull(draftSuggestionInput(draft(memo = "Your OTP is 123456"), "Your OTP is 123456"))
        assertFalse(isMeaningfulEnglishForSuggestion("m\u00E3 otp 123456"))
    }

    @Test
    fun `remembered rule resolves before inference for draft memo`() = runTest {
        val food = category(1L, "Food")
        var inferenceCalls = 0
        val matched = decideRememberedBeforeInference(
            description = "Lunch at a cafe",
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
    fun `opaque memo keeps a visible panel state with no inference input`() {
        // S4.10-A2 user finding: an ordinary opaque bank memo must not silently hide
        // the whole feature. The gate still yields null (no inference input), while
        // the review keeps the header visible with a reason and the note path.
        val opaque = draft(memo = "CT DI notes")
        assertNull(draftSuggestionInput(opaque, "CT DI notes", ""))
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

    @Test
    fun `eligible note still reaches inference for an opaque memo`() {
        // The visible reason above must not replace inference: a qualifying note on an
        // opaque memo remains the effective input.
        val opaque = draft(memo = "CT DI notes")
        assertEquals(
            "Monthly rent",
            draftSuggestionInput(opaque, "CT DI notes", "Monthly rent"),
        )
    }
}
