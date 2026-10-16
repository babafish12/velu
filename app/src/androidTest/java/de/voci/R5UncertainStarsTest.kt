package de.voci

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.LessonProvider
import de.voci.audio.PlaybackService
import de.voci.domain.LessonContent
import de.voci.domain.SessionSpec
import de.voci.domain.VocabItem
import de.voci.features.library.LessonDetailScreen
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * R5 Library stars only; real screen, ViewModel and repository, no player selector UI.
 * Expected on unchanged R4: positive control passes; the other three fail R5 STAR PRODUCT
 * at the first absent star. Build/execution belongs to Parent; these are not executed evidence.
 * Prerequisite: idle local Compose host with no restored session/queued media or preparation.
 * No login, voices, synthesis or network required. The provider override rejects generation;
 * the existing coordinator checkpoint cancels an accidental start before voice lookup/TTS.
 * Preferences and app lazy delegates are never replaced. Only UUID-owned lessons are deleted.
 */
class R5UncertainStarsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun libraryPlaybackActionsAndContentRemain_positiveControl() = fixture { f ->
        f.open(f.lessons.first())
        val lesson = f.lessons.first()
        lesson.items.forEach { item ->
            required(hasContentDescription("Eintrag abspielen: ${item.target}"))
                .performScrollTo().assertIsDisplayed().assertHasClickAction()
            listOf(item.target, item.translation, item.example).forEach { text ->
                required(hasText(text)).performScrollTo().assertIsDisplayed()
            }
        }
        required(hasText("Abspielen")).assertHasClickAction()
        assertEquals("R5 STAR PRODUCT: original Library content", lesson,
            runBlocking { f.app.repository.content(lesson.id) })
        f.unchangedPlayback()
    }

    @Test fun libraryStarsToggleIndependentlyWithoutPlaybackOrNavigation() = fixture { f ->
        val lesson = f.lessons.first()
        val (a, b) = lesson.items
        f.open(lesson)
        star(a, false)
        star(b, false)
        f.toggle(a, false)
        star(b, false)
        f.toggle(a, true) // A alone: checked -> unchecked.
        f.toggle(a, false)
        f.toggle(b, false)
        f.toggle(a, true) // Removing A must retain B.
        star(a, false)
        star(b, true)
        assertEquals("R5 STAR PRODUCT: marking preserves lesson content", lesson,
            runBlocking { f.app.repository.content(lesson.id) })
    }

    @Test fun libraryMarksSurviveReopenAndNewViewModelAndStayLessonScoped() = fixture { f ->
        val first = f.lessons[0]
        val second = f.lessons[1]
        val a = first.items[0]
        f.open(first)
        f.toggle(a, false)
        f.open(first) // Disposes/re-enters the detail composition in the same host.
        star(a, true)
        f.recreateViewModel()
        f.open(first)
        star(a, true)
        star(first.items[1], false)

        // Same IDs in another real repository lesson must start unmarked.
        assertEquals("FIXTURE: identical item IDs across distinct lessons",
            first.items.map { it.id }, second.items.map { it.id })
        f.open(second)
        second.items.forEach { star(it, false) }
        f.toggle(second.items[1], false)
        f.open(first)
        star(a, true)
        star(first.items[1], false)

        // A changed label and position still refer to A's stable identity.
        f.closeDetail()
        val revised = first.copy(items = first.items.reversed().map {
            if (it.id == a.id) it.copy(target = "Renamed blue entry") else it
        })
        runBlocking { f.app.repository.save(revised) }
        f.lessons[0] = revised
        f.recreateViewModel()
        f.open(revised)
        star(revised.items.first(), false)
        star(revised.items.last(), true)
        f.open(second)
        star(second.items.first(), false)
        star(second.items.last(), true)
        f.unchangedPlayback()
    }

    @Test fun libraryStarsHaveSeparateTrailing48DpTargetsAt200PercentFont() = fixture(2f) { f ->
        val lesson = f.lessons.first()
        f.open(lesson)
        lesson.items.forEach { item ->
            val toggle = star(item, false).performScrollTo().assertIsDisplayed()
            toggle.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val play = required(hasContentDescription("Eintrag abspielen: ${item.target}"))
                .assertIsDisplayed().assertHasClickAction()
            val starBounds = toggle.fetchSemanticsNode().boundsInRoot
            val playBounds = play.fetchSemanticsNode().boundsInRoot
            assertTrue("R5 STAR PRODUCT: star is separately to the right, with no action overlap: " +
                "play=$playBounds star=$starBounds", starBounds.left >= playBounds.right)
            assertTrue("R5 STAR PRODUCT: star shares the playback action row",
                starBounds.top < playBounds.bottom && playBounds.top < starBounds.bottom)
            readable(play)
            listOf(item.target, item.translation, item.example).forEach { text ->
                readable(required(hasText(text)).performScrollTo().assertIsDisplayed())
            }
            f.toggle(item, false)
            f.toggle(item, true)
        }
    }

    private fun required(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // Page is an eager scrollable Column in R4. No wait for a missing product feature.
        val count = compose.onAllNodes(matcher).fetchSemanticsNodes().size
        if (count != 1) throw AssertionError("R5 STAR PRODUCT: expected one $matcher; found $count")
        return compose.onNode(matcher)
    }

    private fun description(item: VocabItem, checked: Boolean) =
        if (checked) "Unsicher-Markierung entfernen: ${item.target}" else "Als unsicher markieren: ${item.target}"

    private fun star(item: VocabItem, checked: Boolean): SemanticsNodeInteraction {
        val node = required(hasContentDescription(description(item, checked)))
        product {
            node.assertHasClickAction()
            if (checked) node.assertIsOn() else node.assertIsOff()
            compose.onNodeWithContentDescription(description(item, !checked)).assertDoesNotExist()
        }
        return node
    }

    private fun readable(node: SemanticsNodeInteraction) = product {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("R5 STAR PRODUCT: text layout available", action(results))
        }
        assertTrue("R5 STAR PRODUCT: text layout is nonempty", results.isNotEmpty())
        results.forEach { result ->
            assertEquals("R5 STAR PRODUCT: real 200% font scale", 2f, result.layoutInput.density.fontScale, 0f)
            assertTrue("R5 STAR PRODUCT: positive measured text size", result.size.width > 0 && result.size.height > 0)
            // Paragraph layout width can retain available width while the Text node wraps to intrinsic width.
            // Check rendered line extents instead, allowing only 1 px for pixel rounding.
            assertTrue("R5 STAR PRODUCT: full text height fits", result.multiParagraph.height <= result.size.height + 1f)
            assertFalse("R5 STAR PRODUCT: no exceeded max lines", result.multiParagraph.didExceedMaxLines)
            for (line in 0 until result.lineCount) {
                assertTrue("R5 STAR PRODUCT: line $line left fits", result.getLineLeft(line) >= -1f)
                assertTrue("R5 STAR PRODUCT: line $line right fits", result.getLineRight(line) <= result.size.width + 1f)
                assertFalse("R5 STAR PRODUCT: text must not be ellipsized", result.isLineEllipsized(line))
            }
        }
    }

    private fun product(block: () -> Unit) {
        try { block() } catch (e: AssertionError) {
            throw AssertionError("R5 STAR PRODUCT: ${e.message}", e)
        }
    }

    private fun eventually(label: String, condition: () -> Boolean) {
        try { compose.waitUntil(5_000, condition) }
        catch (e: ComposeTimeoutException) { throw AssertionError(label, e) }
    }

    private inner class Fixture(val app: VociApp, val lessons: MutableList<LessonContent>, val fontScale: Float) {
        private val store = ViewModelStore()
        private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val selected = mutableStateOf<String?>(null)
        private var vm by mutableStateOf(newViewModel())
        private val events = mutableListOf<String>()
        private var eventJob: Job? = null
        private var backCalls = 0
        private var mounted = false
        private val originalState = app.playback.state.value
        private val originalUncertain = app.playback.uncertain.value
        private val originalAnswers = app.playback.answers.value
        private val originalCheckpoint = app.playback.checkpoint
        private var attemptedStarts = 0
        private val baseline = snapshot()

        private fun newViewModel() = VociViewModel(app, SavedStateHandle(), object : LessonProvider {
            override suspend fun generate(spec: SessionSpec): LessonContent =
                throw AssertionError("R5 STAR PRODUCT: Library marking must not generate a lesson")
        }).also { store.put("r5-stars", it) }

        fun mount() {
            app.playback.checkpoint = { stage, id ->
                if (stage == "prepare" && lessons.any { it.id == id }) {
                    attemptedStarts++
                    throw CancellationException("R5 STAR PRODUCT: forbidden audio start stopped before TTS")
                }
                originalCheckpoint(stage, id)
            }
            observeEvents()
            compose.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalLayoutDirection provides LayoutDirection.Ltr
                ) {
                    VociTheme(reduced = true) {
                        selected.value?.let { id -> LessonDetailScreen(id, vm) { backCalls++ } }
                    }
                }
            }
            mounted = true
        }

        private fun observeEvents() {
            eventJob = owner.launch(start = CoroutineStart.UNDISPATCHED) { vm.event.collect { events += it } }
        }

        fun closeDetail() {
            compose.runOnIdle { selected.value = null }
            compose.waitForIdle()
        }

        fun open(lesson: LessonContent) {
            closeDetail()
            compose.runOnIdle { selected.value = lesson.id }
            eventually("FIXTURE: persisted lesson detail did not load") {
                compose.onAllNodesWithText(lesson.items.first().target).fetchSemanticsNodes().size == 1
            }
        }

        fun recreateViewModel() {
            closeDetail()
            compose.runOnIdle {
                eventJob?.cancel()
                store.clear()
                vm = newViewModel()
                observeEvents()
            }
        }

        fun toggle(item: VocabItem, wasChecked: Boolean) {
            star(item, wasChecked).performScrollTo().performClick()
            // Allow asynchronous repository writes, but fail absent nodes explicitly above.
            eventually("R5 STAR PRODUCT: star did not change checked state for ${item.id}") {
                compose.onAllNodesWithContentDescription(description(item, !wasChecked))
                    .fetchSemanticsNodes().size == 1
            }
            star(item, !wasChecked)
            unchangedPlayback()
        }

        // Read real coordinator AND Media3 state. vm.uncertain is NOT the Library store.
        private fun snapshot(): List<Any?> {
            val state = app.playback.state.value
            val player = PlaybackService.localPlayer
            return listOf(state.session, state.index, state.requestId, state.playing,
                app.playback.preparing.value, player, player?.currentMediaItemIndex,
                player?.currentMediaItem?.mediaId, player?.mediaItemCount,
                player?.isPlaying, player?.playWhenReady)
        }

        fun unchangedPlayback() = compose.runOnIdle {
            assertEquals("R5 STAR PRODUCT: actual session/index/Media3 queue unchanged; before=$baseline",
                baseline, snapshot())
            assertEquals("R5 STAR PRODUCT: no audio preparation attempted", 0, attemptedStarts)
            assertEquals("R5 STAR PRODUCT: no navigation events", emptyList<String>(), events)
            assertEquals("R5 STAR PRODUCT: no back navigation", 0, backCalls)
            assertFalse("R5 STAR PRODUCT: no generation or play request", vm.busy.value)
        }

        fun dispose() {
            try {
                if (mounted) {
                    // Remove only our marks through real UI, regardless of the eventual storage schema.
                    for (lesson in lessons) {
                        open(lesson)
                        for (item in lesson.items) {
                            if (compose.onAllNodesWithContentDescription(description(item, true))
                                    .fetchSemanticsNodes().size == 1) {
                                compose.onNodeWithContentDescription(description(item, true))
                                    .performScrollTo().performClick()
                                eventually("FIXTURE: remove only owned Library mark ${item.id}") {
                                    compose.onAllNodesWithContentDescription(description(item, false))
                                        .fetchSemanticsNodes().size == 1
                                }
                            }
                        }
                    }
                    closeDetail()
                }
            } finally {
                compose.runOnIdle {
                    vm.cancel()
                    store.clear()
                    owner.cancel()
                    app.playback.checkpoint = originalCheckpoint
                    // Cleanup only after observations; never seed marks or simulate persistence.
                    app.playback.state.value = originalState
                    app.playback.uncertain.value = originalUncertain
                    app.playback.answers.value = originalAnswers
                }
            }
        }
    }

    private fun fixture(fontScale: Float = 1f, body: (Fixture) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        // Await real restoration before asserting the prerequisite; never erase a user's session.
        runBlocking { withContext(Dispatchers.Main) { withTimeout(8_000) { app.playback.restore() } } }
        compose.runOnIdle {
            assertNull("FIXTURE: Parent must supply a host without a restored session", app.playback.state.value.session)
            assertNull("FIXTURE: no concurrent preparation", app.playback.preparing.value)
            val player = PlaybackService.localPlayer ?: throw AssertionError("FIXTURE: real Media3 player unavailable")
            assertEquals("FIXTURE: no queued media", 0, player.mediaItemCount)
            assertFalse("FIXTURE: no active playback", player.isPlaying)
        }
        val preferences = runBlocking { app.preferences.flow.first() }
        val token = UUID.randomUUID().toString()
        val items = listOf(
            VocabItem("r5-$token-a", "Blue entry", "Der blaue Eintrag", "This is the blue entry.", "Das ist der blaue Eintrag."),
            VocabItem("r5-$token-b", "Green entry", "Der grüne Eintrag", "This is the green entry.", "Das ist der grüne Eintrag.")
        )
        val lessons = (1..2).map { LessonContent("r5-stars-$token-$it", "R5 Library $it", "en-GB", items) }.toMutableList()
        val created = mutableListOf<String>()
        var fixture: Fixture? = null
        var failure: Throwable? = null
        try {
            runBlocking {
                lessons.forEach {
                    assertNull("FIXTURE: synthetic identity must be unused", app.db.dao().lesson(it.id))
                    app.repository.save(it)
                    created += it.id
                }
            }
            val active = compose.runOnIdle { Fixture(app, lessons, fontScale) }
            fixture = active
            active.mount()
            body(active)
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            val cleanup = runCatching { fixture?.dispose() }.exceptionOrNull()
            val deletion = runCatching {
                runBlocking { created.forEach { app.db.dao().deleteLesson(it) } }
                assertEquals("FIXTURE: preferences unchanged", preferences, runBlocking { app.preferences.flow.first() })
            }.exceptionOrNull()
            val problems = listOfNotNull(cleanup, deletion)
            if (failure != null) problems.forEach { failure?.addSuppressed(it) }
            else if (problems.isNotEmpty()) {
                problems.drop(1).forEach { problems.first().addSuppressed(it) }
                throw problems.first()
            }
        }
    }
}
