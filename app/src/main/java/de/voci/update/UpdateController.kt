package de.voci.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data object NoRelease : UpdateState
    data class Offer(val candidate: ReleaseCandidate) : UpdateState
    data class Downloading(val candidate: ReleaseCandidate, val bytes: Long) : UpdateState
    data class Verifying(val candidate: ReleaseCandidate) : UpdateState
    data class Permission(val candidate: ReleaseCandidate) : UpdateState
    data class Installing(val candidate: ReleaseCandidate) : UpdateState
    data class Failed(val problem: UpdateProblem, val userInitiated: Boolean) : UpdateState
    data object Cancelled : UpdateState
    data object Installed : UpdateState
}

interface UpdateHost {
    fun canInstall(): Boolean
    fun openSettings()
    fun install(file: File)
}

/** Activity/ViewModel main-thread owner. No persisted consent, no mutable Ready injection seam.
 * Rotation retains this controller; a cold process creates it without an install intent.
 */
class UpdateController(private val scope: CoroutineScope, private val repository: UpdateRepository) {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var checked = false
    private var host: UpdateHost? = null
    private var operation: Job? = null
    private var cleanup: Job? = null
    private var generation = 0L
    private var prepared: Pair<ReleaseCandidate, File>? = null
    private var pendingHandoff = false
    private var permissionReturned = false
    private var installerReturned = false
    private var closed = false

