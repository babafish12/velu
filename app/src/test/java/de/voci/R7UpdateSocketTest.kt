package de.voci

import de.voci.update.UpdateDownloader
import de.voci.update.UpdateFiles
import de.voci.update.UpdateTransport
import kotlinx.coroutines.*
import okhttp3.*
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Controlled local socket fixture, NOT GitHub/release acceptance. No synthetic Call or body.
 * Only the exact canonical fixture APK request can be rewritten, before creating a real Call.
 * The server sends real HTTP headers and withholds every body byte until peer cancellation.
 */
class R7UpdateSocketTest {
    @Test fun cancellationDuringRealSocketBodyReadTerminatesCallAndRemovesPartial() = runBlocking {
        val root = Files.createTempDirectory("r7-update-socket-").toFile()
        val listener = ServerSocket()
        val serverExecutor = Executors.newSingleThreadExecutor()
        val clientExecutor = Executors.newSingleThreadExecutor()
        val peer = AtomicReference<Socket?>()
        val closing = AtomicBoolean(false)
        val actualCall = AtomicReference<Call?>()
        val bodyReader = AtomicReference<Thread?>()
        val readFailure = AtomicReference<IOException?>()
        val bodyReadEntered = CountDownLatch(1)
        val bodyReadExited = CountDownLatch(1)
        val headersSent = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val returnedApk = AtomicReference<File?>()
        val downloadFailure = AtomicReference<Throwable?>()
        val progress = AtomicLong(0)
        var client: OkHttpClient? = null
        var download: Job? = null
        var primaryFailure: Throwable? = null
        try {
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            listener.bind(InetSocketAddress(loopback, 0), 1)
            listener.soTimeout = 10_000
            val candidate = R7UpdateFixtures.candidate()
            val localUrl = "http://127.0.0.1:${listener.localPort}/r7-withheld.apk"
            val peerTermination = serverExecutor.submit<String> {
                listener.accept().use { socket ->
                    peer.set(socket)
                    check(!closing.get()) { "fixture teardown has begun" }
                    socket.soTimeout = 20_000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (!header.endsWith("\r\n\r\n")) {
                        check(header.length < 16 * 1024 && System.nanoTime() < deadline) { "request headers exceeded fixture bounds" }
                        val next = input.read()
                        check(next != -1) { "client closed before request headers" }
                        header.append(next.toChar())
                    }
                    check(header.lineSequence().first() == "GET /r7-withheld.apk HTTP/1.1")
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Length: ${candidate.sizeBytes}\r\n" +
                            "Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    headersSent.countDown()
                    // No body bytes and no timed server-side close: only EOF/reset proves peer exit.
                    try {
                        check(input.read() == -1) { "unexpected client bytes after GET" }
                        "EOF"
                    } catch (reset: SocketException) {
                        check(!socket.isClosed) { "fixture closed the socket before peer termination" }
                        "RESET"
                    }
                }
            }
            val realClient = UpdateTransport.publicClient().newBuilder()
                .dispatcher(Dispatcher(clientExecutor))
                .connectionPool(ConnectionPool())
                .proxy(Proxy.NO_PROXY)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (hostname != "127.0.0.1") throw UnknownHostException("socket fixture forbids other hosts")
                        return listOf(loopback)
                    }
                })
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun canceled(call: Call) { callCancelled.countDown() }
                })
                .addNetworkInterceptor { chain ->
                    check(chain.request().url.toString() == localUrl) { "unexpected network destination" }
                    val response = chain.proceed(chain.request())
                    val body = checkNotNull(response.body)
                    // Observe the real response source, without substituting headers/data/errors.
                    // In OkHttp 5.5.0 responseBodyStart fires AFTER delegate.read returns, so it
                    // cannot detect this intentionally withheld first byte.
                    val observed = object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            bodyReader.set(Thread.currentThread())
                            bodyReadEntered.countDown()
                            try {
                                return super.read(sink, byteCount)
                            } catch (failure: IOException) {
                                readFailure.set(failure)
                                throw failure
                            } finally {
                                bodyReadExited.countDown()
                            }
                        }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = observed
                    }).build()
                }.build()
            client = realClient
            val calls = object : Call.Factory {
                override fun newCall(request: Request): Call {
                    require(request.url.toString() == candidate.apkUrl && request.method == "GET" && request.body == null) {
                        "socket fixture rejects every request except its canonical APK GET"
                    }
                    require(listOf("Authorization", "Cookie", "Proxy-Authorization", "Host").all { request.header(it) == null })
                    check(actualCall.get() == null) { "socket fixture allows exactly one real Call" }
                    return realClient.newCall(request.newBuilder().url(localUrl).build()).also {
                        check(actualCall.compareAndSet(null, it)) { "concurrent unexpected Call" }
                    }
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                calls.newCall(Request.Builder().url(candidate.apkUrl + "?unexpected=1").build())
            }
            assertNull("rejected URL must not create a real Call", actualCall.get())
            val running = launch(Dispatchers.Default) {
                try {
                    returnedApk.set(UpdateDownloader(UpdateTransport(calls), UpdateFiles(root) { Long.MAX_VALUE })
                        .download(candidate) { progress.set(it) })
                } catch (failure: Throwable) {
                    // Retain failures for assertions on the test thread, including cancellation.
                    downloadFailure.set(failure)
                }
            }
            download = running
            assertTrue("real response-body source was never read", bodyReadEntered.await(5, TimeUnit.SECONDS))
            // flush can deliver headers before the server thread publishes its subsequent countDown.
            assertTrue("server sent real headers", headersSent.await(5, TimeUnit.SECONDS))
            // Source entry alone could race with cancellation before delegate.read. Inspect only
            // this reader's real stack until it is inside the JDK socket InputStream read as well.
            withTimeout(5_000) {
                while (bodyReader.get()?.stackTrace?.none {
                    it.methodName == "read" && (it.className == "java.net.Socket\$SocketInputStream" ||
                        it.className == "java.net.SocketInputStream" || it.className == "sun.nio.ch.NioSocketImpl")
                } != false) delay(10)
            }
            val call = checkNotNull(actualCall.get())
            assertTrue(call.isExecuted())
            assertFalse(call.isCanceled())
            assertEquals("body read must still be blocked", 1L, bodyReadExited.count)
            assertFalse("server must still withhold the body", peerTermination.isDone)
            assertTrue("downloader owns a partial before cancellation", root.walk().any { it.extension == "part" })
            assertEquals(0L, progress.get())

            running.cancel()
            withTimeout(5_000) { running.join() }
            assertTrue("production cancellation must cancel the real Call", call.isCanceled())
            assertEquals(0L, callCancelled.count)
            assertEquals("socket body read must unwind", 0L, bodyReadExited.count)
            assertNotNull("real socket read must fail on cancellation", readFailure.get())
            assertTrue(peerTermination.get(5, TimeUnit.SECONDS) in setOf("EOF", "RESET"))
            assertTrue("download must terminate with cancellation", downloadFailure.get() is CancellationException)
            assertNull("cancelled download must not return an APK", returnedApk.get())
            assertFalse("no partial or promoted APK may survive", root.walk().any { it.extension in setOf("part", "apk") })
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // Emergency teardown is AFTER the observations/assertions, never their success signal.
            // Attempt every release even if an earlier close fails; cleanup failure fails the test.
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) { cleanupFailures += failure }
            }
            cleanup { download?.cancel() }
            cleanup { actualCall.get()?.cancel() }
            closing.set(true)
            cleanup { listener.close() }
            cleanup { peer.get()?.close() }
            withContext(NonCancellable) {
                try { withTimeout(5_000) { download?.join() } }
                catch (failure: Throwable) { cleanupFailures += failure }
            }
            cleanup { client?.connectionPool?.evictAll() }
            cleanup { client?.cache?.close() }
            cleanup { clientExecutor.shutdownNow() }
            cleanup { serverExecutor.shutdownNow() }
            cleanup { check(clientExecutor.awaitTermination(5, TimeUnit.SECONDS)) { "client executor survived" } }
            cleanup { check(serverExecutor.awaitTermination(5, TimeUnit.SECONDS)) { "server executor survived" } }
            cleanup { check(root.deleteRecursively()) { "owned socket-fixture scratch survived" } }
            if (cleanupFailures.isNotEmpty()) {
                val cleanupError = AssertionError("socket fixture cleanup failed").also { error ->
                    cleanupFailures.forEach(error::addSuppressed)
                }
                val original = primaryFailure
                if (original != null) original.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }
}
