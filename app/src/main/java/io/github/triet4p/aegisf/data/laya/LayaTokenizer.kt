package io.github.triet4p.aegisf.data.laya

import java.io.DataInputStream
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * The pinned English Laya tokenizer: NFC normalization, added-token split, GPT-2-style
 * ByteLevel pre-tokenization, and BPE merge ranks read from the binary asset produced by
 * `tools/laya/export_tokenizer_asset.py` against the pinned export bundle
 * (`receptron/laya-onnx` @ `68f27dfe`), never from a JSON file on the device.
 *
 * Encoding contract (verified behavior-for-behavior against the pinned bundle's
 * `tokenizer/tokenizer.json` through the `tokenizers` crate semantics):
 *
 * - text is NFC-normalized first;
 * - added tokens (116 entries: whitespace runs, `[MASK]`/`[CLS]`/`[SEP]`/`[PAD]`/`[UNK]`,
 *   `<|padding|>`, `<|endoftext|>`, email/phone/IP placeholders, `[unusedN]`) split the
 *   normalized text by longest match at each position with no word-boundary or case
 *   requirement; only `[MASK]` strips preceding Unicode whitespace (`lstrip`);
 * - every other span is pre-tokenized with the GPT-2 byte-level regex, mapped UTF-8
 *   byte-by-byte through the GPT-2 byte alphabet, and merged by ascending merge rank;
 * - ids are the pinned vocab ids, so [MASK] is 50284, [CLS] 50281, [SEP] 50282,
 *   [PAD] 50283, and [UNK] 50280.
 *
 * The literal `[MASK]` text is never encoded specially here: callers scrub it before
 * encoding (see [LayaSequence]), so a description cannot inject an option marker.
 */
