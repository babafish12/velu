package de.voci

import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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

/** Stored by ViewModelProvider on the ACTUAL entry, never a synthetic ID/owner adapter. */
class R3gEntryProbe : ViewModel() {
    var cleared = false
        private set
    override fun onCleared() { cleared = true }
}

/** Source-only probes. Synthetic paused display, real owners/lifecycles/semantics/dispatcher.
 * Host fixtures are not Activity recreation or OS process death and never play or authenticate.
 */
class R3gNavigationOwnershipTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: VociApp
    private lateinit var vm: VociViewModel
    private lateinit var nav: VociNavigator
    private lateinit var restoration: StateRestorationTester
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private val lesson = Examples.lessons.first()
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private val statusKey = "r3g_ownership"
    private var activeCase = ""

    @Test fun audioBackRetainsActualPlayerOwnerAndToggle_thenOriginBackClearsIt() =
        fixture("audioBackRetainsActualPlayerOwnerAndToggle_thenOriginBackClearsIt") {
            val origin = compose.runOnIdle { nav.currentBackStackEntry!! }
            start("r3g-owner-a")
            val player = compose.runOnIdle { nav.currentBackStackEntry!! }
            val owner = probe(player)
            compose.onNodeWithText("Übersetzung ausblenden").performScrollTo().performClick()
            openAudio()
            val audio = compose.runOnIdle { nav.currentBackStackEntry!! }
            val audioOwner = probe(audio)
            compose.runOnIdle {
                assertEquals(Lifecycle.State.CREATED, player.lifecycle.currentState)
                assertEquals(Lifecycle.State.RESUMED, audio.lifecycle.currentState)
                assertFalse(owner.cleared)
            }
            assertOnlyHeading("Audio")
            compose.onNodeWithText("Übersetzung zeigen").assertDoesNotExist()
            systemBack()
            route<PlayerRoute>("PRODUCT: Audio system Back returns to current Player")
            compose.runOnIdle {
                assertSame("PRODUCT: same actual Player entry object", player, nav.currentBackStackEntry)
                assertSame(owner, ViewModelProvider(nav.currentBackStackEntry!!)[R3gEntryProbe::class.java])
                assertFalse(owner.cleared)
                assertTrue("PRODUCT: popped Audio VM cleared", audioOwner.cleared)
                assertEquals(Lifecycle.State.DESTROYED, audio.lifecycle.currentState)
                assertEquals(Lifecycle.State.RESUMED, player.lifecycle.currentState)
                assertEquals(Lifecycle.State.CREATED, origin.lifecycle.currentState)
            }
            compose.onNodeWithText("Übersetzung zeigen").performScrollTo().performClick()
            compose.onNodeWithText("Übersetzung ausblenden").assertExists()
            systemBack()
            compose.runOnIdle {
                assertSame(origin, nav.currentBackStackEntry)
                assertTrue("PRODUCT: origin Back clears actual Player VM", owner.cleared)
                assertEquals(Lifecycle.State.DESTROYED, player.lifecycle.currentState)
                assertTrue(sessionPages().isEmpty())
            }
            assertOnlyHeading("Sprint")
        }

    @Test fun cancelledPredictiveBackKeepsOwners_committedBackUsesVisibleHostOnly() =
        fixture("cancelledPredictiveBackKeepsOwners_committedBackUsesVisibleHostOnly") {
            start("r3g-gesture")
            val player = compose.runOnIdle { nav.currentBackStackEntry!! }
            val owner = probe(player)
            openAudio()
            val audio = compose.runOnIdle { nav.currentBackStackEntry!! }
            compose.runOnIdle {
                backDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
                backDispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
                backDispatcher.dispatchOnBackCancelled()
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertSame("PRODUCT: cancelled gesture does not pop Audio", audio, nav.currentBackStackEntry)
                assertFalse(owner.cleared)
                assertEquals(Lifecycle.State.CREATED, player.lifecycle.currentState)
            }
            assertOnlyHeading("Audio")
            compose.runOnIdle {
                backDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
                backDispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 100f, 1f, BackEventCompat.EDGE_LEFT))
            }
            systemBack()
            compose.runOnIdle { assertSame("PRODUCT: committed gesture reaches retained Player", player, nav.currentBackStackEntry) }
            systemBack()
            route<Sprint>("PRODUCT: second Back clears session, hidden host cannot consume")
        }

    @Test fun completionWhileAudioVisibleDeliversResultOnce_andReplayDoesNotReviveA() =
        completionOnAudio(recall = false)

    @Test fun completionWhileAudioVisibleDeliversRecallOnce_andReplayDoesNotReviveA() =
        completionOnAudio(recall = true)

    private fun completionOnAudio(recall: Boolean) = fixture("completionOnAudio-recall=$recall") {
        val origin = compose.runOnIdle { nav.currentBackStackEntry!! }
        start("r3g-completion-a", recall)
        val player = compose.runOnIdle { nav.currentBackStackEntry!! }
        val oldOwner = probe(player)
        openAudio()
        val audio = compose.runOnIdle { nav.currentBackStackEntry!! }
        val audioOwner = probe(audio)
        compose.runOnIdle { app.playback.state.value = app.playback.state.value.copy(ended = true, playedMs = 60000) }
        compose.waitForIdle()
        if (recall) {
            route<Recall>("PRODUCT: root delivers Recall while Player hidden")
            assertOnlyHeading("Kurz prüfen")
        } else {
            route<ResultRoute>("PRODUCT: root delivers Result while Player hidden")
            assertOnlyHeading("Sprint beendet")
        }
        val completion = compose.runOnIdle { nav.currentBackStackEntry!! }
        val completionOwner = probe(completion)
        compose.runOnIdle {
            assertEquals(1, sessionPages().size)
            assertTrue("PRODUCT: obsolete suspended Player genuinely cleared", oldOwner.cleared)
            assertFalse("PRODUCT: Audio owner preserved below completion", audioOwner.cleared)
            assertEquals(audio.id, nav.originEntryId)
            assertNull("PRODUCT: completion claimed exactly once", vm.claimCompletion("r3g-completion-a"))
            assertTrue(vm.event.tryEmit("player:r3g-completion-a"))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertSame("PRODUCT: stale event cannot replace completion", completion, nav.currentBackStackEntry) }
        start("r3g-completion-b")
        compose.runOnIdle { assertTrue("PRODUCT: replay clears previous completion owner", completionOwner.cleared) }
        systemBack()
        compose.runOnIdle { assertSame(audio, nav.currentBackStackEntry); assertSame(audioOwner, probeDirect(audio)) }
        systemBack()
        compose.runOnIdle {
            assertSame("PRODUCT: no stale A behind retained Audio", origin, nav.currentBackStackEntry)
            assertTrue(sessionPages().isEmpty())
            assertEquals("r3g-completion-b", app.playback.state.value.session!!.id)
        }
        compose.onNodeWithText("Diese Sitzung ist nicht mehr aktiv.").assertDoesNotExist()
    }

    @Test fun tabSwitchDisposesSuspendedPlayer_butRetainsAudioOwnerAndOrigin() =
        fixture("tabSwitchDisposesSuspendedPlayer_butRetainsAudioOwnerAndOrigin") {
            val origin = compose.runOnIdle { nav.currentBackStackEntry!! }
            start("r3g-tab-a")
            val player = compose.runOnIdle { nav.currentBackStackEntry!! }
            val owner = probe(player)
            openAudio()
            val audio = compose.runOnIdle { nav.currentBackStackEntry!! }
            val audioOwner = probe(audio)
            clickTab("Kategorien")
            compose.runOnIdle {
                assertTrue(owner.cleared)
                assertFalse(audioOwner.cleared)
                assertEquals(Lifecycle.State.DESTROYED, player.lifecycle.currentState)
                assertTrue(sessionPages().isEmpty())
            }
            clickTab("Sprint")
            compose.runOnIdle {
                assertEquals(audio.id, nav.currentBackStackEntry!!.id)
                assertSame(audioOwner, probeDirect(nav.currentBackStackEntry!!))
            }
            assertOnlyHeading("Audio")
            systemBack()
            compose.runOnIdle {
                assertEquals(origin.id, nav.currentBackStackEntry!!.id)
                assertTrue(audioOwner.cleared)
                assertEquals("r3g-tab-a", app.playback.state.value.session!!.id)
            }
        }

    @Test fun completionOnAnotherTabDeliversResultExactlyOnce() = completionOnTab(false)

    @Test fun completionOnAnotherTabDeliversRecallExactlyOnce() = completionOnTab(true)

    private fun completionOnTab(recall: Boolean) = fixture("completionOnOtherTab-recall=$recall") {
        start("r3g-away", recall)
        val player = compose.runOnIdle { nav.currentBackStackEntry!! }
        val oldOwner = probe(player)
        openAudio()
        clickTab("Kategorien")
        val category = compose.runOnIdle { nav.currentBackStackEntry!! }
        compose.runOnIdle {
            assertTrue(oldOwner.cleared)
            app.playback.state.value = app.playback.state.value.copy(ended = true, playedMs = 60000)
        }
        compose.waitForIdle()
        if (recall) route<Recall>("PRODUCT: completion on other tab delivers Recall")
        else route<ResultRoute>("PRODUCT: completion on other tab delivers Result")
        val completion = compose.runOnIdle { nav.currentBackStackEntry!! }
        val owner = probe(completion)
        compose.runOnIdle {
            assertEquals(1, sessionPages().size)
            assertEquals(category.id, nav.originEntryId)
            assertNull("PRODUCT: exactly once", vm.claimCompletion("r3g-away"))
        }
        systemBack()
        compose.runOnIdle {
            assertSame(category, nav.currentBackStackEntry)
            assertTrue(owner.cleared)
            app.playback.state.value = app.playback.state.value.copy(playedMs = 61000)
            assertTrue(vm.event.tryEmit("player:r3g-away"))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(category, nav.currentBackStackEntry); assertTrue(sessionPages().isEmpty()) }
        assertOnlyHeading("Kategorien")
    }

    @Test fun cancellationDisposesSessionOwnersWithoutClearingAudioBranch() =
        fixture("cancellationDisposesSessionOwnersWithoutClearingAudioBranch") {
            start("r3g-cancel")
            val player = compose.runOnIdle { nav.currentBackStackEntry!! }
            val owner = probe(player)
            openAudio()
            val audio = compose.runOnIdle { nav.currentBackStackEntry!! }
            val retained = probe(audio)
            // Synthetic cancellation snapshot only; no coordinator/player commands.
            compose.runOnIdle { app.playback.state.value = PlaybackState() }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(owner.cleared)
                assertFalse(retained.cleared)
                assertSame(audio, nav.currentBackStackEntry)
                assertTrue(sessionPages().isEmpty())
            }
            systemBack()
            route<Sprint>("PRODUCT: cancellation leaves retained origin reachable")
        }

    @Test fun twoSavedAudioBranchesRetainIdsOwnersMarkersAndScrollAcrossHostRestore() =
        fixture("twoSavedAudioBranchesRetainIdsOwnersMarkersAndScrollAcrossHostRestore") {
            start("r3g-host-a")
            openAudio()
            val a1 = compose.runOnIdle { nav.currentBackStackEntry!!.also { it.savedStateHandle["r3g-host"] = "a1" } }
            val owner1 = probe(a1)
            val origin1 = compose.runOnIdle { tabEntries().map { it.id } }
            val scroll1 = scrollToFraction(0.25f)
            clickTab("Einstellungen")
            start("r3g-host-a")
            openAudio()
            val a2 = compose.runOnIdle { nav.currentBackStackEntry!!.also { it.savedStateHandle["r3g-host"] = "a2" } }
            val owner2 = probe(a2)
            val origin2 = compose.runOnIdle { tabEntries().map { it.id } }
            val scroll2 = scrollToFraction(0.75f)
            assertNotEquals("FIXTURE: independent scroll fixtures", scroll1, scroll2)
            clickTab("Kategorien")
            val categories = compose.runOnIdle { nav.currentBackStackEntry!!.id }
            val old = nav
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            compose.runOnIdle {
                assertNotSame("PRODUCT: real tab controller restored", old.tabs, nav.tabs)
                assertNotSame("PRODUCT: real session controller restored", old.sessions, nav.sessions)
                assertEquals(categories, nav.currentBackStackEntry!!.id)
                assertTrue("PRODUCT: no session saved with either tab", sessionPages().isEmpty())
            }
            clickTab("Einstellungen")
            compose.runOnIdle {
                assertEquals("PRODUCT: exact complete a2 origin branch", origin2, tabEntries().map { it.id })
                assertEquals(a2.id, nav.currentBackStackEntry!!.id)
                assertEquals("a2", nav.currentBackStackEntry!!.savedStateHandle.get<String>("r3g-host"))
                assertSame(owner2, probeDirect(nav.currentBackStackEntry!!))
                assertFalse(owner1.cleared)
                assertFalse(owner2.cleared)
            }
            assertEquals("PRODUCT: independent a2 Page saveable scroll", scroll2, scrollValue(), 0.5f)
            clickTab("Sprint")
            compose.runOnIdle {
                assertEquals("PRODUCT: exact complete a1 origin branch", origin1, tabEntries().map { it.id })
                assertEquals(a1.id, nav.currentBackStackEntry!!.id)
                assertEquals("a1", nav.currentBackStackEntry!!.savedStateHandle.get<String>("r3g-host"))
                assertSame(owner1, probeDirect(nav.currentBackStackEntry!!))
            }
            assertEquals("PRODUCT: independent a1 Page saveable scroll", scroll1, scrollValue(), 0.5f)
        }

    private val scroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private fun scrollValue(): Float {
        compose.onAllNodes(scroll).assertCountEquals(1)
        return compose.onNode(scroll).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    }
    private fun scrollToFraction(fraction: Float): Float {
        compose.onAllNodes(scroll).assertCountEquals(1)
        val range = compose.onNode(scroll).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("FIXTURE: overflowing real Audio required", range.maxValue() > 0f)
        compose.onNode(scroll).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue() * fraction) }
        compose.waitForIdle()
        return scrollValue().also { assertTrue("FIXTURE: actual positive Page scroll", it > 0f) }
    }
    private fun tabEntries() = nav.tabs.navigatorProvider.getNavigator(ComposeNavigator::class.java).backStack.value

    private fun start(id: String, recall: Boolean = false) {
        compose.runOnIdle {
            assertTrue("FIXTURE: root event subscription ready", vm.event.subscriptionCount.value > 0)
            app.playback.state.value = PlaybackState(session = PreparedSession(id, lesson, emptyList(), recall, 1f), requestId = id)
            assertTrue(vm.event.tryEmit("player:$id"))
        }
        compose.waitForIdle()
        route<PlayerRoute>("FIXTURE: production event opens actual Player")
        compose.runOnIdle { assertEquals(id, nav.currentBackStackEntry!!.toRoute<PlayerRoute>().sessionId) }
    }
    private fun openAudio() {
        compose.onNodeWithText("Audio").performScrollTo().performClick()
        compose.waitForIdle()
        route<Audio>("FIXTURE: actual Audio action")
    }
    private fun systemBack() {
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun clickTab(label: String) {
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertIsDisplayed().performClick()
        compose.waitForIdle()
    }
    private fun assertOnlyHeading(label: String) {
        compose.onAllNodes(heading).assertCountEquals(1)
        compose.onNode(hasText(label) and heading).performScrollTo().assertIsDisplayed()
    }
    private fun probeDirect(entry: NavBackStackEntry) = ViewModelProvider(entry)[R3gEntryProbe::class.java]
    private fun probe(entry: NavBackStackEntry) = compose.runOnIdle { probeDirect(entry) }
    private fun sessionPages() = nav.sessions.navigatorProvider.getNavigator(ComposeNavigator::class.java).backStack.value
        .filter { !it.destination.hasRoute<SessionIdle>() }
    private inline fun <reified T : Any> route(message: String) = compose.runOnIdle {
        assertTrue(message, nav.currentDestination?.hasRoute<T>() == true)
    }
    private fun labeled(label: String, action: () -> Unit) {
        try { action() }
        catch (failure: AssertionError) { throw AssertionError("$label\n${failure.message}", failure) }
        catch (failure: Exception) { throw AssertionError("$label\n${failure.message}", failure) }
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
                store.put("r3g-ownership", vm)
            }
            restoration = StateRestorationTester(compose)
            restoration.setContent {
                if (visible.value) {
                    nav = rememberVociNavigator(vm)
                    backDispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                    Box(Modifier.height(360.dp)) { VociTheme(reduced = true) { VociNavigation(vm, nav) } }
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
