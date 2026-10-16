package de.voci

import de.voci.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Synthetic HTTP bytes, real metadata/parser/stream/controller. Archive seam is explicitly fake;
 * actual PackageManager coverage is R7UpdateArchiveTest, never inferred from these tests. */
class R7UpdateControllerTest {
    @Test fun automaticCheckRotationDeferAndRapidConsent() = runBlocking {
        fixture { controller, host, calls, verifier ->
            controller.resume(host)
            await(controller) { it is UpdateState.Offer }
            assertEquals(2, calls.requests.size)
            controller.pause(); controller.stop(changingConfigurations = true); controller.resume(host)
            assertEquals(2, calls.requests.size)
            controller.defer(); controller.consent()
            assertTrue(controller.state.value is UpdateState.Idle)
            assertEquals(2, calls.requests.size)
            controller.retry(); await(controller) { it is UpdateState.Offer }
            repeat(5) { controller.consent() }
            await(controller) { it is UpdateState.Installing }
            assertEquals(1, host.installs)
            assertEquals(1, calls.requests.count { it.url.toString().endsWith("/velu.apk") })
            assertTrue(verifier.calls >= 2)
            controller.resume(host); controller.settingsReturned(); controller.consent()
            assertEquals(1, host.installs)
        }
    }
    @Test fun permissionReturnRetainsConsentButReverifiesExactlyOnce() = runBlocking {
        fixture { controller, host, _, verifier ->
            host.allowed = false
            controller.resume(host); await(controller) { it is UpdateState.Offer }; controller.consent()
            await(controller) { it is UpdateState.Permission }
            assertEquals(1, host.settings)
            controller.pause(); controller.stop(false)
            controller.stop(true); controller.resume(host)
            assertEquals(1, host.settings); assertEquals(0, host.installs)
            controller.pause(); host.allowed = true
            controller.settingsReturned()
            val before = verifier.calls
            assertEquals(0, host.installs)
            controller.resume(host)
            await(controller) { it is UpdateState.Installing }
            assertTrue(verifier.calls > before)
            controller.settingsReturned(); controller.resume(host)
            assertEquals(1, host.installs)
        }
    }
    @Test fun permissionDeniedAndMutatedFileAreRecoverableNeverInstalled() = runBlocking {
        fixture { controller, host, _, verifier ->
            host.allowed = false
            controller.resume(host); await(controller) { it is UpdateState.Offer }; controller.consent()
            await(controller) { it is UpdateState.Permission }
            controller.pause(); controller.stop(false); controller.settingsReturned(); controller.resume(host)
            await(controller) { it is UpdateState.Failed }
            assertEquals(UpdateProblem.PERMISSION, (controller.state.value as UpdateState.Failed).problem)
            assertEquals(0, host.installs)
            controller.retry(); await(controller) { it is UpdateState.Offer }; controller.consent()
            await(controller) { it is UpdateState.Permission }
            verifier.lastFile!!.writeBytes(byteArrayOf(9, 8, 7, 6))
            host.allowed = true; controller.settingsReturned()
            await(controller) { it is UpdateState.Failed }
            assertEquals(UpdateProblem.VERIFICATION, (controller.state.value as UpdateState.Failed).problem)
            assertEquals(0, host.installs)
        }
    }
    @Test fun homeAndCancelInvalidateDelayedVerificationAndColdRestartHasNoIntent() = runBlocking {
        fixture { controller, host, _, verifier ->
            verifier.gate = CompletableDeferred()
            verifier.ignoreCancellation = true
            controller.resume(host); await(controller) { it is UpdateState.Offer }; controller.consent()
            await(controller) { it is UpdateState.Verifying }
            controller.pause(); controller.stop(false)
            verifier.gate!!.complete(Unit)
            controller.resume(host); yield()
            assertEquals(0, host.installs)
            assertTrue(controller.state.value is UpdateState.Cancelled)
            controller.retry(); await(controller) { it is UpdateState.Offer }
            controller.consent(); controller.cancel()
            delay(50)
            assertEquals(0, host.installs)
            assertTrue(controller.state.value is UpdateState.Cancelled)
        }
        fixture { fresh, host, calls, _ ->
            fresh.resume(host); await(fresh) { it is UpdateState.Offer }
            assertEquals(0, host.installs)
            assertFalse(calls.requests.any { it.url.toString().endsWith("/velu.apk") })
        }
    }
    @Test fun rotationDuringVerificationRetainsConsentWithoutBackgroundHandoff() = runBlocking {
        fixture { controller, host, _, verifier ->
            verifier.gate = CompletableDeferred()
            controller.resume(host); await(controller) { it is UpdateState.Offer }; controller.consent()
            await(controller) { it is UpdateState.Verifying }
            controller.pause(); controller.stop(true)
            verifier.gate!!.complete(Unit)
            delay(50)
            assertEquals(0, host.installs)
            controller.resume(host)
            await(controller) { it is UpdateState.Installing }
            assertEquals(1, host.installs)
            controller.installerReturned()
            assertTrue(controller.state.value is UpdateState.Cancelled)
        }
    }
    @Test fun missingSettingsOrInstallerDoesNotClaimSuccess() = runBlocking {
        for (permission in listOf(true, false)) fixture { controller, host, _, _ ->
            host.allowed = permission
            host.failLaunch = true
            controller.resume(host); await(controller) { it is UpdateState.Offer }; controller.consent()
            await(controller) { it is UpdateState.Failed }
            assertEquals(UpdateProblem.PLATFORM, (controller.state.value as UpdateState.Failed).problem)
        }
    }
    private suspend fun await(c: UpdateController, predicate: (UpdateState) -> Boolean) =
        withTimeout(5_000) { c.state.first(predicate) }

    private suspend fun fixture(block: suspend (UpdateController, Host, SyntheticCalls, SyntheticArchive) -> Unit) = coroutineScope {
        val root = Files.createTempDirectory("r7-controller").toFile()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val candidate = R7UpdateFixtures.candidate(bytes)
        val manifest = R7UpdateFixtures.manifest(hash = candidate.sha256)
        val release = R7UpdateFixtures.release(manifest)
        val calls = SyntheticCalls { request ->
            response(request, when {
                request.url.toString() == ReleasePolicy.LATEST_URL -> release.toByteArray()
                request.url.toString().endsWith("/velu-update.json") -> manifest.toByteArray()
                request.url.toString() == candidate.apkUrl -> bytes
                else -> error("fixture refused unexpected request")
            })
        }
        val files = UpdateFiles(root) { Long.MAX_VALUE }
        val verifier = SyntheticArchive()
        val work = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val controller = UpdateController(work, UpdateRepository(UpdateTransport(calls), files, verifier, { 2L }, 36))
        try { block(controller, Host(), calls, verifier) }
        finally { controller.close().join(); work.coroutineContext[Job]!!.cancelAndJoin(); root.deleteRecursively() }
    }
    private class Host : UpdateHost {
        var allowed = true; var settings = 0; var installs = 0; var failLaunch = false
        override fun canInstall() = allowed
        override fun openSettings() { if (failLaunch) throw UpdateFailure(UpdateProblem.PLATFORM); settings++ }
        override fun install(file: File) { if (failLaunch) throw UpdateFailure(UpdateProblem.PLATFORM); installs++ }
    }
    private class SyntheticArchive : ArchiveVerifier {
        var calls = 0; var gate: CompletableDeferred<Unit>? = null; var lastFile: File? = null
        var ignoreCancellation = false
        override suspend fun verify(file: File, candidate: ReleaseCandidate) {
            calls++; lastFile = file
            if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
            verifyBytes(file, candidate)
        }
    }
}
