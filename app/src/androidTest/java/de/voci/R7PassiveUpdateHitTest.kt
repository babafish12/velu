package de.voci

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real product composition/controller/repository/transport; synthetic metadata failure only.
 * All clicks below inject touch events at the target center, never SemanticsActions.OnClick.
 * Keep these four tests unchanged when fixing the product layout.
 */
class R7PassiveUpdateHitTest {
    @get:Rule val compose = createComposeRule()
    private val tabs = listOf("Sprint", "Kategorien", "Bibliothek", "Einstellungen")
    private val noRelease = "Noch kein öffentlicher Release verfügbar."
    private val offline = "Updateprüfung ohne Verbindung. Gespeicherte Lektionen bleiben verfügbar."

    @Test fun noReleaseNoticeDoesNotBlockNavigationTouches() = fixture(Mode.NO_RELEASE) { controller, selected ->
        awaitNotice(controller, Mode.NO_RELEASE)
        assertNavigationTouches(selected)
    }

    @Test fun offlineNoticeDoesNotBlockNavigationTouches() = fixture(Mode.OFFLINE) { controller, selected ->
        awaitNotice(controller, Mode.OFFLINE)
        assertNavigationTouches(selected)
    }

    @Test fun idleControlNavigationTouchesWork() = fixture(Mode.IDLE) { controller, selected ->
        compose.runOnIdle { assertEquals(UpdateState.Idle, controller.state.value) }
        compose.onNodeWithText(noRelease).assertDoesNotExist()
        compose.onNodeWithText(offline).assertDoesNotExist()
        assertNavigationTouches(selected)
    }

    @Test fun dismissedNoReleaseControlNavigationTouchesWork() = fixture(Mode.NO_RELEASE) { controller, selected ->
        awaitNotice(controller, Mode.NO_RELEASE)
        compose.onNodeWithText("Schließen").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(UpdateState.Idle, controller.state.value) }
        compose.onNodeWithText(noRelease).assertDoesNotExist()
        assertNavigationTouches(selected)
    }

    // Deliberately local touch implementation: do not import Compose's semantic performClick.
    private fun SemanticsNodeInteraction.performClick() = performTouchInput { click(center) }

    private fun awaitNotice(controller: UpdateController, mode: Mode) {
        val expected = if (mode == Mode.NO_RELEASE) UpdateState.NoRelease
            else UpdateState.Failed(UpdateProblem.OFFLINE, userInitiated = false)
        compose.waitUntil(5_000) { controller.state.value == expected }
        compose.onNodeWithText(if (mode == Mode.NO_RELEASE) noRelease else offline).assertIsDisplayed()
    }

    private fun assertNavigationTouches(selected: androidx.compose.runtime.MutableState<String>) {
        val failures = mutableListOf<String>()
        tabs.drop(1).forEach { target ->
            compose.onNodeWithText(target).assertIsDisplayed().performClick()
            compose.runOnIdle {
                if (selected.value != target) failures += "$target selected=${selected.value}"
            }
        }
        assertTrue("R7 PASSIVE UPDATE PRODUCT: navigation touch ${failures.joinToString()}", failures.isEmpty())
    }

    private enum class Mode { IDLE, NO_RELEASE, OFFLINE }

    private fun fixture(mode: Mode, block: (UpdateController, androidx.compose.runtime.MutableState<String>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "r7-passive-hit-${UUID.randomUUID()}")
        check(root.mkdir())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requests = AtomicInteger()
        var client: OkHttpClient? = null
        var controller: UpdateController? = null
        try {
            val ownedClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).addInterceptor { chain ->
                    check(chain.request().url.toString() == ReleasePolicy.LATEST_URL) {
                        "fixture refused non-latest request before network"
                    }
                    check(mode != Mode.IDLE) { "idle control must not request metadata" }
                    requests.incrementAndGet()
                    if (mode == Mode.OFFLINE) throw IOException("synthetic offline")
                    // Never call chain.proceed: no network, manifest or APK download.
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(404).message("synthetic no release").body("".toResponseBody()).build()
                }.build()
            client = ownedClient
            val archive = object : ArchiveVerifier {
                override suspend fun verify(file: File, candidate: ReleaseCandidate) = error("unexpected archive verification")
            }
            val ownedController = UpdateController(scope,
                UpdateRepository(UpdateTransport(ownedClient), UpdateFiles(root), archive, { 2L }, 36))
            controller = ownedController
            val host = object : UpdateHost {
                override fun canInstall(): Boolean = error("unexpected install permission")
                override fun openSettings() = error("unexpected settings")
                override fun install(file: File) = error("unexpected installer")
            }
            val selected = mutableStateOf(tabs.first())
            compose.setContent {
                VociTheme(reduced = true) {
                    UpdateLayout(ownedController) {
                        Box(Modifier.fillMaxSize()) {
                            NavigationBar(Modifier.align(Alignment.BottomCenter)) {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = selected.value == tab,
                                        onClick = { selected.value = tab },
                                        icon = { Spacer(Modifier.size(24.dp)) },
                                        label = { Text(tab) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (mode != Mode.IDLE) compose.runOnIdle { ownedController.resume(host) }
            block(ownedController, selected)
            if (mode == Mode.IDLE) assertEquals(0, requests.get())
        } finally {
            try {
                runBlocking {
                    try { withContext(Dispatchers.Main) { controller?.close() }?.join() }
                    finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
                }
            } finally {
                try {
                    client?.dispatcher?.cancelAll()
                    client?.connectionPool?.evictAll()
                    client?.dispatcher?.executorService?.shutdown()
                    client?.cache?.close()
                } finally { check(root.deleteRecursively()) }
            }
        }
    }
}
