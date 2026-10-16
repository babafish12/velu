package de.voci

import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.toRoute
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.PlaybackService
import de.voci.audio.PlaybackState
import de.voci.audio.PreparedSession
import de.voci.data.Examples
import de.voci.domain.SessionProgress
import de.voci.navigation.*
import de.voci.navigation.Result as ResultRoute
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Actual production NavHost and UI navigation; only the paused display/session is synthetic.
 * No play(), synthesis, provider, login, transport or persistent-state writes. This is neither
 * process death or the separate natural 200%-landscape journey. The adapter additionally
 * measures actual Page scroll in a bounded viewport. Requires the R3C1 completed-baseline /
 * ready-empty-Media3 guard. Parent owns compilation and execution.
 */
class R3eAudioEntryCollisionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: VociApp
    private lateinit var vm: VociViewModel
    private lateinit var nav: VociNavigator
    private val lesson = Examples.lessons.first()
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private val markerKey = "r3e-entry-marker"
    private val statusKey = "r3e_audio_entry"
    private var activeCase = ""

    private data class EntryStamp(val id: String, val marker: String?, val owner: R3gEntryProbe)
    private val audioScroll = mutableMapOf<String, Float>()
    private val playerOwners = mutableListOf<R3gEntryProbe>()

    @Test fun savedAudioAliasMustNotReplaceCurrentAudioEntry_b03() =
        fixture("savedAudioAliasMustNotReplaceCurrentAudioEntry_b03") {
            val sprint = stampCurrent<Sprint>("sprint-origin")
            val paused = seedPaused("r3e-b03-a")
            miniPlayerAudio(paused)
            val a1 = stampCurrent<Audio>("a1")
            rememberScroll(a1, 0.3f)
            assertSessionTail(listOf(sprint.id), a1, paused)
            record("a1-before-save")

            // Settings is already selected on Audio. Still execute its real Role.Tab click.
            labeled("FIXTURE: Settings is selected while Audio is current") {
                compose.onNode(hasText("Einstellungen") and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                    .assertIsDisplayed().assertIsEnabled().assertIsSelected()
            }
            tab("Einstellungen")
            route<Settings>("FIXTURE: selected Settings tab must actually open its root")
            val settings = stampCurrent<Settings>("settings-origin")
            compose.runOnIdle {
                assertEquals("FIXTURE: lone Audio tail removed from active stack by UI save",
                    listOf(sprint.id, settings.id), stack().map { it.id })
                assertEquals("FIXTURE: saving a1 leaves paused A unchanged", paused, app.playback.state.value)
            }
            record("a1-saved-settings-root")

            miniPlayerAudio(paused)
            val a2 = stampCurrent<Audio>("a2")
            rememberScroll(a2, 0.7f)
            compose.runOnIdle {
                assertNotEquals("FIXTURE: a2 must be a distinct entry", a1.id, a2.id)
                assertNotEquals("FIXTURE: markers must differ", a1.marker, a2.marker)
                assertNotEquals("FIXTURE: independent real scroll values", audioScroll.getValue(a1.id), audioScroll.getValue(a2.id))
                assertNotSame("FIXTURE: distinct Audio ViewModels", a1.owner, a2.owner)
                assertTrue("FIXTURE: a1 remains outside the active stack", stack().none { it.id == a1.id })
            }
            assertSessionTail(listOf(sprint.id, settings.id), a2, paused)
            record("a2-before-colliding-save")
            tab("Kategorien")
            route<Categories>("FIXTURE: Categories tab action completed")
            record("a2-after-save-categories")
            tab("Einstellungen")
            record("a2-after-settings-restore")
            assertPreserved("B03 PRODUCT:", sprint, settings, a2, paused)
            assertScroll(a2)

            // Navigation 2.10.2 non-inclusive popUpTo(Sprint, saveState=true) aliases the
            // saved [a1] tail to BOTH Sprint and Audio (and the start graph). See the frozen
            // NavControllerImpl.javap.txt:1410-1535,7160-7248. Thus Sprint is a real public
            // recovery path. Do not inspect/mutate private maps or navigate(Audio) directly.
            // This assertion follows the primary exact-a2 assertion: unchanged R3D stops there.
            tab("Sprint")
            record("original-a1-via-sprint-tab")
            compose.runOnIdle {
                assertTrue("B03 PRODUCT: saved Sprint tail must still open Audio: ${trace()}",
                    nav.currentDestination?.hasRoute<Audio>() == true)
                assertEquals("B03 PRODUCT: original saved a1 must remain recoverable via Sprint: ${trace()}",
                    a1, currentStamp())
                assertEquals("B03 PRODUCT: original Sprint branch has no foreign Settings/session tail",
                    listOf(sprint.id, a1.id), stack().map { it.id })
                assertEquals("B03 PRODUCT: Sprint origin marker retained", sprint, stamp(stack().first()))
                assertEquals("B03 PRODUCT: recovering a1 must not alter playback", paused, app.playback.state.value)
            }
            assertScroll(a1)
        }

    @Test fun nonCollidingCurrentAudioEntrySurvivesTabRoundtrip_positiveControl() =
        fixture("nonCollidingCurrentAudioEntrySurvivesTabRoundtrip_positiveControl") {
            val sprint = stampCurrent<Sprint>("sprint-origin")
            val paused = seedPaused("r3e-control-a")
            // First visit to Audio: no earlier saved Audio alias exists in this fresh host.
            tab("Einstellungen")
            val settings = stampCurrent<Settings>("settings-origin")
            miniPlayerAudio(paused)
            val audio = stampCurrent<Audio>("current-audio")
            rememberScroll(audio, 0.5f)
            assertSessionTail(listOf(sprint.id, settings.id), audio, paused)
            record("noncolliding-before-save")
            tab("Kategorien")
            route<Categories>("FIXTURE: Categories tab action completed")
            record("noncolliding-after-save")
            tab("Einstellungen")
            record("noncolliding-after-restore")
            assertPreserved("CONTROL:", sprint, settings, audio, paused)
            assertScroll(audio)
        }

    @Test fun ordinarySavedAudioWithoutSessionTailRetainsState_positiveControl() =
        fixture("ordinarySavedAudioWithoutSessionTailRetainsState_positiveControl") {
            val sprint = stampCurrent<Sprint>("sprint-origin")
            tab("Einstellungen")
            val settings = stampCurrent<Settings>("settings-origin")
            audioAction() // Actual Settings row, never a Player/event or direct navigate().
            val audio = stampCurrent<Audio>("ordinary-audio")
            rememberScroll(audio, 0.5f)
            compose.runOnIdle {
                assertEquals("FIXTURE: ordinary Audio has only Sprint / Settings below it",
                    listOf(sprint.id, settings.id, audio.id), stack().map { it.id })
                assertTrue("FIXTURE: ordinary Audio has no session tail", stack().none(::isSession))
                assertEquals("FIXTURE: ordinary Audio has empty playback", PlaybackState(), app.playback.state.value)
            }
            record("ordinary-before-save")
            tab("Kategorien")
            route<Categories>("FIXTURE: Categories tab action completed")
            record("ordinary-after-save")
            tab("Einstellungen")
            record("ordinary-after-restore")
            assertPreserved("CONTROL:", sprint, settings, audio, PlaybackState())
            assertScroll(audio)
        }

    private fun seedPaused(id: String): PlaybackState {
        // Empty cues intentionally prevent coordinator update/persistence. No audible success claim.
        val paused = PlaybackState(session = PreparedSession(id, lesson, emptyList(), false, 1f), requestId = id)
        compose.runOnIdle {
            assertEquals("FIXTURE: seed only on true Sprint", 1, stack().size)
            assertTrue("FIXTURE: true Sprint before synthetic A", nav.currentDestination!!.hasRoute<Sprint>())
            assertEquals("FIXTURE: seed requires empty display", PlaybackState(), app.playback.state.value)
            app.playback.state.value = paused
        }
        compose.waitForIdle()
        return paused
    }

    private fun miniPlayerAudio(paused: PlaybackState) {
        clickUnique("mini-player title", hasText(lesson.title) and hasClickAction())
        route<PlayerRoute>("FIXTURE: mini-player opens actual full-screen Player")
        compose.runOnIdle {
            assertEquals("FIXTURE: mini-player opens the same A", paused.session!!.id,
                nav.currentBackStackEntry!!.toRoute<PlayerRoute>().sessionId)
            assertEquals("FIXTURE: mini-player navigation preserves paused display", paused, app.playback.state.value)
            playerOwners += ViewModelProvider(nav.currentBackStackEntry!!)[R3gEntryProbe::class.java]
        }
        labeled("FIXTURE: actual populated Player") {
            compose.onNode(hasText(lesson.title) and heading).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Wiederholen").assertExists().assertIsEnabled()
        }
        audioAction()
    }

    private fun audioAction() {
        clickUnique("Audio action", hasText("Audio") and hasClickAction(), scroll = true)
        route<Audio>("FIXTURE: actual UI action opens Audio")
        labeled("FIXTURE: visible Audio heading") {
            compose.onAllNodes(hasText("Audio") and heading).assertCountEquals(1)
            compose.onNode(hasText("Audio") and heading).assertIsDisplayed()
        }
    }

    private fun tab(label: String) = clickUnique("bottom tab '$label'",
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    private fun clickUnique(label: String, matcher: SemanticsMatcher, scroll: Boolean = false) =
        labeled("FIXTURE: unique enabled visible $label") {
            compose.onAllNodes(matcher).assertCountEquals(1)
            val node = compose.onNode(matcher)
            if (scroll) node.performScrollTo()
            node.assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitForIdle()
        }

    private inline fun <reified T : Any> route(message: String) = compose.runOnIdle {
        assertTrue("$message: ${trace()}", nav.currentDestination?.hasRoute<T>() == true)
    }

    private inline fun <reified T : Any> stampCurrent(label: String): EntryStamp {
        route<T>("FIXTURE: route before stamping $label")
        return compose.runOnIdle {
            val entry = nav.currentBackStackEntry!!
            assertNull("FIXTURE: new entry has no test marker", entry.savedStateHandle.get<String>(markerKey))
            entry.savedStateHandle[markerKey] = "$activeCase:$label:${UUID.randomUUID()}"
            stamp(entry)
        }
    }

    private fun assertSessionTail(originIds: List<String>, audio: EntryStamp, paused: PlaybackState) = compose.runOnIdle {
        val entries = stack()
        assertEquals("FIXTURE: exactly retained origin / Audio: ${trace()}", originIds.size + 1, entries.size)
        assertEquals("FIXTURE: exact non-session origins", originIds, entries.take(originIds.size).map { it.id })
        assertTrue("FIXTURE: tab domain contains no session", entries.none(::isSession))
        val session = sessionStack().filter(::isSession).single()
        assertTrue("FIXTURE: independent Player owner", session.destination.hasRoute<PlayerRoute>())
        assertEquals("FIXTURE: one session entry for same A", paused.session!!.id, sessionId(session))
        assertEquals("FIXTURE: actual origin entry reference", originIds.last(), nav.originEntryId)
        assertFalse("FIXTURE: suspended session host", nav.sessionVisible)
        assertEquals("FIXTURE: exact current Audio", audio, currentStamp())
        assertEquals("FIXTURE: exact paused A", paused, app.playback.state.value)
    }

    private fun assertPreserved(prefix: String, sprint: EntryStamp, origin: EntryStamp,
                                audio: EntryStamp, paused: PlaybackState) = compose.runOnIdle {
        // Also checked on the RED path, before the decisive entry identity assertion.
        assertEquals("$prefix navigation must leave playback unchanged", paused, app.playback.state.value)
        val settings = stack().singleOrNull { it.destination.hasRoute<Settings>() }
        assertNotNull("$prefix originating Settings retained: ${trace()}", settings)
        assertEquals("$prefix originating Settings ID/local marker retained", origin, stamp(settings!!))
        assertTrue("$prefix restored destination is Audio: ${trace()}", nav.currentDestination?.hasRoute<Audio>() == true)
        // Never reduce this to a route-only assertion or accept a1 as an alternative.
        assertEquals("$prefix current Audio entry ID and local marker must survive collision: ${trace()}",
            audio, currentStamp())
        assertEquals("$prefix exact non-session stack retained", listOf(sprint.id, origin.id, audio.id), stack().map { it.id })
        assertEquals("$prefix original Sprint ID/local marker retained", sprint, stamp(stack().first()))
        assertTrue("$prefix tab domain has no sessions", stack().none(::isSession))
        assertTrue("$prefix obsolete session owners removed", sessionStack().none(::isSession))
        assertTrue("$prefix actual obsolete Player ViewModels cleared", playerOwners.all { it.cleared })
        assertFalse("$prefix retained Audio owner was not cleared", audio.owner.cleared)
        assertFalse("$prefix retained origin owner was not cleared", origin.owner.cleared)
    }

    private val scroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private fun rememberScroll(entry: EntryStamp, fraction: Float) {
        compose.onAllNodes(scroll).assertCountEquals(1)
        val range = compose.onNode(scroll).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("FIXTURE: real Audio must overflow bounded viewport", range.maxValue() > 0f)
        compose.onNode(scroll).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue() * fraction) }
        compose.waitForIdle()
        audioScroll[entry.id] = compose.onNode(scroll).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("FIXTURE: real nonzero scroll recorded", audioScroll.getValue(entry.id) > 0f)
    }
    private fun assertScroll(entry: EntryStamp) {
        compose.onAllNodes(scroll).assertCountEquals(1)
        assertEquals("B03/CONTROL PRODUCT: exact entry's independent saveable Page scroll",
            audioScroll.getValue(entry.id),
            compose.onNode(scroll).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.5f)
        compose.runOnIdle { assertFalse("PRODUCT: original Audio ViewModel remains live", entry.owner.cleared) }
    }

    private fun sessionStack() = nav.sessions.navigatorProvider.getNavigator(ComposeNavigator::class.java).backStack.value
    private fun stack() = nav.tabs.navigatorProvider.getNavigator(ComposeNavigator::class.java).backStack.value
    private fun stamp(entry: NavBackStackEntry) = EntryStamp(entry.id, entry.savedStateHandle.get<String>(markerKey), ViewModelProvider(entry)[R3gEntryProbe::class.java])
    private fun currentStamp() = nav.currentBackStackEntry?.let(::stamp)
    private fun isSession(entry: NavBackStackEntry) = entry.destination.hasRoute<PlayerRoute>() ||
        entry.destination.hasRoute<Recall>() || entry.destination.hasRoute<ResultRoute>()
    private fun sessionId(entry: NavBackStackEntry): String? = when {
        entry.destination.hasRoute<PlayerRoute>() -> entry.toRoute<PlayerRoute>().sessionId
        entry.destination.hasRoute<Recall>() -> entry.toRoute<Recall>().sessionId
        entry.destination.hasRoute<ResultRoute>() -> entry.toRoute<ResultRoute>().sessionId
        else -> null
    }
    // Includes graph entries as well as every active page; only OUR marker key is read.
    private fun trace() = (stack() + sessionStack()).joinToString(" -> ") {
        "${it.destination.route}[entry=${it.id},marker=${it.savedStateHandle.get<String>(markerKey)},session=${sessionId(it)}]"
    }
    private fun record(stage: String) {
        val line = compose.runOnIdle { "$activeCase | $stage | ${trace()}" }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(statusKey, line) })
    }
    private fun labeled(label: String, action: () -> Unit) {
        try { action() } catch (failure: AssertionError) {
            throw AssertionError("$label\n${failure.message}", failure)
        } catch (failure: Exception) {
            throw AssertionError("$label\n${failure.message}", failure)
        }
    }

    private fun fixture(caseName: String, body: () -> Unit) {
        activeCase = caseName
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        // Read only progress, never credentials. Refuse an incomplete saved session BEFORE restore().
        labeled("FIXTURE: durable completed baseline prerequisite") { runBlocking { withTimeout(5000) {
            val progress = app.db.dao().latestProgress()?.let { Json.decodeFromString<SessionProgress>(it.json) }
            assertTrue("FIXTURE: Parent must naturally finish durable session before running; never clear it", progress == null || progress.completed)
        } } }
        val playback = compose.runOnIdle { app.playback }
        labeled("FIXTURE: bounded existing playback restoration") { runBlocking { withTimeout(5000) { playback.restore() } } }
        val original = compose.runOnIdle {
            assertNotNull("FIXTURE: real Media3 service ready", PlaybackService.localPlayer)
            assertEquals("FIXTURE: real playlist empty, never clear it", 0, PlaybackService.localPlayer!!.mediaItemCount)
            assertFalse("FIXTURE: real service must not be playing", PlaybackService.localPlayer!!.isPlaying)
            assertNull("FIXTURE: no preparation", playback.preparing.value)
            assertEquals("FIXTURE: exact initial empty session state required", PlaybackState(), playback.state.value)
            playback.state.value
        }
        val originalPreparing = playback.preparing.value
        val originalAnswers = playback.answers.value
        val originalUncertain = playback.uncertain.value
        // No delegates are replaced or evaluated by reflection. Pin identity/initialization so an
        // unexpected path cannot silently initialize real auth/provider and go unnoticed.
        val delegates = listOf("auth\$delegate", "provider\$delegate").map { name ->
            val field = VociApp::class.java.getDeclaredField(name).apply { isAccessible = true }
            val value = field.get(app) as Lazy<*>
            Triple(field, value, value.isInitialized())
        }
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        var contentInstalled = false
        try {
            compose.runOnIdle {
                vm = VociViewModel(app, SavedStateHandle())
                store.put("r3e-navigation", vm)
            }
            compose.setContent {
                if (visible.value) {
                    nav = rememberVociNavigator(vm)
                    // Bounded viewport makes real Page rememberScrollState observable on every AVD.
                    Box(Modifier.height(360.dp)) { VociTheme(reduced = true) { VociNavigation(vm, nav) } }
                }
            }
            contentInstalled = true
            compose.waitForIdle()
            route<Sprint>("FIXTURE: live NavHost starts at Sprint")
            labeled("FIXTURE: valid visible host") { compose.onNode(hasText("Sprint") and heading).assertIsDisplayed() }
            record("fixture-ready")
            body()
        } finally {
            try {
                if (contentInstalled) { compose.runOnIdle { visible.value = false }; compose.waitForIdle() }
            } finally {
                compose.runOnIdle {
                    playback.state.value = original
                    playback.preparing.value = originalPreparing
                    playback.answers.value = originalAnswers
                    playback.uncertain.value = originalUncertain
                    store.clear() // Never vm.cancel(): that would mutate playback ownership.
                    delegates.forEach { (field, value, initialized) ->
                        assertSame("FIXTURE: original lazy delegate preserved", value, field.get(app))
                        assertEquals("FIXTURE: auth/provider must not initialize", initialized, value.isInitialized())
                    }
                    assertEquals("FIXTURE: fixture must leave real playlist empty", 0, PlaybackService.localPlayer!!.mediaItemCount)
                    assertFalse("FIXTURE: real service remains paused", PlaybackService.localPlayer!!.isPlaying)
                    assertEquals("FIXTURE: original playback restored", original, playback.state.value)
                    assertEquals("FIXTURE: original preparation restored", originalPreparing, playback.preparing.value)
                    assertEquals("FIXTURE: original answers restored", originalAnswers, playback.answers.value)
                    assertEquals("FIXTURE: original uncertainty restored", originalUncertain, playback.uncertain.value)
                    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                        putString(statusKey, "$activeCase | cleanup-preserved | delegates, playback, empty Media3; ViewModelStore cleared")
                    })
                }
            }
        }
    }
}
