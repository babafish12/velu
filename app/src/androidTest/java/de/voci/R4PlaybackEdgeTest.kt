package de.voci

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.AudioFiles
import de.voci.audio.PlaybackService
import de.voci.audio.PreparedSession
import de.voci.domain.LessonContent
import de.voci.domain.SessionProgress
import de.voci.domain.SessionSpec
import de.voci.domain.VocabItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real system TTS / Media3 regressions. Requires installed offline English and German voices.
 * No device configuration, authentication, fabricated progress or simulated player completion.
 * FIXTURE failures identify prerequisites; R4 EDGE PRODUCT failures identify contracts.
 */
class R4PlaybackEdgeTest {
    @Test fun pendingUncachedSelectionSurvivesOldItemsNaturalEnd() = fixture { app, vm, lesson ->
        val p = app.playback
        val a = lesson.items[0].id
        val b = lesson.items[1].id
        val initial = start(app, lesson, a)
        queue(app, initial, a, "FIXTURE")
        waitFor("FIXTURE: A is really playing") { PlaybackService.localPlayer!!.isPlaying }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldHook = app.tts.checkpoint
        var selection: Job? = null
        var ended = false
        var delivered = false
        val observer = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            p.state.collect { state ->
                if (state.session?.id == initial.id && !release.isCompleted) {
                    ended = ended || state.ended
                    delivered = delivered || vm.claimCompletion(initial.id) != null
                }
            }
        }
        try {
            app.tts.checkpoint = { stage, cue ->
                oldHook(stage, cue)
                if (stage == "synthesized" && cue.itemId == b) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            selection = p.selectItem(initial.id, b)
            waitFor("FIXTURE: unique B reaches real synthesized checkpoint", 45_000) {
                assertNull("FIXTURE: B preparation before gate", p.state.value.error)
                entered.isCompleted
            }
            val player = PlaybackService.localPlayer!!
            assertTrue("FIXTURE: selection still suspended", selection.isActive)
            // Seek the REAL player into A's final WAV silence. Do not resume it here:
            // a fixed coordinator may deliberately pause A while B is pending.
            player.seekTo(player.mediaItemCount - 1, (initial.cues.last().pauseMs - 300).coerceAtLeast(0))
            waitFor("FIXTURE: final silence loaded") { player.playbackState != Player.STATE_BUFFERING }
            // Bounded wall time, never wait for STATE_ENDED (fixed code may pause A).
            // At speed 1 this exceeds the entire 650ms silence plus event dispatch time.
            delay(2_000)
            val endedWhilePending = ended || p.state.value.ended
            val deliveredWhilePending = delivered || vm.claimCompletion(initial.id) != null
            release.complete(Unit)
            boundedJoin(selection, "R4 EDGE PRODUCT: accepted selection completes")
            assertFalse("R4 EDGE PRODUCT: no ended state while accepted selection pending", endedWhilePending)
            assertFalse("R4 EDGE PRODUCT: no completion delivery while accepted selection pending", deliveredWhilePending)
            assertNull("R4 EDGE PRODUCT: selection has no preparation error", p.state.value.error)
            val selected = p.state.value.session!!
            assertEquals("R4 EDGE PRODUCT: selection keeps session", initial.id, selected.id)
            queue(app, selected, b, "R4 EDGE PRODUCT")
            waitFor("R4 EDGE PRODUCT: B preserves user's play intention") {
                player.isPlaying && player.playWhenReady && p.state.value.playing
            }
        } finally {
            release.complete(Unit)
            app.tts.checkpoint = oldHook
            selection?.cancel()
            withContext(NonCancellable) {
                selection?.let { boundedJoin(it, "FIXTURE: selection cleanup") }
                observer.cancelAndJoin()
            }
        }
    }

    @Test fun cachedFullCoursePlaysWithEmptyVoiceInventory_positiveControl() = fixture { app, _, lesson ->
        val course = cachedCourse(app, lesson)
        val before = File(app.filesDir, "plans/${lesson.id}.json").readBytes()
        val row = app.db.dao().lesson(lesson.id)
        val inventory = app.tts.voiceInventory
        try {
            app.tts.voiceInventory = { emptySet() }
            val replay = start(app, lesson, null, "R4 EDGE PRODUCT")
            assertNull("R4 EDGE PRODUCT: offline full course remains full course", replay.singleItemId)
            assertEquals("R4 EDGE PRODUCT: cached full-course cues", course.cues, replay.cues)
            queue(app, replay, null, "R4 EDGE PRODUCT")
            waitFor("R4 EDGE PRODUCT: cached full course actually plays") { PlaybackService.localPlayer!!.isPlaying }
            preserved(app, lesson, before, row)
        } finally { app.tts.voiceInventory = inventory }
    }

