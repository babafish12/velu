package de.voci

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.toRoute
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.PlaybackService
import de.voci.audio.PlaybackState
import de.voci.audio.PreparedSession
import de.voci.data.Examples
import de.voci.navigation.*
import de.voci.navigation.Result as ResultRoute
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic display-state integration through the real root, ViewModel and NavHost.
 * No playback commands, preferences, credentials, database writes or network.
 * Parent must first naturally finish its session and restart into an empty Media3 playlist.
 */
class R3bCompletionNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: VociNavigator
    private lateinit var vm: VociViewModel
    private lateinit var app: VociApp
    private lateinit var handle: SavedStateHandle
    private lateinit var restoration: StateRestorationTester
    private val lesson = Examples.lessons.first()

    @Test fun completionAwayFromPlayerOpensResultWithoutRecall() = fixture {
        start("away", false)
        compose.runOnIdle { assertTrue(nav.popBackStack()) }
        finish()
        assertResult("away")
        compose.onNodeWithText("Nochmals").assertExists()
        backToDetail()
    }

    @Test fun completionAwayFromPlayerOpensOptInRecall() = fixture {
        start("recall", true)
        compose.runOnIdle { assertTrue(nav.popBackStack()) }
        finish()
        compose.runOnIdle { assertEquals("recall", nav.currentBackStackEntry!!.toRoute<Recall>().sessionId) }
        compose.onNodeWithText("Kurz prüfen").assertExists()
        compose.onNodeWithText("Überspringen").performScrollTo().performClick()
        assertResult("recall")
        backToDetail()
    }

    @Test fun rootAndPlayerCompletionObserversDeliverOnceAndBackStaysBack() = fixture {
        start("both", false)
        finish()
        assertResult("both")
        backToDetail()
        compose.runOnIdle {
            app.playback.state.value = app.playback.state.value.copy(playedMs = 61000)
            assertNull(vm.claimCompletion("both"))
        }
        compose.waitForIdle()
        assertDetail()
    }

    @Test fun restoredNavigationAndViewModelDoNotDeliverAgain() = fixture {
        start("restored", false); finish(); assertResult("restored")
        val restoredHandle = compose.runOnIdle {
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        }
        restoration.emulateSavedInstanceStateRestore()
        assertResult("restored")
        backToDetail()
        val owner = ViewModelStore()
        try {
            compose.runOnIdle {
                val restoredVm = VociViewModel(app, restoredHandle)
                owner.put("restored", restoredVm)
                assertNull(restoredVm.claimCompletion("restored"))
            }
            restoration.emulateSavedInstanceStateRestore()
            assertDetail()
        } finally { compose.runOnIdle { owner.clear() } }
    }

    @Test fun multipleReplayCyclesPreserveLessonAndCategoryBackStack() = fixture(category = true) {
        for (id in listOf("first", "second", "third")) {
            start(id, false)
            // Back from a fresh replay must never expose the previous result.
            backToDetail()
            compose.runOnIdle { nav.openPlayer(id) }
            finish(); assertResult(id)
        }
        backToDetail()
        compose.runOnIdle {
            assertTrue(nav.popBackStack())
            assertEquals("restaurant", nav.currentBackStackEntry!!.toRoute<CategoryDetail>().categoryId)
            assertTrue(nav.popBackStack())
            assertTrue(nav.currentDestination!!.hasRoute<Categories>())
        }
    }

    @Test fun replayReplacesRecallAndOlderRestoredSessionRoutesByIdentity() = fixture {
        // Structural adapter: repeat actual entries in the independent session host.
        // This is current-host cleanup coverage, NOT a replay of a legacy Activity Bundle.
        compose.runOnIdle {
            nav.sessions.navigate(ResultRoute("obsolete-a"))
            nav.sessions.navigate(Recall("obsolete-b"))
            nav.sessions.navigate(ResultRoute("obsolete-b"))
        }
        start("fresh", true)
        backToDetail()
        finish()
        compose.runOnIdle { assertEquals("fresh", nav.currentBackStackEntry!!.toRoute<Recall>().sessionId) }
        backToDetail()
    }

    @Test fun stalePlayerEventCannotCoverCurrentCompletion() = fixture {
        start("current", false); finish(); assertResult("current")
        compose.runOnIdle { assertTrue(vm.event.tryEmit("player:obsolete")) }
        compose.waitForIdle()
        assertResult("current")
    }

    private fun start(id: String, recall: Boolean) {
        compose.runOnIdle {
            app.playback.state.value = PlaybackState(
                session = PreparedSession(id, lesson, emptyList(), recall, 1f), requestId = id)
            assertTrue(vm.event.tryEmit("player:$id"))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(id, nav.currentBackStackEntry!!.toRoute<PlayerRoute>().sessionId) }
    }
    private fun finish() {
        compose.runOnIdle { app.playback.state.value = app.playback.state.value.copy(ended = true, playedMs = 60000) }
        compose.waitForIdle()
    }
    private fun assertResult(id: String) = compose.runOnIdle {
        assertEquals(id, nav.currentBackStackEntry!!.toRoute<ResultRoute>().sessionId)
        assertEquals(1, nav.sessions.navigatorProvider.getNavigator(ComposeNavigator::class.java).backStack.value.count {
            it.destination.hasRoute<PlayerRoute>() || it.destination.hasRoute<Recall>() || it.destination.hasRoute<ResultRoute>()
        })
    }
    private fun backToDetail() {
        compose.runOnIdle { assertTrue(nav.popBackStack()) }
        compose.waitForIdle()
        assertDetail()
        compose.onNodeWithText("Diese Sitzung ist nicht mehr aktiv.").assertDoesNotExist()
    }
    private fun assertDetail() = compose.runOnIdle {
        assertEquals(lesson.id, nav.currentBackStackEntry!!.toRoute<LessonDetail>().lessonId)
    }
    private fun fixture(category: Boolean = false, body: () -> Unit) {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        val playback = compose.runOnIdle { app.playback }
        runBlocking { playback.restore() }
        compose.runOnIdle {
            assertNotNull("FIXTURE: Media3 must be ready", PlaybackService.localPlayer)
            assertEquals("FIXTURE: requires empty playlist, never clear real audio", 0, PlaybackService.localPlayer!!.mediaItemCount)
            assertNull("FIXTURE: no running preparation", playback.preparing.value)
        }
        val original = compose.runOnIdle { playback.state.value }
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        try {
            compose.runOnIdle {
                playback.state.value = PlaybackState()
                handle = SavedStateHandle()
                vm = VociViewModel(app, handle)
                store.put("navigation", vm)
            }
            restoration = StateRestorationTester(compose)
            restoration.setContent {
                if (visible.value) {
                    nav = rememberVociNavigator(vm)
                    VociTheme(reduced = true) { VociNavigation(vm, nav) }
                }
            }
            compose.waitForIdle()
            compose.runOnIdle {
                if (category) { nav.navigate(Categories); nav.navigate(CategoryDetail("restaurant")) }
                else nav.navigate(Library)
                nav.navigate(LessonDetail(lesson.id))
            }
            compose.waitForIdle()
            body()
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { playback.state.value = original; store.clear() }
        }
    }
}
