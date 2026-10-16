package de.voci

import de.voci.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** Static-review regressions: real repository/controller/files, synthetic HTTP and archive/platform
 * seams only. These fixtures neither validate an Android archive nor perform a real self-update. */
class R7UpdateReviewTest {
    @Test fun ownedCleanupFailureCannotEscapeOrReplacePrimaryFailure() = runBlocking {
        cleanupCase(replaceWithDirectory = true)
    }

    @Test fun ownedCleanupSuccessPreservesPrimaryFailureControl() = runBlocking {
        cleanupCase(replaceWithDirectory = false)
    }

    private suspend fun cleanupCase(replaceWithDirectory: Boolean) = fixture { f ->
        // Distinct from requireOwned's VERIFICATION error, so cleanup cannot replace the cause.
        val primary = UpdateFailure(UpdateProblem.PLATFORM)
        val entered = CompletableDeferred<OwnedDownload>()
        f.archiveAction = { file, _ ->
            // Repository.verify has already performed its real ownership and hash/size checks.
            entered.complete(OwnedDownload(file, currentCoroutineContext().job))
            f.verifierRelease.await()
            throw primary
        }
        f.controller.resume(f.host)
        val offer = f.offer()
        fixtureRequire(offer.candidate == f.candidate, "unexpected offered candidate")
        f.joinChildren()
        f.controller.consent()
        val owned = bounded("archive verifier entry") { entered.await() }
        fixtureRequire(owned.job in f.supervisor.children.toList(), "verifier is not in the owned controller launch")
        fixtureRequire(!owned.job.isCompleted, "verifier gate did not hold the controller launch")
        fixtureRequire(owned.file.parentFile.canonicalFile == File(f.root, "updates").canonicalFile,
            "download escaped the test-owned updates directory")
        fixtureRequire(owned.file.isFile && fixtureIO("read downloaded bytes") { owned.file.readBytes() }.contentEquals(f.bytes),
            "verifier did not receive the real downloaded bytes")
        fixtureRequire(f.apkRequests.get() == 1 && f.archiveCalls == 1, "unexpected download/verifier count")

        if (replaceWithDirectory) {
            // Only this fixture's fresh file is replaced. Nonempty directory deletion fails even
            // when running as root; no chmod, foreign file, symlink or permission assumption.
            fixtureRequire(owned.file.delete(), "could not remove the fixture download")
            fixtureRequire(owned.file.mkdir(), "could not replace download with a directory")
            fixtureIO("create cleanup obstruction") {
                File(owned.file, "fixture-child").writeText("owned cleanup obstruction")
            }
            fixtureRequire(owned.file.isDirectory && owned.file.listFiles()?.size == 1,
                "nonempty directory obstruction was not created")
            fixtureRequire(!owned.file.delete(), "filesystem unexpectedly deleted the nonempty directory")
        }
        f.verifierRelease.complete(Unit)
        // Failed UI alone is insufficient: join the actual consent launch through its finally and
        // exception handler. A dedicated supervisor prevents the exception from aborting JUnit.
        bounded("consent launch completion including finally") { owned.job.join() }
        f.joinChildren()
        f.assertNoFixtureException()
        assertEquals("R7 UPDATE CLEANUP PRODUCT: preserve the primary failure state",
            UpdateState.Failed(primary.problem, true), f.controller.state.value)
        assertEquals("R7 UPDATE CLEANUP PRODUCT: failed verification must not install", 0, f.host.installs)
        if (!replaceWithDirectory) {
            assertFalse("R7 UPDATE CLEANUP CONTROL: successful cleanup must remove the owned file", owned.file.exists())
        }
        assertTrue(
            "R7 UPDATE CLEANUP PRODUCT: cleanup failure must not escape the controller; escaped=${f.escaped}",
            f.escaped.isEmpty()
        )
    }

    @Test fun installedNoticeRetryStartsANewCheck() = runBlocking {
        fixture { f ->
            f.controller.resume(f.host)
            val offer = f.offer()
            fixtureRequire(offer.candidate == f.candidate, "unexpected offered candidate")
            f.joinChildren()
            fixtureRequire(f.metadataRequests.get() == 2, "initial check did not fetch both metadata assets")
            f.controller.consent()
            val handedOff = bounded("synthetic installer handoff") { f.host.handoff.await() }
            f.joinChildren()
            f.assertNoFixtureException()
            fixtureRequire(f.escaped.isEmpty(), "unexpected launch exception before installer return: ${f.escaped}")
            fixtureRequire(f.controller.state.value == UpdateState.Installing(offer.candidate),
                "controller did not reach Installing")
            fixtureRequire(handedOff.isFile && fixtureIO("read handoff bytes") { handedOff.readBytes() }.contentEquals(f.bytes),
                "platform seam did not receive the downloaded file")
            fixtureRequire(f.host.installs == 1 && f.apkRequests.get() == 1 && f.archiveCalls == 2,
                "expected one download/handoff and both archive verifications")

            // Declared local unit fixture, NOT an Android installation or PackageManager change.
            f.installedVersionCode.set(offer.candidate.versionCode.toLong())
            f.controller.installerReturned()
            assertEquals("FIXTURE: installed-version source and callback must establish Installed",
                UpdateState.Installed, f.controller.state.value)
            val metadataBeforeRetry = f.metadataRequests.get()

            // Scope shares this runBlocking event loop. No suspension occurs between retry and
            // this assertion, so a newly launched check cannot race past Checking to Current.
            // The host stays attached throughout; no resume() can supply an unrelated check.
            f.controller.retry()
            assertEquals("R7 UPDATE RETRY PRODUCT: installed notice retry must start a new check",
                UpdateState.Checking, f.controller.state.value)
            bounded("retry check completion") { f.controller.state.first { it == UpdateState.Current } }
            f.joinChildren()
            f.assertNoFixtureException()
            assertEquals("R7 UPDATE RETRY PRODUCT: retry must fetch fresh release and manifest metadata",
                metadataBeforeRetry + 2, f.metadataRequests.get())
            assertEquals("R7 UPDATE RETRY PRODUCT: current version must not download again", 1, f.apkRequests.get())
            assertEquals("R7 UPDATE RETRY PRODUCT: current version must not launch installer again", 1, f.host.installs)
            assertEquals("R7 UPDATE RETRY PRODUCT: metadata-only retry must not verify another archive", 2, f.archiveCalls)
            assertTrue("R7 UPDATE RETRY PRODUCT: retry must not escape a launch exception: ${f.escaped}", f.escaped.isEmpty())
        }
    }