    @Test fun cachedSingleStartNeedsNoVoiceInventory() = fixture { app, _, lesson ->
        val course = cachedCourse(app, lesson)
        val before = File(app.filesDir, "plans/${lesson.id}.json").readBytes()
        val row = app.db.dao().lesson(lesson.id)
        val inventory = app.tts.voiceInventory
        try {
            app.tts.voiceInventory = { emptySet() }
            val single = start(app, lesson, lesson.items[0].id, "R4 EDGE PRODUCT")
            cachedSingle(app, single, course, lesson.items[0].id)
            waitFor("R4 EDGE PRODUCT: cached single start really plays") { PlaybackService.localPlayer!!.isPlaying }
            preserved(app, lesson, before, row)
        } finally { app.tts.voiceInventory = inventory }
    }

    @Test fun cachedSingleSelectionNeedsNoVoiceInventory() = fixture { app, _, lesson ->
        val course = cachedCourse(app, lesson)
        val before = File(app.filesDir, "plans/${lesson.id}.json").readBytes()
        val row = app.db.dao().lesson(lesson.id)
        val initial = start(app, lesson, lesson.items[0].id)
        queue(app, initial, lesson.items[0].id, "FIXTURE")
        PlaybackService.localPlayer!!.pause()
        val inventory = app.tts.voiceInventory
        var selection: Job? = null
        try {
            app.tts.voiceInventory = { emptySet() }
            selection = app.playback.selectItem(initial.id, lesson.items[1].id)
            boundedJoin(selection, "R4 EDGE PRODUCT: cached selection returns")
            assertNull("R4 EDGE PRODUCT: cached selection needs no voices", app.playback.state.value.error)
            val selected = app.playback.state.value.session!!
            assertEquals("R4 EDGE PRODUCT: cached selection retains session", initial.id, selected.id)
            cachedSingle(app, selected, course, lesson.items[1].id)
            assertFalse("R4 EDGE PRODUCT: cached selection preserves pause", PlaybackService.localPlayer!!.playWhenReady)
            preserved(app, lesson, before, row)
        } finally {
            app.tts.voiceInventory = inventory
            selection?.cancel()
            withContext(NonCancellable) { selection?.let { boundedJoin(it, "FIXTURE: selection cleanup") } }
        }
    }

    @Test fun repeatUncertainAfterSingleSwitchReplaysMarkedAInsteadOfB() = fixture { app, vm, lesson ->
        val a = lesson.items[0].id
        val b = lesson.items[1].id
        vm.playItem(lesson.id, a)
        val initial = prepared(app, "FIXTURE", vm = vm)
        queue(app, initial, a, "FIXTURE")
        vm.markUncertain(a)
        PlaybackService.localPlayer!!.pause()
        val selection = app.playback.selectItem(initial.id, b)
        try {
            boundedJoin(selection, "FIXTURE: switch to B")
            assertNull("FIXTURE: B preparation", app.playback.state.value.error)
            val selected = app.playback.state.value.session!!
            queue(app, selected, b, "FIXTURE")
            assertEquals("FIXTURE: only A marked", setOf(a), vm.uncertain.value)
            val player = PlaybackService.localPlayer!!
            player.seekTo(player.mediaItemCount - 1, (selected.cues.last().pauseMs - 300).coerceAtLeast(0))
            player.play()
            waitFor("FIXTURE: B naturally ends in real Media3", 8_000) {
                player.playbackState == Player.STATE_ENDED && app.playback.state.value.ended
            }
            vm.repeatUncertain()
            val replay = prepared(app, "R4 EDGE PRODUCT", previousId = selected.id, vm = vm)
            assertEquals("R4 EDGE PRODUCT: repeat keeps original lesson ID", lesson.id, replay.lesson.id)
            queue(app, replay, a, "R4 EDGE PRODUCT")
            assertEquals("R4 EDGE PRODUCT: uncertain repeat stays finite single mode", a, replay.singleItemId)
            assertFalse("R4 EDGE PRODUCT: uncertain repeat does not enable recall", replay.recall)
            waitFor("R4 EDGE PRODUCT: marked A actually replays") { player.isPlaying }
            player.seekTo(player.mediaItemCount - 1, (replay.cues.last().pauseMs - 300).coerceAtLeast(0))
            waitFor("R4 EDGE PRODUCT: repeated A naturally terminates", 8_000) {
                player.playbackState == Player.STATE_ENDED && app.playback.state.value.ended
            }
        } finally {
            selection.cancel()
            withContext(NonCancellable) { boundedJoin(selection, "FIXTURE: repeat selection cleanup") }
        }
    }