class LayaTokenizer private constructor(
    private val vocab: List<String>,
    private val tokenToId: Map<String, Int>,
    private val mergeRank: Map<Pair<String, String>, Int>,
    private val addedByContent: Map<String, AddedToken>,
    private val maxAddedLength: Int,
) {
    data class AddedToken(
        val id: Int,
        val content: String,
        val lstrip: Boolean,
        /** True when every char is strip-whitespace (the N-space runs): absorbable. */
        val isWhitespaceRun: Boolean,
    )

    // The four structural ids live in the added-token table, not the BPE vocab (the vocab
    // holds `<|endoftext|>` at 50279 but no bracket specials): resolve them there so a
    // regenerated asset with moved ids fails loudly here instead of shifting markers.
    val maskId: Int = addedByContent.getValue(MASK_SURFACE).id
    val clsId: Int = addedByContent.getValue("[CLS]").id
    val sepId: Int = addedByContent.getValue("[SEP]").id
    val padId: Int = addedByContent.getValue("[PAD]").id

    /**
     * Encodes [text] exactly as the pinned bundle does with `add_special_tokens=false`:
     * no sentence wrapping is added here; [LayaSequence] places `[CLS]`/`[SEP]` itself.
     */
    fun encode(text: String): List<Int> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val output = ArrayList<Int>()
        var index = 0
        while (index < normalized.length) {
            val found = nextAddedMatch(normalized, index)
            if (found == null) {
                encodeSpan(normalized.substring(index, normalized.length), output)
                break
            }
            val (next, added) = found
            // A whitespace-only match at the gap start may itself be absorbed: when the
            // maximal strip-whitespace run from here ends exactly where an `lstrip`
            // token starts, the whole run (added whitespace tokens included) is dropped,
            // matching the reference split. Anything else emits this token normally.
            if (next == index && added.isWhitespaceRun) {
                var runEnd = index
                while (runEnd < normalized.length && isStripWhitespace(normalized[runEnd])) {
                    runEnd++
                }
                val after = nextAddedMatch(normalized, runEnd)
                if (after != null && after.first == runEnd && after.second.lstrip) {
                    output.add(after.second.id)
                    index = runEnd + after.second.content.length
                    continue
                }
                output.add(added.id)
                index += added.content.length
                continue
            }
            // An `lstrip` token consumes contiguous whitespace immediately before it, but
            // only back to the current gap start: previously emitted pieces are never
            // reopened. The gap holds no added match before `next` (leftmost), so encoding
            // up to the rewound point drops the consumed whitespace exactly like the
            // reference.
            var start = next
            if (added.lstrip) {
                while (start > index && isStripWhitespace(normalized[start - 1])) {
                    start--
                }
            }
            if (start > index) {
                encodeSpan(normalized.substring(index, start), output)
            }
            output.add(added.id)
            index = next + added.content.length
        }
        return output
    }

    private fun matchAddedAt(text: String, index: Int): AddedToken? {
        // Longest content wins at each position; all contents are literal, no case folding.
        var best: AddedToken? = null
        var bestLength = 0
        val limit = minOf(text.length, index + maxAddedLength)
        var end = index + 1
        while (end <= limit) {
            val candidate = addedByContent[text.substring(index, end)]
            if (candidate != null && end - index > bestLength) {
                best = candidate
                bestLength = end - index
            }
            end++
        }
        return best
    }

    private fun nextAddedMatch(text: String, from: Int): Pair<Int, AddedToken>? {
        // The leftmost added-token match at or after [from] (longest content wins at each
        // position). A plain scan is fast enough: option/state spans are at most a few
        // hundred chars.
        var index = from
        while (index < text.length) {
            val match = matchAddedAt(text, index)
            if (match != null) return Pair(index, match)
            index++
        }
        return null
    }

    private fun encodeSpan(span: String, output: MutableList<Int>) {
        val matcher = GPT2_PATTERN.matcher(span)
        while (matcher.find()) {
            val mapped = StringBuilder(matcher.group().length * 2)
            for (byte in matcher.group().toByteArray(StandardCharsets.UTF_8)) {
                mapped.append(BYTE_TO_CHAR[byte.toInt() and 0xFF])
            }
            for (merged in applyMerges(mapped.toString())) {
                val id = tokenToId[merged]
                    ?: error("pinned vocab lacks BPE token of ${merged.length} chars")
                output.add(id)
            }
        }
    }

    private fun applyMerges(word: String): List<String> {
        if (word.length <= 1) return listOf(word)
        var parts = word.map { it.toString() }
        while (parts.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestLeft = ""
            var bestRight = ""
            for (i in 0 until parts.size - 1) {
                val rank = mergeRank[Pair(parts[i], parts[i + 1])] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestLeft = parts[i]
                    bestRight = parts[i + 1]
                }
            }
            if (bestRank == Int.MAX_VALUE) break
            val merged = ArrayList<String>(parts.size - 1)
            var i = 0
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] == bestLeft && parts[i + 1] == bestRight) {
                    merged.add(bestLeft + bestRight)
                    i += 2
                } else {
                    merged.add(parts[i])
                    i++
                }
            }
            parts = merged
        }
        return parts
    }

    companion object {
        const val MASK_SURFACE = "[MASK]"
        const val UNKNOWN_OPTION = "unknown"
        const val UNKNOWN_DESCRIPTION = "none of the listed categories fit this expense"

        /**
         * Unicode White_Space=yes set (Rust `char::is_whitespace`), which is what the pinned
         * tokenizer's `[MASK]` lstrip consumes. This is wider than
         * [Character.isWhitespace] (which misses NBSP) and narrower than
         * [Character.isSpaceChar] (which keeps ZWSP/BOM): ZWSP, U+180E and BOM are kept.
         */
        fun isStripWhitespace(char: Char): Boolean {
            val code = char.code
            if (code <= 0x1F) {
                return code == 0x09 || code == 0x0A || code == 0x0B ||
                    code == 0x0C || code == 0x0D
            }
            if (code == 0x20 || code == 0x85 || code == 0xA0 || code == 0x1680) return true
            if (code in 0x2000..0x200A) return true
            return code == 0x2028 || code == 0x2029 || code == 0x202F ||
                code == 0x205F || code == 0x3000
        }

        /** GPT-2 byte alphabet: byte value -> mapped char, indexed by unsigned byte. */
        val BYTE_TO_CHAR: CharArray = buildByteAlphabet()

        private fun buildByteAlphabet(): CharArray {
            val bytes = ArrayList<Int>(256)
            val chars = ArrayList<Int>(256)
            for (code in '!'.code..'~'.code) {
                bytes.add(code)
                chars.add(code)
            }
            for (code in '¡'.code..'¬'.code) {
                bytes.add(code)
                chars.add(code)
            }
            for (code in '®'.code..'ÿ'.code) {
                bytes.add(code)
                chars.add(code)
            }
            var extra = 0
            for (code in 0..255) {
                if (!bytes.contains(code)) {
                    bytes.add(code)
                    chars.add(256 + extra)
                    extra++
                }
            }
            val table = CharArray(256)
            for (i in bytes.indices) {
                table[bytes[i]] = chars[i].toChar()
            }
            return table
        }

        private val GPT2_PATTERN: Pattern = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+",
        )

        fun load(asset: DataInputStream): LayaTokenizer {
            val magic = ByteArray(8)
            asset.readFully(magic)
            require(magic.contentEquals("LAYATOK1".toByteArray(StandardCharsets.US_ASCII))) {
                "not a Laya tokenizer asset"
            }
            val vocabSize = asset.readInt()
            require(vocabSize in 1..200_000) { "implausible vocab size $vocabSize" }
            val vocab = ArrayList<String>(vocabSize)
            repeat(vocabSize) {
                val length = asset.readInt()
                require(length in 1..2048) { "implausible token length $length" }
                val raw = ByteArray(length)
                asset.readFully(raw)
                vocab.add(String(raw, StandardCharsets.UTF_8))
            }
            val tokenToId = HashMap<String, Int>(vocabSize * 2)
            for (id in vocab.indices) {
                require(tokenToId.put(vocab[id], id) == null) {
                    "duplicate vocab token at id $id"
                }
            }
            val mergeCount = asset.readInt()
            require(mergeCount in 1..200_000) { "implausible merge count $mergeCount" }
            val mergeRank = HashMap<Pair<String, String>, Int>(mergeCount * 2)
            repeat(mergeCount) { rank ->
                val leftLength = asset.readInt()
                require(leftLength in 1..2048) { "implausible merge length" }
                val leftRaw = ByteArray(leftLength)
                asset.readFully(leftRaw)
                val rightLength = asset.readInt()
                require(rightLength in 1..2048) { "implausible merge length" }
                val rightRaw = ByteArray(rightLength)
                asset.readFully(rightRaw)
                mergeRank[
                    Pair(
                        String(leftRaw, StandardCharsets.UTF_8),
                        String(rightRaw, StandardCharsets.UTF_8),
                    ),
                ] = rank
            }
            val addedCount = asset.readInt()
            require(addedCount in 1..1024) { "implausible added-token count $addedCount" }
            val addedByContent = HashMap<String, AddedToken>(addedCount * 2)
            var maxAddedLength = 0
            repeat(addedCount) {
                val id = asset.readInt()
                val length = asset.readInt()
                require(length in 1..512) { "implausible added-token length" }
                val raw = ByteArray(length)
                asset.readFully(raw)
                val flags = asset.readByte().toInt()
                val content = String(raw, StandardCharsets.UTF_8)
                val token = AddedToken(
                    id = id,
                    content = content,
                    lstrip = flags and 0x01 != 0,
                    isWhitespaceRun = content.isNotEmpty() && content.all(::isStripWhitespace),
                )
                // First content wins on a shared surface; the writer emits ids ascending so
                // the lowest id owns it, matching reference longest-match order.
                if (!addedByContent.containsKey(token.content)) {
                    addedByContent[token.content] = token
                }
                maxAddedLength = maxOf(maxAddedLength, token.content.length)
            }
            return LayaTokenizer(vocab, tokenToId, mergeRank, addedByContent, maxAddedLength)
        }
    }
}