    private data class OwnedDownload(val file: File, val job: Job)

    private class SyntheticHost : UpdateHost {
        var installs = 0
        val handoff = CompletableDeferred<File>()
        override fun canInstall() = true
        override fun openSettings(): Unit = throw AssertionError("FIXTURE: unexpected settings request")
        override fun install(file: File) {
            installs++
            handoff.complete(file)
        }
    }

    private class Fixture(context: CoroutineContext) {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val candidate = R7UpdateFixtures.candidate(bytes)
        val root: File = fixtureIO("create owned temp directory") {
            Files.createTempDirectory("r7-update-review-").toFile().canonicalFile
        }
        private val manifest = R7UpdateFixtures.manifest(hash = candidate.sha256)
        private val release = R7UpdateFixtures.release(manifest)
        private val manifestUrl = "https://github.com/${ReleasePolicy.REPOSITORY}/releases/download/${candidate.releaseTag}/velu-update.json"
        val installedVersionCode = AtomicLong(candidate.versionCode.toLong() - 1)
        val metadataRequests = AtomicInteger()
        val apkRequests = AtomicInteger()
        val escaped = CopyOnWriteArrayList<Throwable>()
        val supervisor = SupervisorJob()
        private val handler = CoroutineExceptionHandler { _, error -> escaped.add(error) }
        private val scope = CoroutineScope(context.minusKey(Job) + supervisor + handler)
        val verifierRelease = CompletableDeferred<Unit>()
        var archiveCalls = 0
        var archiveAction: suspend (File, ReleaseCandidate) -> Unit = { file, offered -> verifyBytes(file, offered) }
        private val archive = object : ArchiveVerifier {
            override suspend fun verify(file: File, candidate: ReleaseCandidate) {
                archiveCalls++
                archiveAction(file, candidate)
            }
        }
        // SyntheticCalls has no real-network delegate. Only exact fixture URLs get a response;
        // all other requests fail before any I/O, including unexpected methods.
        private val calls = SyntheticCalls { request ->
            fixtureRequire(request.method == "GET", "unexpected request method")
            val data = when (request.url.toString()) {
                ReleasePolicy.LATEST_URL -> { metadataRequests.incrementAndGet(); release.toByteArray() }
                manifestUrl -> { metadataRequests.incrementAndGet(); manifest.toByteArray() }
                candidate.apkUrl -> { apkRequests.incrementAndGet(); bytes }
                else -> throw AssertionError("FIXTURE: refused unexpected URL ${request.url}")
            }
            response(request, data)
        }
        val controller = UpdateController(scope,
            UpdateRepository(UpdateTransport(calls), UpdateFiles(root) { Long.MAX_VALUE }, archive,
                { installedVersionCode.get() }, 36))
        val host = SyntheticHost()

        suspend fun offer(): UpdateState.Offer = bounded("initial Offer") {
            controller.state.first { it is UpdateState.Offer } as UpdateState.Offer
        }

        suspend fun joinChildren() = bounded("owned controller jobs completion") {
            // Progress jobs and advance() jobs may be added while joining the current snapshot.
            while (true) {
                val jobs = supervisor.children.toList()
                if (jobs.isEmpty()) break
                jobs.joinAll()
            }
        }

        fun assertNoFixtureException() {
            escaped.firstOrNull { it is AssertionError }?.let {
                throw AssertionError("FIXTURE: synthetic seam failed", it)
            }
        }

        suspend fun dispose() {
            val exceptionsBeforeClose = escaped.size
            verifierRelease.complete(Unit)
            try {
                bounded("controller close/cleanup completion") { controller.close().join() }
            } finally {
                // Only the fixture supervisor is cancelled. close() joins the controller's
                // separately-owned NonCancellable cleanup chain before deleting fixture files.
                bounded("owned supervisor cancellation") { supervisor.cancelAndJoin() }
                fixtureRequire(fixtureIO("remove owned temp directory") { root.deleteRecursively() },
                    "could not remove the test-owned temp directory")
            }
            fixtureRequire(escaped.size == exceptionsBeforeClose,
                "additional launch exception during teardown: ${escaped.drop(exceptionsBeforeClose)}")
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(currentCoroutineContext())
        var failure: Throwable? = null
        try { block(f) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { f.dispose() }
            catch (cleanup: Throwable) {
                // Teardown trouble must never mask the intended product assertion.
                val original = failure
                if (original != null) original.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private companion object {
        fun <T> fixtureIO(label: String, block: () -> T): T = try { block() }
        catch (error: Exception) { throw AssertionError("FIXTURE: $label failed", error) }

        fun fixtureRequire(condition: Boolean, message: String) {
            if (!condition) throw AssertionError("FIXTURE: $message")
        }

        suspend fun <T> bounded(label: String, block: suspend () -> T): T = try {
            withTimeout(5_000) { block() }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("FIXTURE: timed out waiting for $label", timeout)
        }
    }
}
