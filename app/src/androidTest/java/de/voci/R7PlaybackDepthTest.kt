package de.voci

import android.app.Notification
import android.app.NotificationManager
import androidx.datastore.preferences.core.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.LessonProvider
import de.voci.ai.LessonPrompt
import de.voci.audio.PlaybackService
import de.voci.audio.PreparedSession
import de.voci.data.PreferencesStore
import de.voci.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual Activity / service-owned Media3 / offline system TTS. Parent runs serially on an idle
 * coordinator with the user's session checkpointed, as for R7ForegroundPlaybackTest.
 * Uses existing TTS/coordinator checkpoints; never writes a fake player or visibility flag.
 */
class R7PlaybackDepthTest {
    @Test fun fullQueueContainsEverySourceCueOnceBeyondOldBudgetAndDoesNotRefill() = fixture { app, _, lesson ->
        val p = app.playback
        val session = start(app, lesson)
        val player = PlaybackService.localPlayer!!
        player.pause()
        assertTrue("FIXTURE: measured lesson exceeds old minute", session.sourceCues.sumOf { it.durationMs + it.pauseMs } > 60_000)
        assertEquals("R7: complete source exactly once", session.sourceCues, session.cues)
        assertEquals(lesson.items.map { it.id }, session.cues.filter { it.phase == "target" }.map { it.itemId })
        assertEquals(session.cues.size * 2, player.mediaItemCount)
        p.speed(1.25f).join()
        assertEquals("R7: speed preserves finite cues", session.cues, p.state.value.session!!.cues)
        p.selectItem(session.id, lesson.items[1].id).join()
        val first = session.sourceCues.indexOfFirst { it.itemId == lesson.items[1].id && it.phase == "target" }
        val expected = session.sourceCues.drop(first)
        assertEquals("R7: selection seeks forward without timer filler", expected, p.state.value.session!!.cues)
        p.speed(.75f).join(); p.skip(1).join(); p.skip(-1).join()
        assertEquals("R7: speed and skip do not refill", expected, p.state.value.session!!.cues)
        assertFalse(player.playWhenReady)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
    }

