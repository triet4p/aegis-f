package io.github.triet4p.aegisf.notification

import android.app.Notification
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S3.3 parser: what one permitted VietinBank notification reads into, and what stays
 * unresolved.
 *
 * The fixtures are the user's own redacted sample
 * (`artifacts/sprint-3/handoffs/vietinbank-notification-context.md`), kept as the observed
 * format: the bank line, the masked account line, the readable transaction line, the masked
 * balance, and the dotted description. Which of title, text, or big text the bank really uses
 * was not supplied, so the split used here is synthetic and the parser is expected to find the
 * lines wherever they were posted.
 *
 * The cases are the ones a consumer would notice if they went wrong: the balance or the account
 * number becoming the transaction amount, a debit being read as a spend, a masked amount or
 * memo being filled in with a guess, a conflict being resolved by picking one value, another
 * bank's message being read as a VietinBank transaction, or the moment the message states being
 * turned into an instant in whatever zone the device happens to have. The tests run the real
 * parser, and the last one runs the real intake gate in front of it, because that is the pair
 * of calls the listener makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BankNotificationParserTest {

    @Test
    fun `the user's VietinBank sample keeps amount direction timestamp and an unresolved memo`() {
        val parsed = asParsed(
            parse(title = vietinBankTitle, text = vietinBankBody),
        )

        assertEquals(BankSource.VIETINBANK, parsed.bank)
        // GD: -50,000 VND — the transaction line, not the masked balance beside it.
        assertEquals(NotificationField.Present(50_000L), parsed.amountVnd)
        // The bank's own debit. It is not an expense: the parse carries no ledger type at all.
        assertEquals(BankDirection.DEBIT, parsed.direction)
        // VietinBank: 16/10/2026 07:38 — the wall clock the bank printed, with no zone claimed.
        assertEquals(NotificationField.Present(sampleMoment), parsed.occurredAt)
        // ND: ........ is masking, so the memo is unresolved rather than the literal dots.
        assertEquals(NotificationField.Unresolved(UnresolvedReason.Redacted), parsed.memo)
    }

    @Test
    fun `the stated moment is the bank's wall clock and never depends on the device zone`() {
        // The bank states no zone, so the parse must not read the device's. Two very different
        // device zones have to yield the identical wall clock: the old conversion returned epoch
        // millis that shifted with the zone offset, which is exactly the asserted instant this
        // no longer makes.
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
            assertEquals("Asia/Ho_Chi_Minh", TimeZone.getDefault().id)
            val inHoChiMinh = asParsed(parse(title = vietinBankTitle, text = vietinBankBody))
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals("America/New_York", TimeZone.getDefault().id)
            val inNewYork = asParsed(parse(title = vietinBankTitle, text = vietinBankBody))

            assertEquals(NotificationField.Present(sampleMoment), inHoChiMinh.occurredAt)
            assertEquals(inHoChiMinh.occurredAt, inNewYork.occurredAt)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun `the balance and the account line are never read as the transaction amount`() {
        // A readable balance beside a masked amount: the amount stays unresolved, and the
        // balance number never stands in for it.
        val withMaskedAmount = asParsed(
            parse(text = vietinBankText("TK: 1234567890", "GD: Redacted", "SDC: 500,000 VND")),
        )
        assertEquals(
            NotificationField.Unresolved(UnresolvedReason.Unreadable),
            withMaskedAmount.amountVnd,
        )
        assertEquals(BankDirection.UNKNOWN, withMaskedAmount.direction)

        // A balance-only alert has no transaction line to read at all.
        assertEquals(
            BankNotificationParse.UnsupportedFormat,
            parse(text = vietinBankText("SDC: 500,000 VND")),
        )
    }

    @Test
    fun `a credit stays the bank's credit and is not turned into income`() {
        val parsed = asParsed(parse(text = vietinBankText("GD: +50,000 VND")))

        assertEquals(NotificationField.Present(50_000L), parsed.amountVnd)
        assertEquals(BankDirection.CREDIT, parsed.direction)
    }

    @Test
    fun `an amount with no sign leaves the direction unknown`() {
        val parsed = asParsed(parse(text = vietinBankText("GD: 50,000 VND")))

        assertEquals(NotificationField.Present(50_000L), parsed.amountVnd)
        assertEquals(BankDirection.UNKNOWN, parsed.direction)
    }

    @Test
    fun `a notification without a timestamp or a description keeps both explicitly unresolved`() {
        // The bank's name alone states its attribution but no moment, and there is no ND line.
        val parsed = asParsed(parse(title = "VietinBank", text = "GD: -50,000 VND"))

        assertEquals(NotificationField.Present(50_000L), parsed.amountVnd)
        assertEquals(NotificationField.Missing, parsed.occurredAt)
        assertEquals(NotificationField.Missing, parsed.memo)
    }

    @Test
    fun `an amount the supported shape cannot read stays unresolved instead of being guessed`() {
        val unreadable = listOf(
            "GD: Redacted",
            "GD: ........",
            "GD: 50,000 USD",
            "GD: 0 VND",
        )

        unreadable.forEach { line ->
            val parsed = asParsed(parse(text = vietinBankText(line)))
            assertEquals(line, NotificationField.Unresolved(UnresolvedReason.Unreadable), parsed.amountVnd)
            assertEquals(line, BankDirection.UNKNOWN, parsed.direction)
        }
    }

    @Test
    fun `conflicting repeated lines are unresolved while an identical repeat is one statement`() {
        // Android often posts the same text in more than one field: that is one statement.
        val repeated = asParsed(
            parse(
                title = bankLine,
                text = "GD: -50,000 VND",
                bigText = "GD: -50,000 VND",
            ),
        )
        assertEquals(NotificationField.Present(50_000L), repeated.amountVnd)
        assertEquals(BankDirection.DEBIT, repeated.direction)

        // Genuinely different amounts cannot both be the transaction's, so neither is chosen.
        val conflicting = asParsed(
            parse(
                title = bankLine,
                text = "GD: -50,000 VND",
                bigText = "GD: -60,000 VND",
            ),
        )
        assertEquals(NotificationField.Unresolved(UnresolvedReason.Ambiguous), conflicting.amountVnd)
        assertEquals(BankDirection.UNKNOWN, conflicting.direction)
    }

    @Test
    fun `a meaningful description is kept while masking alone is not one`() {
        assertEquals(
            NotificationField.Present("Cà phê sáng"),
            asParsed(parse(text = vietinBankText("GD: -50,000 VND", "ND: Cà phê sáng"))).memo,
        )
        assertEquals(
            NotificationField.Unresolved(UnresolvedReason.Redacted),
            asParsed(parse(text = vietinBankText("GD: -50,000 VND", "ND: ........"))).memo,
        )
        assertEquals(
            NotificationField.Unresolved(UnresolvedReason.Redacted),
            asParsed(parse(text = vietinBankText("GD: -50,000 VND", "ND: Redacted"))).memo,
        )
    }

    @Test
    fun `a message that is not the observed VietinBank transaction format is not parsed as one`() {
        // Another bank's transaction-shaped message: no VietinBank attribution line.
        assertEquals(
            BankNotificationParse.UnsupportedFormat,
            parse(
                text = lines(
                    "Techcombank: 16/10/2026 07:38",
                    "GD: -50,000 VND",
                ),
            ),
        )

        // A VietinBank message that states no transaction line, such as a promotion.
        assertEquals(
            BankNotificationParse.UnsupportedFormat,
            parse(text = lines("VietinBank: ưu đãi hoàn tiền 50,000 VND")),
        )
    }

    @Test
    fun `the intake gate runs before the parser so only a permitted message is ever parsed`() {
        // The listener makes exactly these two calls in this order: gate, then parse. A
        // non-listed package is refused by the gate, so its content is never parsed at all.
        val otherApp = ContentReadProbe(
            "com.example.game",
            1,
            postedNotification(title = vietinBankTitle, text = vietinBankBody),
        )
        assertEquals(
            NotificationIntakeDecision.NotPermitted,
            decideNotificationIntake(otherApp, bankPackages),
        )

        val listed = ContentReadProbe(
            "com.vietinbank.ipay",
            2,
            postedNotification(title = vietinBankTitle, text = vietinBankBody),
        )
        val decision = decideNotificationIntake(listed, bankPackages)
        assertTrue("expected the listed package to be permitted but was $decision", decision is NotificationIntakeDecision.Permitted)

        val parsed = asParsed(
            parseBankNotification((decision as NotificationIntakeDecision.Permitted).content),
        )
        assertEquals(NotificationField.Present(50_000L), parsed.amountVnd)
        assertEquals(BankDirection.DEBIT, parsed.direction)
        assertEquals(NotificationField.Present(sampleMoment), parsed.occurredAt)
        assertEquals(NotificationField.Unresolved(UnresolvedReason.Redacted), parsed.memo)
    }

    private fun parse(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
    ): BankNotificationParse =
        parseBankNotification(PermittedNotificationContent(title, text, bigText))

    private fun asParsed(parse: BankNotificationParse): BankNotificationParse.Parsed {
        assertTrue("expected a parsed notification but was $parse", parse is BankNotificationParse.Parsed)
        return parse as BankNotificationParse.Parsed
    }

    /** A real posted notification whose content holds only the fields it was built with. */
    private fun postedNotification(title: String?, text: String?): Notification = Notification().apply {
        extras.putCharSequence(Notification.EXTRA_TITLE, title)
        extras.putCharSequence(Notification.EXTRA_TEXT, text)
    }

    private companion object {
        /** The heading line of the user's VietinBank sample. */
        const val vietinBankTitle = "Tin biến động số dư"

        /** The bank attribution line of the sample, with its observed timestamp. */
        const val bankLine = "VietinBank: 16/10/2026 07:38"

        /** The body of the user's sample, verbatim after the title line. */
        val vietinBankBody = lines(
            bankLine,
            "TK: Redacted",
            "GD: -50,000 VND",
            "SDC: xxx,000 VND",
            "ND: ........",
        )

        /**
         * The moment the sample states, as the bank's own wall clock: 16/10/2026 07:38 and no
         * zone, so the expected value is a wall clock rather than an instant that would shift
         * with whatever zone the device happens to be in.
         */
        val sampleMoment = BankStatedMoment(date = "2026-10-16", time = "07:38")

        val bankPackages = setOf("com.vietinbank.ipay")

        fun lines(vararg values: String): String = values.joinToString("\n")

        /** The bank attribution line followed by [values], as the observed format posts them. */
        fun vietinBankText(vararg values: String): String = lines(bankLine, *values)
    }
}
