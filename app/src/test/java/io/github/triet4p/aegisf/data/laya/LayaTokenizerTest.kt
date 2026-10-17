package io.github.triet4p.aegisf.data.laya

import java.io.DataInputStream
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S4.2 tokenizer parity boundary: the app's [LayaTokenizer] port must encode exactly
 * like the pinned export bundle (`tokenizer/tokenizer.json` at the pinned revision)
 * across BPE merges, added-token splits, `[MASK]` lstrip, whitespace runs, NFC variants,
 * and punctuation-heavy expense text.
 *
 * Fixture: `app/src/test/resources/laya/tokenizer_goldens.json`, generated from the real
 * bundle by the reference tooling (input string -> expected pinned ids). The binary asset
 * under test is the committed `app/src/main/assets/laya/laya_tokenizer.bin`, produced by
 * the same generation step (`tools/laya/export_tokenizer_asset.py`), so this test pins
 * generation and consumption together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayaTokenizerTest {

    private fun loadTokenizer(): LayaTokenizer = LayaTokenizerLoader.loadFromRepoFile()

    private fun loadGoldens(): List<Pair<String, List<Int>>> {
        val fixture = File("src/test/resources/laya/tokenizer_goldens.json").takeIf { it.isFile }
            ?: File("app/src/test/resources/laya/tokenizer_goldens.json")
        val root = org.json.JSONObject(fixture.readText(Charsets.UTF_8))
        val cases = root.getJSONArray("cases")
        return (0 until cases.length()).map { i ->
            val entry = cases.getJSONObject(i)
            val ids = entry.getJSONArray("ids")
            entry.getString("text") to (0 until ids.length()).map { j -> ids.getInt(j) }
        }
    }

    @Test
    fun `tokenizer matches pinned bundle on every golden string`() {
        val tokenizer = loadTokenizer()
        val goldens = loadGoldens()
        assertTrue("golden fixture too small: ${goldens.size}", goldens.size >= 50)
        val mismatched = goldens.filter { (text, ids) -> tokenizer.encode(text) != ids }
        assertEquals(
            "mismatched: " + mismatched.take(3).joinToString("; ") { (text, ids) ->
                "$text expected ${ids.take(8)} got ${tokenizer.encode(text).take(8)}"
            },
            emptyList<String>(),
            mismatched.map { it.first },
        )
    }

    @Test
    fun `tokenizer exposes the pinned special ids`() {
        val tokenizer = loadTokenizer()
        assertEquals(50284, tokenizer.maskId)
        assertEquals(50281, tokenizer.clsId)
        assertEquals(50282, tokenizer.sepId)
        assertEquals(50283, tokenizer.padId)
    }

    @Test
    fun `mask lstrip never reaches past the previous token`() {
        val tokenizer = loadTokenizer()
        // `x [MASK] y` keeps the word before the gap: only the gap whitespace is consumed.
        assertEquals(listOf(89, 50284, 340), tokenizer.encode("x [MASK] y"))
        assertEquals(listOf(50284), tokenizer.encode("  [MASK]"))
        assertEquals(listOf(50284, 50284), tokenizer.encode("[MASK]  [MASK]"))
    }

    @Test
    fun `lstrip keeps zero-width and word-joiner characters`() {
        val tokenizer = loadTokenizer()
        // ZWSP (U+200B) is not Unicode White_Space, so it stays as BPE bytes, unlike NBSP.
        assertEquals(listOf(66, 12882, 50284), tokenizer.encode("a\u200B[MASK]"))
        assertEquals(listOf(66, 50284), tokenizer.encode("a\u00A0[MASK]"))
    }
}
