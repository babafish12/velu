package de.voci

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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.UUID

/** One recovery regression using real offline TTS, Media3 and session persistence.
 * Parent provides an idle local service, offline en-GB/de-DE voices and isolates foreign
 * last-session files. Expected PRODUCT RED until repeat/replan permits completion recovery.
 * Fixture helpers are copied from frozen R5WheelEdgeTest; no synthetic playback events.
 */
class R5RejectedSelectionReplayTest {
    @Test fun repeatAfterRejectedSelectionAllowsNaturalCompletion() = fixture { app, vm, lesson ->
        val p = app.playback
        val initial = pausedSingle(app, lesson)
        val player = PlaybackService.localPlayer!!
        val originalQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val last = File(app.filesDir, "last-session.json")
        assertEquals("FIXTURE: original A persisted", initial, disk(last))
        val inventory = app.tts.voiceInventory
        val oldHook = p.checkpoint
        var selection: Job? = null
        var replay: Job? = null
        try {
            assertFalse("FIXTURE: B is uncached", initial.sourceCues.any { it.itemId == lesson.items[1].id })
            app.tts.voiceInventory = { emptySet() }
            selection = p.selectItem(initial.id, lesson.items[1].id)
            boundedJoin(selection, "FIXTURE: rejected B selection returns")
            assertEquals("FIXTURE: real MissingVoice rejection", "Sprachdaten fehlen (en-GB).", p.state.value.error)
            assertSame("FIXTURE: rejection retains exact A session", initial, p.state.value.session)
            assertEquals("FIXTURE: rejection retains exact A queue", originalQueue,
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it) })
            queue(app, initial, lesson.items[0], "FIXTURE")
            assertFalse("FIXTURE: rejected selection leaves A paused", player.playWhenReady)
            assertFalse("FIXTURE: A has not ended", player.playbackState == Player.STATE_ENDED)
            app.tts.voiceInventory = inventory

            val replanned = CompletableDeferred<Unit>()
            p.checkpoint = { stage, lessonId ->
                oldHook(stage, lessonId)
                if (stage == "replan_write" && lessonId == lesson.id) replanned.complete(Unit)
            }
            replay = p.repeat()
            boundedJoin(replay, "R5 RECOVERY PRODUCT: repeat returns")
            assertTrue("FIXTURE: public repeat reached real replan persistence", replanned.isCompleted)
            val repeated = p.state.value.session ?: throw AssertionError("R5 RECOVERY PRODUCT: repeated session exists")
            assertEquals("R5 RECOVERY PRODUCT: repeat retains session ID", initial.id, repeated.id)
            assertEquals("R5 RECOVERY PRODUCT: repeat retains lesson", initial.lesson, repeated.lesson)
            queue(app, repeated, lesson.items[0], "R5 RECOVERY PRODUCT")
            assertEquals("R5 RECOVERY PRODUCT: real repeated session persisted", repeated, disk(last))
            assertFalse("R5 RECOVERY PRODUCT: repeat retains pause intention", player.playWhenReady)

