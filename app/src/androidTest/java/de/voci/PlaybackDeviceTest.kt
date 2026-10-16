package de.voci
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
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
class PlaybackDeviceTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun synthesisPlaybackPauseRotationFocusAndSave()=runBlocking<Unit> {
  val app=compose.activity.application as VociApp;val p=app.playback;val l=Examples.lessons.first();val start=System.nanoTime()
  withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title,l.language,1),1f) }
  withTimeout(60000) { while(!p.state.value.playing) { assertNull(p.state.value.error);delay(100) } }
  val firstMs=(System.nanoTime()-start)/1000000;val session=p.state.value.session!!.id
  delay(1000);assertTrue(p.state.value.playedMs>0)
  withContext(Dispatchers.Main) { p.pausePlay() };delay(300);val position=p.state.value.positionMs;delay(600);assertEquals(position,p.state.value.positionMs)
  compose.activityRule.scenario.recreate();delay(700);assertEquals(session,p.state.value.session?.id);assertFalse(p.state.value.playing)
  withContext(Dispatchers.Main) { p.pausePlay() };delay(400);assertTrue(p.state.value.playing)
  compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);delay(700);assertTrue(p.state.value.playing);assertEquals(session,p.state.value.session?.id)
  compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);delay(300)
  val audio=app.getSystemService(AudioManager::class.java)
  val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setOnAudioFocusChangeListener({}).build()
  assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(request));delay(500);assertFalse(p.state.value.playing);audio.abandonAudioFocusRequest(request)
  delay(500);withContext(Dispatchers.Main) { if(p.state.value.playing)p.pausePlay();p.repeat();p.skip(1);p.skip(-1);p.speed(1.15f) }
  withTimeout(60000) { while(p.preparing.value!=null) { assertNull(p.state.value.error);delay(100) } }
  p.pin();assertTrue(p.offline(p.state.value.session!!))
  val copy=l.copy(id="delete-regression")
  app.repository.save(copy,saved=true)
  p.deleteLesson(copy.id)
  assertNull(app.repository.content(copy.id));assertTrue(p.offline(p.state.value.session!!))
  File(app.filesDir,"playback-evidence.txt").writeText("session=$session\nfirst_player_isPlaying_ms=$firstMs\nrotation_session_preserved=true\nbackground_playing=true\npause_position_stable=true\naudio_focus_pause=true\nmeasured_plan_ms=${p.state.value.session!!.cues.sumOf { it.durationMs+it.pauseMs }}\n")
 }
}
