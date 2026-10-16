package de.voci
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.data.Examples
import de.voci.domain.SessionSpec
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Real elapsed Media3 playback, not an accelerated test clock. Approximately nine minutes total. */
class ActiveBudgetPlaybackTest {
 @Test fun fullOneThreeFiveMinutePlaybackRespectsActiveBudgetWithRepeatSpeedAndSkip()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp;val p=app.playback
  val report=StringBuilder("minutes\trate\tplayed_ms\twall_ms\tuser_pause_ms\n")
  for((minutes,rate) in listOf(1 to .85f,3 to 1f,5 to 1.15f)) {
   val l=Examples.lessons.first().copy(id="r1-active-$minutes");app.repository.save(l)
   withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title,l.language,minutes),1f) }
   withTimeout(90000) { while(!p.state.value.playing) { assertNull(p.state.value.error);delay(50) } }
   val begin=System.nanoTime();var controls=false;var pauseMs=0L
   withTimeout((minutes*60000L*1.15).toLong()+30000) {
    while(!p.state.value.ended) {
     assertNull(p.state.value.error)
     if(!controls && p.state.value.playedMs>=6000 && p.preparing.value==null) {
      controls=true
      withContext(Dispatchers.Main) { p.pausePlay() };delay(300)
      val paused=p.state.value.playedMs;val pauseStart=System.nanoTime();delay(1700);assertEquals(paused,p.state.value.playedMs)
      pauseMs=(System.nanoTime()-pauseStart)/1000000+300
      withContext(Dispatchers.Main) {
       val player=requireNotNull(de.voci.audio.PlaybackService.localPlayer)
       val before=player.currentMediaItemIndex;val heard=p.state.value.playedMs
       p.skip(1).join();assertEquals((before/2+1)*2,player.currentMediaItemIndex)
       assertTrue("Seek is not heard time",p.state.value.playedMs-heard<250)
       p.speed(rate).join();p.repeat().join()
      };delay(300)
      withContext(Dispatchers.Main) { p.pausePlay() }
     }
     delay(100)
    }
   }
   val actual=p.state.value;val wall=(System.nanoTime()-begin)/1000000
   assertTrue(controls);assertEquals(rate,actual.session!!.speed)
   assertTrue("Active time ${actual.playedMs} for $minutes",actual.playedMs in minutes*54000L..minutes*66000L)
   assertTrue("Unexpected playback stalls: $wall",wall-pauseMs<=minutes*66000L+2000)
   assertEquals(0,actual.remainingMs)
   report.append("$minutes\t$rate\t${actual.playedMs}\t$wall\t$pauseMs\n")
   File(app.filesDir,"r1-active-budget.tsv").writeText(report.toString())
   p.deleteLesson(l.id)
  }
 }
}
