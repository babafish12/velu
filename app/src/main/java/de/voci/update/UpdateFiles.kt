package de.voci.update

import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Owns only fresh files beneath cache/updates. Never sweeps caches or installer-owned APKs. */
class UpdateFiles(cacheDir: File, private val available: (File) -> Long = { it.usableSpace }) {
    private val root = File(cacheDir.canonicalFile, "updates")
    fun fresh(size: Long): File {
        requireUpdate(size in 1..ReleasePolicy.APK_LIMIT)
        requireUpdate((root.isDirectory || root.mkdir()) && root.canonicalFile == root.absoluteFile, UpdateProblem.SPACE)
        requireUpdate(available(root) >= size + 8L * 1024 * 1024, UpdateProblem.SPACE)
        return storageIO { File.createTempFile("velu-${UUID.randomUUID()}-", ".part", root) }
    }
    fun requireOwned(file: File) {
        requireUpdate(file.parentFile?.canonicalFile == root.canonicalFile && file.canonicalFile == file.absoluteFile &&
            file.name.startsWith("velu-") && file.extension in setOf("part", "apk") && file.isFile, UpdateProblem.VERIFICATION)
    }
    fun promote(part: File): File {
        requireOwned(part)
        requireUpdate(part.extension == "part", UpdateProblem.VERIFICATION)
        val final = File(root, part.name.removeSuffix(".part") + ".apk")
        requireUpdate(!final.exists() && part.renameTo(final), UpdateProblem.SPACE)
        return final
    }
    fun discard(file: File) { requireOwned(file); requireUpdate(file.delete(), UpdateProblem.SPACE) }
}

class UpdateDownloader(private val transport: UpdateTransport, private val files: UpdateFiles) {
    suspend fun download(candidate: ReleaseCandidate, progress: (Long) -> Unit): File {
        var returnedFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                UpdateUrls.requireAsset(candidate.apkUrl, candidate.releaseTag, candidate.apkUrl.substringAfterLast('/'))
                val part = files.fresh(candidate.sizeBytes)
                var promoted: File? = null
                var delivered = false
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    transport.read(candidate.apkUrl, ReleasePolicy.APK_LIMIT, candidate.sizeBytes) { input, alive ->
                        storageIO { UpdateOutput(part) }.use { output ->
                            val hashing = DigestOutputStream(output, digest)
                            copyBounded(input, hashing, ReleasePolicy.APK_LIMIT, candidate.sizeBytes, alive, progress)
                            hashing.flush(); storageIO { output.fd.sync() }
                        }
                    }
                    requireUpdate(digest.digest().hex() == candidate.sha256, UpdateProblem.VERIFICATION)
                    verifyBytes(part, candidate)
                    coroutineContext.ensureActive()
                    val final = files.promote(part)
                    promoted = final
                    coroutineContext.ensureActive()
                    returnedFile = final
                    delivered = true
                    final
                } finally {
                    if (part.exists()) files.discard(part)
                    if (!delivered) promoted?.let { if (it.exists()) files.discard(it) }
                }
            }
        } catch (e: Exception) {
            // withContext has prompt cancellation on dispatcher return: even a completed rename
            // must be removed if its result never reached the controller.
            withContext(NonCancellable + Dispatchers.IO) {
                returnedFile?.let { if (it.exists()) files.discard(it) }
            }
            throw e
        }
    }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

/** Used again immediately before installation; bounded even if a private cache file was replaced. */
fun verifyBytes(file: File, candidate: ReleaseCandidate): Unit = try {
    requireUpdate(candidate.sizeBytes in 1..ReleasePolicy.APK_LIMIT && file.isFile && file.length() == candidate.sizeBytes,
        UpdateProblem.VERIFICATION)
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            total += n
            requireUpdate(total <= candidate.sizeBytes, UpdateProblem.VERIFICATION)
            digest.update(buffer, 0, n)
        }
    }
    requireUpdate(total == candidate.sizeBytes && file.length() == total && digest.digest().hex() == candidate.sha256,
        UpdateProblem.VERIFICATION)
} catch (_: IOException) { throw UpdateFailure(UpdateProblem.VERIFICATION) }

private inline fun <T> storageIO(block: () -> T): T = try { block() }
catch (_: IOException) { throw UpdateFailure(UpdateProblem.SPACE) }

/** Disk errors must not be mislabeled as a lost connection by the transport's read boundary. */
private class UpdateOutput(file: File) : FileOutputStream(file) {
    override fun write(value: Int) = storageIO { super.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) = storageIO { super.write(bytes, offset, length) }
    override fun flush() = storageIO { super.flush() }
    override fun close() = storageIO { super.close() }
}