    @Test fun leavingDuringPreparationCancelsLateAudio() = fixture { app, activity, lesson ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = app.playback.checkpoint
        try {
            app.playback.checkpoint = { stage, id ->
                old(stage, id)
                if (stage == "prepare" && id == lesson.id) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            app.playback.start(lesson, SessionSpec(lesson.title, lesson.language, entryCount = 5), 1f)
            withTimeout(15_000) { entered.await() }
            withContext(Dispatchers.Default) { activity.moveToState(Lifecycle.State.CREATED) }
            assertSilent(app)
            release.complete(Unit)
            delay(500)
            withContext(Dispatchers.Default) { activity.moveToState(Lifecycle.State.RESUMED) }
            delay(500)
            assertSilent(app)
            assertNull(app.playback.preparing.value)
            assertEquals(0, PlaybackService.localPlayer!!.mediaItemCount)
        } finally { release.complete(Unit); app.playback.checkpoint = old }
    }

    @Test fun legacyTimedPlanReusesFullSourceWithoutChangingSavedLessonOrPlan() = fixture { app, activity, lesson ->
        val p = app.playback
        val source = start(app, lesson)
        PlaybackService.localPlayer!!.pause()
        val file = File(app.filesDir, "plans/${lesson.id}.json")
        // Deliberately synthetic legacy plan, backed by the real measured TTS source assets.
        val timed = source.copy(cues = source.sourceCues.take(2) + source.sourceCues.take(2), finite = false)
        file.writeText(Json.encodeToString(timed))
        val bytes = file.readBytes()
        val row = requireNotNull(app.db.dao().lesson(lesson.id)).copy(saved = true)
        app.db.dao().putLesson(row)
        val old = app.tts.voiceInventory
        try {
            app.tts.voiceInventory = { emptySet() }
            val replay = start(app, lesson)
            assertEquals(source.sourceCues, replay.cues)
            assertEquals(lesson, replay.lesson)
            assertEquals(row, app.db.dao().lesson(lesson.id))
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(replay.cues.size * 2, PlaybackService.localPlayer!!.mediaItemCount)
            // Synthetic old session/progress, with real measured source. Exercise production restore
            // including an old repeated cue's thinking-pause index; this is not a process-death claim.
            withContext(Dispatchers.Default) { activity.moveToState(Lifecycle.State.CREATED) }
            @Suppress("UNCHECKED_CAST")
            val writes = p.javaClass.getDeclaredField("progressWrites").apply { isAccessible = true }.get(p) as MutableSet<Job>
            writes.toList().joinAll()
            val oldSession = source.copy(id = "r7-restore-${UUID.randomUUID()}",
                cues = source.sourceCues + source.sourceCues.take(2), finite = false)
            val oldProgress = SessionProgress(oldSession.id, lesson.id, oldSession.cues.lastIndex, 200,
                p.state.value.playedMs, mediaItemIndex = oldSession.cues.lastIndex * 2 + 1)
            File(app.filesDir, "last-session.json").writeText(Json.encodeToString(oldSession))
            app.db.dao().putProgress(de.voci.data.ProgressEntity(oldSession.id, Json.encodeToString(oldProgress)))
            PlaybackService.localPlayer!!.clearMediaItems()
            p.restore()
            assertEquals(oldSession.id, p.state.value.session!!.id)
            assertEquals(source.sourceCues, p.state.value.session!!.cues)
            assertEquals(3, PlaybackService.localPlayer!!.currentMediaItemIndex)
            assertEquals(200L, PlaybackService.localPlayer!!.currentPosition)
            assertSilent(app)
            withContext(Dispatchers.Default) { activity.moveToState(Lifecycle.State.RESUMED) }
            assertSilent(app)
            assertArrayEquals(bytes, file.readBytes())
        } finally { app.tts.voiceInventory = old }
    }

    @Test fun leavingDuringSpeedAdjustmentCannotApplyLateQueueOrPlayIntent() = fixture { app, activity, lesson ->
        val p = app.playback
        val initial = start(app, lesson, lesson.items.first().id)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = p.checkpoint
        var job: Job? = null
        try {
            p.checkpoint = { stage, id ->
                old(stage, id)
                if (stage == "replan_items" && id == lesson.id) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            job = p.speed(1.25f)
            withTimeout(10_000) { entered.await() }
            withContext(Dispatchers.Default) { activity.moveToState(Lifecycle.State.CREATED) }
            assertSilent(app)
            release.complete(Unit); withTimeout(10_000) { requireNotNull(job).join() }
            assertEquals(initial, p.state.value.session)
            withContext(Dispatchers.Default) { activity.moveToState(Lifecycle.State.RESUMED) }
            assertSilent(app)
            p.pausePlay()
            await { PlaybackService.localPlayer!!.isPlaying }
            assertEquals(initial.cues, p.state.value.session!!.cues)
        } finally { release.complete(Unit); p.checkpoint = old; job?.cancel(); withContext(NonCancellable) { job?.join() } }
    }

    @Test fun leavingDuringSelectionRetainsPositionAndExplicitPlayResumesWithoutSynthesis() = fixture { app, activity, lesson ->
        val p = app.playback
        val initial = start(app, lesson, lesson.items.first().id)
        val player = PlaybackService.localPlayer!!
        await { player.isPlaying && player.currentPosition > 200 }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = app.tts.checkpoint
        var selection: Job? = null
        try {
            app.tts.checkpoint = { stage, cue ->
                old(stage, cue)
                if (stage == "synthesized" && cue.itemId == lesson.items[1].id) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            selection = p.selectItem(initial.id, lesson.items[1].id)
            withTimeout(60_000) { entered.await() }
            val index = player.currentMediaItemIndex; val position = player.currentPosition
            withContext(Dispatchers.Default) { activity.moveToState(Lifecycle.State.CREATED) }
            assertSilent(app)
            release.complete(Unit); withTimeout(15_000) { requireNotNull(selection).join() }
            assertEquals(initial.id, p.state.value.session!!.id)
            assertEquals(initial.singleItemId, p.state.value.session!!.singleItemId)
            withContext(Dispatchers.Default) { activity.moveToState(Lifecycle.State.RESUMED) }
            assertSilent(app)
            player.play() // Stale/direct transport cannot re-arm a returned app.
            assertSilent(app)
            assertEquals(index, player.currentMediaItemIndex)
            assertEquals(position, player.currentPosition)
            await {
                app.db.dao().latestProgress()?.let { Json.decodeFromString<SessionProgress>(it.json) }
                    ?.let { it.sessionId == initial.id && it.mediaItemIndex == index && it.positionMs == position } == true
            }
            app.tts.checkpoint = { _, _ -> throw AssertionError("R7: explicit resume must reuse queued assets") }
            p.pausePlay()
            await { player.isPlaying && player.playWhenReady }
            assertEquals(initial.id, p.state.value.session!!.id)
            assertEquals(initial.cues, p.state.value.session!!.cues)
        } finally {
            release.complete(Unit); app.tts.checkpoint = old
            selection?.cancel(); withContext(NonCancellable) { selection?.join() }
        }
    }

    @Test fun leavingDuringGenerationRejectsLateProviderResult() = fixture { app, activity, lesson ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val generatedId = CompletableDeferred<String>()
        val store = ViewModelStore()
        val provider = object : LessonProvider {
            override suspend fun generate(spec: SessionSpec): LessonContent {
                assertEquals(app.preferences.flow.first().entryCount, spec.entryCount)
                val count = requireNotNull(spec.entryCount)
                assertTrue(LessonPrompt.text(spec).contains("EXACTLY $count distinct"))
                val items = (1..count).map { index -> lesson.items.first().copy(id = "v$index", target = "Phrase $index") }
                val response = lesson.copy(language = spec.language, items = items,
                    dialogue = listOf(DialogueTurn("d1", 0, "Hello", "Hallo"), DialogueTurn("d2", 1, "Good evening", "Guten Abend")),
                    questions = listOf(RecognitionQuestion("q1", "v1", "One?", listOf("Eins", "Zwei"), 0),
                        RecognitionQuestion("q2", "v2", "Two?", listOf("Zwei", "Drei"), 0)))
                val parsed = LessonPrompt.parse(Json.encodeToString(response), spec)
                generatedId.complete(parsed.id)
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                return parsed
            }
        }
        // This fixture never supplies auth credentials or performs HTTP; provider is synthetic.
        // Require an already ChatGPT-selected preference; do not silently migrate a legacy provider.
        assertEquals("FIXTURE: generation provider must be selected", "chatgpt", app.preferences.flow.first().provider)
        val vm = VociViewModel(app, SavedStateHandle(), provider).also { store.put("r7-generation", it) }
        try {
            vm.topic(lesson.title); vm.start()
            withTimeout(10_000) { entered.await() }
            withContext(Dispatchers.Default) { activity.moveToState(Lifecycle.State.CREATED) }
            release.complete(Unit); delay(500)
            assertFalse(vm.busy.value); assertFalse(vm.generationBusy.value)
            assertNull(app.db.dao().lesson(generatedId.await()))
            assertSilent(app)
            withContext(Dispatchers.Default) { activity.moveToState(Lifecycle.State.RESUMED) }
            delay(500); assertSilent(app)
        } finally {
            release.complete(Unit); vm.cancel(); store.clear()
            if(generatedId.isCompleted)withContext(NonCancellable) { app.db.dao().deleteLesson(generatedId.await()) }
        }
    }

    private suspend fun start(app: VociApp, lesson: LessonContent, item: String? = null): PreparedSession {
        val id = app.playback.start(lesson, SessionSpec(lesson.title, lesson.language, entryCount = 5), 1f, singleItemId = item)
        await(180_000) {
            assertNull("FIXTURE: real system TTS succeeds", app.playback.state.value.error)
            app.playback.state.value.session?.id == id && app.playback.preparing.value == null
        }
        return requireNotNull(app.playback.state.value.session)
    }

    private fun assertSilent(app: VociApp) {
        PlaybackService.localPlayer!!.let { assertFalse(it.isPlaying); assertFalse(it.playWhenReady) }
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.none {
            it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        })
    }

    private suspend fun await(timeout: Long = 15_000, condition: suspend () -> Boolean) {
        withTimeout(timeout) { while (!condition()) delay(25) }
    }

    private fun fixture(body: suspend (VociApp, ActivityScenario<MainActivity>, LessonContent) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try { withContext(Dispatchers.Main.immediate) {
            val p = app.playback
            await { PlaybackService.localPlayer != null }
            p.restore()
            assertNull("FIXTURE: Parent must isolate unfinished user session", p.state.value.session)
            assertEquals(0, PlaybackService.localPlayer!!.mediaItemCount)
            val before = p.state.value; val answers = p.answers.value; val marks = p.uncertain.value
            val backups = listOf("last-session.json", "last-session.json.bak", "last-session.json.new")
                .associate { name -> File(app.filesDir, name).let { it to if (it.exists()) it.readBytes() else null } }
            val audioBefore = app.db.dao().audioFiles().map { it.key }.toSet()
            val silenceBefore = app.filesDir.list().orEmpty().filter { it.startsWith("silence-") }.toSet()
            val token = UUID.randomUUID().toString()
            val lesson = LessonContent("r7-depth-$token", "R7 depth", "en-GB", (1..5).map {
                VocabItem("r7-$token-$it", "Please bring the menu to our table beside the window so we can choose our evening meal together, number $it ${token.take(8)}.",
                    "Bitte bringen Sie die Speisekarte zu unserem Tisch am Fenster, Nummer $it ${token.take(8)}.",
                    "We would like to enjoy a quiet evening with our friends after a long journey, number $it ${token.take(8)}.",
                    "Wir möchten nach einer langen Reise einen ruhigen Abend verbringen, Nummer $it ${token.take(8)}.")
            })
            val ttsHook = app.tts.checkpoint; val hook = p.checkpoint
            try {
                for (locale in listOf("en-GB", "de-DE")) assertTrue("FIXTURE: offline voice $locale", app.tts.voices(locale).isNotEmpty())
                app.repository.save(lesson)
                body(app, scenario, lesson)
            } finally { withContext(NonCancellable) {
                app.tts.checkpoint = ttsHook; p.checkpoint = hook
                p.cancelPreparation()?.join()
                PlaybackService.localPlayer!!.let { it.pause(); it.clearMediaItems() }
                p.state.value = before; p.answers.value = answers; p.uncertain.value = marks
                // Existing read-only cleanup seam, same as the immutable R7 tracer.
                @Suppress("UNCHECKED_CAST")
                val writes = p.javaClass.getDeclaredField("progressWrites").apply { isAccessible = true }.get(p) as MutableSet<Job>
                writes.toList().joinAll()
                for (entry in app.db.dao().progressEntries()) if (Json.decodeFromString<SessionProgress>(entry.json).lessonId == lesson.id)
                    app.db.dao().removeProgress(entry.sessionId)
                app.db.dao().deleteLesson(lesson.id)
                for (suffix in listOf("", ".bak", ".new")) File(app.filesDir, "plans/${lesson.id}.json$suffix").delete()
                for (asset in app.db.dao().audioFiles()) if (asset.key !in audioBefore) {
                    File(asset.path).delete(); app.db.dao().removeAudio(asset.key)
                }
                for (file in app.filesDir.listFiles().orEmpty()) if (file.name.startsWith("silence-") && file.name !in silenceBefore) file.delete()
                @Suppress("UNCHECKED_CAST")
                val raw = PreferencesStore::class.java.getDeclaredField("store").apply { isAccessible = true }.get(app.preferences)
                    as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
                raw.updateData { it.toMutablePreferences().apply {
                    remove(androidx.datastore.preferences.core.stringSetPreferencesKey("uncertain.lesson.v1:${lesson.id}"))
                }.toPreferences() }
                for ((file, bytes) in backups) if (bytes == null) file.delete() else file.writeBytes(bytes)
            } }
        } } finally { scenario.close() }
    }
}
