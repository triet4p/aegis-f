package io.github.triet4p.aegisf.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Replaces internal mask-token syntax with readable, value-free copy for display only.
 *
 * The conversation surface must never show the opaque `⟦…⟧` encoding: a provider may quote a
 * token it received, and a token is not a word the user can read. Only complete token spans are
 * replaced; an opening delimiter that has not closed yet stays visible while it streams, exactly
 * like an unpaired emphasis marker. The stored answer and the masking boundary are untouched.
 */
internal fun chatVisibleText(text: String, maskedValue: String): String =
    text.replace(MASK_TOKEN, maskedValue)

/**
 * The readable presentation of one assistant answer.
 *
 * The delivered text is kept character for character except for the small set of Markdown
 * markers a reader should not see in a conversation: ATX headings lose their `#` run and become
 * bold, `**…**` emphasis loses its markers and becomes bold, a leading `-`/`*` bullet becomes a
 * bullet glyph with its indentation kept, a horizontal-rule line becomes a separator, and a
 * paired backtick span becomes monospace without its backticks. An unpaired marker is left
 * visible so a marker that is still arriving can never hide or reflow delivered words, and no
 * amount or other delivered character is changed.
 *
 * This is deliberately not a Markdown parser: links, tables, code fences and images stay exactly
 * as the provider sent them rather than being silently transformed.
 */
internal fun chatMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.split('\n')
    lines.forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        when {
            heading != null -> withStyle(BoldStyle) {
                appendEmphasis(heading.groupValues[2])
            }

            bullet != null -> {
                append(bullet.groupValues[1])
                append("• ")
                appendEmphasis(bullet.groupValues[2])
            }

            RULE.matches(line) -> append(RULE_LINE)

            else -> appendEmphasis(line)
        }
    }
}

private fun AnnotatedString.Builder.appendEmphasis(line: String) {
    var cursor = 0
    while (true) {
        val openBold = line.indexOf(EMPHASIS, cursor)
        val openCode = line.indexOf(CODE, cursor)
        val open = when {
            openBold < 0 -> openCode
            openCode < 0 -> openBold
            else -> minOf(openBold, openCode)
        }
        if (open < 0) {
            append(line.substring(cursor))
            return
        }
        if (open == openCode && (openBold < 0 || openCode < openBold)) {
            val close = line.indexOf(CODE, open + CODE.length)
            if (close < 0) {
                // The closing backtick has not arrived yet: show the words without the marker
                // rather than exposing raw punctuation while the answer streams.
                append(line.substring(cursor, open))
                append(line.substring(open + CODE.length))
                return
            }
            append(line.substring(cursor, open))
            if (close > open + CODE.length) {
                withStyle(CodeStyle) {
                    append(line.substring(open + CODE.length, close))
                }
            }
            cursor = close + CODE.length
            if (cursor >= line.length) return
            continue
        }
        val close = line.indexOf(EMPHASIS, open + EMPHASIS.length)
        if (close < 0) {
            append(line.substring(cursor, open))
            append(line.substring(open + EMPHASIS.length))
            return
        }
        append(line.substring(cursor, open))
        if (close > open + EMPHASIS.length) {
            withStyle(BoldStyle) {
                append(line.substring(open + EMPHASIS.length, close))
            }
        }
        cursor = close + EMPHASIS.length
        if (cursor >= line.length) return
    }
}

private val BoldStyle = SpanStyle(fontWeight = FontWeight.Bold)
private val CodeStyle = SpanStyle(fontFamily = FontFamily.Monospace)
private const val EMPHASIS = "**"
private const val CODE = "`"
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*]\\s+(.*)$")
private val RULE = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
private val MASK_TOKEN = Regex("⟦[^⟧\\n]*⟧")
private const val RULE_LINE = "────────────"
