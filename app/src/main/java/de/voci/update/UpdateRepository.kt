package de.voci.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface ArchiveVerifier {
    suspend fun verify(file: File, candidate: ReleaseCandidate)
}

sealed interface UpdateCheck {
    data class Available(val candidate: ReleaseCandidate) : UpdateCheck
    data object Current : UpdateCheck
    data object NoRelease : UpdateCheck
}

class UpdateRepository(
    private val transport: UpdateTransport,
    private val files: UpdateFiles,
    private val archive: ArchiveVerifier,
    private val installedCode: () -> Long,
    private val sdkInt: Int
) {
    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val releaseText = try { transport.text(ReleasePolicy.LATEST_URL, ReleasePolicy.RELEASE_LIMIT) }
        catch (e: UpdateFailure) { if (e.problem == UpdateProblem.NO_RELEASE) return@withContext UpdateCheck.NoRelease else throw e }
        val release = ReleasePolicy.release(releaseText)
        if (!release.stable) return@withContext UpdateCheck.NoRelease
        val asset = release.manifest()
        requireUpdate(asset.size <= ReleasePolicy.MANIFEST_LIMIT)
        val manifest = transport.text(asset.url, ReleasePolicy.MANIFEST_LIMIT, asset.size)
        val candidate = ReleasePolicy.candidate(releaseText, manifest, installedCode(), sdkInt)
        candidate?.let(UpdateCheck::Available) ?: UpdateCheck.Current
    }
    suspend fun download(candidate: ReleaseCandidate, progress: (Long) -> Unit) =
        UpdateDownloader(transport, files).download(candidate, progress)
    suspend fun verify(file: File, candidate: ReleaseCandidate) {
        withContext(Dispatchers.IO) { files.requireOwned(file); verifyBytes(file, candidate) }
        archive.verify(file, candidate)
    }
    suspend fun discard(file: File) = withContext(Dispatchers.IO) { if (file.exists()) files.discard(file) }
    fun installedVersionCode() = installedCode()
}
