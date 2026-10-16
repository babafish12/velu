package de.voci

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.ChatGptProvider
import de.voci.auth.AuthManager
import de.voci.auth.CredentialStore
import de.voci.auth.VaultState
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * One focused successor to R5's obsolete literal-brand case; the other R5 cases stay frozen.
 * Parent compiles/runs. The installed-label assertion deliberately precedes all audio fixtures.
 * Synthetic owned provider, empty in-memory auth, HTTP rejected before DNS; no user credentials.
 * The original R5 cancellation/drain/preference cleanup and pre-TTS checkpoint are retained.
 */
class R6BrandMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun veluReducedHeaderIsImmediatelyReadableAndNonblocking() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        assertEquals("R6 BRAND PRODUCT: installed app label must be Velu", "Velu",
            app.applicationInfo.loadLabel(app.packageManager).toString())
        fixture { f ->
            compose.mainClock.autoAdvance = false
            val headerClock = compose.mainClock.currentTime
            f.show(reduced = true, fontScale = 2f)
            required(hasContentDescription("Velu"), "Velu header logo").assertIsDisplayed()
            readable("Velu", 2f)
            readable("Sprache", 2f)
            compose.onNodeWithText("Situation").assertIsEnabled().assert(isEditable()).assert(hasSetTextAction())
            compose.onNodeWithText("Starten").assertExists().assertIsEnabled().assertHasClickAction()
            assertEquals("R7 FIXTURE: initial header/input/start semantics need no frames", headerClock,
                compose.mainClock.currentTime)
            // Separate preparation window: frames also run focus/IME/layout and other Compose work,
            // not just scrolling. This journey does not claim a continuous freeze since composition.
            compose.mainClock.autoAdvance = true
            val topic = "Synthetic Velu restaurant request"
            compose.onNodeWithText("Situation").performScrollTo().performTextReplacement(topic)
            compose.onNodeWithText("Starten").performScrollTo().assertIsDisplayed().assertIsEnabled()
            readable("Starten", 2f)
            // Settle real input and scrolling, then put the exact merged button used by f.start
            // wholly in its viewport. Its repeated performScrollTo must therefore be zero-delta.
            compose.waitForIdle()
            val start = compose.onNodeWithText("Starten")
            start.performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.waitForIdle()
            val startNode = start.fetchSemanticsNode()
            val scrollNode = generateSequence(startNode.parent) { it.parent }
                .first { it.config.contains(SemanticsActions.ScrollBy) }
            compose.runOnIdle {
                val coordinates = scrollNode.layoutInfo.coordinates
                val viewport = coordinates.boundsInParent().translate(
                    coordinates.parentLayoutCoordinates?.positionInRoot() ?: Offset.Zero)
                val button = Rect(startNode.positionInRoot, startNode.size.toSize())
                assertTrue("R7 FIXTURE: complete Starten button in scroll viewport",
                    button.width > 0f && button.height > 0f &&
                        button.left >= viewport.left && button.right <= viewport.right &&
                        button.top >= viewport.top && button.bottom <= viewport.bottom)
            }
            compose.mainClock.autoAdvance = false
            val dispatchClock = compose.mainClock.currentTime
            // Read framework metadata only: no event injected and no clock advancement.
            var clickEventPeriod = 0L
            start.performTouchInput { clickEventPeriod = eventPeriodMillis }
            assertTrue("R7 FIXTURE: positive framework event period", clickEventPeriod > 0L)
            assertEquals("R7 FIXTURE: reading input period needs no frames", dispatchClock, compose.mainClock.currentTime)
            val request = f.start()
            // 1.12.1 performClick = down + move(default eventPeriodMillis) + up.
            // AndroidInputDispatcher.flush advances the clock by event timestamp deltas even
            // with autoAdvance=false. Require EXACTLY that input time, with no additional delay.
            val inputEndClock = dispatchClock + clickEventPeriod
            assertEquals("R6 BRAND PRODUCT: actual Situation input reaches owned provider", topic,
                runBlocking { request.entered.await() }.topic)
            assertEquals("R7 FIXTURE: dispatch adds only framework click event time", inputEndClock, compose.mainClock.currentTime)
            compose.runOnIdle { assertTrue("R6 BRAND PRODUCT: immediate busy ownership", f.vm.busy.value) }
            assertEquals("R7 FIXTURE: immediate busy adds no time after input", inputEndClock, compose.mainClock.currentTime)
            compose.mainClock.autoAdvance = true
            feedback()
            compose.onNodeWithText("Abbrechen").performScrollTo().performClick()
            compose.runOnIdle { assertFalse("R6 BRAND PRODUCT: immediate cancellation", f.vm.busy.value) }
            noFeedback()
            compose.onNodeWithText("Starten").performScrollTo().assertIsEnabled()
            request.result.complete(f.lesson)
            eventually { request.returned.isCompleted && !f.vm.busy.value }
            compose.runOnIdle { assertTrue("R6 BRAND PRODUCT: canceled request cannot navigate", f.events.isEmpty()) }
            assertNull("R6 BRAND PRODUCT: canceled output not saved", runBlocking { f.app.repository.content(f.lesson.id) })
        }
    }

    /** Framework-only control on the existing Compose/Foundation dependency, no app UI or VM.
     * Capture ScrollBy before starting it; frozen observations must never enter Compose idling.
     */
    @Test fun r7FrameworkScrollRequiresFramesButVisibleTargetDoesNot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var scroll: ScrollState
        var viewport = Rect.Zero
        var target = Rect.Zero
        var actualFontScale = 0f
        var clicks = 0
        var callbackClock = -1L
        try {
            compose.mainClock.autoAdvance = false
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    actualFontScale = LocalDensity.current.fontScale
                    scroll = rememberScrollState()
                    Box(Modifier.size(200.dp, 160.dp).onGloballyPositioned {
                        viewport = Rect(it.positionInRoot(), it.size.toSize())
                    }) {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("r7-scroll")) {
                            Spacer(Modifier.height(480.dp))
                            BasicText("Target", Modifier.height(48.dp).testTag("r7-target").clickable {
                                clicks++
                                callbackClock = compose.mainClock.currentTime
                            }.onGloballyPositioned {
                                target = Rect(it.positionInRoot(), it.size.toSize())
                            })
                        }
                    }
                }
            }
            compose.waitForIdle()
            val scrollBy = requireNotNull(compose.onNodeWithTag("r7-scroll")
                .fetchSemanticsNode().config[SemanticsActions.ScrollBy].action)
            var initialValue = 0
            var distance = 0
            var initialViewport = Rect.Zero
            var initialTarget = Rect.Zero
            instrumentation.runOnMainSync {
                assertEquals(2f, actualFontScale, 0f)
                assertFalse(scroll.isScrollInProgress)
                initialValue = scroll.value
                distance = scroll.maxValue - initialValue
                initialViewport = viewport
                initialTarget = target
                assertTrue("CONTROL: laid out bounded viewport", viewport.width > 0f && viewport.height > 0f)
                assertTrue("CONTROL: genuinely offscreen target", target.height > 0f && target.top >= viewport.bottom)
                assertTrue("CONTROL: finite nonzero distance", distance > 0 && scroll.maxValue < Int.MAX_VALUE)
            }
            val frozenClock = compose.mainClock.currentTime
            // Exactly one real semantics invocation; no performScrollTo or semantic lookup while it is pending.
            instrumentation.runOnMainSync { assertTrue(scrollBy(0f, distance.toFloat())) }
            var started = false
            val deadline = SystemClock.uptimeMillis() + 10_000
            do {
                instrumentation.runOnMainSync { started = scroll.isScrollInProgress }
            } while (!started && SystemClock.uptimeMillis() < deadline)
            assertTrue("CONTROL: ScrollBy coroutine actually started within bound", started)
            repeat(3) {
                instrumentation.runOnMainSync {
                    assertTrue("CONTROL: started scroll is still incomplete", scroll.isScrollInProgress)
                    assertEquals("CONTROL: no movement without a clock frame", initialValue, scroll.value)
                    assertEquals(initialViewport, viewport)
                    assertEquals(initialTarget, target)
                }
                assertEquals("CONTROL: frozen observation advances no frames", frozenClock, compose.mainClock.currentTime)
            }
            // Resume the SAME pending scroll; no second request, content or viewport change.
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            instrumentation.runOnMainSync {
                assertFalse("CONTROL: original scroll completed", scroll.isScrollInProgress)
                assertEquals(initialValue + distance, scroll.value)
                assertEquals(scroll.maxValue, scroll.value)
                assertEquals(initialViewport, viewport)
                assertEquals(initialTarget.size, target.size)
                assertTrue("CONTROL: target fully visible",
                    target.left >= viewport.left && target.right <= viewport.right &&
                        target.top >= viewport.top && target.bottom <= viewport.bottom)
            }
            assertTrue("CONTROL: completion required frames", compose.mainClock.currentTime > frozenClock)
            compose.mainClock.autoAdvance = false
            val visibleClock = compose.mainClock.currentTime
            compose.onNodeWithTag("r7-target").performScrollTo().assertIsDisplayed()
            instrumentation.runOnMainSync {
                assertFalse(scroll.isScrollInProgress)
                assertEquals(initialValue + distance, scroll.value)
            }
            assertEquals("CONTROL: visible-target performScrollTo is zero-delta", visibleClock, compose.mainClock.currentTime)
            // Same real performClick path as f.start, isolated from app UI/VM/provider.
            var clickEventPeriod = 0L
            compose.onNodeWithTag("r7-target").performTouchInput { clickEventPeriod = eventPeriodMillis }
            assertTrue("CONTROL: positive framework event period", clickEventPeriod > 0L)
            assertEquals("CONTROL: metadata read injects no time", visibleClock, compose.mainClock.currentTime)
            compose.onNodeWithTag("r7-target").performScrollTo().assertIsEnabled().performClick()
            instrumentation.runOnMainSync {
                assertEquals("CONTROL: one real touch callback", 1, clicks)
                assertEquals("CONTROL: callback entered at input event boundary", visibleClock + clickEventPeriod, callbackClock)
                assertFalse(scroll.isScrollInProgress)
                assertEquals(initialValue + distance, scroll.value)
            }
            assertEquals("CONTROL: click advances exactly its event period", visibleClock + clickEventPeriod,
                compose.mainClock.currentTime)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** Fresh first-composition-to-dispatch gate control, with the fixture's existing nonempty topic.
     * OnClick semantics may invoke an offscreen button: this is callback/gating coverage, NOT
     * physical touch/scroll coverage. The corrected original journey retains real input + scrolling.
     */
    @Test fun r7FreshStartCallbackDispatchesWithoutPreparatoryFrames() {
        fixture { f ->
            compose.mainClock.autoAdvance = false
            val firstCompositionClock = compose.mainClock.currentTime
            val request = f.enqueue()
            f.show(reduced = true, fontScale = 2f)
            compose.onNodeWithText("Starten").assertExists().assertIsEnabled().assertHasClickAction()
                .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            assertEquals("R7 CONTROL: exact default topic reaches owned pending provider", "Synthetic restaurant request",
                runBlocking { withTimeout(10_000) { request.entered.await() } }.topic)
            assertEquals("R7 CONTROL: no first-composition-to-provider frames", firstCompositionClock,
                compose.mainClock.currentTime)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue("R7 CONTROL: immediate busy ownership", f.vm.busy.value)
                assertFalse("R7 CONTROL: provider remains pending", request.result.isCompleted)
            }
            assertEquals("R7 CONTROL: no first-composition-to-busy frames", firstCompositionClock,
                compose.mainClock.currentTime)
            // Unchanged fixture finally restores the clock, cancels/drains this pending request,
            // removes composition and restores preferences/delegates even on assertion failure.
        }
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
        if (count != 1) throw AssertionError("R6 BRAND PRODUCT: missing/ambiguous $label; found $count")
        return compose.onNode(matcher)
    }

    private fun readable(text: String, scale: Float) {
        val nodes = compose.onAllNodes(hasText(text), useUnmergedTree = true)
        assertTrue("R6 BRAND PRODUCT: text exists: $text", nodes.fetchSemanticsNodes().isNotEmpty())
        for (i in nodes.fetchSemanticsNodes().indices) {
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[i].performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                assertTrue(it(layouts))
            }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { result ->
                assertEquals(scale, result.layoutInput.density.fontScale, 0f)
                assertTrue(result.size.width > 0 && result.size.height > 0)
                assertTrue("R6 BRAND PRODUCT: full glyph height", result.multiParagraph.height <= result.size.height + 1f)
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
        }).also { store.put("r6-brand", it); it.topic("Synthetic restaurant request") }
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

    private fun fixture(body: (Fixture) -> Unit) = isolatedApp { app ->
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
        val id = "r6-brand-${UUID.randomUUID()}"
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
    /** Never evaluate the original auth/provider/http delegates, including during restoration. */
    private fun isolatedApp(body: (VociApp) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requests = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw IOException("FIXTURE: R6 brand forbids HTTP before DNS/network")
        }.build()
        val vault = object : CredentialStore {
            override fun read() = VaultState(host = "urn:r6:brand-empty-fixture", accounts = emptyList(), active = null)
            override fun write(state: VaultState) { throw AssertionError("FIXTURE: credential writes forbidden") }
        }
        val auth = AuthManager(null, http, owner, vault)
        val originals = mutableListOf<Triple<java.lang.reflect.Field, Lazy<*>, Boolean>>()
        var failure: Throwable? = null
        try {
            compose.runOnIdle {
                for ((name, replacement) in listOf(
                    "auth" to lazy { auth }, "http" to lazy { http },
                    "provider" to lazy { ChatGptProvider(http, auth) }
                )) {
                    val field = VociApp::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
                    val original = field.get(app) as Lazy<*>
                    originals += Triple(field, original, original.isInitialized())
                    field.set(app, replacement)
                }
            }
            body(app)
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            val restore = runCatching {
                compose.runOnIdle {
                    owner.cancel()
                    originals.asReversed().forEach { (field, original, _) -> field.set(app, original) }
                }
            }.exceptionOrNull()
            val close = runCatching {
                http.dispatcher.cancelAll()
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
                originals.forEach { (field, original, initialized) ->
                    assertSame("FIXTURE: original delegate restored", original, field.get(app))
                    assertEquals("FIXTURE: original initialization unchanged", initialized, original.isInitialized())
                }
                assertEquals("FIXTURE: no attempted HTTP", 0, requests.get())
            }.exceptionOrNull()
            val problems = listOfNotNull(restore, close)
            if (failure != null) problems.forEach { failure.addSuppressed(it) }
            else if (problems.isNotEmpty()) {
                problems.drop(1).forEach { problems.first().addSuppressed(it) }
                throw problems.first()
            }
        }
    }
}
