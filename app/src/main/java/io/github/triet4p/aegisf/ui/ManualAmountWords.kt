package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.AegisLanguage

/**
 * The amount of one record written out, for the hero amount card's subtitle.
 *
 * The digits remain the authoritative value; this line only repeats them in words so a reader
 * can check a long figure at a glance. It is pure formatting: it never changes the amount, and
 * an amount that cannot be written out simply has no subtitle.
 *
 * English spells groups of three with the conventional short scale. Vietnamese uses the same
 * groups with its own scale words and the usual `mười`/`mươi`, `mốt`, `tư`, `lăm` forms. A
 * missing lower group is read with `không trăm`/`lẻ` so "một nghìn năm" cannot be mistaken for
 * one thousand and five.
 */
internal fun amountInWords(amountVnd: Long, language: AegisLanguage): String? {
    if (amountVnd <= 0L) return null
    val words = when (language) {
        AegisLanguage.ENGLISH -> englishWords(amountVnd)
        AegisLanguage.VIETNAMESE -> vietnameseWords(amountVnd)
    }
    if (words.isEmpty()) return null
    val currency = when (language) {
        AegisLanguage.ENGLISH -> "dong"
        AegisLanguage.VIETNAMESE -> "đồng"
    }
    return sentenceCase(words) + " " + currency
}

private val englishOnes = listOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
    "seventeen", "eighteen", "nineteen",
)
private val englishTens = listOf(
    "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
)
private val englishScales = listOf(
    "", "thousand", "million", "billion", "trillion", "quadrillion", "quintillion",
)

private fun englishWords(amountVnd: Long): String {
    val groups = groupsOfThree(amountVnd)
    val spoken = mutableListOf<String>()
    for (index in groups.indices.reversed()) {
        val group = groups[index]
        if (group == 0) continue
        val words = englishGroup(group)
        val scale = englishScales[index]
        spoken += if (scale.isEmpty()) words else "$words $scale"
    }
    return spoken.joinToString(" ")
}

private fun englishGroup(group: Int): String {
    val parts = mutableListOf<String>()
    val hundreds = group / 100
    val rest = group % 100
    if (hundreds > 0) parts += "${englishOnes[hundreds]} hundred"
    if (rest > 0) {
        if (rest < 20) {
            parts += englishOnes[rest]
        } else {
            val tens = rest / 10
            val units = rest % 10
            parts += englishTens[tens] + if (units > 0) "-${englishOnes[units]}" else ""
        }
    }
    return parts.joinToString(" ")
}

private val vietnameseDigits = listOf(
    "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín",
)
private val vietnameseScales = listOf(
    "", "nghìn", "triệu", "tỷ", "nghìn tỷ", "triệu tỷ", "tỷ tỷ",
)

private fun vietnameseWords(amountVnd: Long): String {
    val groups = groupsOfThree(amountVnd)
    val spoken = mutableListOf<String>()
    var seenHigher = false
    for (index in groups.indices.reversed()) {
        val group = groups[index]
        if (group == 0) continue
        // A group below one hundred after a higher group is read with its empty hundreds
        // column stated, so 1,005 is "một nghìn không trăm lẻ năm", not "một nghìn năm".
        val leadingZeroHundred = seenHigher && group < 100
        val words = vietnameseGroup(group, leadingZeroHundred)
        val scale = vietnameseScales[index]
        spoken += if (scale.isEmpty()) words else "$words $scale"
        seenHigher = true
    }
    return spoken.joinToString(" ")
}

private fun vietnameseGroup(group: Int, leadingZeroHundred: Boolean): String {
    val parts = mutableListOf<String>()
    val hundreds = group / 100
    val tens = (group / 10) % 10
    val units = group % 10
    when {
        leadingZeroHundred -> parts += "không trăm"
        hundreds > 0 -> parts += "${vietnameseDigits[hundreds]} trăm"
    }
    when {
        tens == 0 -> {
            if (units > 0) {
                if (hundreds > 0 || leadingZeroHundred) parts += "lẻ"
                parts += vietnameseDigits[units]
            }
        }
        tens == 1 -> {
            parts += "mười"
            when (units) {
                0 -> Unit
                5 -> parts += "lăm"
                else -> parts += vietnameseDigits[units]
            }
        }
        else -> {
            parts += "${vietnameseDigits[tens]} mươi"
            when (units) {
                0 -> Unit
                1 -> parts += "mốt"
                4 -> parts += "tư"
                5 -> parts += "lăm"
                else -> parts += vietnameseDigits[units]
            }
        }
    }
    return parts.joinToString(" ")
}

/** The base-thousand groups of [amountVnd], least significant first. */
private fun groupsOfThree(amountVnd: Long): List<Int> {
    val groups = mutableListOf<Int>()
    var remaining = amountVnd
    while (remaining > 0L) {
        groups += (remaining % 1_000L).toInt()
        remaining /= 1_000L
    }
    return groups
}

private fun sentenceCase(words: String): String {
    if (words.isEmpty()) return words
    return words.replaceFirstChar { first -> first.titlecase() }
}
