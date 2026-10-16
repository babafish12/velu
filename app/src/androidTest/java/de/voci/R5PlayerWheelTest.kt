package de.voci

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.LessonProvider
import de.voci.audio.AudioFiles
import de.voci.audio.PlaybackService
import de.voci.audio.PreparedSession
import de.voci.data.PreferencesStore
import de.voci.domain.LessonContent
import de.voci.domain.SessionProgress
import de.voci.domain.SessionSpec
import de.voci.domain.VocabItem
import de.voci.features.player.PlayerScreen
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * Parent runs this against the unchanged popup player FIRST: expected 3 WHEEL PRODUCT REDs
 * (missing Eintragsrad), 1 real-audio positive control. Not execution evidence.
 * Requires an idle local host and installed offline en-GB/de-DE voices. Uses actual TTS WAVs
 * and Media3; no auth access, generation, device settings, fabricated completion or new APIs.
 * Replaces interaction coverage of the two R4 popup tests without modifying their bytes.
 * Covers Single selection, not whole-course budgeting, perspective appearance, TalkBack traversal,
 * gesture cancellation/disposal, or a finger held across completion; those remain separate gates.
 */
class R5PlayerWheelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun originalSingleKeepsSourceIdentityAndRealAudio_positiveControl() = fixture { f ->
        val initial = f.start()
        f.queue(initial, f.a, "R5 WHEEL CONTROL")
        assertEquals("R5 WHEEL CONTROL: original lesson identity and content", f.lesson, initial.lesson)
        assertEquals("R5 WHEEL CONTROL: repository content unchanged", f.lesson,
            runBlocking { f.app.repository.content(f.lesson.id) })
        f.show(initial)
        f.exampleIsReadable(initial, f.a, 1f)
    }

    @Test fun pausedSingleTouchSwipeCommitsBWithSameSessionAndFourCues() = fixture { f ->
        val initial = f.start()
        f.show(initial)
        val wheel = requiredWheel() // Immediate PRODUCT failure on baseline, before any swipe.
        row(f.a).assertIsSelected()
        row(f.b).assertIsNotSelected().assertHasClickAction()
        wheel.performScrollTo().assertIsDisplayed().performTouchInput {
            // Two source items: upward drag to the last endpoint, slow enough to avoid a fling.
            swipe(start = center.copy(y = height * .8f), end = center.copy(y = height * .2f), durationMillis = 700)
        }
        val selected = f.selected(initial, playing = false)
        f.queue(selected, f.b, "R5 WHEEL PRODUCT")
        row(f.b).assertIsSelected()
        row(f.a).assertIsNotSelected()
        noPopup()
        // With no new gesture, programmatic alignment must not keep replacing/seeking the queue.
        val discontinuities = java.util.concurrent.atomic.AtomicInteger()
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                discontinuities.incrementAndGet()
            }
        }
        compose.runOnIdle { PlaybackService.localPlayer!!.addListener(listener) }
        try {
            runBlocking { delay(1_200) }
            compose.runOnIdle {
                assertEquals("R5 WHEEL PRODUCT: settled paused wheel does not seek again", 0, discontinuities.get())
                assertEquals("R5 WHEEL PRODUCT: selected session stays stable", selected, f.app.playback.state.value.session)
                assertFalse("R5 WHEEL PRODUCT: pause intention retained", PlaybackService.localPlayer!!.playWhenReady)
            }
        } finally { compose.runOnIdle { PlaybackService.localPlayer!!.removeListener(listener) } }
        f.exampleIsReadable(selected, f.b, 1f)
    }

    @Test fun playingWheelChoiceSurvivesSlowSynthesisAtOldNaturalBoundary() = fixture { f ->
        val initial = f.start()
        f.show(initial)
        requiredWheel()
        row(f.a).assertIsSelected()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldHook = f.app.tts.checkpoint
        var endedWhilePending = false
        val observer = f.owner.launch {
            f.app.playback.state.collect { state ->
                if (entered.isCompleted && !release.isCompleted && state.session?.id == initial.id) {
                    endedWhilePending = endedWhilePending || state.ended
                }
            }
        }
        try {
            f.app.tts.checkpoint = { stage, cue ->
                oldHook(stage, cue)
                if (stage == "synthesized" && cue.itemId == f.b.id) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            compose.runOnIdle { PlaybackService.localPlayer!!.play() }
            eventually("FIXTURE: A really playing before accessible wheel choice") { PlaybackService.localPlayer!!.isPlaying }
            row(f.b).assertHasClickAction().performClick()
            eventually("R5 WHEEL PRODUCT: wheel choice reaches real B synthesis", 45_000) {
                assertNull("R5 WHEEL PRODUCT: accepted choice preparation error", f.app.playback.state.value.error)
                entered.isCompleted
            }
            compose.runOnIdle {
                val player = PlaybackService.localPlayer!!
                assertEquals("R5 WHEEL PRODUCT: pending selection still owns A's session", initial.id, f.app.playback.state.value.session?.id)
                assertEquals("R5 WHEEL PRODUCT: B not prematurely published", f.a.id, f.app.playback.state.value.session?.singleItemId)
                // Real final WAV silence. Do not force play: selection may correctly hold A paused.
                player.seekTo(player.mediaItemCount - 1, (initial.cues.last().pauseMs - 300).coerceAtLeast(0))
            }
            eventually("FIXTURE: old final silence loaded") { PlaybackService.localPlayer!!.playbackState != Player.STATE_BUFFERING }
            runBlocking { delay(2_000) } // Exceeds real 650ms final silence; never wait for fabricated END.
            compose.runOnIdle {
                assertFalse("R5 WHEEL PRODUCT: no premature ended state", endedWhilePending || f.app.playback.state.value.ended)
                assertTrue("R5 WHEEL PRODUCT: no completion navigation during accepted choice", f.completions.isEmpty())
            }
            release.complete(Unit)
            val selected = f.selected(initial, playing = true)
            f.queue(selected, f.b, "R5 WHEEL PRODUCT")
            row(f.b).assertIsSelected()
            noPopup()
            compose.runOnIdle { assertTrue("R5 WHEEL PRODUCT: no lost choice/completion", f.completions.isEmpty()) }
        } finally {
            release.complete(Unit)
            f.app.tts.checkpoint = oldHook
            runBlocking { observer.cancelAndJoin() }
        }
    }

    @Test fun reducedMotionAt200PercentFontStillSelectsAndExposesReadableContentAndActions() = fixture { f ->
        val initial = f.start()
        f.show(initial, reduced = true, fontScale = 2f)
        requiredWheel()
        row(f.a).assertIsSelected()
        // Accessible click is the non-gesture alternative; real touch is covered above.
        row(f.b).performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        val selected = f.selected(initial, playing = false)
        f.queue(selected, f.b, "R5 WHEEL PRODUCT")
        row(f.b).performScrollTo().assertIsDisplayed().assertIsSelected()
        readableText(f.b.target, 2f)
        f.exampleIsReadable(selected, f.b, 2f)
        for (action in listOf("Play", "Weiter", "Wiederholen", "Audio")) {
            required(hasText(action)).performScrollTo().assertIsDisplayed().assertHasClickAction()
            readableText(action, 2f)
        }
        noPopup()
        // Visual flatness/reduced decoration needs Parent video; this proves functional selection.
    }

    private fun requiredWheel(): SemanticsNodeInteraction {
        val wheel = required(hasContentDescription("Eintragsrad"))
        noPopup()
        return wheel
    }

    private fun noPopup() {
        assertEquals("R5 WHEEL PRODUCT: obsolete Einträge popup action removed", 0,
            compose.onAllNodesWithText("Einträge").fetchSemanticsNodes().size)
    }

    private fun row(item: VocabItem) = required(hasContentDescription("Eintrag auswählen: ${item.target}"))

    private fun required(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val count = compose.onAllNodes(matcher).fetchSemanticsNodes().size
        if (count != 1) throw AssertionError("R5 WHEEL PRODUCT: expected one $matcher; found $count")
        return compose.onNode(matcher)
    }

    private fun readableText(text: String, fontScale: Float) {
        val nodes = compose.onAllNodes(hasText(text), useUnmergedTree = true)
        assertTrue("R5 WHEEL PRODUCT: actual utterance/translation/action present: $text", nodes.fetchSemanticsNodes().isNotEmpty())
        for (i in nodes.fetchSemanticsNodes().indices) {
            val node = nodes[i].performScrollTo().assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue("R5 WHEEL PRODUCT: measured text layout", action(results))
            }
            assertTrue("R5 WHEEL PRODUCT: nonempty text layout", results.isNotEmpty())
            results.forEach { result ->
                assertEquals("R5 WHEEL PRODUCT: scoped font scale", fontScale, result.layoutInput.density.fontScale, 0f)
                assertTrue("R5 WHEEL PRODUCT: positive measured text size", result.size.width > 0 && result.size.height > 0)
                // Paragraph layout width can retain available width while the Text node wraps to intrinsic width.
                // Check rendered line extents instead, allowing only 1 px for pixel rounding.
                assertTrue("R5 WHEEL PRODUCT: full text height fits", result.multiParagraph.height <= result.size.height + 1f)
                assertFalse("R5 WHEEL PRODUCT: no exceeded max lines", result.multiParagraph.didExceedMaxLines)
                for (line in 0 until result.lineCount) {
                    assertTrue("R5 WHEEL PRODUCT: line $line left fits", result.getLineLeft(line) >= -1f)
                    assertTrue("R5 WHEEL PRODUCT: line $line right fits", result.getLineRight(line) <= result.size.width + 1f)
                    assertFalse("R5 WHEEL PRODUCT: no ellipsis", result.isLineEllipsized(line))
                }
            }
        }
    }

    private fun eventually(label: String, timeout: Long = 8_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { compose.runOnIdle(condition) } }
        catch (e: ComposeTimeoutException) { throw AssertionError("$label (timeout ${timeout}ms)", e) }
    }

    private inner class Fixture(val app: VociApp, val lesson: LessonContent) {
        val a = lesson.items[0]
        val b = lesson.items[1]
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        val vm = VociViewModel(app, SavedStateHandle(), object : LessonProvider {
            override suspend fun generate(spec: SessionSpec): LessonContent =
                throw AssertionError("FIXTURE: wheel must never generate or access a provider")
        }).also { store.put("r5-wheel", it) }
        val visible = mutableStateOf(true)
        val completions = mutableListOf<String>()
        var shown = false

        fun start(): PreparedSession {
            runBlocking {
                for (locale in listOf("en-GB", "de-DE")) assertTrue("FIXTURE: installed offline voice $locale",
                    withTimeout(20_000) { app.tts.voices(locale) }.isNotEmpty())
            }
            val id = compose.runOnIdle {
                app.playback.start(lesson, SessionSpec(lesson.title, lesson.language, 1), 1f, singleItemId = a.id)
            }
            eventually("FIXTURE: real finite A preparation", 60_000) {
                assertNull("FIXTURE: real preparation error", app.playback.state.value.error)
                app.playback.preparing.value == null && app.playback.state.value.session?.id == id
            }
            eventually("FIXTURE: actual Media3 playback") { PlaybackService.localPlayer?.isPlaying == true }
            compose.runOnIdle { PlaybackService.localPlayer!!.pause() }
            eventually("FIXTURE: initial pause") { !PlaybackService.localPlayer!!.isPlaying && !app.playback.state.value.playing }
            return app.playback.state.value.session!!
        }

        fun show(session: PreparedSession, reduced: Boolean = false, fontScale: Float = 1f) {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    if (visible.value) VociTheme(reduced = reduced) {
                        PlayerScreen(session.id, vm, {}, {}) { id, _ -> completions += id }
                    }
                }
            }
            shown = true
            compose.waitForIdle()
        }

        fun selected(initial: PreparedSession, playing: Boolean): PreparedSession {
            eventually("R5 WHEEL PRODUCT: B settles with original session and play intention", 60_000) {
                val state = app.playback.state.value
                val player = PlaybackService.localPlayer!!
                assertNull("R5 WHEEL PRODUCT: selection error", state.error)
                assertEquals("R5 WHEEL PRODUCT: selection keeps original session", initial.id, state.session?.id)
                state.session?.singleItemId == b.id && state.session?.cues?.all { it.itemId == b.id } == true &&
                    !state.ended && player.playWhenReady == playing && player.isPlaying == playing && state.playing == playing
            }
            return app.playback.state.value.session!!
        }

        fun queue(session: PreparedSession, item: VocabItem, label: String) {
            assertEquals("$label: source lesson retained", lesson, session.lesson)
            assertEquals("$label: finite Single identity", item.id, session.singleItemId)
            assertFalse("$label: no forced recall", session.recall)
            assertEquals("$label: four cues", 4, session.cues.size)
            assertEquals("$label: four phases", listOf("target", "meaning", "repeat", "example"), session.cues.map { it.phase })
            val texts = mapOf("target" to item.target, "meaning" to item.translation, "repeat" to item.target, "example" to item.example)
            val assets = runBlocking { session.cues.map { cue ->
                assertEquals("$label: selected item", item.id, cue.itemId)
                assertEquals("$label: actual utterance mapping", texts[cue.phase], cue.text)
                val asset = app.db.dao().audio(cue.key) ?: throw AssertionError("$label: missing audio metadata")
                assertTrue("$label: measured duration", cue.durationMs > 0 && cue.durationMs == asset.durationMs)
                assertTrue("$label: validated real WAV", AudioFiles.valid(File(asset.path), asset.bytes, asset.sha256))
                asset
            } }
            compose.runOnIdle {
                val player = PlaybackService.localPlayer!!
                assertEquals("$label: actual four audio/pause pairs", 8, player.mediaItemCount)
                session.cues.forEachIndexed { i, cue ->
                    assertEquals("$label: Media3 audio identity", cue.key, player.getMediaItemAt(i * 2).mediaId)
                    assertEquals("$label: Media3 validated file", assets[i].path, player.getMediaItemAt(i * 2).localConfiguration?.uri?.toString())
                    assertEquals("$label: actual pause", "pause", player.getMediaItemAt(i * 2 + 1).mediaId)
                }
                assertEquals("$label: actual active item", item.id, session.cues[player.currentMediaItemIndex / 2].itemId)
            }
        }

        fun exampleIsReadable(session: PreparedSession, item: VocabItem, fontScale: Float) {
            val index = session.cues.indexOfFirst { it.phase == "example" && it.itemId == item.id }
            assertTrue("FIXTURE: actual example cue", index >= 0)
            compose.runOnIdle { PlaybackService.localPlayer!!.let { it.pause(); it.seekTo(index * 2, 0) } }
            eventually("R5 WHEEL PRODUCT: actual example phase reflected in UI state") {
                app.playback.state.value.index == index && PlaybackService.localPlayer!!.currentMediaItem?.mediaId == session.cues[index].key
            }
            readableText(item.example, fontScale)
            readableText(item.exampleTranslation, fontScale)
        }
    }

    private fun fixture(body: (Fixture) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        eventually("FIXTURE: local Media3 service connected") { app.playback; PlaybackService.localPlayer != null }
        runBlocking { withContext(Dispatchers.Main) { withTimeout(10_000) { app.playback.restore() } } }
        compose.runOnIdle {
            assertNull("FIXTURE: requires no restored session", app.playback.state.value.session)
            assertNull("FIXTURE: requires no pending preparation", app.playback.preparing.value)
            assertEquals("FIXTURE: requires empty Media3 queue", 0, PlaybackService.localPlayer!!.mediaItemCount)
        }
        val token = UUID.randomUUID().toString().take(8)
        val lesson = LessonContent("r5-wheel-$token", "R5 wheel $token", "en-GB", listOf(
            VocabItem("r5-wheel-$token-a", "Blue $token", "Blau $token", "It is blue $token.", "Es ist blau $token."),
            VocabItem("r5-wheel-$token-b", "Green $token", "Grün $token", "It is green $token.", "Es ist grün $token.")
        ))
        val p = app.playback
        val playerParameters = compose.runOnIdle { PlaybackService.localPlayer!!.playbackParameters }
        val playWhenReady = compose.runOnIdle { PlaybackService.localPlayer!!.playWhenReady }
        val state = p.state.value
        val preparing = p.preparing.value
        val answers = p.answers.value
        val uncertain = p.uncertain.value
        val voiceInventory = app.tts.voiceInventory
        val ttsHook = app.tts.checkpoint
        val playbackHook = p.checkpoint
        val lastFile = File(app.filesDir, "last-session.json")
        val lastBytes = if (lastFile.exists()) lastFile.readBytes() else null
        val oldAudio = File(app.filesDir, "audio").list().orEmpty().toSet()
        val oldSilences = app.filesDir.list().orEmpty().filter { it.startsWith("silence-") }.toSet()
        val ownedKeys = Collections.synchronizedSet(mutableSetOf<String>())
        // No app delegate swaps; only remove this UUID's new uncertain tombstone in cleanup.
        @Suppress("UNCHECKED_CAST")
        val settings = PreferencesStore::class.java.getDeclaredField("store").apply { isAccessible = true }
            .get(app.preferences) as DataStore<Preferences>
        val settingsBefore = runBlocking { settings.data.first() }
        val markKey = stringSetPreferencesKey("uncertain.lesson.v1:${lesson.id}")
        assertNull("FIXTURE: UUID has no previous marks", settingsBefore[markKey])
        var f: Fixture? = null
        var failure: Throwable? = null
        try {
            app.tts.checkpoint = { stage, cue ->
                ttsHook(stage, cue)
                if (stage == "renamed" && lesson.items.any { it.id == cue.itemId }) {
                    File(app.filesDir, "audio").listFiles().orEmpty()
                        .filter { it.name !in oldAudio && it.extension == "wav" && !it.name.endsWith(".part.wav") }
                        .forEach { ownedKeys.add(it.nameWithoutExtension) }
                }
            }
            runBlocking {
                assertNull("FIXTURE: unique lesson", app.db.dao().lesson(lesson.id))
                app.repository.save(lesson)
            }
            val active = compose.runOnIdle { Fixture(app, lesson) }
            f = active
            val prefs = runBlocking { app.preferences.flow.first() }
            eventually("FIXTURE: VM preferences collected") { active.vm.prefs.value == prefs }
            body(active)
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            val unmount = runCatching {
                f?.takeIf { it.shown }?.let { active ->
                    compose.runOnIdle { active.visible.value = false }
                    compose.waitForIdle()
                }
            }.exceptionOrNull()
            val cleanup = runCatching {
                runBlocking {
                    withContext(Dispatchers.Main) {
                        withTimeout(15_000) {
                            val draining = p.cancelPreparation()
                            f?.vm?.cancel()
                            f?.store?.clear()
                            draining?.join()
                            // Stop observation of the test queue before draining its outstanding DB writes.
                            p.state.value = state
                            PlaybackService.localPlayer!!.let { it.pause(); it.clearMediaItems() }
                            @Suppress("UNCHECKED_CAST")
                            val writes = p.javaClass.getDeclaredField("progressWrites").apply { isAccessible = true }
                                .get(p) as MutableSet<Job>
                            writes.toList().joinAll()
                            app.db.dao().deleteLesson(lesson.id)
                            for (entry in app.db.dao().progressEntries()) {
                                val progress = runCatching { Json.decodeFromString<SessionProgress>(entry.json) }.getOrNull()
                                if (progress?.lessonId == lesson.id) app.db.dao().removeProgress(entry.sessionId)
                            }
                            File(app.filesDir, "plans/${lesson.id}.json").delete()
                            if (lastBytes == null) lastFile.delete() else lastFile.writeBytes(lastBytes)
                            for (key in ownedKeys.toList()) {
                                val asset = app.db.dao().audio(key)
                                if (asset != null) { File(asset.path).delete(); app.db.dao().removeAudio(key) }
                                else File(app.filesDir, "audio/$key.wav").delete()
                            }
                            app.filesDir.listFiles().orEmpty().filter {
                                it.name.startsWith("silence-") && it.name.endsWith(".wav") && it.name !in oldSilences
                            }.forEach { it.delete() }
                            settings.edit { it.remove(markKey) }
                            assertEquals("FIXTURE: all settings preserved", settingsBefore, settings.data.first())
                        }
                    }
                }
            }.exceptionOrNull()
            // Exact flow values and delegates restored even when body or cleanup fails.
            compose.runOnIdle {
                f?.owner?.cancel()
                f?.store?.clear()
                app.tts.voiceInventory = voiceInventory
                app.tts.checkpoint = ttsHook
                p.checkpoint = playbackHook
                p.state.value = state
                p.preparing.value = preparing
                p.answers.value = answers
                p.uncertain.value = uncertain
                PlaybackService.localPlayer!!.let {
                    it.playbackParameters = playerParameters
                    it.playWhenReady = playWhenReady
                }
            }
            val problems = listOfNotNull(unmount, cleanup)
            if (failure != null) problems.forEach { failure.addSuppressed(it) }
            else if (problems.isNotEmpty()) {
                problems.drop(1).forEach { problems.first().addSuppressed(it) }
                throw problems.first()
            }
        }
    }
}
