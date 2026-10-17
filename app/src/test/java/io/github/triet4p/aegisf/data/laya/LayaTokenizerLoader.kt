package io.github.triet4p.aegisf.data.laya

import java.io.DataInputStream
import java.io.File

/**
 * Shared loader for Laya tests: the committed binary tokenizer asset, reached as a plain
 * file because unit tests run with the working directory at the module dir.
 */
internal object LayaTokenizerLoader {
    fun loadFromRepoFile(): LayaTokenizer {
        val asset = File("src/main/assets/laya/laya_tokenizer.bin").takeIf { it.isFile }
            ?: File("app/src/main/assets/laya/laya_tokenizer.bin")
        return DataInputStream(asset.inputStream().buffered()).use { LayaTokenizer.load(it) }
    }
}
