package de.voci
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.data.Examples
import de.voci.domain.SessionSpec
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.After
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import java.io.File

class RemainingBudgetTest {
 @Test fun skipTakesEffectOnActualPlayerBeforeReplanNearPauseBoundary()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp;val p=app.playback
  val l=Examples.lessons.first().copy(id="r1-budget-skip");app.repository.save(l)
  try {
   withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title),1f) }
   withTimeout(60000) { while(p.preparing.value!=null || p.state.value.session==null)delay(20) }
   val journal=StringBuilder("direction\tbefore_index\tafter_index\tbefore_played\tafter_played\n")
   for(delta in listOf(1,-1)) {
    val sought=CompletableDeferred<Unit>();var target=-1;var before=0L;var beforeIndex=0;var observedIndex=-1;var observedPosition=-1L
    withContext(Dispatchers.Main) {
     val actual=requireNotNull(de.voci.audio.PlaybackService.localPlayer)
     actual.pause();actual.seekTo(if(delta==1)1 else 3,900)
    }
    delay(250)
    withContext(Dispatchers.Main) {
     val actual=requireNotNull(de.voci.audio.PlaybackService.localPlayer)
     beforeIndex=actual.currentMediaItemIndex;before=p.state.value.playedMs;target=(beforeIndex/2+delta)*2
     p.checkpoint={stage,id->if(stage=="skip_seek" && id==l.id) {
      // No suspension here: inspect the same service-thread seek before item loading can yield.
      observedIndex=actual.currentMediaItemIndex;observedPosition=actual.currentPosition;sought.complete(Unit)
     } }
     p.skip(delta).join()
     assertTrue("Production skip checkpoint not reached",sought.isCompleted)
     assertEquals("Seek must reach actual service Player before replan",target,observedIndex)
     assertEquals(0L,observedPosition)
     assertEquals(target,actual.currentMediaItemIndex)
     assertTrue("Skipped media must not count as heard",p.state.value.playedMs-before<250)
     assertTrue(p.state.value.remainingMs+p.state.value.playedMs<=60500)
     val session=p.state.value.session!!
     assertEquals(session.cues.flatMap { listOf(it.key,"pause") },(0 until actual.mediaItemCount).map { actual.getMediaItemAt(it).mediaId })
     journal.append("$delta\t$beforeIndex\t${actual.currentMediaItemIndex}\t$before\t${p.state.value.playedMs}\n")
    }
   }
   File(app.filesDir,"r1-skip-boundary.tsv").writeText(journal.toString())
  } finally { p.checkpoint={_,_->} }
 }
 @After fun cleanup()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
  for(l in app.db.dao().lessons().first().filter { it.id.startsWith("r1-budget-") })app.playback.deleteLesson(l.id)
 }
 @Test fun speedDuringPrebufferCannotAppendStaleControllerPlaylist()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp;val p=app.playback
  val l=Examples.lessons.first().copy(id="r1-budget-prebuffer");app.repository.save(l)
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  try {
   p.checkpoint={stage,id->if(stage=="persist" && id==l.id) { entered.complete(Unit);release.await() } }
   withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title),1f) };withTimeout(30000) { entered.await() }
   withTimeout(5000) { while(!p.state.value.playing)delay(20) }
   withContext(Dispatchers.Main) { p.speed(.85f).join() };release.complete(Unit)
   withTimeout(30000) { while(p.preparing.value!=null) { assertNull(p.state.value.error);delay(20) } }
   delay(200);assertNull(p.state.value.error)
   withContext(Dispatchers.Main) {
    val actual=requireNotNull(de.voci.audio.PlaybackService.localPlayer);val session=p.state.value.session!!
    assertEquals(.85f,actual.playbackParameters.speed)
    val expected=session.cues.flatMap { listOf(it.key,"pause") }
    val observed=(0 until actual.mediaItemCount).map { actual.getMediaItemAt(it).mediaId }
    assertEquals("Actual playlist must exactly match the newly published plan",expected,observed)
   }
  } finally { release.complete(Unit);p.checkpoint={_,_->} }
 }
 @Test fun latestSpeedSurvivesImmediateRepeat()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp;val p=app.playback
  val l=Examples.lessons.first().copy(id="r1-budget-rapid");app.repository.save(l)
  withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title),1f) }
  withTimeout(60000) { while(p.preparing.value!=null || p.state.value.session==null)delay(100) }
  withContext(Dispatchers.Main) { p.pausePlay() };delay(300)
  withContext(Dispatchers.Main) { p.speed(1.15f);p.repeat();p.speed(.85f);p.repeat() };delay(1500)
  assertEquals(.85f,p.state.value.session!!.speed)
  assertTrue(p.state.value.playedMs+p.state.value.remainingMs<=60500)
 }
 @Test fun measuredWholeSegmentsAdaptToRateAndRepeatWithinBudget()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp;val p=app.playback
  val evidence=StringBuilder("minutes\trate\tplayed\tremaining\tsegments\n")
  for(minutes in listOf(1,3,5))for(rate in listOf(.85f,1f,1.15f)) {
   val l=Examples.lessons.first().copy(id="r1-budget-$minutes-${(rate*100).toInt()}");app.repository.save(l)
   withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title,l.language,minutes),1f) }
   withTimeout(90000) { while(p.preparing.value!=null || p.state.value.session==null) { assertNull(p.state.value.error);delay(100) } }
   delay(1100);withContext(Dispatchers.Main) { p.pausePlay() };delay(200)
   val consumed=p.state.value.playedMs;delay(300);assertEquals(consumed,p.state.value.playedMs)
   withContext(Dispatchers.Main) { p.speed(rate) };delay(700)
   assertEquals("Actual session must adopt and replan rate",rate,p.state.value.session!!.speed)
   repeat(3) { withContext(Dispatchers.Main) { p.repeat() };delay(500) }
   val s=p.state.value;assertTrue(s.session!!.cues.all { it.durationMs>0 })
   assertTrue("Measured remaining + consumed exceeds target",s.remainingMs+s.playedMs<=minutes*60000L+500)
   assertTrue("Plan loses more than reserve",s.remainingMs+s.playedMs>=minutes*54000L)
   evidence.append("$minutes\t$rate\t${s.playedMs}\t${s.remainingMs}\t${s.session!!.cues.size}\n")
  }
  File(app.filesDir,"r1-remaining-budget.tsv").writeText(evidence.toString())
 }
}
