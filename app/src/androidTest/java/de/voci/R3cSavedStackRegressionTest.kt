package de.voci

import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
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

/** Real root/VM/NavHost, synthetic display state only. Never invokes play(), TTS or transport.
 * The retired legacy B02 is replaced by repeated current session owners plus retained Audio
 * entries through public navigate(), then NavHost saved state;
 * this is NOT OS process death. Parent separately owns natural completion/replay UI evidence.
 * Requires a completed durable baseline, ready empty Media3, and no preparation. No cleanup
 * of real sessions, preferences, DB or files. Auth/provider lazy delegates are never evaluated.
 */
class R3cSavedStackRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: VociApp
    private lateinit var vm: VociViewModel
    private lateinit var nav: VociNavigator
    private lateinit var restoration: StateRestorationTester
    private val lesson = Examples.lessons.first()
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private var originId = ""

    @Test fun savedLibraryAudioStackCannotResurrectCompletedSession_b01() = fixture {
        openLesson()
        start("r3c-b01-a")
        audio()
        val savedIds = compose.runOnIdle {
            assertEquals("FIXTURE: one A before saving tab", listOf("r3c-b01-a"), sessionIds())
            stack().map { it.id }
        }
        record("b01-before-tab-save")
        tab("Einstellungen")
        route<Settings>("FIXTURE: actual Settings tab selected")
        compose.runOnIdle {
            assertTrue("FIXTURE: Library branch must be saved outside active stack: ${trace()}",
                stack().none { it.id in savedIds.drop(1) })
            assertEquals("FIXTURE: saving tab must not complete A", display("r3c-b01-a"), app.playback.state.value)
        }
        complete("r3c-b01-a")
        start("r3c-b01-b") // Synthetic replay through the actual ViewModel -> NavHost event collector.
        pageBack("FIXTURE:")
        route<Settings>("FIXTURE: replay B returns to other tab before Library restoration")
        tab("Bibliothek")
        route<Audio>("FIXTURE: saved Library branch restores Audio")
        compose.runOnIdle {
            assertEquals("FIXTURE: replay B must still be current", display("r3c-b01-b"), app.playback.state.value)
        }
        record("b01-restored-library-audio")
        pageBack("B01 PRODUCT:")
        compose.runOnIdle {
            val current = nav.currentBackStackEntry!!
            assertTrue("B01 PRODUCT: restored Audio must expose lesson or current B, never stale/blank A: ${trace()}",
                current.destination.hasRoute<LessonDetail>() ||
                    (current.destination.hasRoute<PlayerRoute>() && current.toRoute<PlayerRoute>().sessionId == "r3c-b01-b"))
            assertTrue("B01 PRODUCT: obsolete session entries remain: ${trace()}", sessionIds().all { it == "r3c-b01-b" })
        }
        if (compose.runOnIdle { nav.currentDestination!!.hasRoute<PlayerRoute>() }) {
            playerVisible("B01 PRODUCT:")
            pageBack("B01 PRODUCT:")
        }
        origin("B01 PRODUCT:")
        compose.runOnIdle { assertTrue("B01 PRODUCT: no stale player/recall/result after Back: ${trace()}", sessionIds().isEmpty()) }
    }

    // Retires threeEqualLegacyPlayersAreRemovedAfterRestorationAndReplay_b02 ONLY at the
    // user-authorized legacy format boundary. Immutable R3G0A owns original parcel migration.
    @Test fun repeatedCurrentOwnersAndTwoAudioEntriesSurviveHostRestore_thenSupersede() = fixture {
        openLesson()
        start("r3g-current-a")
        audio()
        val a1 = compose.runOnIdle { nav.currentBackStackEntry!!.also {
            it.savedStateHandle["r3g-audio"] = "first"
        }.id }
        // A second actual equal Audio entry, with independent owner state, above a1.
        compose.runOnIdle { nav.navigate(Audio) }
        compose.waitForIdle()
        val a2 = compose.runOnIdle { nav.currentBackStackEntry!!.also {
            it.savedStateHandle["r3g-audio"] = "second"
        }.id }
        compose.runOnIdle {
            // Repeated route-equal owners in the session domain, BEFORE host save/restore.
            repeat(2) { nav.sessions.navigate(PlayerRoute("r3g-current-a")) }
            sessionStack().filter { it.destination.hasRoute<PlayerRoute>() }.forEachIndexed { index, entry ->
                entry.savedStateHandle["r3g-player"] = "player-$index"
            }
        }
        compose.waitForIdle()
        val before = compose.runOnIdle { stack().map { it.id } }
        val sessionBefore = compose.runOnIdle { sessionStack().map { it.id } }
        val obsolete = compose.runOnIdle {
            val entries = sessionStack().filter { it.destination.hasRoute<PlayerRoute>() }
            assertEquals(3, entries.size)
            assertEquals(3, entries.map { it.id }.toSet().size)
            entries.map { androidx.lifecycle.ViewModelProvider(it)[R3gEntryProbe::class.java] }
        }
        val audioOwners = compose.runOnIdle { listOf(a1, a2).map { id ->
            androidx.lifecycle.ViewModelProvider(stack().single { it.id == id })[R3gEntryProbe::class.java]
        } }
        val oldNav = nav
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotSame("CURRENT PRODUCT: actual tab controller recreated", oldNav.tabs, nav.tabs)
            assertNotSame("CURRENT PRODUCT: actual session controller recreated", oldNav.sessions, nav.sessions)
            assertEquals("CURRENT PRODUCT: every retained origin/Audio ID in order", before, stack().map { it.id })
            assertEquals("CURRENT PRODUCT: repeated equal session entry IDs/order restore", sessionBefore, sessionStack().map { it.id })
            assertEquals("CURRENT PRODUCT: a1 state independent", "first", stack().single { it.id == a1 }.savedStateHandle.get<String>("r3g-audio"))
            assertEquals("CURRENT PRODUCT: a2 state independent", "second", stack().single { it.id == a2 }.savedStateHandle.get<String>("r3g-audio"))
            listOf(a1, a2).forEachIndexed { index, id ->
                assertSame("CURRENT PRODUCT: Audio ViewModel retained in same-process host simulation", audioOwners[index],
                    androidx.lifecycle.ViewModelProvider(stack().single { it.id == id })[R3gEntryProbe::class.java])
            }
            sessionStack().filter { it.destination.hasRoute<PlayerRoute>() }.forEachIndexed { index, entry ->
                assertEquals("CURRENT PRODUCT: equal Player state is independent", "player-$index", entry.savedStateHandle.get<String>("r3g-player"))
                assertSame(obsolete[index], androidx.lifecycle.ViewModelProvider(entry)[R3gEntryProbe::class.java])
            }
            assertEquals(display("r3g-current-a"), app.playback.state.value)
        }
        complete("r3g-current-a")
        start("r3g-current-b")
        pageBack("CURRENT PRODUCT:")
        route<Audio>("CURRENT PRODUCT: replay Back returns to retained a2")
        compose.runOnIdle {
            assertEquals(before, stack().map { it.id })
            assertTrue("CURRENT PRODUCT: obsolete equal Player owners cleared", obsolete.all { it.cleared })
            assertTrue("CURRENT PRODUCT: no surviving session pages", sessionIds().isEmpty())
        }
        pageBack("CURRENT PRODUCT:")
        compose.runOnIdle { assertEquals(a1, nav.currentBackStackEntry!!.id) }
        pageBack("CURRENT PRODUCT:")
        origin("CURRENT PRODUCT:")
        compose.runOnIdle { assertEquals(display("r3g-current-b"), app.playback.state.value) }
    }

    @Test fun nonSessionSavedLibraryDetailRetainsState_positiveControl() = fixture {
        tab("Bibliothek")
        route<Library>("FIXTURE: Library selected")
        labeled("FIXTURE: set Library search and language") {
            compose.onNodeWithText("Suchen").performTextInput("Restaurant")
            compose.onNodeWithText("IT").performClick()
        }
        val libraryId = compose.runOnIdle { nav.currentBackStackEntry!!.id }
        openVisibleLesson()
        tab("Einstellungen")
        route<Settings>("FIXTURE: other tab selected")
        val oldNav = nav
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.runOnIdle { assertNotSame("FIXTURE: tab NavHost must be recreated", oldNav.tabs, nav.tabs) }
        tab("Bibliothek")
        origin("CONTROL:")
        pageBack("CONTROL:")
        route<Library>("CONTROL: Back must reach preserved Library")
        compose.runOnIdle {
            assertEquals("CONTROL: original Library entry retained", libraryId, nav.currentBackStackEntry!!.id)
            assertEquals("CONTROL: search retained", "Restaurant", vm.query.value)
            assertEquals("CONTROL: language filter retained", "it-IT", vm.filter.value)
            assertEquals("CONTROL: non-session navigation leaves exact empty playback", PlaybackState(), app.playback.state.value)
        }
        labeled("CONTROL: restored search/filter must be rendered") {
            compose.onNode(hasSetTextAction()).assertTextContains("Restaurant")
            compose.onNodeWithText("IT").assertIsSelected()
            compose.onNode(hasText(lesson.title) and hasClickAction()).assertIsDisplayed()
        }
    }

    @Test fun currentPlayerAudioBackRetainsUsablePlayer_positiveControl() = fixture {
        openLesson()
        start("r3c-control-current")
        val playerId = compose.runOnIdle { nav.currentBackStackEntry!!.id }
        labeled("FIXTURE: change only saveable display state") {
            compose.onNodeWithText("Übersetzung ausblenden").performScrollTo().performClick()
            compose.onNodeWithText("Übersetzung zeigen").assertExists()
        }
        audio()
        pageBack("CONTROL:")
        route<PlayerRoute>("CONTROL: Audio Back must return to current Player")
        compose.runOnIdle {
            assertEquals("CONTROL: same Player entry instance identity", playerId, nav.currentBackStackEntry!!.id)
            assertEquals("CONTROL: current Player session", "r3c-control-current", nav.currentBackStackEntry!!.toRoute<PlayerRoute>().sessionId)
            assertEquals("CONTROL: display/navigation must not alter playback", display("r3c-control-current"), app.playback.state.value)
        }
        playerVisible("CONTROL:")
        labeled("CONTROL: saved display toggle remains usable") {
            compose.onNodeWithText("Übersetzung zeigen").performScrollTo().performClick()
            compose.onNodeWithText("Übersetzung ausblenden").assertExists()
        }
        pageBack("CONTROL:")
        origin("CONTROL:")
    }

    private fun display(id: String) = PlaybackState(
        session = PreparedSession(id, lesson, emptyList(), false, 1f), requestId = id)

    private fun start(id: String) {
        compose.runOnIdle {
            assertNull("FIXTURE: no preparation before player event", app.playback.preparing.value)
            assertTrue("FIXTURE: actual NavHost event collector must be subscribed", vm.event.subscriptionCount.value > 0)
            assertNotEquals("FIXTURE: each replay requires a distinct session", id, app.playback.state.value.session?.id)
            app.playback.state.value = display(id)
            assertTrue("FIXTURE: player event enqueue failed", vm.event.tryEmit("player:$id"))
        }
        compose.waitForIdle()
        route<PlayerRoute>("FIXTURE: real player event must open Player")
        compose.runOnIdle {
            assertEquals("FIXTURE: exact event route identity", id, nav.currentBackStackEntry!!.toRoute<PlayerRoute>().sessionId)
            assertEquals("FIXTURE: exact current/replay state", display(id), app.playback.state.value)
        }
        playerVisible("FIXTURE:")
        record("player-$id")
    }

    private fun complete(id: String) {
        compose.runOnIdle {
            assertEquals("FIXTURE: exact incomplete A before synthetic end", display(id), app.playback.state.value)
            app.playback.state.value = display(id).copy(ended = true, playedMs = 60000)
        }
        compose.waitForIdle()
        route<ResultRoute>("FIXTURE: real completion observer must deliver Result(A)")
        compose.runOnIdle {
            assertEquals("FIXTURE: result session ID", id, nav.currentBackStackEntry!!.toRoute<ResultRoute>().sessionId)
            assertEquals("FIXTURE: exact ended state", display(id).copy(ended = true, playedMs = 60000), app.playback.state.value)
        }
        labeled("FIXTURE: visible result") { compose.onNodeWithText("Nochmals").assertIsDisplayed() }
        record("completed-$id")
    }

    private fun stack() = nav.tabs.navigatorProvider.getNavigator(ComposeNavigator::class.java).backStack.value
    private fun sessionId(e: NavBackStackEntry): String? = when {
        e.destination.hasRoute<PlayerRoute>() -> e.toRoute<PlayerRoute>().sessionId
        e.destination.hasRoute<Recall>() -> e.toRoute<Recall>().sessionId
        e.destination.hasRoute<ResultRoute>() -> e.toRoute<ResultRoute>().sessionId
        else -> null
    }
    private fun sessionStack() = nav.sessions.navigatorProvider.getNavigator(ComposeNavigator::class.java).backStack.value
    private fun sessionIds() = sessionStack().mapNotNull(::sessionId)
    private fun trace() = (stack() + sessionStack()).joinToString(" -> ") { "${it.destination.route}[entry=${it.id},session=${sessionId(it)}]" }
    private fun record(stage: String) {
        val trace = compose.runOnIdle { trace() }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("r3c_synthetic_navigation", "$stage: $trace")
        })
    }
    private inline fun <reified T : Any> route(message: String) = compose.runOnIdle {
        assertTrue("$message: ${trace()}", nav.currentDestination?.hasRoute<T>() == true)
    }
    private fun tab(label: String) = labeled("FIXTURE: real bottom tab '$label'") {
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertIsDisplayed().performClick()
        compose.waitForIdle()
    }
    private fun pageBack(prefix: String) = labeled("FIXTURE: actual page Back") {
        compose.onAllNodes(heading).assertCountEquals(1)
        val title = compose.onNode(heading).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
        // Page's Row centers Back beside the heading; flattened semantic siblings also include
        // AudioTransport. Use unclipped layout geometry so scrolling cannot skew those centers.
        val pageBack = hasText("Zurück") and hasClickAction() and hasAnySibling(heading) and
            SemanticsMatcher("beside and vertically centered on the unique page heading") { node ->
                val buttonPosition = node.positionInRoot
                val titlePosition = title.positionInRoot
                node.size.width > 0 && node.size.height > 0 &&
                    kotlin.math.abs(buttonPosition.y + node.size.height / 2f -
                        (titlePosition.y + title.size.height / 2f)) <= 1f &&
                    (buttonPosition.x + node.size.width <= titlePosition.x ||
                        titlePosition.x + title.size.width <= buttonPosition.x)
            }
        compose.onAllNodes(pageBack).assertCountEquals(1)
        val button = compose.onNode(pageBack).performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNode(heading).assertIsDisplayed()
        compose.onAllNodes(pageBack).assertCountEquals(1)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("r3c_synthetic_navigation", "FIXTURE: page Back verified for $prefix " +
                "heading=${title.boundsInRoot}, button=${button.fetchSemanticsNode().boundsInRoot}")
        })
        button.performClick()
        compose.waitForIdle()
    }
    private fun audio() {
        labeled("FIXTURE: Player Audio action") { compose.onNodeWithText("Audio").performScrollTo().performClick() }
        compose.waitForIdle()
        route<Audio>("FIXTURE: Player action opens Audio")
        labeled("FIXTURE: visible Audio") { compose.onNode(hasText("Audio") and heading).assertIsDisplayed() }
    }
    private fun playerVisible(prefix: String) = labeled("$prefix Player must be populated and usable") {
        compose.onNode(hasText(lesson.title) and heading).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Wiederholen").assertExists().assertIsEnabled()
        compose.onNodeWithText("Audio").assertExists().assertIsEnabled()
        compose.onNodeWithText("Diese Sitzung ist nicht mehr aktiv.").assertDoesNotExist()
    }
    private fun openLesson() { tab("Bibliothek"); route<Library>("FIXTURE: Library selected"); openVisibleLesson() }
    private fun openVisibleLesson() {
        labeled("FIXTURE: bundled lesson must be present") {
            compose.waitUntil(5000) { vm.lessons.value.any { it.id == lesson.id } }
            compose.onNode(hasText(lesson.title) and hasClickAction()).performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Abspielen").fetchSemanticsNodes().size == 1 }
        }
        route<LessonDetail>("FIXTURE: real lesson row opens details")
        compose.runOnIdle {
            assertEquals("FIXTURE: exact lesson origin", lesson.id, nav.currentBackStackEntry!!.toRoute<LessonDetail>().lessonId)
            originId = nav.currentBackStackEntry!!.id
            nav.currentBackStackEntry!!.savedStateHandle["r3c-origin"] = "retained-detail-state"
        }
        origin("FIXTURE:")
    }
    private fun origin(prefix: String) {
        route<LessonDetail>("$prefix Back must reach valid originating lesson")
        compose.runOnIdle {
            assertEquals("$prefix lesson ID", lesson.id, nav.currentBackStackEntry!!.toRoute<LessonDetail>().lessonId)
            assertEquals("$prefix original detail entry ID retained", originId, nav.currentBackStackEntry!!.id)
            assertEquals("$prefix original detail saved state retained", "retained-detail-state", nav.currentBackStackEntry!!.savedStateHandle.get<String>("r3c-origin"))
        }
        labeled("$prefix valid visible lesson detail") {
            // Detail content is a real asynchronous repository read after its composition returns.
            compose.waitUntil(5000) { compose.onAllNodesWithText("Abspielen").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Abspielen").performScrollTo().assertIsDisplayed()
        }
    }
    private fun labeled(label: String, action: () -> Unit) {
        try { action() } catch (failure: AssertionError) {
            throw AssertionError("$label\n${failure.message}", failure)
        } catch (failure: Exception) {
            throw AssertionError("$label\n${failure.message}", failure)
        }
    }

    private fun fixture(body: () -> Unit) {
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
                store.put("r3c-navigation", vm)
            }
            restoration = StateRestorationTester(compose)
            restoration.setContent {
                if (visible.value) {
                    nav = rememberVociNavigator(vm)
                    VociTheme(reduced = true) { VociNavigation(vm, nav) }
                }
            }
            contentInstalled = true
            compose.waitForIdle()
            route<Sprint>("FIXTURE: live NavHost starts at Sprint")
            labeled("FIXTURE: valid visible host") { compose.onNode(hasText("Sprint") and heading).assertIsDisplayed() }
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
                }
            }
        }
    }
}
