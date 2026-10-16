package de.voci.update

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Isolated public transport. Injected Call.Factory is for deterministic tests, never auth DI. */
class UpdateTransport(private val calls: Call.Factory = publicClient()) {
    companion object {
        fun publicClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES).authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE).cache(null)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    }

    suspend fun bytes(url: String, limit: Int, expected: Long? = null): ByteArray =
        read(url, limit.toLong(), expected) { input, alive ->
            val output = ByteArrayOutputStream()
            copyBounded(input, output, limit.toLong(), expected, alive) {}
            output.toByteArray()
        }

    suspend fun text(url: String, limit: Int, expected: Long? = null): String {
        val data = bytes(url, limit, expected)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw UpdateFailure(UpdateProblem.INVALID) }
    }

    suspend fun <T> read(url: String, limit: Long, expected: Long? = null,
        consume: (InputStream, () -> Unit) -> T): T {
        UpdateUrls.requireInitial(url)
        requireUpdate(limit in 1..ReleasePolicy.APK_LIMIT && (expected == null || expected in 1..limit))
        var current = url
        try {
            repeat(4) { hop ->
                coroutineContext.ensureActive()
                val request = Request.Builder().url(current)
                    .header("Accept", if (url == ReleasePolicy.LATEST_URL) "application/vnd.github+json" else "application/octet-stream")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "Velu-Updater")
                    .apply { if (url == ReleasePolicy.LATEST_URL) header("X-GitHub-Api-Version", "2022-11-28") }
                    .build()
                val call = calls.newCall(request)
                var redirect: String? = null
                var result: T? = null
                var completed = false
                coroutineScope {
                    // Unconfined cancellation handler executes Call.cancel immediately even while
                    // execute()/body.read() is blocked on IO. Joining this scope precedes file cleanup.
                    val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val context = coroutineContext
                            call.execute().use { response ->
                                context.ensureActive()
                                if (response.code in setOf(301, 302, 303, 307, 308)) {
                                    requireUpdate(url != ReleasePolicy.LATEST_URL && hop < 3)
                                    val next = response.header("Location") ?: throw UpdateFailure(UpdateProblem.INVALID)
                                    UpdateUrls.requireRedirect(next)
                                    redirect = next
                                } else {
                                    when (response.code) {
                                        200 -> Unit
                                        404 -> throw UpdateFailure(if (url == ReleasePolicy.LATEST_URL) UpdateProblem.NO_RELEASE else UpdateProblem.INVALID)
                                        403, 429 -> throw UpdateFailure(UpdateProblem.QUOTA)
                                        else -> throw UpdateFailure(UpdateProblem.SERVER)
                                    }
                                    requireUpdate(response.header("Content-Encoding").let { it == null || it == "identity" })
                                    val body = response.body ?: throw UpdateFailure(UpdateProblem.INVALID)
                                    val declared = body.contentLength()
                                    requireUpdate(declared == -1L || declared in 0..limit)
                                    requireUpdate(expected == null || declared == -1L || declared == expected)
                                    // Even custom consumers cannot read an unbounded body.
                                    val bounded = CountedInput(body.byteStream(), limit)
                                    result = consume(bounded) { context.ensureActive() }
                                    context.ensureActive()
                                    requireUpdate(declared == -1L || bounded.count == declared)
                                    requireUpdate(expected == null || bounded.count == expected)
                                    completed = true
                                }
                            }
                        }
                    } finally { cancellation.cancel() }
                }
                if (completed) {
                    @Suppress("UNCHECKED_CAST")
                    return result as T
                }
                current = redirect ?: throw UpdateFailure(UpdateProblem.INVALID)
            }
            throw UpdateFailure(UpdateProblem.INVALID)
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw UpdateFailure(UpdateProblem.OFFLINE)
        }
    }
}

private class CountedInput(private val input: InputStream, private val limit: Long) : InputStream() {
    var count = 0L; private set
    override fun read(): Int {
        val n = input.read()
        if (n != -1) { count++; requireUpdate(count <= limit) }
        return n
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, minOf(len.toLong(), limit - count + 1).toInt())
        if (n > 0) { count += n; requireUpdate(count <= limit) }
        return n
    }
}

internal fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long, expected: Long?,
    alive: () -> Unit, progress: (Long) -> Unit): Long {
    val buffer = ByteArray(32 * 1024)
    var count = 0L
    while (true) {
        alive()
        val n = input.read(buffer)
        if (n == -1) break
        if (n == 0) continue
        count += n
        requireUpdate(count <= limit && (expected == null || count <= expected))
        alive(); output.write(buffer, 0, n); progress(count)
    }
    alive()
    requireUpdate(expected == null || count == expected)
    return count
}
