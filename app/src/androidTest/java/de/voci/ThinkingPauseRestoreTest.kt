package de.voci
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.session.*
import de.voci.audio.*
import de.voci.data.Examples
import de.voci.domain.SessionSpec
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Run prepareThinkingPause, force-stop the real app process, then restoreThinkingPauseAndComplete. */
class ThinkingPauseRestoreTest {
 private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
 private suspend fun controller():MediaController { val f=withContext(Dispatchers.Main) { MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync() };return withContext(Dispatchers.IO) { f.get() } }
 @Test fun prepareThinkingPause()=runBlocking {
  val p=app.playback;val l=Examples.lessons.first().copy(id="c-r1-process-pause");app.repository.save(l)
  withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title),1f) }
  val connection=controller();val c=withContext(Dispatchers.Main) { requireNotNull(PlaybackService.localPlayer) }
  try {
   withTimeout(30000) { while(withContext(Dispatchers.Main) { c.currentMediaItemIndex%2!=1 || c.currentPosition<200 || p.preparing.value!=null }) { assertNull(p.state.value.error);delay(10) } }
   withContext(Dispatchers.Main) { c.pause() };delay(1300)
   val snapshot=withContext(Dispatchers.Main) { buildJsonObject { put("session",p.state.value.session!!.id);put("mediaIndex",c.currentMediaItemIndex);put("position",c.currentPosition);put("played",p.state.value.playedMs);put("pid",android.os.Process.myPid()) } }
   assertEquals(1,snapshot.getValue("mediaIndex").jsonPrimitive.int%2);assertTrue(snapshot.getValue("position").jsonPrimitive.long>=200)
   File(app.filesDir,"r1-pause-restore-expected.json").writeText(snapshot.toString())
  } finally { withContext(Dispatchers.Main) { connection.release() } }
 }
 @Test fun restoreThinkingPauseAndComplete()=runBlocking {
  val expected=Json.parseToJsonElement(File(app.filesDir,"r1-pause-restore-expected.json").readText()).jsonObject
  assertNotEquals(expected.getValue("pid").jsonPrimitive.int,android.os.Process.myPid())
  val p=app.playback;val connection=controller();val c=withContext(Dispatchers.Main) { requireNotNull(PlaybackService.localPlayer) }
  try {
   withTimeout(15000) { while(p.state.value.session==null)delay(50) }
   withTimeout(15000) { while(withContext(Dispatchers.Main) { c.playbackState!=androidx.media3.common.Player.STATE_READY })delay(20) }
   assertEquals(expected.getValue("session").jsonPrimitive.content,p.state.value.session!!.id)
   withContext(Dispatchers.Main) { assertFalse(c.isPlaying);assertEquals(expected.getValue("mediaIndex").jsonPrimitive.int,c.currentMediaItemIndex);assertEquals(expected.getValue("position").jsonPrimitive.long,c.currentPosition) }
   assertEquals(expected.getValue("played").jsonPrimitive.long,p.state.value.playedMs)
   val start=System.nanoTime();withContext(Dispatchers.Main) { p.pausePlay() }
   withTimeout(70000) { while(!p.state.value.ended) { assertNull(p.state.value.error);delay(100) } }
   assertTrue(p.state.value.playedMs in 54000L..66000L)
   File(app.filesDir,"r1-pause-restore-result.txt").writeText("old_pid=${expected.getValue("pid")}\nnew_pid=${android.os.Process.myPid()}\nresumed_media_index=${expected.getValue("mediaIndex")}\nresumed_position=${expected.getValue("position")}\nfinal_played_ms=${p.state.value.playedMs}\nremaining_wall_ms=${(System.nanoTime()-start)/1000000}\n")
  } finally { withContext(Dispatchers.Main) { connection.release() };p.deleteLesson("c-r1-process-pause") }
 }
}
