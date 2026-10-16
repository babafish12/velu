package de.voci

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.ChatGptProvider
import de.voci.audio.AudioFiles
import de.voci.audio.PlaybackService
import de.voci.audio.PreparedSession
import de.voci.auth.AuthManager
import de.voci.auth.CredentialStore
import de.voci.auth.VaultState
import de.voci.data.Examples
import de.voci.domain.ProviderState
import de.voci.domain.SessionSpec
import de.voci.features.library.LessonDetailScreen
import de.voci.features.player.PlayerScreen
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
import java.util.concurrent.atomic.AtomicInteger

/** Requested R4 contracts only. Auth states are synthetic DISPLAY states, never authorization.
 * Audio tests require installed offline English/German voices and the existing seeded IT lesson.
 * No private production method is invoked; reflection only swaps/restores lazy dependencies.
 */
class R4RequestedFeaturesTest {
    @get:Rule val compose = createComposeRule()
    private val example = Examples.lessons.single { it.id == "example-it" }
    private val notice = "Nicht bei ChatGPT angemeldet."

    @Test fun signedOutNoticeAppearsBeforeGenerationAndAccountActionWorks() = fixture { f ->
        var accounts = 0
        f.show { SprintScreen(f.vm) { accounts++ } }
        assertNull("FIXTURE: no failed generation request", f.vm.error.value)
        assertFalse("FIXTURE: no generation in flight", f.vm.busy.value)
        required(hasText(notice)).assertIsDisplayed()
        required(hasText("Bei ChatGPT anmelden")).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("R4 PRODUCT: existing account callback", 1, accounts) }
        assertNull("R4 PRODUCT: notice must not depend on failed generation", f.vm.error.value)
    }

    @Test fun otherAuthDisplayStatesDoNotClaimSignedOut_positiveControl() = fixture { f ->
        compose.runOnIdle { f.auth.state.value = ProviderState.PLAN_AUTHORIZED }
        f.show { SprintScreen(f.vm) {} }
        for (state in ProviderState.entries.filter { it != ProviderState.SIGNED_OUT }) {
            compose.runOnIdle { f.auth.state.value = state }
            compose.waitForIdle()
            assertEquals("R4 PRODUCT: $state must not claim SIGNED_OUT", 0,
                compose.onAllNodesWithText(notice).fetchSemanticsNodes().size)
        }
    }

    @Test fun librarySingleEntryPlaysOnlyItsFourCuesWithoutOverwritingCourse() = fixture { f ->
        f.detail()
        // Check every unique action before spending time preparing audio on an unchanged APK.
        example.items.forEach { required(hasContentDescription("Eintrag abspielen: ${it.target}")) }
        f.voices()
        val original = runBlocking { f.app.repository.content(example.id) }
        val planFile = File(f.app.filesDir, "plans/${example.id}.json")
        val planBefore = if (planFile.exists()) planFile.readBytes() else null
        val rowBefore = runBlocking { f.app.db.dao().lesson(example.id) }
        val selected = example.items.last()
        required(hasContentDescription("Eintrag abspielen: ${selected.target}"))
            .performScrollTo().performClick()
        val session = f.prepared("R4 PRODUCT")
        assertEquals("R4 PRODUCT: original lesson ownership", example.id, session.lesson.id)
        assertEquals("R4 PRODUCT: one finite target/meaning/repeat/example pass", 4, session.cues.size)
        assertEquals("R4 PRODUCT: all required phases", setOf("target", "meaning", "repeat", "example"), session.cues.map { it.phase }.toSet())
        assertTrue("R4 PRODUCT: no other item or dialogue", session.cues.all { it.itemId == selected.id })
        val texts = mapOf("target" to selected.target, "meaning" to selected.translation,
            "repeat" to selected.target, "example" to selected.example)
        session.cues.forEach { assertEquals("R4 PRODUCT: selected phase content", texts[it.phase], it.text) }
        f.ownsAudio(session)
        assertEquals("R4 PRODUCT: course content preserved", original, runBlocking { f.app.repository.content(example.id) })
        assertEquals("R4 PRODUCT: course metadata preserved", rowBefore, runBlocking { f.app.db.dao().lesson(example.id) })
        val planAfter = if (planFile.exists()) planFile.readBytes() else null
        assertTrue("R4 PRODUCT: full-course plan must not be replaced", when {
            planBefore == null -> planAfter == null
            planAfter == null -> false
            else -> planBefore.contentEquals(planAfter)
        })
        // Finite termination at the measured end, without skip/seek/simulated completion.
        val duration = session.cues.sumOf { it.durationMs + it.pauseMs } / session.speed
        assertTrue("R4 PRODUCT: single item must not fill a one-minute course", duration < 54_000)
        eventually("R4 PRODUCT: natural single-item end", (duration.toLong() + 8_000).coerceAtMost(62_000)) {
            f.app.playback.state.value.ended && PlaybackService.localPlayer?.playbackState == Player.STATE_ENDED
        }
    }

    @Test fun libraryFullCourseStillPlaysWithoutAuthentication_positiveControl() = fixture { f ->
        f.detail()
        f.voices()
        required(hasText("Abspielen")).performScrollTo().performClick()
        val session = f.prepared("FIXTURE")
        assertEquals("R4 PRODUCT: full course retains lesson", example, session.lesson)
        assertTrue("R4 PRODUCT: full course contains multiple vocabulary items",
            session.cues.map { it.itemId }.intersect(example.items.map { it.id }.toSet()).size > 1)
        assertTrue("R4 PRODUCT: full course retains time budget", session.budgetMs in listOf(60_000L, 180_000L, 300_000L))
        f.ownsAudio(session)
        assertEquals("R4 PRODUCT: saved playback requires no login", ProviderState.SIGNED_OUT, f.auth.state.value)
    }

    @Test fun playerEntryListSeeksByStableItemAndPreservesPausedSession() = fixture { f ->
        selectEntry(f, playing = false)
    }

    @Test fun playerEntryListIncludesSourceItemsAndPreservesPlayingSession() = fixture { f ->
        selectEntry(f, playing = true)
    }

    private fun selectEntry(f: Fixture, playing: Boolean) {
        f.voices()
        compose.runOnIdle { f.app.playback.start(example, SessionSpec(example.title, example.language, 1), 1f) }
        val initial = f.prepared("FIXTURE")
        f.ownsAudio(initial)
        compose.runOnIdle { PlaybackService.localPlayer!!.pause() }
        eventually("FIXTURE: pause before selection") { !PlaybackService.localPlayer!!.isPlaying }
        val currentId = compose.runOnIdle {
            val s = f.app.playback.state.value.session!!
            s.cues[PlaybackService.localPlayer!!.currentMediaItemIndex / 2].itemId
        }
        val sourceIds = initial.sourceCues.map { it.itemId }.toSet()
        val plannedIds = initial.cues.map { it.itemId }.toSet()
        // Prefer an omitted source item when this device's measured budget omitted one.
        // Always resolve by stable item ID, never a stale planned cue index.
        val selected = example.items.lastOrNull { it.id in sourceIds && it.id !in plannedIds && it.id != currentId }
            ?: example.items.lastOrNull { it.id in sourceIds && it.id != currentId }
            ?: throw AssertionError("FIXTURE: no noncurrent vocabulary source item")
        f.show { PlayerScreen(initial.id, f.vm, {}, {}) { _, _ -> } }
        required(hasText("Einträge")).performScrollTo().performClick()
        val action = required(hasContentDescription("Eintrag auswählen: ${selected.target}"), scrollList = true)
        assertTrue("R4 PRODUCT: entry selector must be scrollable", compose.onAllNodes(
            hasContentDescription("Eintrag auswählen: ${selected.target}") and hasAnyAncestor(hasScrollAction())
        ).fetchSemanticsNodes().isNotEmpty())
        if (playing) {
            compose.runOnIdle { PlaybackService.localPlayer!!.play() }
            eventually("FIXTURE: actual playing before selection") { PlaybackService.localPlayer!!.isPlaying }
        }
        action.performClick()
        eventually("R4 PRODUCT: selection seeks actual player and preserves state") {
            val state = f.app.playback.state.value
            val player = PlaybackService.localPlayer!!
            val session = state.session
            val cue = session?.cues?.getOrNull(player.currentMediaItemIndex / 2)
            session?.id == initial.id && cue?.itemId == selected.id &&
                state.session?.cues?.getOrNull(state.index)?.itemId == selected.id &&
                player.isPlaying == playing && state.playing == playing &&
                player.currentMediaItem?.mediaId == cue.key
        }
        assertEquals("R4 PRODUCT: selector closes after choice", 0,
            compose.onAllNodesWithContentDescription("Eintrag auswählen: ${selected.target}").fetchSemanticsNodes().size)
        assertTrue("R4 PRODUCT: existing backward control remains", compose.onAllNodesWithText("Zurück").fetchSemanticsNodes().isNotEmpty())
        // Existing UI names the forward control 'Weiter'. Preserve that established contract.
        required(hasText("Weiter"))
        assertEquals("R4 PRODUCT: selection preserves course content", example, runBlocking { f.app.repository.content(example.id) })
    }

    private fun required(matcher: SemanticsMatcher, scrollList: Boolean = false): SemanticsNodeInteraction {
        if (scrollList && compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            val lists = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
            for (index in lists.indices) {
                try { compose.onAllNodes(hasScrollAction())[index].performScrollToNode(matcher) }
                catch (_: AssertionError) { /* Explicit absence assertion below, not fixture failure. */ }
                if (compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) break
            }
        }
        val count = compose.onAllNodes(matcher).fetchSemanticsNodes().size
        if (count != 1) throw AssertionError("R4 PRODUCT: expected one accessible action/text $matcher; found $count")
        return compose.onNode(matcher)
    }

    private fun eventually(label: String, timeout: Long = 8_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { compose.runOnIdle(condition) } }
        catch (e: ComposeTimeoutException) { throw AssertionError("$label (timeout ${timeout}ms)", e) }
    }

    private inner class Fixture(val app: VociApp, val vm: VociViewModel, val auth: AuthManager) {
        val visible = mutableStateOf(true)
        var shown = false
        fun show(content: @Composable () -> Unit) {
            compose.setContent { if (visible.value) VociTheme(reduced = true) { content() } }
            shown = true
            compose.waitForIdle()
        }
        fun detail() {
            eventually("FIXTURE: existing seeded example") { vm.lessons.value.any { it.id == example.id } }
            assertEquals("FIXTURE: unchanged bundled example", example, runBlocking { app.repository.content(example.id) })
            show { LessonDetailScreen(example.id, vm) {} }
            try {
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Abspielen").fetchSemanticsNodes().size == 1 }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("FIXTURE: lesson detail did not load", e)
            }
        }
        fun voices() = runBlocking {
            for (locale in listOf(example.language, "de-DE")) {
                val voices = try { withTimeout(10_000) { app.tts.voices(locale) } }
                catch (e: Exception) { throw AssertionError("FIXTURE: offline voice prerequisite for $locale", e) }
                assertTrue("FIXTURE: installed offline voice for $locale", voices.any { !it.isNetworkConnectionRequired })
            }
        }
        fun prepared(label: String): PreparedSession {
            eventually("$label: real TTS preparation", 60_000) {
                app.playback.state.value.error != null ||
                    (app.playback.preparing.value == null && app.playback.state.value.session != null)
            }
            assertNull("$label: audio preparation error", app.playback.state.value.error)
            return app.playback.state.value.session ?: throw AssertionError("$label: missing prepared session")
        }
        fun ownsAudio(session: PreparedSession) {
            val paths = mutableMapOf<String, String>()
            runBlocking {
                session.cues.forEach { cue ->
                    assertTrue("R4 PRODUCT: actual measured duration", cue.durationMs > 0)
                    val asset = app.db.dao().audio(cue.key) ?: throw AssertionError("R4 PRODUCT: missing selected audio metadata")
                    assertTrue("R4 PRODUCT: validated audio file", AudioFiles.valid(File(asset.path), asset.bytes, asset.sha256))
                    paths[cue.key] = asset.path
                }
            }
            eventually("R4 PRODUCT: Media3 actually playing") { PlaybackService.localPlayer?.isPlaying == true }
            compose.runOnIdle {
                val player = PlaybackService.localPlayer!!
                assertEquals("R4 PRODUCT: player owns exactly selected cue/pause pairs", session.cues.size * 2, player.mediaItemCount)
                session.cues.forEachIndexed { index, cue ->
                    assertEquals("R4 PRODUCT: real playlist audio ownership", cue.key, player.getMediaItemAt(index * 2).mediaId)
                    assertEquals("R4 PRODUCT: player uses validated local audio", paths[cue.key],
                        player.getMediaItemAt(index * 2).localConfiguration?.uri?.toString())
                }
            }
        }
    }

    private fun fixture(body: (Fixture) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        val requests = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw IOException("FIXTURE: R4 forbids HTTP before DNS/network")
        }.build()
        val vault = object : CredentialStore {
            override fun read() = VaultState(host = "urn:r4:empty-display-fixture", accounts = emptyList(), active = null)
            override fun write(state: VaultState) { throw AssertionError("FIXTURE: account writes forbidden") }
        }
        val auth = AuthManager(null, http, owner, vault)
        val originals = mutableListOf<Triple<java.lang.reflect.Field, Lazy<*>, Boolean>>()
        var f: Fixture? = null
        try {
            compose.runOnIdle {
                for ((name, replacement) in listOf(
                    "auth" to lazy { auth }, "http" to lazy { http },
                    "provider" to lazy { ChatGptProvider(http, auth) }
                )) {
                    val field = VociApp::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
                    val original = field.get(app) as Lazy<*>
                    originals += Triple(field, original, original.isInitialized())
                    field.set(app, replacement) // Never evaluate the original delegate.
                }
                val vm = VociViewModel(app, SavedStateHandle()).also { store.put("r4", it) }
                f = Fixture(app, vm, auth)
            }
            val fixture = f!!
            val preferences = runBlocking { app.preferences.flow.first() }
            eventually("FIXTURE: preferences collected") { fixture.vm.prefs.value == preferences }
            body(fixture)
        } finally {
            try {
                f?.takeIf { it.shown }?.let { fixture ->
                    compose.runOnIdle { fixture.visible.value = false }
                    compose.waitForIdle()
                }
            } finally {
                compose.runOnIdle {
                    f?.vm?.cancel()
                    PlaybackService.localPlayer?.pause()
                    store.clear()
                    owner.cancel()
                    originals.asReversed().forEach { (field, original, _) -> field.set(app, original) }
                }
                http.dispatcher.cancelAll()
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
                originals.forEach { (_, original, initialized) ->
                    assertEquals("FIXTURE: original delegate initialization unchanged", initialized, original.isInitialized())
                }
                assertEquals("FIXTURE: no attempted HTTP", 0, requests.get())
            }
        }
    }
}