    private suspend fun start(app: VociApp, lesson: LessonContent, item: String?, label: String = "FIXTURE"): PreparedSession {
        app.playback.start(lesson, SessionSpec(lesson.title, lesson.language, 1), 1f, singleItemId = item)
        return prepared(app, label)
    }

    private suspend fun prepared(app: VociApp, label: String, previousId: String? = null, vm: VociViewModel? = null): PreparedSession {
        waitFor("$label: real audio preparation", 60_000) {
            // MissingVoice is an immediate PRODUCT assertion in offline regression cases,
            // never a later fixture timeout waiting for a session that cannot be published.
            assertNull("$label: audio preparation error", app.playback.state.value.error)
            assertNull("$label: VM request error", vm?.error?.value)
            val s = app.playback.state.value.session
            app.playback.preparing.value == null && s != null && s.id != previousId && vm?.busy?.value != true
        }
        return app.playback.state.value.session!!
    }

    private suspend fun cachedCourse(app: VociApp, lesson: LessonContent): PreparedSession {
        val course = start(app, lesson, null)
        app.playback.pin(course.id)
        PlaybackService.localPlayer!!.pause()
        assertTrue("FIXTURE: full course and source assets intact", app.playback.offline(course))
        assertTrue("FIXTURE: full plan has both measured items", lesson.items.all { item -> course.sourceCues.any { it.itemId == item.id } })
        queue(app, course, null, "FIXTURE")
        return course
    }

    private suspend fun cachedSingle(app: VociApp, single: PreparedSession, course: PreparedSession, item: String) {
        assertEquals("R4 EDGE PRODUCT: original lesson identity", course.lesson.id, single.lesson.id)
        assertEquals("R4 EDGE PRODUCT: original measured source cues reused",
            course.sourceCues.filter { it.itemId == item }, single.cues)
        queue(app, single, item, "R4 EDGE PRODUCT")
    }

    private suspend fun queue(app: VociApp, session: PreparedSession, item: String?, label: String) {
        val player = PlaybackService.localPlayer ?: throw AssertionError("$label: missing real Media3 player")
        if (item != null) {
            assertEquals("$label: requested single target", item, session.singleItemId)
            assertEquals("$label: finite four-phase queue", 4, session.cues.size)
            assertEquals("$label: all single phases", setOf("target", "meaning", "repeat", "example"), session.cues.map { it.phase }.toSet())
            assertTrue("$label: only requested item", session.cues.all { it.itemId == item })
        }
        assertEquals("$label: actual cue/pause queue size", session.cues.size * 2, player.mediaItemCount)
        session.cues.forEachIndexed { index, cue ->
            val asset = app.db.dao().audio(cue.key) ?: throw AssertionError("$label: missing audio metadata")
            assertTrue("$label: actual measured duration", cue.durationMs > 0 && cue.durationMs == asset.durationMs)
            assertTrue("$label: validated real audio", AudioFiles.valid(File(asset.path), asset.bytes, asset.sha256))
            assertEquals("$label: Media3 owns cue", cue.key, player.getMediaItemAt(index * 2).mediaId)
            assertEquals("$label: Media3 owns validated file", asset.path, player.getMediaItemAt(index * 2).localConfiguration?.uri?.toString())
            assertEquals("$label: actual pause", "pause", player.getMediaItemAt(index * 2 + 1).mediaId)
        }
        if (item != null) {
            val current = session.cues[player.currentMediaItemIndex / 2]
            assertEquals("$label: actual current target", item, current.itemId)
            assertEquals("$label: actual current audio or pause", if (player.currentMediaItemIndex % 2 == 0) current.key else "pause", player.currentMediaItem?.mediaId)
        }
    }

