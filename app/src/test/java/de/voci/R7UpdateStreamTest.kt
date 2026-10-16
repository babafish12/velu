package de.voci

import de.voci.update.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/** Synthetic Call.Factory fails before any real network; actual transport + streaming code runs. */
class R7UpdateStreamTest {
    @Test fun streamedDownloadHashSizeAtomicRenameAndFailureCleanup() = runBlocking {
        val root = Files.createTempDirectory("r7-updates").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4); val candidate = R7UpdateFixtures.candidate(bytes)
            val transport = UpdateTransport(SyntheticCalls { request -> response(request, bytes) })
            val store = UpdateFiles(root) { Long.MAX_VALUE }
            val progress = mutableListOf<Long>()
            val file = UpdateDownloader(transport, store).download(candidate) { progress += it }
            assertArrayEquals(bytes, file.readBytes()); assertEquals("apk", file.extension)
            assertEquals(4L, progress.last()); assertFalse(root.walk().any { it.extension == "part" })
            file.delete()
            for (bad in listOf(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3, 4, 5), byteArrayOf(9, 8, 7, 6))) {
                try {
                    UpdateDownloader(UpdateTransport(SyntheticCalls { response(it, bad) }), store).download(candidate) {}
                    fail("bad stream accepted")
                } catch (_: UpdateFailure) { }
                assertEquals(0, File(root, "updates").listFiles()!!.size)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun metadataBodyLimitsStatusAndRedirectFailures() = runBlocking {
        suspend fun rejected(code: Int, data: ByteArray, headers: Headers = Headers.headersOf()): UpdateProblem {
            try {
                UpdateTransport(SyntheticCalls { response(it, data, code, headers) }).bytes(ReleasePolicy.LATEST_URL, 8)
                error("unexpected success")
            } catch (e: UpdateFailure) { return e.problem }
        }
        assertEquals(UpdateProblem.NO_RELEASE, rejected(404, byteArrayOf()))
        assertEquals(UpdateProblem.QUOTA, rejected(403, byteArrayOf()))
        assertEquals(UpdateProblem.QUOTA, rejected(429, byteArrayOf()))
        assertEquals(UpdateProblem.SERVER, rejected(500, byteArrayOf()))
        assertEquals(UpdateProblem.INVALID, rejected(200, ByteArray(9)))
        assertEquals(UpdateProblem.INVALID, rejected(302, byteArrayOf(), Headers.headersOf("Location", "http://evil.example/a")))
        assertEquals(UpdateProblem.INVALID, rejected(302, byteArrayOf(), Headers.headersOf("Location", ReleasePolicy.LATEST_URL)))
    }
    @Test fun officialRedirectsAreBoundedAndEveryRequestRemainsUnauthenticated() = runBlocking {
        val candidate = R7UpdateFixtures.candidate()
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/123/abc-123?sig=synthetic"
        val calls = SyntheticCalls { request ->
            if (request.url.toString() == candidate.apkUrl)
                response(request, byteArrayOf(), 302, Headers.headersOf("Location", cdn, "Set-Cookie", "synthetic=must-not-forward"))
            else response(request, byteArrayOf(1, 2, 3, 4))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), UpdateTransport(calls).bytes(candidate.apkUrl, 4, 4))
        assertEquals(2, calls.requests.size)
        calls.requests.forEach {
            assertNull(it.header("Authorization")); assertNull(it.header("Cookie")); assertNull(it.header("Proxy-Authorization"))
        }
        val loop = SyntheticCalls { response(it, byteArrayOf(), 302, Headers.headersOf("Location", cdn)) }
        try { UpdateTransport(loop).bytes(candidate.apkUrl, 4); fail("unbounded redirects") }
        catch (e: UpdateFailure) { assertEquals(UpdateProblem.INVALID, e.problem) }
        assertEquals(4, loop.requests.size)
        val badHop = SyntheticCalls {
            response(it, byteArrayOf(), 302, Headers.headersOf("Location",
                if (it.url.toString() == candidate.apkUrl) cdn else "https://raw.githubusercontent.com/other/file"))
        }
        try { UpdateTransport(badHop).bytes(candidate.apkUrl, 4); fail("bad second hop") }
        catch (e: UpdateFailure) { assertEquals(UpdateProblem.INVALID, e.problem) }
        assertEquals(2, badHop.requests.size)
    }
    @Test fun unknownLengthStreamsEnforceActualCountAndDeclaredLengthMustMatch() = runBlocking {
        val url = R7UpdateFixtures.candidate().apkUrl
        for (declared in listOf(-1L, 4L)) {
            val calls = SyntheticCalls { request ->
                response(request, byteArrayOf()).newBuilder().body(object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = declared
                    private val data = java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)).source().buffer()
                    override fun source() = data
                }).build()
            }
            try { UpdateTransport(calls).bytes(url, 4); fail("actual count ignored") }
            catch (e: UpdateFailure) { assertEquals(UpdateProblem.INVALID, e.problem) }
        }
        val short = SyntheticCalls { request ->
            response(request, byteArrayOf()).newBuilder().body(object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = 4L
                private val data = java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)).source().buffer()
                override fun source() = data
            }).build()
        }
        try { UpdateTransport(short).bytes(url, 4); fail("declared size ignored") }
        catch (e: UpdateFailure) { assertEquals(UpdateProblem.INVALID, e.problem) }
    }
    @Test fun repositorySeparatesCurrentNoReleaseQuotaOfflineAndMalformed() = runBlocking {
        val root = Files.createTempDirectory("r7-check").toFile()
        val verifier = object : ArchiveVerifier {
            override suspend fun verify(file: File, candidate: ReleaseCandidate) = error("metadata must not verify/install an APK")
        }
        try {
            fun repository(calls: SyntheticCalls) = UpdateRepository(UpdateTransport(calls), UpdateFiles(root), verifier, { 3L }, 36)
            val m = R7UpdateFixtures.manifest(); val r = R7UpdateFixtures.release(m)
            val current = SyntheticCalls { response(it, (if (it.url.toString() == ReleasePolicy.LATEST_URL) r else m).toByteArray()) }
            assertEquals(UpdateCheck.Current, repository(current).check())
            assertEquals(2, current.requests.size)
            assertEquals(UpdateCheck.NoRelease, repository(SyntheticCalls { response(it, byteArrayOf(), 404) }).check())
            for ((code, expected) in listOf(403 to UpdateProblem.QUOTA, 429 to UpdateProblem.QUOTA, 500 to UpdateProblem.SERVER, 200 to UpdateProblem.INVALID)) {
                try { repository(SyntheticCalls { response(it, "{}".toByteArray(), code) }).check(); fail("error became current") }
                catch (e: UpdateFailure) { assertEquals(expected, e.problem) }
            }
            try { repository(SyntheticCalls { throw java.net.SocketTimeoutException("synthetic") }).check(); fail("timeout became current") }
            catch (e: UpdateFailure) { assertEquals(UpdateProblem.OFFLINE, e.problem) }
            val oversized = SyntheticCalls { response(it, ByteArray(ReleasePolicy.RELEASE_LIMIT + 1)) }
            try { repository(oversized).check(); fail("release cap ignored") }
            catch (e: UpdateFailure) { assertEquals(UpdateProblem.INVALID, e.problem) }
        } finally { root.deleteRecursively() }
    }
    @Test fun delayedBodyCancellationCancelsUnderlyingCallAndDeletesPartial() = runBlocking {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val calls = SyntheticCalls(onCancel = { cancelled.countDown() }) { request ->
            val input = object : java.io.InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    check(cancelled.await(5, TimeUnit.SECONDS)) { "Call.cancel was not invoked during blocked body read" }
                    throw IOException("synthetic cancelled body")
                }
            }
            response(request, byteArrayOf()).newBuilder().body(object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source() = input.source().buffer()
            }).build()
        }
        val root = Files.createTempDirectory("r7-cancel").toFile()
        try {
            val job = launch(Dispatchers.Default) {
                UpdateDownloader(UpdateTransport(calls), UpdateFiles(root) { Long.MAX_VALUE })
                    .download(R7UpdateFixtures.candidate()) {}
                fail("cancelled body produced file")
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertEquals(0L, cancelled.count)
            assertFalse(root.walk().any { it.extension in setOf("part", "apk") })
        } finally { root.deleteRecursively() }
    }
    @Test fun insufficientSpaceAndReadFailureLeaveNoPartial() = runBlocking {
        val root = Files.createTempDirectory("r7-space").toFile()
        try {
            val calls = SyntheticCalls { throw IOException("synthetic offline") }
            try {
                UpdateDownloader(UpdateTransport(calls), UpdateFiles(root) { 0 }).download(R7UpdateFixtures.candidate()) {}
                fail("space check missing")
            } catch (e: UpdateFailure) { assertEquals(UpdateProblem.SPACE, e.problem) }
            assertEquals(0, calls.requests.size)
            try {
                UpdateDownloader(UpdateTransport(calls), UpdateFiles(root) { Long.MAX_VALUE }).download(R7UpdateFixtures.candidate()) {}
                fail("read failure accepted")
            } catch (e: UpdateFailure) { assertEquals(UpdateProblem.OFFLINE, e.problem) }
            assertFalse(root.walk().any { it.extension in setOf("part", "apk") })
        } finally { root.deleteRecursively() }
    }
}

