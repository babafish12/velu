package de.voci

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.ChatGptProvider
import de.voci.ai.LessonProvider
import de.voci.auth.AuthManager
import de.voci.auth.CredentialStore
import de.voci.auth.VaultState
import de.voci.data.PreferencesStore
import de.voci.domain.LessonContent
import de.voci.domain.SessionSpec
import de.voci.features.sprint.SprintScreen
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** UI-composition-only tracer. No generation, audio, or durable entry-count propagation claim.
 * The independent unit contract exercises the real validator, not a provider success stub.
 */
class R7ContentAmountTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sprintOffersContentAmountsInsteadOfMinutes() = r7UiFixture(compose) { _, vm ->
        val visible = mutableStateOf(true)
        try {
            compose.setContent { if (visible.value) VociTheme(reduced = true) { SprintScreen(vm, {}) } }
            // Test existence before scrolling so the first RED identifies the missing product UI.
            for (text in listOf("Inhaltsmenge", "5 Einträge", "10 Einträge", "20 Einträge")) {
                assertEquals("R7 CONTENT PRODUCT: missing control '$text'", 1,
                    compose.onAllNodesWithText(text).fetchSemanticsNodes().size)
                compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            }
            for (text in listOf("Dauer", "1 Min", "3 Min", "5 Min")) {
                assertTrue("R7 CONTENT PRODUCT: obsolete minutes control '$text' remains",
                    compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
            }
            for (count in listOf(5, 10, 20)) {
                compose.onNodeWithText("$count Einträge").performScrollTo().assertHasClickAction()
                    .performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodes(hasText("$count Einträge") and isSelected()).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithText("$count Einträge").assertIsSelected()
            }
            // This narrow tracer deliberately never presses Starten or claims persisted generation.
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
        }
    }
}

/** Shared R7 composition fixture: actual ViewModel, test-owned DataStore, empty auth vault,
 * fail-before-network HTTP, and exact restoration of lazy delegates without reading real tokens.
 * Does not replace the coordinator, its controller, or any transport/private player state.
 */
internal fun r7UiFixture(compose: ComposeContentTestRule, body: (VociApp, VociViewModel) -> Unit) {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
    val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val dataOwner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val file = File(app.cacheDir, "r7-ui-${UUID.randomUUID()}.preferences_pb")
    val preferences = PreferencesStore(PreferenceDataStoreFactory.create(scope = dataOwner) { file })
    val store = ViewModelStore()
    val requests = AtomicInteger()
    val http = OkHttpClient.Builder().addInterceptor {
        requests.incrementAndGet()
        throw IOException("FIXTURE: R7 UI forbids HTTP before DNS/network")
    }.build()
    val vault = object : CredentialStore {
        override fun read() = VaultState(host = "urn:r7:ui-empty-fixture", accounts = emptyList(), active = null)
        override fun write(state: VaultState) { throw AssertionError("FIXTURE: credential writes forbidden") }
    }
    val auth = AuthManager(null, http, owner, vault)
    val originals = mutableListOf<Triple<java.lang.reflect.Field, Lazy<*>, Boolean>>()
    var failure: Throwable? = null
    try {
        val vm = compose.runOnIdle {
            for ((name, replacement) in listOf(
                "auth" to lazy { auth }, "http" to lazy { http },
                "provider" to lazy { ChatGptProvider(http, auth) }, "preferences" to lazy { preferences }
            )) {
                val field = VociApp::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
                val original = field.get(app) as Lazy<*>
                originals += Triple(field, original, original.isInitialized())
                field.set(app, replacement)
            }
            VociViewModel(app, SavedStateHandle(), object : LessonProvider {
                override suspend fun generate(spec: SessionSpec): LessonContent =
                    throw AssertionError("FIXTURE: composition-only test must not generate")
            }).also { store.put("r7-ui", it) }
        }
        val expected = runBlocking { withTimeout(5_000) { preferences.flow.first() } }
        compose.waitUntil(5_000) { vm.prefs.value == expected }
        body(app, vm)
    } catch (t: Throwable) { failure = t; throw t }
    finally {
        val problems = mutableListOf<Throwable>()
        fun cleanup(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let(problems::add) }
        cleanup { compose.runOnIdle { store.clear(); owner.cancel() } }
        cleanup { runBlocking { withTimeout(5_000) { dataOwner.coroutineContext[Job]!!.cancelAndJoin() } } }
        // Restore every delegate even if a preceding cleanup action fails.
        originals.asReversed().forEach { (field, original, initialized) -> cleanup {
            compose.runOnIdle { field.set(app, original) }
            assertSame("FIXTURE: delegate restored", original, field.get(app))
            assertEquals("FIXTURE: original initialization unchanged", initialized, original.isInitialized())
        } }
        cleanup { file.delete(); File(file.path + ".tmp").delete() }
        cleanup { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
        cleanup { assertEquals("FIXTURE: no attempted HTTP", 0, requests.get()) }
        if (failure != null) problems.forEach { failure.addSuppressed(it) }
        else if (problems.isNotEmpty()) {
            problems.drop(1).forEach { problems.first().addSuppressed(it) }
            throw problems.first()
        }
    }
}
