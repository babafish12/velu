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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Source-only R6 tracer. Parent supplies an idle service, isolates foreign last-session files,
 * and runs with installed offline en-GB/de-DE voices. Expected R5 RED is the explicit missing
 * language-control AssertionError, after real TTS/Media3 and accessible-selection prerequisites.
 * No product APIs are added or anticipated. The boolean storage key below is the sole proposed
 * R6 preference seam: absent means target first; snapshot/remove/restore only that key.
 * Recreation, long text, playing swaps and branding are separate follow-ups, not claimed here.
 */
class R6WheelLanguageTest {
    @get:Rule val compose = createComposeRule()
    private val wheel = hasContentDescription("Eintragsrad")
    private val wheelTranslationFirst = booleanPreferencesKey("wheelTranslationFirst")

    @Test fun translationInWheelDoesNotReplanPausedAudio() = fixture { f ->
        val initial = f.start()
        f.queue(initial, f.a, "R6 WHEEL CONTROL")
        f.show(initial)
        required(wheel).performScrollTo().assertIsDisplayed()
        targetRow(f.a).assertIsSelected().assertHasClickAction()
        targetRow(f.b).assertIsNotSelected().assertHasClickAction()
        readableText(hasText(f.a.target) and hasAnyAncestor(wheel))

        // Real ordinary accessible selection is a prerequisite, not a substitute for the swap.
        targetRow(f.b).performScrollTo().assertIsDisplayed().performClick()
        f.selected(initial, f.b)
        targetRow(f.b).assertIsSelected()
        targetRow(f.a).assertIsNotSelected().performScrollTo().assertIsDisplayed().performClick()
        val paused = f.selected(initial, f.a)
        f.queue(paused, f.a, "R6 WHEEL CONTROL")
        targetRow(f.a).assertIsSelected()
        targetRow(f.b).assertIsNotSelected()
        assertEquals("R6 WHEEL CONTROL: source repository untouched", f.lesson,
            runBlocking { f.app.repository.content(f.lesson.id) })
        assertEquals("R6 WHEEL CONTROL: duplicate German labels exercise ID selection", f.a.translation, f.b.translation)
        assertNotEquals("R6 WHEEL CONTROL: distinct stable IDs", f.a.id, f.b.id)
        // A is the SECOND source item: choosing the first duplicate by its label is wrong.
        assertEquals("R6 WHEEL CONTROL: A is not first duplicate", f.a.id, f.lesson.items.last().id)
        required(hasText("Übersetzung ausblenden")).assertHasClickAction()
        assertEquals("R6 WHEEL CONTROL: no provider use", 0, f.providerCalls.get())
        compose.runOnIdle { assertTrue("R6 WHEEL CONTROL: no completion", f.completions.isEmpty()) }

        // Let real preparation and source resolution settle before observing only visual changes.
        runBlocking { delay(1_200) }
        val player = compose.runOnIdle { PlaybackService.localPlayer!! }
        val before = compose.runOnIdle { queueSnapshot(player) }
        val files = fileHashes(paused, f)
        val synthesisBefore = f.syntheses.get()
        val checkpointsBefore = f.playbackCalls.get()
        val discontinuities = AtomicInteger()
        val queueChanges = AtomicInteger()
        val resumed = AtomicInteger()
        val ended = AtomicInteger()
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                discontinuities.incrementAndGet()
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) queueChanges.incrementAndGet()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) resumed.incrementAndGet()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) resumed.incrementAndGet() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.incrementAndGet()
            }
        }
        val observer = compose.runOnIdle {
            player.addListener(listener)
            f.owner.launch(start = CoroutineStart.UNDISPATCHED) {
                f.app.playback.state.collect { if (it.ended) ended.incrementAndGet() }
            }
        }
        fun unchanged() {
            // Includes the coordinator's 1s paused polling interval and delayed UI work.
            runBlocking { delay(1_200) }
            compose.runOnIdle {
                val state = f.app.playback.state.value
                assertSame("R6 WHEEL LANGUAGE PRODUCT: actual player retained", player, PlaybackService.localPlayer)
                assertSame("R6 WHEEL LANGUAGE PRODUCT: PreparedSession not replaced", paused, state.session)
                assertEquals("R6 WHEEL LANGUAGE PRODUCT: complete session retained", paused, state.session)
                assertEquals("R6 WHEEL LANGUAGE PRODUCT: lesson retained", f.lesson, state.session?.lesson)
                assertEquals("R6 WHEEL LANGUAGE PRODUCT: session ID retained", initial.id, state.session?.id)
                assertEquals("R6 WHEEL LANGUAGE PRODUCT: single item ID retained", f.a.id, state.session?.singleItemId)
                assertEquals("R6 WHEEL LANGUAGE PRODUCT: complete cue list retained", paused.cues, state.session?.cues)
                assertEquals("R6 WHEEL LANGUAGE PRODUCT: queue/URIs/index/position retained", before, queueSnapshot(player))
                assertFalse("R6 WHEEL LANGUAGE PRODUCT: paused intention", player.playWhenReady)
                assertFalse("R6 WHEEL LANGUAGE PRODUCT: actual audio paused", player.isPlaying)
                assertFalse("R6 WHEEL LANGUAGE PRODUCT: coordinator paused", state.playing)
                assertFalse("R6 WHEEL LANGUAGE PRODUCT: not completed", state.ended)
                assertNull("R6 WHEEL LANGUAGE PRODUCT: no error", state.error)
                assertNull("R6 WHEEL LANGUAGE PRODUCT: no preparation", f.app.playback.preparing.value)
                assertTrue("R6 WHEEL LANGUAGE PRODUCT: no completion navigation", f.completions.isEmpty())
                assertEquals("R6 WHEEL LANGUAGE PRODUCT: no position discontinuity", 0, discontinuities.get())
                assertEquals("R6 WHEEL LANGUAGE PRODUCT: no queue change", 0, queueChanges.get())
                assertEquals("R6 WHEEL LANGUAGE PRODUCT: never resumed", 0, resumed.get())
                assertEquals("R6 WHEEL LANGUAGE PRODUCT: no completion event", 0, ended.get())
            }
            assertEquals("R6 WHEEL LANGUAGE PRODUCT: no synthesis", synthesisBefore, f.syntheses.get())
            assertEquals("R6 WHEEL LANGUAGE PRODUCT: no audio preparation/replan/write", checkpointsBefore, f.playbackCalls.get())
            assertEquals("R6 WHEEL LANGUAGE PRODUCT: no provider access", 0, f.providerCalls.get())
            assertEquals("R6 WHEEL LANGUAGE PRODUCT: all actual audio hashes retained", files, fileHashes(paused, f))
            assertEquals("R6 WHEEL LANGUAGE PRODUCT: repository unchanged", f.lesson,
                runBlocking { f.app.repository.content(f.lesson.id) })
        }
        var swapFailure: Throwable? = null
        try {
            unchanged() // Positive paused-audio prerequisite, before the required new action.
            swapControl().performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
            val translated = hasContentDescription("Eintrag auswählen: ${f.a.translation}") and hasAnyAncestor(wheel)
            // Duplicate labels are legal; selection semantics, not a global text match, disambiguate.
            required(translated and isSelected()).performScrollTo().assertIsDisplayed().assertHasClickAction()
            required(translated and isNotSelected()).assertHasClickAction()
            val translatedRows = compose.onAllNodes(translated).fetchSemanticsNodes()
            assertEquals("R6 WHEEL LANGUAGE PRODUCT: both duplicate-label source rows retained", 2, translatedRows.size)
            assertEquals("R6 WHEEL LANGUAGE PRODUCT: second source item A stays selected despite duplicate labels",
                translatedRows.last().id, required(translated and isSelected()).fetchSemanticsNode().id)
            readableText(hasText(f.a.translation) and hasAnyAncestor(translated and isSelected()))
            assertEquals("R6 WHEEL LANGUAGE PRODUCT: only one selected row", 1,
                compose.onAllNodes(isSelected() and hasAnyAncestor(wheel)).fetchSemanticsNodes().size)
            readableText(hasText(f.a.target) and !hasAnyAncestor(wheel))
            required(hasText("Übersetzung ausblenden")).assertHasClickAction()
            unchanged()

            swapControl().performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
            targetRow(f.a).performScrollTo().assertIsDisplayed().assertIsSelected()
            targetRow(f.b).assertIsNotSelected()
            readableText(hasText(f.a.target) and hasAnyAncestor(wheel))
            readableText(hasText(f.a.translation) and !hasAnyAncestor(wheel))
            unchanged()
        } catch (t: Throwable) { swapFailure = t; throw t }
        finally {
            val listenerCleanup = runCatching { compose.runOnIdle { player.removeListener(listener) } }.exceptionOrNull()
            val observerCleanup = runCatching { runBlocking { observer.cancelAndJoin() } }.exceptionOrNull()
            val problems = listOfNotNull(listenerCleanup, observerCleanup)
            if (swapFailure != null) problems.forEach { swapFailure.addSuppressed(it) }
            else if (problems.isNotEmpty()) {
                problems.drop(1).forEach { problems.first().addSuppressed(it) }
                throw problems.first()
            }
        }
    }

    private fun swapControl(): SemanticsNodeInteraction {
        val matcher = hasContentDescription("Radsprache wechseln")
        val count = compose.onAllNodes(matcher).fetchSemanticsNodes().size
        if (count == 0) throw AssertionError("R6 WHEEL LANGUAGE PRODUCT: missing language swap control")
        if (count != 1) throw AssertionError("R6 WHEEL LANGUAGE PRODUCT: expected one language swap control; found $count")
        return compose.onNode(matcher)
    }

    private fun targetRow(item: VocabItem) = required(
        hasContentDescription("Eintrag auswählen: ${item.target}") and hasAnyAncestor(wheel))

    private fun required(matcher: SemanticsMatcher, unmerged: Boolean = false): SemanticsNodeInteraction {
        val count = compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().size
        if (count != 1) throw AssertionError("R6 WHEEL LANGUAGE PRODUCT: expected one $matcher; found $count")
        return compose.onNode(matcher, useUnmergedTree = unmerged)
    }

    private fun readableText(matcher: SemanticsMatcher) {
        val node = required(matcher, unmerged = true).performScrollTo().assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("R6 WHEEL LANGUAGE PRODUCT: measured text layout", action(results))
        }
        assertTrue("R6 WHEEL LANGUAGE PRODUCT: nonempty text layout", results.isNotEmpty())
        results.forEach { result ->
            assertEquals("R6 WHEEL LANGUAGE PRODUCT: scoped font scale", 1f, result.layoutInput.density.fontScale, 0f)
            assertTrue("R6 WHEEL LANGUAGE PRODUCT: positive measured text size", result.size.width > 0 && result.size.height > 0)
            // Use actual line extents: paragraph layout width may exceed intrinsic Text width.
            assertTrue("R6 WHEEL LANGUAGE PRODUCT: full text height fits", result.multiParagraph.height <= result.size.height + 1f)
            assertFalse("R6 WHEEL LANGUAGE PRODUCT: no exceeded max lines", result.multiParagraph.didExceedMaxLines)
            for (line in 0 until result.lineCount) {
                assertTrue("R6 WHEEL LANGUAGE PRODUCT: line left fits", result.getLineLeft(line) >= -1f)
                assertTrue("R6 WHEEL LANGUAGE PRODUCT: line right fits", result.getLineRight(line) <= result.size.width + 1f)
                assertFalse("R6 WHEEL LANGUAGE PRODUCT: no ellipsis", result.isLineEllipsized(line))
            }
        }
    }

    private data class QueueSnapshot(val items: List<MediaItem>, val identities: List<Pair<String, String?>>,
        val index: Int, val position: Long)

    private fun queueSnapshot(player: Player): QueueSnapshot {
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        return QueueSnapshot(items, items.map { it.mediaId to it.localConfiguration?.uri?.toString() },
            player.currentMediaItemIndex, player.currentPosition)
    }

    private fun fileHashes(session: PreparedSession, f: Fixture): Map<String, String> = runBlocking {
        val files = (session.cues + session.sourceCues).map { cue ->
            val asset = f.app.db.dao().audio(cue.key) ?: throw AssertionError("FIXTURE: missing real audio metadata")
            assertTrue("FIXTURE: real measured WAV and metadata", AudioFiles.valid(File(asset.path), asset.bytes, asset.sha256))
            File(asset.path)
        } + compose.runOnIdle {
            val player = PlaybackService.localPlayer!!
            (0 until player.mediaItemCount).map { File(requireNotNull(player.getMediaItemAt(it).localConfiguration).uri.path!!) }
        }
        files.distinctBy { it.path }.associate { it.path to AudioFiles.hash(it) }
    }

    private fun eventually(label: String, timeout: Long = 8_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { compose.runOnIdle(condition) } }
        catch (e: ComposeTimeoutException) { throw AssertionError("$label (timeout ${timeout}ms)", e) }
    }

    private inner class Fixture(val app: VociApp, val lesson: LessonContent) {
        val a = lesson.items[1]
        val b = lesson.items[0]
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        val providerCalls = AtomicInteger()
        val syntheses = AtomicInteger()
        val playbackCalls = AtomicInteger()
        val vm = VociViewModel(app, SavedStateHandle(), object : LessonProvider {
            override suspend fun generate(spec: SessionSpec): LessonContent {
                providerCalls.incrementAndGet()
                throw AssertionError("FIXTURE: wheel must never generate or access a provider")
            }
        }).also { store.put("r6-wheel", it) }
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

        fun show(session: PreparedSession) {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                    if (visible.value) VociTheme {
                        PlayerScreen(session.id, vm, {}, {}) { id, _ -> completions += id }
                    }
                }
            }
            shown = true
            compose.waitForIdle()
        }
        fun selected(initial: PreparedSession, item: VocabItem): PreparedSession {
            eventually("R6 WHEEL CONTROL: accessible selection settles with original session and paused intention", 60_000) {
                val state = app.playback.state.value
                val player = PlaybackService.localPlayer!!
                assertNull("R6 WHEEL CONTROL: selection error", state.error)
                assertEquals("R6 WHEEL CONTROL: selection keeps original session", initial.id, state.session?.id)
                state.session?.singleItemId == item.id && state.session?.cues?.all { it.itemId == item.id } == true &&
                    app.playback.preparing.value == null && player.playbackState == Player.STATE_READY &&
                    !state.ended && !player.playWhenReady && !player.isPlaying && !state.playing
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
        val token = UUID.randomUUID().toString().replace("-", "")
        val spokenToken = token.take(8)
        val lesson = LessonContent("r6-wheel-$token", "R6 wheel $token", "en-GB", listOf(
            VocabItem("r6-wheel-$token-b", "Hi $spokenToken", "Hallo $spokenToken", "Hi there $spokenToken.", "Hallo dort $spokenToken."),
            VocabItem("r6-wheel-$token-a", "Hello $spokenToken", "Hallo $spokenToken", "Hello again $spokenToken.", "Nochmals hallo $spokenToken.")
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
        // Preserve every other setting; never clear or replace the preference store.
        @Suppress("UNCHECKED_CAST")
        val settings = PreferencesStore::class.java.getDeclaredField("store").apply { isAccessible = true }
            .get(app.preferences) as DataStore<Preferences>
        val settingsBefore = runBlocking { settings.data.first() }
        val markKey = stringSetPreferencesKey("uncertain.lesson.v1:${lesson.id}")
        assertNull("FIXTURE: UUID has no previous marks", settingsBefore[markKey])
        var f: Fixture? = null
        var failure: Throwable? = null
        try {
            runBlocking { settings.edit { it.remove(wheelTranslationFirst) } }
            assertNull("FIXTURE: absent preference exercises R5 target-first default",
                runBlocking { settings.data.first()[wheelTranslationFirst] })
            app.tts.checkpoint = { stage, cue ->
                if (stage == "synthesized") f?.syntheses?.incrementAndGet()
                ttsHook(stage, cue)
                if (stage == "renamed" && lesson.items.any { it.id == cue.itemId }) {
                    File(app.filesDir, "audio").listFiles().orEmpty()
                        .filter { it.name !in oldAudio && it.extension == "wav" && !it.name.endsWith(".part.wav") }
                        .forEach { ownedKeys.add(it.nameWithoutExtension) }
                }
            }
            p.checkpoint = { stage, id ->
                f?.playbackCalls?.incrementAndGet()
                playbackHook(stage, id)
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
                        }
                    }
                }
            }.exceptionOrNull()
            // Preference restoration is independent of artifact cleanup, including on expected RED.
            val preferencesRestore = runCatching {
                runBlocking {
                    settings.edit { values ->
                        values.remove(markKey)
                        val previous = settingsBefore[wheelTranslationFirst]
                        if (previous == null) values.remove(wheelTranslationFirst)
                        else values[wheelTranslationFirst] = previous
                    }
                    assertEquals("FIXTURE: all settings preserved", settingsBefore, settings.data.first())
                }
            }.exceptionOrNull()
            // Exact flow values and delegates restored even when body or cleanup fails.
            val stateRestore = runCatching {
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
            }.exceptionOrNull()
            val problems = listOfNotNull(unmount, cleanup, preferencesRestore, stateRestore)
            if (failure != null) problems.forEach { failure.addSuppressed(it) }
            else if (problems.isNotEmpty()) {
                problems.drop(1).forEach { problems.first().addSuppressed(it) }
                throw problems.first()
            }
        }
    }
}
