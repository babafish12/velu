package de.voci
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.voci.audio.PreparedSession
import de.voci.domain.SessionProgress
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Run after PlaybackDeviceTest followed by adb am force-stop de.voci. */
@RunWith(AndroidJUnit4::class)
class ProcessRestoreTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun persistedSessionRestoresPausedAfterProcessDeath()=runBlocking<Unit> {
  val app=compose.activity.application as VociApp
  val saved=Json.decodeFromString<PreparedSession>(File(app.filesDir,"last-session.json").readText())
  val progress=Json.decodeFromString<SessionProgress>(app.db.dao().latestProgress()!!.json)
  assertFalse(progress.completed);assertTrue(progress.playedMs>0)
  withTimeout(15000) { while(app.playback.state.value.session==null)delay(100) }
  val actual=app.playback.state.value
  assertEquals(saved.id,actual.session!!.id);assertEquals(progress.playedMs,actual.playedMs);assertFalse(actual.playing)
  File(app.filesDir,"restore-evidence.txt").writeText("restored_session=${saved.id}\nplayed_ms=${actual.playedMs}\npaused=true\n")
 }
}
