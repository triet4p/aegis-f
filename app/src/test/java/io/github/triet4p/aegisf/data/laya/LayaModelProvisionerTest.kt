package io.github.triet4p.aegisf.data.laya

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayaModelProvisionerTest {

    @Test
    fun `missing installed model names the files required by the runtime`() = runBlocking {
        val fixture = Fixture.create()
        try {
            val status = fixture.provisioner { LayaRuntimeContract.State.Ready }.inspect()

            assertEquals(
                LayaModelProvisioner.Status.Missing(
                    listOf(
                        "laya.onnx",
                        "laya.onnx.data",
                        "laya_config.json",
                        "tokenizer/laya_tokenizer.bin",
                    ),
                ),
                status,
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `changed installed bytes remain corrupt after a fresh inspection`() = runBlocking {
        val fixture = Fixture.create()
        try {
            fixture.seedExistingModel()
            val graph = fixture.modelDirectory.resolve("laya.onnx")
            graph.writeBytes(graph.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
            var runtimeWasOpened = false

            val status = fixture.provisioner {
                runtimeWasOpened = true
                LayaRuntimeContract.State.Ready
            }.inspect()

            assertTrue(status is LayaModelProvisioner.Status.CorruptOrIncompatible)
            assertTrue((status as LayaModelProvisioner.Status.CorruptOrIncompatible).reason.contains("SHA-256"))
            assertFalse(runtimeWasOpened)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `verified files are streamed published and ready after a fresh inspection`() = runBlocking {
        val fixture = Fixture.create()
        try {
            val progress = mutableListOf<LayaModelProvisioner.Progress>()
            val installed = fixture.provisioner { LayaRuntimeContract.State.Ready }
                .provision(fixture.sources) { progress += it }

            assertEquals(LayaModelProvisioner.Status.Ready, installed)
            fixture.assertInstalledBytes()
            assertTrue(progress.isNotEmpty())
            assertEquals(progress.last().totalBytes, progress.last().copiedBytes)
            assertEquals(
                LayaModelProvisioner.Status.Ready,
                fixture.provisioner { LayaRuntimeContract.State.Ready }.inspect(),
            )
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `missing source is reported and an existing app directory is untouched`() = runBlocking {
        val fixture = Fixture.create()
        try {
            val existing = File(fixture.modelDirectory, "owner-data").apply {
                parentFile!!.mkdirs()
                writeText("keep this directory")
            }
            val missing = fixture.provisioner { LayaRuntimeContract.State.Ready }
                .provision(fixture.sources - "laya.onnx.data")

            assertEquals(
                LayaModelProvisioner.Status.Missing(listOf("laya.onnx.data")),
                missing,
            )
            assertEquals("keep this directory", existing.readText())
            assertNoStagingDirectories(fixture.modelDirectory.parentFile!!)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `a bad digest cannot publish or replace the prior directory`() = runBlocking {
        val fixture = Fixture.create()
        try {
            val existing = File(fixture.modelDirectory, "owner-data").apply {
                parentFile!!.mkdirs()
                writeText("preserve on failed update")
            }
            val badSources = fixture.sources.toMutableMap().apply {
                this["laya.onnx"] = { ByteArrayInputStream(ByteArray(1_100_003) { 9 }) }
            }
            val result = fixture.provisioner { LayaRuntimeContract.State.Ready }.provision(badSources)

            assertTrue(result is LayaModelProvisioner.Status.CorruptOrIncompatible)
            assertTrue(
                (result as LayaModelProvisioner.Status.CorruptOrIncompatible)
                    .reason.contains("SHA-256"),
            )
            assertEquals("preserve on failed update", existing.readText())
            assertTrue(fixture.modelDirectory.resolve("owner-data").isFile)
            assertFalse(fixture.modelDirectory.resolve("laya.onnx").exists())
            assertNoStagingDirectories(fixture.modelDirectory.parentFile!!)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `runtime incompatibility prevents publication and leaves previous bytes in place`() = runBlocking {
        val fixture = Fixture.create()
        try {
            fixture.seedExistingModel()
            val priorBytes = fixture.modelDirectory.resolve("laya.onnx").readBytes()
            val result = fixture.provisioner {
                LayaRuntimeContract.State.CorruptOrIncompatible("probe failed")
            }.provision(fixture.sources)

            assertEquals(
                LayaModelProvisioner.Status.CorruptOrIncompatible("probe failed"),
                result,
            )
            assertArrayEquals(priorBytes, fixture.modelDirectory.resolve("laya.onnx").readBytes())
            assertNoStagingDirectories(fixture.modelDirectory.parentFile!!)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `interrupted source stream is diagnosed without replacing prior app data`() = runBlocking {
        val fixture = Fixture.create()
        try {
            val marker = File(fixture.modelDirectory, "owner-data").apply {
                parentFile!!.mkdirs()
                writeText("prior private data")
            }
            val interruptedSources = fixture.sources.toMutableMap().apply {
                this["laya.onnx"] = {
                    object : FilterInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))) {
                        private var served = false

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (served) throw IllegalStateException("source interrupted")
                            served = true
                            return super.read(buffer, offset, minOf(length, 2))
                        }
                    }
                }
            }
            val result = fixture.provisioner { LayaRuntimeContract.State.Ready }
                .provision(interruptedSources)

            assertTrue(result is LayaModelProvisioner.Status.CorruptOrIncompatible)
            assertTrue((result as LayaModelProvisioner.Status.CorruptOrIncompatible).reason.contains("interrupted"))
            assertEquals("prior private data", marker.readText())
            assertNoStagingDirectories(fixture.modelDirectory.parentFile!!)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `cancelling during a source read removes the stage and preserves prior data`() = runBlocking {
        val fixture = Fixture.create()
        val priorData = File(fixture.modelDirectory, "owner-data").apply {
            parentFile!!.mkdirs()
            writeText("keep existing private data")
        }
        val cancellationJob = Job()
        val cancelDuringRead = fixture.sources.toMutableMap().apply {
            this["laya.onnx"] = {
                object : FilterInputStream(fixture.sources.getValue("laya.onnx")()!!) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(buffer, offset, length)
                        if (count > 0) cancellationJob.cancel()
                        return count
                    }
                }
            }
        }
        var cancellationWasPropagated = false
        try {
            try {
                fixture.provisioner { LayaRuntimeContract.State.Ready }
                    .provision(cancelDuringRead, cancellationJob)
            } catch (_: CancellationException) {
                cancellationWasPropagated = true
            }

            assertTrue(cancellationWasPropagated)
            assertEquals("keep existing private data", priorData.readText())
            assertFalse(fixture.modelDirectory.resolve("laya.onnx").exists())
            assertNoStagingDirectories(fixture.modelDirectory.parentFile!!)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `cancelling staged runtime validation discards the stage and preserves prior data`() =
        runBlocking {
            val fixture = Fixture.create()
            val priorData = File(fixture.modelDirectory, "owner-data").apply {
                parentFile!!.mkdirs()
                writeText("keep existing private data")
            }
            val cancellationJob = Job()
            var runtimeWasOpened = false
            var cancellationWasPropagated = false
            try {
                try {
                    fixture.provisioner {
                        runtimeWasOpened = true
                        cancellationJob.cancel()
                        LayaRuntimeContract.State.Ready
                    }.provision(fixture.sources, cancellationJob)
                } catch (_: CancellationException) {
                    cancellationWasPropagated = true
                }

                assertTrue(runtimeWasOpened)
                assertTrue(cancellationWasPropagated)
                assertEquals("keep existing private data", priorData.readText())
                assertFalse(fixture.modelDirectory.resolve("laya.onnx").exists())
                assertNoStagingDirectories(fixture.modelDirectory.parentFile!!)
            } finally {
                fixture.root.deleteRecursively()
            }
        }

    @Test
    fun `cancelling a runtime check never reports ready or changes installed model`() = runBlocking {
        val fixture = Fixture.create()
        try {
            fixture.seedExistingModel()
            val original = fixture.modelDirectory.resolve("laya.onnx").readBytes()
            val cancellationJob = Job()
            var cancellationWasPropagated = false

            try {
                fixture.provisioner {
                    cancellationJob.cancel()
                    LayaRuntimeContract.State.Ready
                }.inspect(cancellationJob)
            } catch (_: CancellationException) {
                cancellationWasPropagated = true
            }

            assertTrue(cancellationWasPropagated)
            assertArrayEquals(original, fixture.modelDirectory.resolve("laya.onnx").readBytes())
            assertTrue(fixture.modelDirectory.isDirectory)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `ready model is never replaced by another selected source`() = runBlocking {
        val fixture = Fixture.create()
        try {
            fixture.seedExistingModel()
            val original = fixture.modelDirectory.resolve("laya.onnx").readBytes()
            val neverOpen = mapOf("laya.onnx" to { error("already-ready source was opened") })

            val result = fixture.provisioner { LayaRuntimeContract.State.Ready }.provision(neverOpen)

            assertEquals(LayaModelProvisioner.Status.Ready, result)
            assertArrayEquals(original, fixture.modelDirectory.resolve("laya.onnx").readBytes())
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun assertNoStagingDirectories(parent: File) {
        assertTrue(
            parent.listFiles().orEmpty().none { it.name.startsWith(".model-install-") },
        )
    }

    private class Fixture private constructor(
        val root: File,
        val modelDirectory: File,
        private val entries: List<Entry>,
        val sources: Map<String, () -> InputStream?>,
    ) {
        private val tokenizer = entries.last()

        fun provisioner(check: (File) -> LayaRuntimeContract.State) = LayaModelProvisioner(
            modelDirectory = modelDirectory,
            sourceFiles = entries.dropLast(1).map(Entry::pinnedFile),
            tokenizerFile = tokenizer.pinnedFile,
            tokenizerInput = { ByteArrayInputStream(tokenizer.bytes) },
            checkRuntime = check,
        )

        fun seedExistingModel() {
            entries.forEach { entry ->
                val file = modelDirectory.resolve(entry.pinnedFile.path)
                file.parentFile!!.mkdirs()
                file.writeBytes(entry.bytes)
            }
        }

        fun assertInstalledBytes() {
            entries.forEach { entry ->
                assertArrayEquals(entry.bytes, modelDirectory.resolve(entry.pinnedFile.path).readBytes())
            }
        }

        companion object {
            fun create(): Fixture {
                val root = kotlin.io.path.createTempDirectory("laya-provisioner").toFile()
                val modelDirectory = File(root, "files/laya/model")
                val sourceEntries = listOf(
                    entry("laya.onnx", ByteArray(1_100_003) { (it % 251).toByte() }),
                    entry("laya.onnx.data", byteArrayOf(3, 1, 4, 1, 5, 9)),
                    entry("laya_config.json", "{\"max_len\":512}".toByteArray()),
                )
                val tokenizer = entry("tokenizer/laya_tokenizer.bin", byteArrayOf(7, 7, 1, 2))
                return Fixture(
                    root = root,
                    modelDirectory = modelDirectory,
                    entries = sourceEntries + tokenizer,
                    sources = sourceEntries.associate { entry ->
                        entry.pinnedFile.path to { ByteArrayInputStream(entry.bytes) }
                    },
                )
            }

            private fun entry(path: String, bytes: ByteArray): Entry = Entry(
                pinnedFile = PinnedModelFile(
                    path = path,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = bytes.sha256(),
                ),
                bytes = bytes,
            )
        }
    }

    private data class Entry(
        val pinnedFile: PinnedModelFile,
        val bytes: ByteArray,
    )

}

private fun ByteArray.sha256(): String {
    val alphabet = "0123456789abcdef"
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte ->
            val value = byte.toInt() and 0xff
            "${alphabet[value ushr 4]}${alphabet[value and 0x0f]}"
        }
}