            assertTrue("FIXTURE: bounded final pause exists", repeated.cues.last().pauseMs >= 300)
            player.seekTo(player.mediaItemCount - 1, repeated.cues.last().pauseMs - 300)
            waitFor("FIXTURE: final WAV silence ready") { player.playbackState == Player.STATE_READY }
            p.pausePlay()
            waitFor("FIXTURE: real Media3 natural end", 8_000) { player.playbackState == Player.STATE_ENDED }
            assertEquals("FIXTURE: real end belongs to repeated A", repeated, p.state.value.session)
            waitFor("R5 RECOVERY PRODUCT: repeated A publishes natural completion", 8_000) { p.state.value.ended }
            assertEquals("R5 RECOVERY PRODUCT: completion claim available", initial.id,
                vm.claimCompletion(initial.id)?.sessionId)
        } finally {
            app.tts.voiceInventory = inventory
            p.checkpoint = oldHook
            selection?.cancel()
            replay?.cancel()
            withContext(NonCancellable) {
                selection?.let { boundedJoin(it, "FIXTURE: rejected selection cleanup") }
                replay?.let { boundedJoin(it, "FIXTURE: repeat cleanup") }
            }
        }
    }

    private suspend fun disk(file: File): PreparedSession? = withContext(Dispatchers.IO) {
        runCatching { Json.decodeFromString<PreparedSession>(file.readText()) }.getOrNull()
    }

    private suspend fun pausedSingle(app: VociApp, lesson: LessonContent): PreparedSession {
        val p = app.playback
        val id = p.start(lesson, SessionSpec(lesson.title, lesson.language, 1), 1f, singleItemId = lesson.items[0].id)
        waitFor("FIXTURE: real finite A preparation", 60_000) {
            assertNull("FIXTURE: A preparation error", p.state.value.error)
            p.preparing.value == null && p.state.value.session?.id == id
        }
        val player = PlaybackService.localPlayer!!
        waitFor("FIXTURE: actual A playback") { player.isPlaying }
        player.pause()
        waitFor("FIXTURE: A paused") { !player.isPlaying && !p.state.value.playing }
        assertFalse("FIXTURE: pause before natural endpoint", player.playbackState == Player.STATE_ENDED)
        val session = p.state.value.session!!
        queue(app, session, lesson.items[0], "FIXTURE")
        return session
    }

    private suspend fun queue(app: VociApp, session: PreparedSession, item: VocabItem, label: String) {
        assertEquals("$label: finite Single identity", item.id, session.singleItemId)
        assertFalse("$label: no forced recall", session.recall)
        assertEquals("$label: four cues", 4, session.cues.size)
        assertEquals("$label: four phases", listOf("target", "meaning", "repeat", "example"), session.cues.map { it.phase })
        val texts = mapOf("target" to item.target, "meaning" to item.translation, "repeat" to item.target, "example" to item.example)
        val player = PlaybackService.localPlayer!!
        assertEquals("$label: actual four audio/pause pairs", 8, player.mediaItemCount)
        session.cues.forEachIndexed { index, cue ->
            assertEquals("$label: selected item", item.id, cue.itemId)
            assertEquals("$label: utterance mapping", texts[cue.phase], cue.text)
            val asset = app.db.dao().audio(cue.key) ?: throw AssertionError("$label: missing audio metadata")
            assertTrue("$label: measured duration", cue.durationMs > 0 && cue.durationMs == asset.durationMs)
            assertTrue("$label: validated real WAV", AudioFiles.valid(File(asset.path), asset.bytes, asset.sha256))
            assertEquals("$label: Media3 audio identity", cue.key, player.getMediaItemAt(index * 2).mediaId)
            assertEquals("$label: Media3 validated file", asset.path, player.getMediaItemAt(index * 2).localConfiguration?.uri?.toString())
            assertEquals("$label: real pause", "pause", player.getMediaItemAt(index * 2 + 1).mediaId)
        }
    }

    private suspend fun waitFor(label: String, timeout: Long = 8_000, condition: suspend () -> Boolean) {
        val reached = withTimeoutOrNull(timeout) {
            while (!condition()) delay(20)
            true
        }
        if (reached != true) throw AssertionError("$label (timeout ${timeout}ms)")
    }

    private suspend fun boundedJoin(job: Job, label: String) {
        val joined = withTimeoutOrNull(15_000) { job.join(); true }
        if (joined != true) throw AssertionError(label)
    }

    private fun fixture(body: suspend (VociApp, VociViewModel, LessonContent) -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
            val p = app.playback
            waitFor("FIXTURE: local Media3 service connected") { PlaybackService.localPlayer != null }
            withTimeout(10_000) { p.restore() }
            val player = PlaybackService.localPlayer!!
            assertNull("FIXTURE: idle device, no restored session", p.state.value.session)
            assertNull("FIXTURE: no pending preparation", p.preparing.value)
            assertEquals("FIXTURE: empty player queue", 0, player.mediaItemCount)
            val token = UUID.randomUUID().toString()
            val spoken = token.take(8)
            val lesson = LessonContent("r5-rejected-replay-$token", "R5 recovery $token", "en-GB", listOf(
                VocabItem("$token-a", "Blue $spoken", "Blau $spoken", "It is blue $spoken.", "Es ist blau $spoken."),
                VocabItem("$token-b", "Green $spoken", "Grün $spoken", "It is green $spoken.", "Es ist grün $spoken.")
            ))
            val beforeState = p.state.value
            val beforeAnswers = p.answers.value
            val beforeUncertain = p.uncertain.value
            val beforePlay = player.playWhenReady
            val beforeParameters = player.playbackParameters
            val inventory = app.tts.voiceInventory
            val ttsHook = app.tts.checkpoint
            val playbackHook = p.checkpoint
            val lastFile = File(app.filesDir, "last-session.json")
            val lastBytes = if (lastFile.exists()) lastFile.readBytes() else null
            val oldAudio = File(app.filesDir, "audio").list().orEmpty().toSet()
            val oldSilences = app.filesDir.list().orEmpty().filter { it.startsWith("silence-") }.toSet()
            val ownedKeys = Collections.synchronizedSet(mutableSetOf<String>())
            @Suppress("UNCHECKED_CAST")
            val settings = PreferencesStore::class.java.getDeclaredField("store").apply { isAccessible = true }
                .get(app.preferences) as DataStore<Preferences>
            val settingsBefore = settings.data.first()
            val markKey = stringSetPreferencesKey("uncertain.lesson.v1:${lesson.id}")
            assertNull("FIXTURE: UUID has no prior marks", settingsBefore[markKey])
            assertNull("FIXTURE: UUID has no prior lesson", app.db.dao().lesson(lesson.id))
            val store = ViewModelStore()
            var vm: VociViewModel? = null
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
                for (locale in listOf("en-GB", "de-DE")) {
                    assertTrue("FIXTURE: installed offline voice $locale", withTimeout(20_000) { app.tts.voices(locale) }.isNotEmpty())
                }
                app.repository.save(lesson)
                val active = VociViewModel(app, SavedStateHandle(), object : LessonProvider {
                    override suspend fun generate(spec: SessionSpec): LessonContent =
                        throw AssertionError("FIXTURE: replay must never invoke generation")
                }).also { store.put("r5-rejected-replay", it) }
                vm = active
                val prefs = app.preferences.flow.first()
                waitFor("FIXTURE: VM preferences ready") { active.prefs.value == prefs }
                withTimeout(120_000) { body(app, active, lesson) }
            } catch (t: Throwable) { failure = t; throw t }
            finally {
                withContext(NonCancellable) {
                    val cleanup = runCatching {
                        withTimeout(20_000) {
                            val draining = p.cancelPreparation()
                            vm?.cancel(); store.clear(); draining?.join()
                            // Stop sampling test playback before draining actual progress writes.
                            p.state.value = beforeState
                            player.pause(); player.clearMediaItems()
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
                            assertEquals("FIXTURE: all preferences preserved", settingsBefore, settings.data.first())
                        }
                    }.exceptionOrNull()
                    // Restore owned delegates and exact flow/player values even after a failing assertion.
                    app.tts.voiceInventory = inventory; app.tts.checkpoint = ttsHook; p.checkpoint = playbackHook
                    store.clear()
                    p.state.value = beforeState; p.preparing.value = null
                    p.answers.value = beforeAnswers; p.uncertain.value = beforeUncertain
                    player.playbackParameters = beforeParameters; player.playWhenReady = beforePlay
                    if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
                }
            }
        }
    }
}