    fun resume(host: UpdateHost) {
        if (closed) return
        this.host = host
        if (!checked) { checked = true; check() }
        if (mutableState.value is UpdateState.Permission && permissionReturned) {
            permissionReturned = false
            if (!host.canInstall()) fail(UpdateProblem.PERMISSION, true)
            else { pendingHandoff = true; advance() }
        } else if (mutableState.value is UpdateState.Installing && installerReturned) {
            finishInstaller()
        } else if (pendingHandoff) advance()
    }
    fun pause() { host = null }
    fun stop(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        when (mutableState.value) {
            is UpdateState.Downloading, is UpdateState.Verifying -> cancel()
            // Only these intentional external activities retain the user's consent across onStop.
            is UpdateState.Permission, is UpdateState.Installing -> Unit
            else -> Unit
        }
    }
    fun defer() {
        if (mutableState.value is UpdateState.Offer) mutableState.value = UpdateState.Idle
    }
    fun dismiss() {
        when (mutableState.value) {
            is UpdateState.Failed, UpdateState.Cancelled, UpdateState.Current, UpdateState.NoRelease, UpdateState.Installed ->
                mutableState.value = UpdateState.Idle
            else -> Unit
        }
    }
    fun retry() {
        if (host == null || closed) return
        when (mutableState.value) {
            is UpdateState.Failed, UpdateState.Cancelled, UpdateState.NoRelease, UpdateState.Current, UpdateState.Idle,
            UpdateState.Installed -> check()
            else -> Unit
        }
    }
    private fun check() {
        val id = ++generation
        operation?.cancel()
        mutableState.value = UpdateState.Checking
        operation = scope.launch {
            try {
                val result = repository.check()
                if (id != generation || closed) return@launch
                mutableState.value = when (result) {
                    is UpdateCheck.Available -> UpdateState.Offer(result.candidate)
                    UpdateCheck.Current -> UpdateState.Current
                    UpdateCheck.NoRelease -> UpdateState.NoRelease
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (id == generation) fail(problem(e), false) }
        }
    }
    fun consent() {
        if (host == null || closed) return
        val candidate = (mutableState.value as? UpdateState.Offer)?.candidate ?: return
        val id = ++generation
        mutableState.value = UpdateState.Downloading(candidate, 0)
        operation = scope.launch {
            var owned: File? = null
            try {
                val file = repository.download(candidate) { bytes ->
                    // Transport reports from IO. All controller decisions remain on the owner scope.
                    scope.launch {
                        if (id == generation && mutableState.value is UpdateState.Downloading)
                            mutableState.value = UpdateState.Downloading(candidate, bytes)
                    }
                }
                owned = file
                ensureActive()
                if (id != generation || closed) return@launch
                mutableState.value = UpdateState.Verifying(candidate)
                repository.verify(file, candidate)
                ensureActive()
                if (id != generation || closed) return@launch
                prepared = candidate to file; owned = null
                pendingHandoff = true
                operation = null
                advance()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (id == generation) fail(problem(e), true) }
            finally {
                owned?.let { file ->
                    withContext(NonCancellable) {
                        // Best-effort cleanup; discard retains its IO/ownership checks. A cleanup
                        // exception must neither mask the primary failure/cancellation nor change
                        // a successor's state. Fatal JVM errors are deliberately not caught.
                        try { repository.discard(file) }
                        catch (_: Exception) { }
                    }
                }
            }
        }
    }
    private fun advance() {
        val currentHost = host ?: return
        val (candidate, file) = prepared ?: return
        if (!pendingHandoff || closed) return
        pendingHandoff = false
        if (!currentHost.canInstall()) {
            mutableState.value = UpdateState.Permission(candidate)
            permissionReturned = false
            try { currentHost.openSettings() }
            catch (e: Exception) { fail(problem(e), true) }
            return
        }
        val id = ++generation
        mutableState.value = UpdateState.Verifying(candidate)
        operation = scope.launch {
            try {
                // Re-read the exact file, bytes, archive and installed signers after Settings and
                // after any rotation/background pause, immediately before creating the content URI.
                repository.verify(file, candidate)
                ensureActive()
                if (id != generation || closed) return@launch
                val foreground = host
                if (foreground == null) { pendingHandoff = true; return@launch }
                if (!foreground.canInstall()) { fail(UpdateProblem.PERMISSION, true); return@launch }
                mutableState.value = UpdateState.Installing(candidate)
                installerReturned = false
                // Ownership transfers before launch. Never delete an APK an external installer
                // may still be reading, even if its result is cancellation or the Activity dies.
                prepared = null
                foreground.install(file)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (id == generation) fail(problem(e), true) }
        }
    }
    fun settingsReturned() {
        if (mutableState.value !is UpdateState.Permission) return
        permissionReturned = true
        host?.let { resume(it) }
    }
    fun installerReturned() {
        if (mutableState.value !is UpdateState.Installing) return
        installerReturned = true
        if (host != null) finishInstaller()
    }
    private fun finishInstaller() {
        val candidate = (mutableState.value as? UpdateState.Installing)?.candidate ?: return
        installerReturned = false
        mutableState.value = if (repository.installedVersionCode() >= candidate.versionCode.toLong())
            UpdateState.Installed else UpdateState.Cancelled
    }
    fun cancel() {
        if (mutableState.value !is UpdateState.Downloading && mutableState.value !is UpdateState.Verifying &&
            mutableState.value !is UpdateState.Permission) return
        abandon()
        mutableState.value = UpdateState.Cancelled
    }
    private fun fail(problem: UpdateProblem, user: Boolean) {
        abandon()
        mutableState.value = UpdateState.Failed(problem, user)
    }
    private fun abandon(): Job {
        generation++
        val previous = operation
        operation = null; previous?.cancel()
        pendingHandoff = false; permissionReturned = false
        val file = prepared?.second; prepared = null
        val earlierCleanup = cleanup
        val cleanupGeneration = generation
        return scope.launch(NonCancellable) {
            previous?.join()
            earlierCleanup?.join()
            if (file != null) try { repository.discard(file) }
            catch (_: Exception) {
                if (!closed && generation == cleanupGeneration) mutableState.value = UpdateState.Failed(UpdateProblem.SPACE, true)
            }
        }.also { cleanup = it }
    }
    fun close(): Job { closed = true; host = null; return abandon() }
    private fun problem(e: Exception) = (e as? UpdateFailure)?.problem ?: UpdateProblem.PLATFORM
}
