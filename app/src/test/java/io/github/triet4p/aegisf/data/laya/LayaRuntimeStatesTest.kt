package io.github.triet4p.aegisf.data.laya

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S4.2 bundle-state boundary: [LayaRuntime.open] reports [LayaRuntimeContract.State]
 * without ORT natives — missing files stay [Missing], a wrong budget pin reports
 * [CorruptOrIncompatible] before any session opens, and an unreadable tokenizer asset
 * does the same. Opening a real session needs the ARM64 (or host-CPU) native library and
 * is proven by the model-in-the-loop parity test plus the user-run device check instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayaRuntimeStatesTest {

    @Test
    fun `missing bundle reports Missing and serves nothing`() {
        val scratch = kotlin.io.path.createTempDirectory("laya-empty").toFile()
        try {
            val result = LayaRuntime.open(File(scratch, "absent"))
            assertTrue(result is LayaRuntime.Result.Unavailable)
            val state = (result as LayaRuntime.Result.Unavailable).state
            assertTrue(state is LayaRuntimeContract.State.Missing)
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun `wrong budgets report corrupt instead of serving`() {
        // The weights placeholder only satisfies the presence check: the budget pin check
        // fails before any session opens, so no weights bytes are read here. Real-weights
        // proof lives in the model-in-the-loop parity test, not here.
        val tampered = kotlin.io.path.createTempDirectory("laya-tampered").toFile()
        try {
            File(tampered, "laya.onnx").writeBytes(byteArrayOf(1, 2, 3))
            File(tampered, "laya.onnx.data").writeBytes(byteArrayOf(4, 5, 6))
            File(tampered, "laya_config.json").writeText(
                "{\"max_len\": 256, \"head_max_len\": 192}",
                Charsets.UTF_8,
            )
            File(tampered, "tokenizer").mkdirs()
            val asset = File("src/main/assets/laya/laya_tokenizer.bin").takeIf { it.isFile }
                ?: File("app/src/main/assets/laya/laya_tokenizer.bin")
            asset.copyTo(File(tampered, "tokenizer/laya_tokenizer.bin"))
            val result = LayaRuntime.open(tampered)
            val state = (result as? LayaRuntime.Result.Unavailable)?.state
            assertTrue(
                "expected CorruptOrIncompatible, got $result",
                state is LayaRuntimeContract.State.CorruptOrIncompatible,
            )
        } finally {
            tampered.deleteRecursively()
        }
    }

    @Test
    fun `unreadable tokenizer asset reports corrupt instead of serving`() {
        // Same placeholder arrangement as the budget test: the asset check fails before
        // any session opens, so the placeholder weights are never read.
        val tampered = kotlin.io.path.createTempDirectory("laya-bad-asset").toFile()
        try {
            File(tampered, "laya.onnx").writeBytes(byteArrayOf(1, 2, 3))
            File(tampered, "laya.onnx.data").writeBytes(byteArrayOf(4, 5, 6))
            File(tampered, "laya_config.json").writeText(
                "{\"max_len\": 512, \"head_max_len\": 192}",
                Charsets.UTF_8,
            )
            File(tampered, "tokenizer").mkdirs()
            File(tampered, "tokenizer/laya_tokenizer.bin").writeBytes(byteArrayOf(9, 9, 9))
            val result = LayaRuntime.open(tampered)
            val state = (result as? LayaRuntime.Result.Unavailable)?.state
            assertTrue(
                "expected CorruptOrIncompatible, got $result",
                state is LayaRuntimeContract.State.CorruptOrIncompatible,
            )
        } finally {
            tampered.deleteRecursively()
        }
    }
}
