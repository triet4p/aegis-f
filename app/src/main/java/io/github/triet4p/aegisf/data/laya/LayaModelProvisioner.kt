package io.github.triet4p.aegisf.data.laya

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Installs only the pinned Laya export into the app-private `files/laya/model` directory.
 * Each incoming file is copied once with bounded memory while its exact size and SHA-256
 * are checked. A candidate is opened by the real runtime before a same-filesystem rename
 * publishes it; failed or cancelled transfers never replace the active directory.
 */
internal data class PinnedModelFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

class LayaModelProvisioner internal constructor(
    private val modelDirectory: File,
    private val sourceFiles: List<PinnedModelFile>,
    private val tokenizerFile: PinnedModelFile,
    private val tokenizerInput: () -> InputStream?,
    private val checkRuntime: (File) -> LayaRuntimeContract.State,
) {
    constructor(filesDirectory: File, tokenizerInput: () -> InputStream?) : this(
        modelDirectory = File(File(filesDirectory, "laya"), "model"),
        sourceFiles = PINNED_SOURCE_FILES,
        tokenizerFile = PINNED_TOKENIZER_FILE,
        tokenizerInput = tokenizerInput,
        checkRuntime = { directory ->
            when (val result = LayaRuntime.open(directory)) {
                is LayaRuntime.Result.Opened -> {
                    result.runtime.close()
                    LayaRuntimeContract.State.Ready
                }

                is LayaRuntime.Result.Unavailable -> result.state
            }
        },
    )
    private val allFiles: List<PinnedModelFile> = sourceFiles + tokenizerFile
    private val parentDirectory: File = checkNotNull(modelDirectory.parentFile)

    /** Checks the installed bundle while observing [cancellationJob] at cooperative checkpoints. */
    suspend fun inspect(cancellationJob: Job? = null): Status = try {
        ensureProvisioningActive(cancellationJob)
        recoverInterruptedPublish()
        deleteAbandonedStages()
        val status = inspectDirectory(
            modelDirectory,
            checkHashes = true,
            cancellationJob = cancellationJob,
        )
        ensureProvisioningActive(cancellationJob)
        if (status == Status.Ready) deleteAbandonedBackups()
        status
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Status.CorruptOrIncompatible(
            "Model verification failed: ${error.message ?: error.javaClass.simpleName}",
        )
    }

    /**
     * Streams and verifies source files into a private stage before publication. Existing valid
     * installations are never overwritten. [cancellationJob] is a separate cooperative signal:
     * a synchronous file read or native runtime open must return before it can take effect.
     */
    suspend fun provision(
        sources: Map<String, () -> InputStream?>,
        cancellationJob: Job? = null,
        onProgress: (Progress) -> Unit = {},
    ): Status {
        ensureProvisioningActive(cancellationJob)
        if (inspect(cancellationJob) == Status.Ready) return Status.Ready
        ensureProvisioningActive(cancellationJob)
        val missingSources = sourceFiles.map(PinnedModelFile::path).filterNot(sources::containsKey)
        if (missingSources.isNotEmpty()) {
            ensureProvisioningActive(cancellationJob)
            return Status.Missing(missingSources)
        }
        if (!parentDirectory.exists() && !parentDirectory.mkdirs()) {
            return Status.CorruptOrIncompatible("Cannot create the app-private model directory.")
        }

        val stagingDirectory = File(parentDirectory, ".model-install-${UUID.randomUUID()}")
        if (!stagingDirectory.mkdir()) {
            return Status.CorruptOrIncompatible("Cannot create a private model staging directory.")
        }

        val expectedTotal = allFiles.sumOf(PinnedModelFile::sizeBytes)
        var copiedBeforeFile = 0L
        try {
            for (file in sourceFiles) {
                ensureProvisioningActive(cancellationJob)
                val open = sources.getValue(file.path)
                copiedBeforeFile += copyAndVerify(
                    file = file,
                    stagingDirectory = stagingDirectory,
                    cancellationJob = cancellationJob,
                    openInput = open,
                    totalBytes = expectedTotal,
                    copiedBeforeFile = copiedBeforeFile,
                    onProgress = onProgress,
                )
            }
            copyAndVerify(
                file = tokenizerFile,
                stagingDirectory = stagingDirectory,
                cancellationJob = cancellationJob,
                openInput = tokenizerInput,
                totalBytes = expectedTotal,
                copiedBeforeFile = copiedBeforeFile,
                onProgress = onProgress,
            )

            ensureProvisioningActive(cancellationJob)
            val compatibility = inspectDirectory(
                stagingDirectory,
                checkHashes = false,
                cancellationJob = cancellationJob,
            )
            if (compatibility != Status.Ready) return compatibility

            ensureProvisioningActive(cancellationJob)
            publish(stagingDirectory)
            return Status.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Status.CorruptOrIncompatible(
                "Model transfer failed: ${error.message ?: error.javaClass.simpleName}",
            )
        } finally {
            if (stagingDirectory.exists()) stagingDirectory.deleteRecursively()
        }
    }

    private suspend fun inspectDirectory(
        directory: File,
        checkHashes: Boolean,
        cancellationJob: Job?,
    ): Status {
        ensureProvisioningActive(cancellationJob)
        if (!directory.isDirectory) return Status.Missing(allFiles.map(PinnedModelFile::path))

        val missing = allFiles.map(PinnedModelFile::path).filter { !directory.resolve(it).isFile }
        if (missing.isNotEmpty()) return Status.Missing(missing)

        for (file in allFiles) {
            ensureProvisioningActive(cancellationJob)
            val installed = directory.resolve(file.path)
            if (installed.length() != file.sizeBytes) {
                return Status.CorruptOrIncompatible(
                    "${file.path} has ${installed.length()} bytes; expected ${file.sizeBytes}.",
                )
            }
            if (checkHashes) {
                val actualHash = sha256(installed, cancellationJob)
                if (!actualHash.equals(file.sha256, ignoreCase = true)) {
                    return Status.CorruptOrIncompatible(
                        "${file.path} SHA-256 does not match the pinned export.",
                    )
                }
            }
        }

        ensureProvisioningActive(cancellationJob)
        val runtimeState = try {
            checkRuntime(directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Status.CorruptOrIncompatible(
                "Runtime verification failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        ensureProvisioningActive(cancellationJob)
        return when (runtimeState) {
            LayaRuntimeContract.State.Ready -> Status.Ready
            LayaRuntimeContract.State.Missing -> Status.Missing(allFiles.map(PinnedModelFile::path))
            is LayaRuntimeContract.State.CorruptOrIncompatible ->
                Status.CorruptOrIncompatible(runtimeState.reason)
        }
    }

    private suspend fun copyAndVerify(
        file: PinnedModelFile,
        stagingDirectory: File,
        cancellationJob: Job?,
        openInput: () -> InputStream?,
        totalBytes: Long,
        copiedBeforeFile: Long,
        onProgress: (Progress) -> Unit,
    ): Long {
        ensureProvisioningActive(cancellationJob)
        val input = openInput() ?: throw IllegalStateException("${file.path} could not be opened.")
        val outputFile = stagingDirectory.resolve(file.path)
        val outputParent = checkNotNull(outputFile.parentFile)
        if (!outputParent.exists() && !outputParent.mkdirs()) {
            input.close()
            throw IllegalStateException("Cannot create the staging folder for ${file.path}.")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        var lastReported = 0L
        input.use { source ->
            FileOutputStream(outputFile).use { output ->
                while (true) {
                    ensureProvisioningActive(cancellationJob)
                    val count = source.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > file.sizeBytes) {
                        throw IllegalStateException("${file.path} is larger than its pinned size.")
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    if (copied - lastReported >= PROGRESS_STEP_BYTES) {
                        onProgress(
                            Progress(
                                file = file.path,
                                copiedBytes = copiedBeforeFile + copied,
                                totalBytes = totalBytes,
                            ),
                        )
                        lastReported = copied
                    }
                }
                output.fd.sync()
            }
        }

        ensureProvisioningActive(cancellationJob)
        if (copied != file.sizeBytes) {
            throw IllegalStateException("${file.path} has $copied bytes; expected ${file.sizeBytes}.")
        }
        val actualHash = digest.digest().toHex()
        if (!actualHash.equals(file.sha256, ignoreCase = true)) {
            throw IllegalStateException("${file.path} SHA-256 does not match the pinned export.")
        }
        onProgress(
            Progress(
                file = file.path,
                copiedBytes = copiedBeforeFile + copied,
                totalBytes = totalBytes,
            ),
        )
        return copied
    }

    private fun publish(stagingDirectory: File) {
        val backupDirectory = File(parentDirectory, ".model-backup-${UUID.randomUUID()}")
        var previousMoved = false
        try {
            if (modelDirectory.exists()) {
                if (!modelDirectory.renameTo(backupDirectory)) {
                    throw IllegalStateException("Cannot move the previous model aside safely.")
                }
                previousMoved = true
            }
            if (!stagingDirectory.renameTo(modelDirectory)) {
                throw IllegalStateException("Cannot publish the verified model directory.")
            }
            if (previousMoved) backupDirectory.deleteRecursively()
        } catch (error: Exception) {
            if (previousMoved && !modelDirectory.exists()) backupDirectory.renameTo(modelDirectory)
            throw error
        }
    }

    private fun recoverInterruptedPublish() {
        if (modelDirectory.exists() || !parentDirectory.isDirectory) return
        val backup = parentDirectory.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(BACKUP_PREFIX) }
            ?.firstOrNull()
            ?: return
        backup.renameTo(modelDirectory)
    }

    private fun deleteAbandonedStages() {
        parentDirectory.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(STAGING_PREFIX) }
            ?.forEach { it.deleteRecursively() }
    }

    private fun deleteAbandonedBackups() {
        parentDirectory.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(BACKUP_PREFIX) }
            ?.forEach { it.deleteRecursively() }
    }

    private suspend fun sha256(file: File, cancellationJob: Job?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        FileInputStream(file).use { input ->
            while (true) {
                ensureProvisioningActive(cancellationJob)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private suspend fun ensureProvisioningActive(cancellationJob: Job?) {
        currentCoroutineContext().ensureActive()
        cancellationJob?.ensureActive()
    }
    data class Progress(
        val file: String,
        val copiedBytes: Long,
        val totalBytes: Long,
    )

    sealed interface Status {
        data object Ready : Status
        data class Missing(val files: List<String>) : Status
        data class CorruptOrIncompatible(val reason: String) : Status
    }



    companion object {
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val PROGRESS_STEP_BYTES = 16L * 1024L * 1024L
        private const val STAGING_PREFIX = ".model-install-"
        private const val BACKUP_PREFIX = ".model-backup-"

        private val PINNED_SOURCE_FILES = listOf(
            PinnedModelFile(
                path = "laya.onnx",
                sizeBytes = 3_807_291L,
                sha256 = "a874eb254b58b0fcb1e7ad56fbb188c29d64e08c9a46b689433e1f52c66dba1e",
            ),
            PinnedModelFile(
                path = "laya.onnx.data",
                sizeBytes = 1_685_258_240L,
                sha256 = "487746363a8da57bcadb4345352997d22a0fb90d70aa22c6856668d023242aba",
            ),
            PinnedModelFile(
                path = "laya_config.json",
                sizeBytes = 369L,
                sha256 = "5049005dc6ae3ca5e82cc7d85c421357d5c543817300c8e8c5281ddbc69bb561",
            ),
        )
        private val PINNED_TOKENIZER_FILE = PinnedModelFile(
            path = "tokenizer/laya_tokenizer.bin",
            sizeBytes = 1_317_378L,
            sha256 = "09b371ce4b613716bdad862985c37774dfd3ea6373919c8312f9a6cf8bb9ad1b",
        )

    }
}

private fun ByteArray.toHex(): String = buildString(size * 2) {
    val alphabet = "0123456789abcdef"
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(alphabet[value ushr 4])
        append(alphabet[value and 0x0f])
    }
}