    private suspend fun preserved(app: VociApp, lesson: LessonContent, plan: ByteArray, row: de.voci.data.LessonEntity?) {
        assertArrayEquals("R4 EDGE PRODUCT: original course plan bytes unchanged", plan, File(app.filesDir, "plans/${lesson.id}.json").readBytes())
        assertEquals("R4 EDGE PRODUCT: original metadata unchanged", row, app.db.dao().lesson(lesson.id))
        assertEquals("R4 EDGE PRODUCT: original content unchanged", lesson, app.repository.content(lesson.id))
    }

    private suspend fun waitFor(label: String, timeout: Long = 8_000, condition: () -> Boolean) {
        try { withTimeout(timeout) { while (!condition()) delay(20) } }
        catch (e: TimeoutCancellationException) { throw AssertionError("$label (timeout ${timeout}ms)", e) }
    }

    private suspend fun boundedJoin(job: Job, label: String) {
        try { withTimeout(60_000) { job.join() } }
        catch (e: TimeoutCancellationException) { throw AssertionError(label, e) }
    }

    private fun fixture(body: suspend (VociApp, VociViewModel, LessonContent) -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
            val token = UUID.randomUUID().toString().take(8)
            val lesson = LessonContent("r4-edge-$token", "R4 edge $token", "en-GB", listOf(
                VocabItem("r4-edge-$token-a", "Blue $token", "Blau $token", "It is blue $token.", "Es ist blau $token."),
                VocabItem("r4-edge-$token-b", "Green $token", "Grün $token", "It is green $token.", "Es ist grün $token.")
            ))
            val store = ViewModelStore()
            val inventory = app.tts.voiceInventory
            val hook = app.tts.checkpoint
            val coordinatorHook = app.playback.checkpoint
            val ownedKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val existingAudioNames = File(app.filesDir, "audio").list().orEmpty().toSet()
            app.tts.checkpoint = { stage, cue ->
                hook(stage, cue)
                if (stage == "renamed" && lesson.items.any { it.id == cue.itemId }) {
                    File(app.filesDir, "audio").listFiles().orEmpty()
                        .filter { it.name !in existingAudioNames && it.extension == "wav" }
                        .forEach { ownedKeys.add(it.nameWithoutExtension) }
                }
            }
            val tracking = launch(start = CoroutineStart.UNDISPATCHED) {
                app.playback.state.collect { state ->
                    state.session?.takeIf { it.lesson.id == lesson.id }?.let { s -> ownedKeys += (s.cues + s.sourceCues).map { it.key } }
                }
            }
            var vm: VociViewModel? = null
            try {
                for (locale in listOf("en-GB", "de-DE")) {
                    assertTrue("FIXTURE: installed offline voice $locale", withTimeout(20_000) { app.tts.voices(locale) }.isNotEmpty())
                }
                app.repository.save(lesson)
                val activeVm = VociViewModel(app, SavedStateHandle()).also { store.put("r4-edge", it) }
                vm = activeVm
                val prefs = app.preferences.flow.first()
                waitFor("FIXTURE: VM preferences ready") { activeVm.prefs.value == prefs }
                body(app, activeVm, lesson)
            } finally {
                // Restore every touched hook even if cancellation/draining or cleanup fails.
                app.tts.voiceInventory = inventory
                app.tts.checkpoint = hook
                app.playback.checkpoint = coordinatorHook
                withContext(NonCancellable) {
                    try {
                        withTimeout(10_000) {
                            val draining = app.playback.cancelPreparation()
                            vm?.cancel()
                            store.clear()
                            draining?.join()
                            PlaybackService.localPlayer?.let { it.pause(); it.clearMediaItems() }
                            tracking.cancelAndJoin()
                            // Do NOT use deleteLesson/clearCache: those sweep unrelated unpinned assets.
                            app.db.dao().deleteLesson(lesson.id)
                            for (entry in app.db.dao().progressEntries()) {
                                val progress = runCatching { Json.decodeFromString<SessionProgress>(entry.json) }.getOrNull()
                                if (progress?.lessonId == lesson.id) app.db.dao().removeProgress(entry.sessionId)
                            }
                            File(app.filesDir, "plans/${lesson.id}.json").delete()
                            val last = File(app.filesDir, "last-session.json")
                            if (runCatching { Json.decodeFromString<PreparedSession>(last.readText()).lesson.id }.getOrNull() == lesson.id) last.delete()
                            for (key in ownedKeys) app.db.dao().audio(key)?.let { asset ->
                                File(asset.path).delete(); app.db.dao().removeAudio(key)
                            }
                        }
                    } finally {
                        tracking.cancel()
                        store.clear()
                    }
                }
            }
        }
    }
}