internal fun response(request: Request, bytes: ByteArray, code: Int = 200, headers: Headers = Headers.headersOf()): Response =
    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
        .headers(headers).body(bytes.toResponseBody()).build()

internal class SyntheticCalls(
    private val onCancel: () -> Unit = {},
    private val answer: (Request) -> Response
) : Call.Factory {
    val requests = mutableListOf<Request>()
    override fun newCall(request: Request): Call {
        requests += request
        return object : Call {
            private var cancelled = false
            private var taggedRequest = request
            private var eventListener = EventListener.NONE
            override fun request() = request
            override fun execute() = answer(request)
            override fun enqueue(responseCallback: Callback) = error("synthetic synchronous transport only")
            override fun cancel() { cancelled = true; onCancel(); eventListener.canceled(this) }
            override fun isExecuted() = true
            override fun isCanceled() = cancelled
            override fun timeout() = Timeout.NONE
            override fun addEventListener(eventListener: EventListener) = synchronized(this) {
                this.eventListener = this.eventListener.plus(eventListener)
            }
            override fun <T : Any> tag(type: KClass<T>): T? = synchronized(this) { taggedRequest.tag(type) }
            override fun <T> tag(type: Class<out T>): T? = synchronized(this) { taggedRequest.tag(type) }
            override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
                tag(type.java, computeIfAbsent)
            override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = synchronized(this) {
                taggedRequest.tag(type) ?: computeIfAbsent().also {
                    taggedRequest = taggedRequest.newBuilder().tag(type, it).build()
                }
            }
            override fun clone(): Call = newCall(request)
        }
    }
}
