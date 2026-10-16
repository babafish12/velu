package de.voci

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.LessonProvider
import de.voci.audio.PlaybackService
import de.voci.data.PreferencesStore
import de.voci.domain.*
import de.voci.features.sprint.SprintScreen
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Synthetic provider only, real VM/request/repository paths. Parent must compile/run.
 * Baseline: first three tests RED on the missing status region, fourth RED on missing Spreli;
 * originalRequestOwnership_positiveControl should pass. No audio/auth success claim.
 * Preparation is suspended at the existing prepare checkpoint BEFORE any TTS/file work.
 * Launch timing, native splash and decorative motion still require Parent video evidence.
 */
class R5MotionFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingStatusHasIndependentProgressAndActualCancel() = fixture { f ->
        f.show(reduced = true, fontScale = 2f)
        val first = f.start()
        feedback()
        readable("Lektion wird erstellt", 2f)
        compose.onNodeWithText("Abbrechen").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse("R5 MOTION PRODUCT: cancel clears busy immediately", f.vm.busy.value) }
        noFeedback()
        compose.onNodeWithText("Starten").assertIsEnabled()
        // Deliberately cancellation-resistant old provider: the actual VM must reject its result.
        first.result.complete(f.lesson)
        val next = f.start()
        feedback()
        compose.runOnIdle { assertTrue(f.events.isEmpty()); assertTrue(f.vm.busy.value) }
        assertNull("R5 MOTION PRODUCT: canceled output not saved", runBlocking { f.app.repository.content(f.lesson.id) })
        compose.onNodeWithText("Abbrechen").performScrollTo().performClick()
        next.result.complete(f.lesson)
    }

    @Test fun providerErrorClearsFeedbackAndRetryStartsFreshRequest() = fixture { f ->
        f.show()
        val first = f.start()
        feedback()
        first.result.completeExceptionally(IllegalStateException("synthetic provider failure"))
        eventually { !f.vm.busy.value && f.vm.error.value != null }
        noFeedback()
        compose.onNodeWithText("Lektion nicht erstellt. Verbindung und ChatGPT-Konto prüfen.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Konto öffnen").assertHasClickAction()
        val retry = f.start()
        feedback()
        compose.runOnIdle { assertNull(f.vm.error.value); assertTrue(f.events.isEmpty()) }
        compose.onNodeWithText("Abbrechen").performScrollTo().performClick()
        retry.result.complete(f.lesson)
    }

    @Test fun syntheticSuccessClearsFeedbackAndRequestsPlayer() = fixture { f ->
        f.show()
        val request = f.start()
        feedback()
        assertEquals("FIXTURE: requested language", f.lesson.language, runBlocking { request.entered.await() }.language)
        request.result.complete(f.lesson)
        eventually { !f.vm.busy.value && f.events.size == 1 && f.preparing.isCompleted }
        noFeedback()
        compose.runOnIdle {
            assertNull("R5 MOTION PRODUCT: no false generation error", f.vm.error.value)
            val id = f.app.playback.state.value.requestId
            assertNotNull("R5 MOTION PRODUCT: real playback preparation request", id)
            assertEquals(listOf("player:$id"), f.events)
            assertNull("FIXTURE: preparation blocked, no fabricated audio session", f.app.playback.state.value.session)
        }
        assertEquals("R5 MOTION PRODUCT: actual generated content persisted", f.lesson,
            runBlocking { f.app.repository.content(f.lesson.id) })
    }

    @Test fun reducedBrandedHeaderIsImmediatelyReadableAndNonblocking() = fixture { f ->
        compose.mainClock.autoAdvance = false
        f.show(reduced = true, fontScale = 2f)
        required(hasContentDescription("Spreli"), "Spreli logo").assertIsDisplayed()
        readable("Sprache", 2f)
        compose.onNodeWithText("Situation").performScrollTo().performTextReplacement("Synthetic restaurant request")
        compose.onNodeWithText("Starten").performScrollTo().assertIsDisplayed().assertIsEnabled()
        readable("Starten", 2f)
        val request = f.start()
        // No advanceTimeBy / splash timeout: real input and provider dispatch must work immediately.
        compose.runOnIdle { assertTrue(f.vm.busy.value) }
        compose.mainClock.autoAdvance = true
        feedback()
        compose.onNodeWithText("Abbrechen").performScrollTo().performClick()
        request.result.complete(f.lesson)
    }

    @Test fun originalRequestOwnership_positiveControl() = fixture { f ->
        f.show()
        val first = f.start()
        compose.onNodeWithText("Abbrechen").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(f.vm.busy.value) }
        // A successor waits for the old request to drain, then retains its own busy ownership.
        val next = f.enqueue()
        compose.onNodeWithText("Starten").performScrollTo().performClick()
        first.result.complete(f.lesson)
        eventually { next.entered.isCompleted }
        compose.runOnIdle { assertTrue(f.vm.busy.value); assertTrue(f.events.isEmpty()) }
        assertNull(runBlocking { f.app.repository.content(f.lesson.id) })
        compose.onNodeWithText("Abbrechen").performScrollTo().performClick()
        next.result.complete(f.lesson)
        eventually { next.returned.isCompleted && !f.vm.busy.value }
        compose.runOnIdle { assertTrue(f.events.isEmpty()) }
    }

    private val status = hasContentDescription("Lektion wird erstellt")
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun feedback() {
        required(status, "Lektion wird erstellt status region").performScrollTo().assertIsDisplayed()
        val indicator = required(progress, "independent progress indicator")
        indicator.performScrollTo().assertIsDisplayed().assertIsEnabled()
        indicator.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        indicator.assert(!hasAnyAncestor(hasClickAction()))
        compose.onNodeWithText("Abbrechen").performScrollTo().assertIsDisplayed().assertHasClickAction()
    }

    private fun noFeedback() {
        compose.onAllNodes(status).assertCountEquals(0)
        compose.onAllNodes(progress).assertCountEquals(0)
        compose.onAllNodesWithText("Abbrechen").assertCountEquals(0)
    }

    private fun required(matcher: SemanticsMatcher, label: String): SemanticsNodeInteraction {
        val count = compose.onAllNodes(matcher).fetchSemanticsNodes().size
        if (count != 1) throw AssertionError("R5 MOTION PRODUCT: missing/ambiguous $label; found $count")
        return compose.onNode(matcher)
    }

    private fun readable(text: String, scale: Float) {
        val nodes = compose.onAllNodes(hasText(text), useUnmergedTree = true)
        assertTrue("R5 MOTION PRODUCT: text exists: $text", nodes.fetchSemanticsNodes().isNotEmpty())
        for (i in nodes.fetchSemanticsNodes().indices) {
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[i].performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                assertTrue(it(layouts))
            }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { result ->
                assertEquals(scale, result.layoutInput.density.fontScale, 0f)
                assertTrue(result.size.width > 0 && result.size.height > 0)
                assertTrue("R5 MOTION PRODUCT: full glyph height", result.multiParagraph.height <= result.size.height + 1f)
                assertFalse(result.multiParagraph.didExceedMaxLines)
                for (line in 0 until result.lineCount) {
                    assertTrue(result.getLineLeft(line) >= -1f)
                    assertTrue(result.getLineRight(line) <= result.size.width + 1f)
                    assertFalse(result.isLineEllipsized(line))
                }
            }
        }
    }

    private fun eventually(condition: () -> Boolean) = compose.waitUntil(10_000) { compose.runOnIdle(condition) }

    private class Request {
        val entered = CompletableDeferred<SessionSpec>()
        val result = CompletableDeferred<LessonContent>()
        val returned = CompletableDeferred<Unit>()
    }

    private inner class Fixture(val app: VociApp, val lesson: LessonContent) {
        val requests = mutableListOf<Request>()
        val queue = Channel<Request>(Channel.UNLIMITED)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        val preparing = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val vm = VociViewModel(app, SavedStateHandle(), object : LessonProvider {
            override suspend fun generate(spec: SessionSpec): LessonContent {
                val request = queue.receive()
                request.entered.complete(spec)
                return try { withContext(NonCancellable) { request.result.await() } }
                finally { request.returned.complete(Unit) }
            }
        }).also { store.put("r5-motion", it); it.topic("Synthetic restaurant request") }
        var shown = false
        init { owner.launch { vm.event.collect { events += it } } }
        fun enqueue() = Request().also { requests += it; check(queue.trySend(it).isSuccess) }
        fun start(): Request {
            val request = enqueue()
            compose.onNodeWithText("Starten").performScrollTo().assertIsEnabled().performClick()
            eventually { request.entered.isCompleted }
            compose.runOnIdle { assertTrue("FIXTURE: real provider request busy", vm.busy.value) }
            return request
        }
        fun show(reduced: Boolean = false, fontScale: Float = 1f) {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    if (visible.value) VociTheme(reduced = reduced) { SprintScreen(vm) {} }
                }
            }
            shown = true
            compose.waitForIdle()
        }
    }

    private fun fixture(body: (Fixture) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        eventually { app.playback; PlaybackService.localPlayer != null }
        runBlocking { withContext(Dispatchers.Main) { withTimeout(10_000) { app.playback.restore() } } }
        val p = app.playback
        compose.runOnIdle {
            assertNull("FIXTURE: idle device, no restored session", p.state.value.session)
            assertNull("FIXTURE: no pending preparation", p.preparing.value)
            assertEquals(0, PlaybackService.localPlayer!!.mediaItemCount)
        }
        val beforeState = p.state.value
        val beforeAnswers = p.answers.value
        val beforeUncertain = p.uncertain.value
        val oldHook = p.checkpoint
        val oldPlay = compose.runOnIdle { PlaybackService.localPlayer!!.playWhenReady }
        @Suppress("UNCHECKED_CAST")
        val settings = PreferencesStore::class.java.getDeclaredField("store").apply { isAccessible = true }
            .get(app.preferences) as DataStore<Preferences>
        val before = runBlocking { settings.data.first() }
        val providerKey = stringPreferencesKey("provider")
        val languageKey = stringPreferencesKey("language")
        val minutesKey = intPreferencesKey("minutes")
        val recallKey = booleanPreferencesKey("recall")
        val id = "r5-motion-${UUID.randomUUID()}"
        val lesson = LessonContent(id, "Synthetic restaurant", "en-GB", listOf(
            VocabItem("water", "Water, please", "Wasser, bitte", "Still water, please.", "Stilles Wasser, bitte."),
            VocabItem("menu", "The menu", "Die Speisekarte", "May I see the menu?", "Darf ich die Speisekarte sehen?"),
            VocabItem("bill", "The bill", "Die Rechnung", "The bill, please.", "Die Rechnung, bitte.")
        ), dialogue = listOf(DialogueTurn("d1", 0, "Water, please.", "Wasser, bitte."),
            DialogueTurn("d2", 1, "Still water?", "Stilles Wasser?")))
        var f: Fixture? = null
        var failure: Throwable? = null
        try {
            runBlocking { settings.edit {
                it[providerKey] = "chatgpt"; it[languageKey] = "en-GB"; it[minutesKey] = 1; it[recallKey] = false
            } }
            val active = compose.runOnIdle { Fixture(app, lesson) }
            f = active
            p.checkpoint = { stage, lessonId ->
                if (stage == "prepare" && lessonId == id) { active.preparing.complete(Unit); awaitCancellation() }
                else oldHook(stage, lessonId)
            }
            eventually { active.vm.prefs.value.provider == "chatgpt" && active.vm.prefs.value.language == "en-GB" }
            body(active)
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            compose.mainClock.autoAdvance = true
            val cleanup = runCatching {
                f?.let { active ->
                    if (active.shown) { compose.runOnIdle { active.visible.value = false }; compose.waitForIdle() }
                    runBlocking { withContext(Dispatchers.Main) { withTimeout(10_000) {
                        active.vm.cancel()
                        active.requests.forEach { it.result.completeExceptionally(CancellationException("fixture cleanup")) }
                        val job = VociViewModel::class.java.getDeclaredField("generation").apply { isAccessible = true }.get(active.vm) as Job?
                        active.store.clear(); job?.join()
                        p.cancelPreparation()?.join()
                        active.owner.cancel(); active.queue.close()
                        app.db.dao().deleteLesson(id)
                    } } }
                }
            }.exceptionOrNull()
            // Restore exact owned preference keys and delegates even following a PRODUCT assertion.
            val restore = runCatching {
                p.checkpoint = oldHook
                compose.runOnIdle {
                    f?.owner?.cancel(); f?.store?.clear()
                    p.state.value = beforeState; p.preparing.value = null
                    p.answers.value = beforeAnswers; p.uncertain.value = beforeUncertain
                    PlaybackService.localPlayer!!.playWhenReady = oldPlay
                }
                runBlocking { settings.edit { values ->
                    fun <T> restoreKey(key: Preferences.Key<T>) {
                        val value = before[key]
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    restoreKey(providerKey); restoreKey(languageKey); restoreKey(minutesKey); restoreKey(recallKey)
                } }
                assertEquals("FIXTURE: all preferences restored", before, runBlocking { settings.data.first() })
            }.exceptionOrNull()
            val problems = listOfNotNull(cleanup, restore)
            if (failure != null) problems.forEach { failure.addSuppressed(it) }
            else if (problems.isNotEmpty()) { problems.drop(1).forEach { problems.first().addSuppressed(it) }; throw problems.first() }
        }
    }
}
