package de.voci

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.media.session.MediaController as PlatformMediaController
import android.media.session.MediaSession
import android.os.Bundle
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.UUID

/** Parent builds/runs, serially, on an unlocked local device with offline en-GB/de-DE voices.
 * Like R5/R6 audio fixtures, requires an idle coordinator, no restored session or queued work.
 * Parent must preserve/isolate an unfinished user session before running; this test never clears it.
 * Uses the actual MainActivity, Application coordinator, system TTS and service-owned player.
 * No fake state drives assertions. Reflection only READS the two established R5/R6 cleanup seams:
 * PreferencesStore.store (remove our marks key) and progressWrites (join outstanding DB writes).
 * There is no public progress-write drain; no private field is replaced or rewritten.
 */
@RunWith(AndroidJUnit4::class)
class R7ForegroundPlaybackTest {
    @Test fun leavingActivityStopsPlayerAndRemovesMediaNotification() = fixture { app, scenario, trace, session ->
        val notifications = app.getSystemService(NotificationManager::class.java)
        // App-owned, supported service connection established while RESUMED, retained across
        // actual onStop. This is a stale connected-controller probe, NOT an OS notification click.
        // Media3 1.11.1 SessionToken.getPlatformToken is package-private; do not reflect into it.
        val future = onMain {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        try {
            await("FIXTURE: app-owned Media3 controller connects", 10_000) { future.isDone }
            val staleController = onMain { future.get() }
            onMain {
                assertTrue("FIXTURE: Media3 controller connected", staleController.isConnected)
                assertEquals("FIXTURE: connected token belongs to app", app.packageName,
                    staleController.connectedToken?.packageName)
                staleController.pause()
            }
            await("FIXTURE: connected controller pauses actual service-owned player") {
                onMain { PlaybackService.localPlayer!!.let { !it.isPlaying && !it.playWhenReady } }
            }
            onMain { staleController.play() }
            await("FIXTURE: connected controller plays actual service-owned player") {
                onMain { PlaybackService.localPlayer!!.isPlaying }
            }
            val beforeNotifications = mediaNotifications(app, notifications)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putInt("r7_pre_stop_media_notification_count", beforeNotifications.size)
            })
            // Optional additional path: retain only tokens actually advertised by this app.
            // Absence is allowed; the post-stop no-notification invariant below is unconditional.
            val staleNotificationControllers = beforeNotifications.map { notification ->
                @Suppress("DEPRECATION")
                val token = notification.notification.extras
                    .getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
                    ?: throw AssertionError("FIXTURE: published media notification has a real token")
                PlatformMediaController(app, token).also {
                    assertEquals("FIXTURE: notification token belongs to app", app.packageName, it.packageName)
                }
            }
            assertAdvancing("FIXTURE: playback still advances immediately before leaving")
            onMain {
                assertTrue("FIXTURE: finite lesson cannot naturally finish during stop deadline",
                    app.playback.state.value.remainingMs > 30_000)
                assertEquals("FIXTURE: same prepared session", session.id, app.playback.state.value.session?.id)
            }
            var original: MainActivity? = null
            scenario.onActivity { original = it }
            scenario.moveToState(Lifecycle.State.CREATED)
            val stop = onMain { trace.stops.lastOrNull { it.activity === original } }
                ?: throw AssertionError("FIXTURE: actual Activity.onStop was observed")
            assertFalse("FIXTURE: exit is not configuration recreation", stop.changingConfiguration)
            assertEquals("FIXTURE: Activity is stopped", Lifecycle.State.CREATED, scenario.state)

            // FIRST product assertion: unchanged MainActivity must fail here, on actual Media3 state.
            // The deadline starts at the observed onStop, not after a long ActivityScenario transition.
            await("R7 FOREGROUND PRODUCT: onStop must silence the real player and clear play intent",
                (stop.atMs + 5_000 - SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
                onMain { PlaybackService.localPlayer?.let { !it.isPlaying && !it.playWhenReady } ?: true }
            }
            await("R7 FOREGROUND PRODUCT: no app media notification remains", 5_000) {
                mediaNotifications(app, notifications).isEmpty()
            }
            // The SAME connected endpoint that proved pause/play before onStop; no new session.
            onMain { staleController.play() }
            assertSilent(app, notifications, "stale connected-controller Play cannot resurrect playback")
            for (controller in staleNotificationControllers) {
                controller.transportControls.play()
                assertSilent(app, notifications, "stale advertised-token Play cannot resurrect playback")
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals("FIXTURE: returned Activity is resumed", Lifecycle.State.RESUMED, scenario.state)
            assertSilent(app, notifications, "returning to RESUMED stays silent without explicit user Play")
        } finally {
            // Handles connected AND pending/failed futures without leaking a late connection.
            withContext(NonCancellable) { onMain { MediaController.releaseFuture(future) } }
        }
    }

    @Test fun configurationRecreationKeepsForegroundSession() = fixture { app, scenario, trace, session ->
        val player = onMain { requireNotNull(PlaybackService.localPlayer) }
        val queue = onMain { (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } }
        var original: MainActivity? = null
        scenario.onActivity { original = it }
        assertAdvancing("FIXTURE: genuine ready/playing audio before recreation")
        val position = onMain { player.currentMediaItemIndex to player.currentPosition }
        scenario.recreate()
        var replacement: MainActivity? = null
        scenario.onActivity { replacement = it }
        assertNotSame("FIXTURE: ActivityScenario created a different Activity", original, replacement)
        assertTrue("FIXTURE: real onStop was a configuration change", onMain {
            trace.stops.any { it.activity === original && it.changingConfiguration }
        })
        await("R7 FOREGROUND PRODUCT: configuration recreation retains ready/playing intent", 15_000) {
            onMain {
                PlaybackService.localPlayer?.let {
                    it.playbackState == Player.STATE_READY && it.isPlaying && it.playWhenReady
                } == true
            }
        }
        onMain {
            assertSame("R7 FOREGROUND PRODUCT: same service-owned player", player, PlaybackService.localPlayer)
            assertEquals("R7 FOREGROUND PRODUCT: same foreground session", session.id, app.playback.state.value.session?.id)
            assertEquals("R7 FOREGROUND PRODUCT: same finite queue", queue,
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertTrue("R7 FOREGROUND PRODUCT: recreation does not restart the queue",
                player.currentMediaItemIndex > position.first ||
                    (player.currentMediaItemIndex == position.first && player.currentPosition >= position.second))
        }
        assertAdvancing("R7 FOREGROUND PRODUCT: recreated foreground session really advances")
    }

    private fun mediaNotifications(app: VociApp, manager: NotificationManager) =
        manager.activeNotifications.filter {
            it.packageName == app.packageName && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        }

    private suspend fun assertSilent(app: VociApp, manager: NotificationManager, reason: String) {
        // Observe a window, not just the instant before the asynchronous transport command arrives.
        val until = SystemClock.elapsedRealtime() + 3_000
        do {
            onMain {
                PlaybackService.localPlayer?.let {
                    assertFalse("R7 FOREGROUND PRODUCT: $reason (isPlaying)", it.isPlaying)
                    assertFalse("R7 FOREGROUND PRODUCT: $reason (playWhenReady)", it.playWhenReady)
                }
            }
            assertTrue("R7 FOREGROUND PRODUCT: $reason (media notification)", mediaNotifications(app, manager).isEmpty())
            delay(50)
        } while (SystemClock.elapsedRealtime() < until)
    }

    private suspend fun assertAdvancing(label: String) {
        val before = onMain {
            val player = requireNotNull(PlaybackService.localPlayer)
            assertEquals("$label: ready", Player.STATE_READY, player.playbackState)
            assertTrue("$label: isPlaying", player.isPlaying)
            assertTrue("$label: play intent", player.playWhenReady)
            player.currentMediaItemIndex to player.currentPosition
        }
        await(label, 5_000) {
            onMain {
                val player = requireNotNull(PlaybackService.localPlayer)
                player.isPlaying && (player.currentMediaItemIndex > before.first ||
                    (player.currentMediaItemIndex == before.first && player.currentPosition >= before.second + 200))
            }
        }
    }

    private suspend fun await(label: String, timeoutMs: Long = 15_000, condition: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError("$label (deadline ${timeoutMs}ms)")
            delay(50)
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private fun fixture(body: suspend (VociApp, ActivityScenario<MainActivity>, Stops, PreparedSession) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        val p = onMain { app.playback }
        await("FIXTURE: real local service connects") { onMain { PlaybackService.localPlayer != null } }
        onMain {
            withTimeout(15_000) { p.restore() }
            assertNull("FIXTURE: Parent must isolate unfinished user session", p.state.value.session)
            assertNull("FIXTURE: no preparation already running", p.preparing.value)
            assertEquals("FIXTURE: idle real player queue", 0, PlaybackService.localPlayer!!.mediaItemCount)
            assertFalse("FIXTURE: no pre-existing play intent", PlaybackService.localPlayer!!.playWhenReady)
        }
        val beforeState = p.state.value
        val beforeAnswers = p.answers.value
        val beforeUncertain = p.uncertain.value
        val beforeParameters = onMain { PlaybackService.localPlayer!!.playbackParameters }
        val lastFiles = listOf("last-session.json", "last-session.json.bak", "last-session.json.new")
            .associate { name -> File(app.filesDir, name).let { it to if (it.exists()) it.readBytes() else null } }
        val oldAudio = File(app.filesDir, "audio").list().orEmpty().toSet()
        val oldSilences = app.filesDir.list().orEmpty().filter { it.startsWith("silence-") }.toSet()
        val ownedKeys = Collections.synchronizedSet(mutableSetOf<String>())
        val ttsHook = app.tts.checkpoint
        @Suppress("UNCHECKED_CAST")
        val settings = PreferencesStore::class.java.getDeclaredField("store").apply { isAccessible = true }
            .get(app.preferences) as DataStore<Preferences>
        val settingsBefore = settings.data.first()
        val id = "r7-foreground-${UUID.randomUUID()}"
        val spoken = id.takeLast(8)
        val lesson = LessonContent(id, "R7 foreground $spoken", "en-GB", listOf(VocabItem(
            "$id-item",
            "Please listen carefully while we describe the quiet restaurant beside the station. " +
                "We would like a table near the window where we can talk about our journey and read the menu together. " +
                "After choosing our meal we will ask for some water and fresh bread, and enjoy a relaxed conversation. " +
                "Before leaving we would like to thank the friendly staff, check the bill carefully and arrange another visit with our friends $spoken.",
            "Wir möchten am Fenster sitzen und die Speisekarte lesen $spoken.",
            "We will enjoy a quiet meal together $spoken.", "Wir essen gemeinsam in Ruhe $spoken."
        )))
        val markKey = stringSetPreferencesKey("uncertain.lesson.v1:$id")
        assertNull("FIXTURE: UUID has no existing lesson", app.db.dao().lesson(id))
        assertNull("FIXTURE: UUID has no existing preferences", settingsBefore[markKey])
        val trace = Stops()
        var scenario: ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        try {
            app.registerActivityLifecycleCallbacks(trace)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            assertEquals("FIXTURE: real MainActivity resumed", Lifecycle.State.RESUMED, scenario.state)
            app.tts.checkpoint = { stage, cue ->
                ttsHook(stage, cue)
                if (stage == "renamed" && cue.itemId == lesson.items.single().id) {
                    File(app.filesDir, "audio").listFiles().orEmpty()
                        .filter { it.name !in oldAudio && it.extension == "wav" && !it.name.endsWith(".part.wav") }
                        .forEach { ownedKeys.add(it.nameWithoutExtension) }
                }
            }
            for (locale in listOf("en-GB", "de-DE")) {
                assertTrue("FIXTURE: installed offline voice $locale",
                    withTimeout(25_000) { app.tts.voices(locale) }.isNotEmpty())
            }
            app.repository.save(lesson)
            val sessionId = onMain {
                p.start(lesson, SessionSpec(lesson.title, lesson.language, 1), 1f, singleItemId = lesson.items.single().id)
            }
            await("FIXTURE: four real finite Single cues finish preparation", 180_000) {
                assertNull("FIXTURE: system TTS/preparation must succeed", p.state.value.error)
                p.preparing.value == null && p.state.value.session?.id == sessionId
            }
            val session = requireNotNull(p.state.value.session)
            assertEquals("FIXTURE: finite Single item", lesson.items.single().id, session.singleItemId)
            assertEquals("FIXTURE: real cue phases", listOf("target", "meaning", "repeat", "example"), session.cues.map { it.phase })
            session.cues.forEachIndexed { index, cue ->
                val asset = app.db.dao().audio(cue.key) ?: throw AssertionError("FIXTURE: missing real audio metadata")
                assertTrue("FIXTURE: positive measured duration", cue.durationMs > 0 && cue.durationMs == asset.durationMs)
                assertTrue("FIXTURE: validated system TTS file", AudioFiles.valid(File(asset.path), asset.bytes, asset.sha256))
                onMain {
                    val item = PlaybackService.localPlayer!!.getMediaItemAt(index * 2)
                    assertEquals("FIXTURE: actual player uses cue", cue.key, item.mediaId)
                    assertEquals("FIXTURE: actual player uses validated file", asset.path, item.localConfiguration?.uri?.toString())
                }
            }
            await("FIXTURE: real Media3 READY and playing") {
                onMain { PlaybackService.localPlayer!!.let {
                    it.playbackState == Player.STATE_READY && it.isPlaying &&
                        it.currentMediaItem?.mediaId in session.cues.map { cue -> cue.key }
                } }
            }
            onMain {
                assertEquals("FIXTURE: four finite audio/pause pairs", 8, PlaybackService.localPlayer!!.mediaItemCount)
                assertEquals("FIXTURE: no looping to mask natural completion", Player.REPEAT_MODE_OFF, PlaybackService.localPlayer!!.repeatMode)
            }
            assertAdvancing("FIXTURE: actual player position advances")
            body(app, scenario, trace, session)
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            withContext(NonCancellable) {
                val problems = mutableListOf<Throwable>()
                suspend fun cleanup(block: suspend () -> Unit) {
                    try { block() } catch (t: Throwable) { problems.add(t) }
                }
                cleanup { scenario?.close() }
                app.unregisterActivityLifecycleCallbacks(trace)
                cleanup {
                    onMain {
                        withTimeout(20_000) { p.cancelPreparation()?.join() }
                    }
                }
                // Stop sampling test progress BEFORE draining writes, even if activity close failed.
                cleanup {
                    onMain {
                        p.state.value = beforeState
                        PlaybackService.localPlayer?.let { it.pause(); it.stop(); it.clearMediaItems() }
                        @Suppress("UNCHECKED_CAST")
                        val writes = p.javaClass.getDeclaredField("progressWrites").apply { isAccessible = true }
                            .get(p) as MutableSet<Job>
                        withTimeout(20_000) { writes.toList().joinAll() }
                    }
                }
                cleanup {
                    withTimeout(20_000) {
                        app.db.dao().deleteLesson(id)
                        for (entry in app.db.dao().progressEntries()) {
                            if (runCatching { Json.decodeFromString<SessionProgress>(entry.json).lessonId == id }.getOrDefault(false))
                                app.db.dao().removeProgress(entry.sessionId)
                        }
                        for (key in ownedKeys.toList()) {
                            val asset = app.db.dao().audio(key)
                            File(asset?.path ?: File(app.filesDir, "audio/$key.wav").path).delete()
                            app.db.dao().removeAudio(key)
                        }
                        app.filesDir.listFiles().orEmpty().filter {
                            it.name.startsWith("silence-") && it.name.endsWith(".wav") && it.name !in oldSilences
                        }.forEach { it.delete() }
                    }
                }
                cleanup {
                    lastFiles.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
                }
                cleanup {
                    settings.edit { it.remove(markKey) }
                    assertEquals("FIXTURE: all original settings preserved", settingsBefore, settings.data.first())
                }
                cleanup {
                    onMain {
                        app.tts.checkpoint = ttsHook
                        p.state.value = beforeState
                        p.answers.value = beforeAnswers
                        p.uncertain.value = beforeUncertain
                        PlaybackService.localPlayer?.playbackParameters = beforeParameters
                    }
                }
                if (failure != null) problems.forEach { failure.addSuppressed(it) }
                else if (problems.isNotEmpty()) {
                    problems.drop(1).forEach { problems.first().addSuppressed(it) }
                    throw problems.first()
                }
            }
        }
    }

    private class Stops : Application.ActivityLifecycleCallbacks {
        data class Stop(val activity: Activity, val changingConfiguration: Boolean, val atMs: Long)
        // Written and inspected on the main thread; no synthetic lifecycle event injection.
        val stops = mutableListOf<Stop>()
        override fun onActivityStopped(activity: Activity) {
            if (activity is MainActivity) stops.add(Stop(activity, activity.isChangingConfigurations, SystemClock.elapsedRealtime()))
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
