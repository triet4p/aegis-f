package io.github.triet4p.aegisf.data

import java.util.Locale

/** Case-insensitive exact-description key; only surrounding/repeated whitespace is normalized. */
internal fun rememberedCategoryRuleKey(description: String): String? {
    val lowered = description.lowercase(Locale.ROOT)
    val key = buildString(lowered.length) {
        var pendingSpace = false
        for (character in lowered) {
            if (character.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }
    return key.ifEmpty { null }
}
