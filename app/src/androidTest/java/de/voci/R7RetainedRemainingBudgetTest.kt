package de.voci
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.data.Examples
import de.voci.domain.LessonValidator as LessonContract
import de.voci.domain.SessionSpec
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.After
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import java.io.File

/** Retained source assertions; Parent compilation/execution pending. */
class R7RetainedRemainingBudgetTest {
 @Test fun r7SkipTakesEffectOnActualPlayerBeforeReplanNearPauseBoundary()=r7RetainedFixture { _, _ ->
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp;val p=app.playback
  val l=Examples.lessons.first().copy(id="r7-retained-skip-${java.util.UUID.randomUUID()}");app.repository.save(l)
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
     assertEquals("Skip preserves finite source without filler",p.state.value.session!!.sourceCues,p.state.value.session!!.cues)
     val session=p.state.value.session!!
     assertEquals(session.cues.flatMap { listOf(it.key,"pause") },(0 until actual.mediaItemCount).map { actual.getMediaItemAt(it).mediaId })
     journal.append("$delta\t$beforeIndex\t${actual.currentMediaItemIndex}\t$before\t${p.state.value.playedMs}\n")
    }
   }
   File(app.filesDir,"r7-retained-skip.tsv").writeText(journal.toString())
  } finally { p.checkpoint={_,_->} }
 }
 @Test fun r7LatestSpeedSurvivesImmediateRepeat()=r7RetainedFixture { _, _ ->
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp;val p=app.playback
  val l=Examples.lessons.first().copy(id="r7-retained-rapid-${java.util.UUID.randomUUID()}");app.repository.save(l)
  withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title),1f) }
  withTimeout(60000) { while(p.preparing.value!=null || p.state.value.session==null)delay(100) }
  withContext(Dispatchers.Main) { p.pausePlay() };delay(300)
  withContext(Dispatchers.Main) { p.speed(1.15f);p.repeat();p.speed(.85f);p.repeat() };delay(1500)
  assertEquals(.85f,p.state.value.session!!.speed)
  assertEquals("Rate/repeat preserve finite source without filler",p.state.value.session!!.sourceCues,p.state.value.session!!.cues)
 }
 @Test fun r7MeasuredWholeSegmentsAdaptToRateAndRepeatWithinBudget()=r7RetainedFixture { _, _ ->
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp;val p=app.playback
  val evidence=StringBuilder("entries\trate\tplayed\tremaining\tsegments\n")
  for(count in listOf(5,10,20))for(rate in listOf(.85f,1f,1.15f)) {
   // Local synthetic expansion of bundled text, never a live provider result.
   val base=Examples.lessons.first();val token=java.util.UUID.randomUUID().toString()
   val l=base.copy(id="r7-retained-$count-$token",items=(0 until count).map { i->base.items[i%base.items.size].copy(id="r7-$token-item-$i", target="${base.items[i%base.items.size].target} ${i + 1}", example="${base.items[i%base.items.size].example} ${i + 1}") },dialogue=emptyList(),questions=emptyList())
   LessonContract.validate(l)
   assertEquals((0 until count).map { "${base.items[it%base.items.size].target} ${it + 1}" },l.items.map { it.target })
   assertEquals(count,l.items.map { it.target }.distinct().size)
   assertEquals(count,l.items.map { it.example }.distinct().size)
   assertEquals(count,l.items.map { it.id }.distinct().size);app.repository.save(l)
   withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title,l.language,entryCount=count),1f) }
   withTimeout(90000) { while(p.preparing.value!=null || p.state.value.session==null) { assertNull(p.state.value.error);delay(100) } }
   delay(1100);withContext(Dispatchers.Main) { p.pausePlay() };delay(200)
   val consumed=p.state.value.playedMs;delay(300);assertEquals(consumed,p.state.value.playedMs)
   withContext(Dispatchers.Main) { p.speed(rate) };delay(700)
   assertEquals("Actual session must adopt and replan rate",rate,p.state.value.session!!.speed)
   repeat(3) { withContext(Dispatchers.Main) { p.repeat() };delay(500) }
   val s=p.state.value;assertTrue(s.session!!.cues.all { it.durationMs>0 })
   assertEquals("Complete finite source preserved",s.session!!.sourceCues,s.session!!.cues)
   assertEquals("All explicit content IDs retained in order",l.items.map { it.id },s.session!!.cues.filter { it.phase=="target" }.map { it.itemId })
   evidence.append("$count\t$rate\t${s.playedMs}\t${s.remainingMs}\t${s.session!!.cues.size}\n")
  }
  File(app.filesDir,"r7-retained-rates.tsv").writeText(evidence.toString())
 }
}
