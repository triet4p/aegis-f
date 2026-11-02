package io.github.triet4p.aegisf.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The neutral masked-value contract: one opaque identity per value, a lexical shape hint only,
 * and role-specific local parsing.
 *
 * These are the shapes the owner's real note used ("giá 10000", "Ngayd 02/11", "10h-11h") plus
 * the matrix the correction must not regress: unit/grouping spellings, a numeric account next to
 * a numeric price, counts that are not money, invalid day/month and out-of-range times, and
 * year-local resolution. Nothing here asserts a semantic token kind: the same tests would pass if
 * the value were an account or a price, because the role is chosen at the tool argument.
 */
class MaskedValueShapesTest {

    private val ownerNote =
        "Ngày 02/11 lúc khoảng 10h-11h tôi đã mua bánh mì ở Bánh Mì Phố với giá 10000"

    /** The owner's actual stored note text, including its "Ngayd" spelling. */
    private val ownerNoteAsStored =
        "Ngayd 02/11 lúc khoảng 10h-11h tôi đã mua bánh mì ở Bánh Mì Phố với giá 10000 "

    private fun tokensOf(vault: SensitiveVault, masked: String): List<String> =
        SensitiveVault.TOKEN_PATTERN.findAll(masked).map { it.value }.toList()

    @Test
    fun `owner note mints neutral values with lexical shape hints only`() {
        val vault = SensitiveVault("pt2-owner-note")
        val masked = vault.maskText(ownerNote)

        // No raw literal survives into the masked text.
        for (literal in listOf("10000", "02/11", "10h", "11h")) {
            assertFalse("raw '$literal' survives: $masked", masked.contains(literal))
        }

        val issued = vault.issuedTokens()
        assertEquals("one amount, one date and two interval times", 4, issued.size)
        assertTrue(issued.all { it.token.startsWith("⟦V-") })
        // Date and time are written with digits; that is all the hint says.
        assertTrue(issued.all { it.shape == IssuedValueShape.NUMERIC })

        val tokens = issued.map { it.token }
        val amount = tokens.single { vault.parseAmountOrNull(it) == 10_000L }
        val date = tokens.single { vault.parseDateOrNull(it, 2026) == "2026-11-02" }
        val times = tokens.filter { vault.parseTimeOrNull(it) != null }.map { vault.parseTimeOrNull(it) }
        assertEquals(setOf("10:00", "11:00"), times.toSet())
        assertEquals("2026-11-02", vault.parseDate(date, 2026))
        assertEquals("2027-11-02", vault.parseDate(date, 2027))
        try {
            vault.parseDate(date)
            fail("a value with no stated year must not resolve without a reference year")
        } catch (expected: TokenValidationException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
        // A non-integer value refuses the amount role; a date/time refuses the time role.
        try {
            vault.parseAmount(date)
            fail("a date value must not parse as a whole-dong amount")
        } catch (_: TokenValidationException) {
            // expected: choose the value the argument needs
        }
        try {
            vault.parseTime(amount)
            fail("an amount value must not parse as a wall-clock time")
        } catch (_: TokenValidationException) {
            // expected
        }
    }

    @Test
    fun `the stored owner note spelling still masks amount date and interval`() {
        val vault = SensitiveVault("pt2-owner-stored")
        val masked = vault.maskText(ownerNoteAsStored)
        for (literal in listOf("10000", "02/11", "10h", "11h")) {
            assertFalse("raw '$literal' survives: $masked", masked.contains(literal))
        }
        val tokens = tokensOf(vault, masked)
        assertEquals(4, tokens.size)
        assertEquals(10_000L, tokens.mapNotNull(vault::parseAmountOrNull).single())
        assertEquals("2026-11-02", tokens.mapNotNull { vault.parseDateOrNull(it, 2026) }.single())
        assertEquals(
            setOf("10:00", "11:00"),
            tokens.mapNotNull(vault::parseTimeOrNull).toSet(),
        )
        assertTrue(vault.issuedTokens().all { it.shape == IssuedValueShape.NUMERIC })
    }

    @Test
    fun `amount grouping spellings share one neutral token and parse exactly`() {
        val vault = SensitiveVault("pt2-grouping")
        val spellings = listOf("giá 10.000", "giá 10,000", "10.000 VND", "10000 VND", "trả 10000")
        val tokens = spellings.map { masked ->
            SensitiveVault.TOKEN_PATTERN.find(vault.maskText(masked))?.value
                ?: error("no value token in $masked")
        }
        assertEquals("every spelling is one value", 1, tokens.distinct().size)
        assertEquals(10_000L, vault.parseAmount(tokens.first()))

        // Ordering between two distinct amounts stays decidable without literals.
        val smaller = SensitiveVault.TOKEN_PATTERN.find(vault.maskText("giá 2.500"))!!.value
        assertTrue(vault.compareAmounts(smaller, tokens.first()) < 0)
    }

    @Test
    fun `a numeric price and a numeric account are separate values with no type gate`() {
        val vault = SensitiveVault("pt2-price-account")
        val masked = vault.maskText("giá 10000, tài khoản 4821")
        val tokens = tokensOf(vault, masked)
        assertEquals("both numeric values are masked", 2, tokens.size)
        assertEquals(setOf(IssuedValueShape.NUMERIC), vault.issuedTokens().map { it.shape }.toSet())
        // The value after the price word is the price; the model chooses by context, and the
        // host parses whichever value it receives for the amount argument.
        val priceToken = SensitiveVault.TOKEN_PATTERN.find(
            masked.substringAfter("giá "),
        )!!.value
        assertEquals(10_000L, vault.parseAmount(priceToken))
        val accountToken = tokens.single { it != priceToken }
        assertEquals(4_821L, vault.parseAmount(accountToken))
    }

    @Test
    fun `counts and bare ids are not masked as sensitive values`() {
        val vault = SensitiveVault("pt2-negative")
        val count = vault.maskText("mua 2 bánh mì")
        assertTrue(count, SensitiveVault.TOKEN_PATTERN.find(count) == null)
        assertEquals(emptyList<IssuedMaskToken>(), vault.issuedTokens())

        // A bare digit run of four or more is masked as a neutral numeric value, as is an
        // explicit account hint. Neither is labeled an account or an amount.
        val bare = vault.maskText("mã 7319")
        val bareToken = SensitiveVault.TOKEN_PATTERN.find(bare)!!.value
        assertEquals(IssuedValueShape.NUMERIC, vault.shapeOf(bareToken))
        val account = vault.maskText("account ending 4821")
        assertEquals(
            IssuedValueShape.NUMERIC,
            vault.shapeOf(SensitiveVault.TOKEN_PATTERN.find(account)!!.value),
        )
    }

    @Test
    fun `informal dates and times are range-validated at detection`() {
        val vault = SensitiveVault("pt2-range")
        // Impossible day/month is not a date literal; nothing is minted for it.
        val invalidDate = vault.maskText("ngày 32/13")
        assertTrue(invalidDate, SensitiveVault.TOKEN_PATTERN.find(invalidDate) == null)
        // An out-of-range hour is not a time literal.
        val invalidTime = vault.maskText("lúc 25h")
        assertTrue(invalidTime, SensitiveVault.TOKEN_PATTERN.find(invalidTime) == null)

        val clock = vault.maskText("lúc 9h30")
        assertEquals("09:30", vault.parseTime(SensitiveVault.TOKEN_PATTERN.find(clock)!!.value))
        val cueDate = vault.maskText("ngày 2/11")
        assertEquals(
            "2026-11-02",
            vault.parseDate(SensitiveVault.TOKEN_PATTERN.find(cueDate)!!.value, 2026),
        )
        // A full date states its own year and needs no reference.
        val fullDate = vault.maskText("02/11/2026")
        assertEquals("2026-11-02", vault.parseDate(SensitiveVault.TOKEN_PATTERN.find(fullDate)!!.value))
    }

    @Test
    fun `a standalone day month clarification answer is masked and usable`() {
        val vault = SensitiveVault("pt2-standalone")
        val masked = vault.maskText("02/11")
        val token = SensitiveVault.TOKEN_PATTERN.find(masked)!!.value
        assertTrue(token, token.startsWith("⟦V-"))
        assertEquals("2026-11-02", vault.parseDate(token, 2026))
    }

    @Test
    fun `a year-less date and the same full date are distinct values`() {
        val vault = SensitiveVault("pt2-distinct-dates")
        val short = SensitiveVault.TOKEN_PATTERN.find(vault.maskText("ngày 02/11"))!!.value
        val full = SensitiveVault.TOKEN_PATTERN.find(vault.maskText("2026-11-02"))!!.value
        assertNotEquals("the ambiguous and the exact date do not share an identity", short, full)
        assertEquals("2026-11-02", vault.parseDate(full))
        assertEquals("2026-11-02", vault.parseDate(short, 2026))
    }

    @Test
    fun `glued sensitive values outside provider id fields are still leaks`() {
        val vault = SensitiveVault("pt2-identifier-boundary")
        vault.maskText(ownerNote)
        val accountVault = SensitiveVault("pt2-identifier-account")
        accountVault.maskText("account ending 12345678")

        // A unit or suffix glued directly to the value is still a leak.
        assertTrue(vault.scanLeak("It cost 10000VND"))
        assertTrue(vault.scanLeak("10000k"))
        assertTrue(vault.scanLeak("10000usd"))
        assertTrue(vault.scanLeak("acc10000"))
        assertTrue(vault.scanLeak("around 11h today"))
        // An account suffix glued to a reference prefix is still a leak.
        assertTrue(accountVault.scanLeak("acc12345678"))
        // The exact-value check used by the provider-id exemption refuses a bare value and
        // does not treat a longer opaque identifier as that value.
        assertTrue(vault.isMappedValueExact("10000"))
        assertFalse(vault.isMappedValueExact("call_00_f345vxh2eznfxf3sk11h6ax0"))
    }

    @Test
    fun `masked source text passes the inbound scan and stays leak free`() {
        val vault = SensitiveVault("pt2-scan")
        val boundary = MaskedPromptBoundary(vault)
        val masked = boundary.maskOutbound(ownerNote, MaskedPath.CONTEXT)
        assertTrue(boundary.scanInbound(masked) is InboundScan.Accepted)
        assertFalse(vault.scanLeak(masked))
        assertTrue(boundary.scanInbound("the amount was 10000") is InboundScan.Refused)
        assertTrue(boundary.scanInbound("on 02/11") is InboundScan.Refused)
        assertTrue(boundary.scanInbound("around 10:00") is InboundScan.Refused)
    }

    @Test
    fun `unknown foreign and unclosed tokens are never accepted incrementally`() {
        val vault = SensitiveVault("pt2-incremental-shapes")
        val boundary = MaskedPromptBoundary(vault)
        vault.maskText("giá 10000")
        val known = vault.issuedTokens().single().token

        assertTrue(boundary.scanInbound(known) is InboundScan.Accepted)
        assertTrue(boundary.scanInbound("⟦V-${vault.scopeTag}-9999⟧") is InboundScan.Refused)
        assertTrue(boundary.scanInbound("⟦V-forign1234-0001⟧") is InboundScan.Refused)
        // An old typed token shape is not a token this contract issues.
        assertTrue(boundary.scanInbound("⟦AMT-${vault.scopeTag}-0001⟧") is InboundScan.Refused)
        // A token split across a chunk boundary is not a token yet: the gate holds it, and the
        // boundary refuses the never-closed shape once the turn ends.
        assertTrue(boundary.scanInbound(known.dropLast(2)) is InboundScan.Accepted)
    }
}

/** Local parse probes used by these tests; they never rehydrate text or expose a raw value. */
private fun SensitiveVault.parseAmountOrNull(token: String): Long? =
    try {
        parseAmount(token)
    } catch (_: TokenValidationException) {
        null
    }

private fun SensitiveVault.parseDateOrNull(token: String, referenceYear: Int? = null): String? =
    try {
        parseDate(token, referenceYear)
    } catch (_: TokenValidationException) {
        null
    }

private fun SensitiveVault.parseTimeOrNull(token: String): String? =
    try {
        parseTime(token)
    } catch (_: TokenValidationException) {
        null
    }
