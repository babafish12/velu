package de.voci

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ui.theme.VociTheme
import de.voci.update.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic metadata/bytes, real Compose/controller/parser/stream. Archive is a deliberately
 * suspended test seam; neither test claims successful APK verification or installation.
 */
class R7UpdateUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun automaticPromptWaitsForConsentAndLaterDefers() = fixture { controller, requests, downloads, _, _ ->
        compose.onNodeWithText("Neue Version 0.3.0").assertIsDisplayed()
        assertEquals(0, downloads.get())
        compose.onNodeWithText("Später").performClick()
        compose.onNodeWithText("Neue Version 0.3.0").assertDoesNotExist()
        assertEquals(2, requests.get())
        assertEquals(0, downloads.get())
        compose.runOnIdle { controller.consent() }
        assertEquals(0, downloads.get())
    }
    @Test fun consentStreamsBytesAndCancellationPreventsInstaller() = fixture { controller, _, downloads, installs, gate ->
        compose.onNodeWithText("Herunterladen & installieren").performClick()
        compose.waitUntil(5_000) { controller.state.value is UpdateState.Verifying }
        compose.onNodeWithText("Update wird geprüft").assertIsDisplayed()
        assertEquals(1, downloads.get())
        compose.onNodeWithText("Abbrechen").performClick()
        compose.runOnIdle { gate.complete(Unit) }
        compose.onNodeWithText("Update nicht installiert oder abgebrochen.").assertIsDisplayed()
        assertEquals(0, installs.get())
    }
    private fun fixture(block: (UpdateController, AtomicInteger, AtomicInteger, AtomicInteger, CompletableDeferred<Unit>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "r7-update-ui-${UUID.randomUUID()}")
        check(root.mkdir())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requests = AtomicInteger(); val downloads = AtomicInteger(); val installs = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val base = "https://github.com/${ReleasePolicy.REPOSITORY}/releases/download/v0.3.0/"
        val manifest = """{"schemaVersion":1,"applicationId":"de.voci","versionCode":3,"versionName":"0.3.0","apkName":"velu.apk","sha256":"$hash","sizeBytes":4,"minSdk":28}"""
        val release = """{"tag_name":"v0.3.0","draft":false,"prerelease":false,"assets":[{"name":"velu.apk","size":4,"browser_download_url":"${base}velu.apk"},{"name":"velu-update.json","size":${manifest.toByteArray().size},"browser_download_url":"${base}velu-update.json"}]}"""
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            requests.incrementAndGet()
            val body = when (chain.request().url.toString()) {
                ReleasePolicy.LATEST_URL -> release.toByteArray()
                "${base}velu-update.json" -> manifest.toByteArray()
                "${base}velu.apk" -> { downloads.incrementAndGet(); bytes }
                else -> error("fixture refused before network")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("synthetic").body(body.toResponseBody()).build()
        }.build()
        val archive = object : ArchiveVerifier {
            override suspend fun verify(file: File, candidate: ReleaseCandidate) { verifyBytes(file, candidate); gate.await() }
        }
        val controller = UpdateController(scope, UpdateRepository(UpdateTransport(client), UpdateFiles(root), archive, { 2L }, 36))
        val host = object : UpdateHost {
            override fun canInstall() = true
            override fun openSettings() = error("unexpected Settings")
            override fun install(file: File) { installs.incrementAndGet() }
        }
        try {
            compose.setContent { VociTheme(reduced = true) { UpdateOverlay(controller) } }
            compose.runOnIdle { controller.resume(host) }
            compose.waitUntil(5_000) { controller.state.value is UpdateState.Offer }
            block(controller, requests, downloads, installs, gate)
        } finally {
            runBlocking {
                withContext(Dispatchers.Main) { controller.close() }.join()
                scope.coroutineContext[Job]!!.cancelAndJoin()
            }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            check(root.deleteRecursively())
        }
    }
}
