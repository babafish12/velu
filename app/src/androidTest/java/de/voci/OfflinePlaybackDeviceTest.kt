package de.voci
import android.provider.Settings
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.voci.data.Examples
import de.voci.domain.SessionSpec
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflinePlaybackDeviceTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun completeSavedLessonInFlightMode()=runBlocking<Unit> {
  val app=compose.activity.application as VociApp;assertEquals(1,Settings.Global.getInt(app.contentResolver,Settings.Global.AIRPLANE_MODE_ON))
  val p=app.playback;val l=Examples.lessons.first();assertTrue(p.offlineLesson(l.id));val start=System.nanoTime()
  withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title,l.language,1),1f) }
  withTimeout(15000) { while(!p.state.value.playing)delay(100) };val first=(System.nanoTime()-start)/1000000
  withTimeout(90000) { while(!p.state.value.ended) { assertNull(p.state.value.error);delay(250) } }
  assertTrue(p.state.value.playedMs>=p.state.value.session!!.cues.sumOf { it.durationMs+it.pauseMs }*.98)
  File(app.filesDir,"offline-evidence.txt").writeText("airplane_mode=1\nfirst_player_isPlaying_ms=$first\ncomplete=true\nplayed_ms=${p.state.value.playedMs}\nwall_ms=${(System.nanoTime()-start)/1000000}\n")
 }
}
