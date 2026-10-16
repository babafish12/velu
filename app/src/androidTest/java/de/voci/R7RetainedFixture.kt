package de.voci

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.PlaybackService
import de.voci.data.PreferencesStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import java.io.File

/** Parent isolates each case. No auth reads/writes; real lifecycle, system TTS and Media3 only.
 * Snapshot only lesson/audio/session/settings data; never traverse credential storage.
 */
internal fun r7RetainedFixture(body: suspend (VociApp, ActivityScenario<MainActivity>) -> Unit) = runBlocking {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    try {
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        val p = withContext(Dispatchers.Main) { app.playback }
        withContext(Dispatchers.Main) {
            withTimeout(15_000) { while (PlaybackService.localPlayer == null) delay(25) }
            p.restore()
            assertTrue(app.visible)
            assertNull("Parent must checkpoint/isolate any existing session", p.state.value.session)
            assertNull(p.preparing.value)
            assertEquals(0, PlaybackService.localPlayer!!.mediaItemCount)
        }
        val state = p.state.value; val answers = p.answers.value; val marks = p.uncertain.value
        val hook = p.checkpoint; val ttsHook = app.tts.checkpoint; val voices = app.tts.voiceInventory
        val parameters = withContext(Dispatchers.Main) { PlaybackService.localPlayer!!.playbackParameters }
        val dao = app.db.dao()
        val lessons = dao.lessons().first(); val audio = dao.audioFiles(); val progress = dao.progressEntries()
        @Suppress("UNCHECKED_CAST")
        val settings = PreferencesStore::class.java.getDeclaredField("store").apply { isAccessible = true }
            .get(app.preferences) as DataStore<Preferences>
        val preferences = settings.data.first()
        fun files(): List<File> = listOf("audio", "plans").flatMap { name ->
            File(app.filesDir, name).walkTopDown().filter { it.isFile }.toList()
        } + app.filesDir.listFiles().orEmpty().filter {
            it.isFile && (it.name.startsWith("last-session.json") || it.name.startsWith("silence-"))
        }
        val backup = files().associate { it to it.readBytes() }
        var failure: Throwable? = null
        try { body(app, scenario) }
        catch (t: Throwable) { failure = t; throw t }
        finally {
            val problems = mutableListOf<Throwable>()
            suspend fun cleanup(block: suspend () -> Unit) {
                try { withContext(NonCancellable) { block() } } catch (t: Throwable) { problems += t }
            }
            cleanup {
                withContext(Dispatchers.Main) {
                    p.checkpoint = hook; app.tts.checkpoint = ttsHook; app.tts.voiceInventory = voices
                    p.cancelPreparation()?.join()
                    PlaybackService.localPlayer!!.let { it.pause(); it.clearMediaItems() }
                }
                // Destroy the real Activity/VM before restoring durable data.
                scenario.close()
                withContext(Dispatchers.Main) {
                    @Suppress("UNCHECKED_CAST")
                    val writes = p.javaClass.getDeclaredField("progressWrites").apply { isAccessible = true }.get(p) as MutableSet<Job>
                    writes.toList().joinAll()
                    p.state.value = state; p.answers.value = answers; p.uncertain.value = marks
                    PlaybackService.localPlayer?.playbackParameters = parameters
                }
            }
            cleanup {
                dao.lessons().first().filter { row -> lessons.none { it.id == row.id } }.forEach { dao.deleteLesson(it.id) }
                lessons.forEach { dao.putLesson(it) }
                dao.progressEntries().filter { row -> progress.none { it.sessionId == row.sessionId } }.forEach { dao.removeProgress(it.sessionId) }
                progress.forEach { dao.putProgress(it) }
                dao.audioFiles().filter { row -> audio.none { it.key == row.key } }.forEach { dao.removeAudio(it.key) }
                audio.forEach { dao.putAudio(it) }
                files().filter { it !in backup }.forEach { it.delete() }
                backup.forEach { (file, bytes) -> file.parentFile!!.mkdirs(); file.writeBytes(bytes) }
            }
            cleanup { settings.updateData { preferences }; assertEquals(preferences, settings.data.first()) }
            if (failure != null) problems.forEach { failure.addSuppressed(it) }
            else if (problems.isNotEmpty()) { problems.drop(1).forEach { problems.first().addSuppressed(it) }; throw problems.first() }
        }
    } finally { scenario.close() }
}
