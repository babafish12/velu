package de.voci
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.session.*
import android.content.ComponentName
import de.voci.audio.*
import de.voci.data.*
import de.voci.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import java.io.File

class PlaybackBoundaryTest {
 private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
 private val p get()=app.playback
 private suspend fun prepared() { withTimeout(90000) { while(p.preparing.value!=null || p.state.value.session==null) { assertNull(p.state.value.error);delay(50) } } }
 private suspend fun start(id:String):LessonContent { val l=Examples.lessons.first().copy(id=id);app.repository.save(l);withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title),1f) };prepared();return l }
 private suspend fun vm()=withContext(Dispatchers.Main) { VociViewModel(app,SavedStateHandle()) }
 private suspend fun controller():MediaController { val future=withContext(Dispatchers.Main) { MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync() };return withContext(Dispatchers.IO) { future.get() } }
 @After fun cleanup()=runBlocking { withContext(Dispatchers.Main) { p.checkpoint={_,_->};p.cancelPreparation()?.join() };for(l in app.db.dao().lessons().first().filter { it.id.startsWith("c-r1-") })p.deleteLesson(l.id) }
 @Test fun earlierRepositoryReadCannotStartDeletedLesson()=runBlocking {
  val id="c-r1-read-delete";app.repository.save(Examples.lessons.first().copy(id=id));val vm=vm()
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val events=mutableListOf<String>();val collect=launch(Dispatchers.Main) { vm.event.collect { events+=it } }
  try {
   vm.generationCheckpoint={if(it=="play_read") { entered.complete(Unit);withContext(NonCancellable) { release.await() } } }
   withContext(Dispatchers.Main) { vm.play(id) };withTimeout(5000) { entered.await() }
   val deletion=withContext(Dispatchers.Main) { vm.deleteLesson(id) };delay(100);release.complete(Unit);deletion.join()
   withTimeout(30000) { while(vm.busy.value || p.preparing.value!=null)delay(50) }
   assertNull(app.repository.content(id));assertTrue(events.isEmpty());assertNotEquals(id,p.state.value.session?.lesson?.id);assertFalse(File(app.filesDir,"plans/$id.json").exists())
  } finally { release.complete(Unit);collect.cancel();withContext(Dispatchers.Main) { vm.cancel() } }
 }
 @Test fun saveActionCannotPinSuccessorSession()=runBlocking {
  val a=start("c-r1-save-A");val vm=vm();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  try {
   vm.generationCheckpoint={if(it=="save_read") { entered.complete(Unit);withContext(NonCancellable) { release.await() } } }
   val save=withContext(Dispatchers.Main) { vm.saveSession() };withTimeout(5000) { entered.await() }
   val b=start("c-r1-save-B");release.complete(Unit);save.join()
   assertFalse("Save A must never pin B",app.db.dao().lesson(b.id)!!.saved)
   assertTrue("Save A must succeed for A or report failure",app.db.dao().lesson(a.id)!!.saved || vm.error.value!=null)
  } finally { release.complete(Unit) }
 }
 @Test fun clearCachePreservesFullActiveSourcePoolForReplan()=runBlocking {
  val base=Examples.lessons.first();val l=base.copy(id="c-r1-cache-pool",items=(1..6).map { i->VocabItem("v$i","Vorrei un tavolo per $i persone, per favore.","Ich möchte bitte einen Tisch für $i Personen.","Vorrei prenotare un tavolo per $i persone per domani sera, vicino alla finestra, se possibile, grazie.","Ich möchte für morgen Abend einen Tisch für $i Personen reservieren, möglichst am Fenster, danke.") },dialogue=emptyList(),questions=emptyList())
  app.repository.save(l);withContext(Dispatchers.Main) { p.start(l,SessionSpec("Restaurant"),1f) };prepared();withContext(Dispatchers.Main) { if(p.state.value.playing)p.pausePlay() }
  val s=p.state.value.session!!;assertTrue("Fixture needs source outside fitted plan",s.sourceCues.any { source->s.cues.none { it.key==source.key } })
  val vm=vm();withContext(Dispatchers.Main) { vm.clearCache().join() }
  for(cue in s.sourceCues) { val asset=app.db.dao().audio(cue.key);assertNotNull("Active source deleted: ${cue.phase}",asset);assertTrue(AudioFiles.valid(File(asset!!.path),asset.bytes,asset.sha256)) }
  withContext(Dispatchers.Main) { p.speed(.85f).join();p.repeat() };delay(500);assertTrue(p.offline(p.state.value.session!!));assertNull(p.state.value.error)
 }
 @Test fun liveBoundaryDuringReplanCannotRestartCurrentSpokenFile()=runBlocking {
  start("c-r1-live-boundary");val connection=controller();val c=withContext(Dispatchers.Main) { requireNotNull(PlaybackService.localPlayer) };val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  try {
   withTimeout(15000) { while(withContext(Dispatchers.Main) { c.currentMediaItemIndex!=1 || c.currentPosition<100 })delay(10) }
   p.checkpoint={stage,_->if(stage=="replan_items") { entered.complete(Unit);withContext(NonCancellable) { release.await() } } }
   val change=withContext(Dispatchers.Main) { p.speed(.85f) };withTimeout(5000) { entered.await() }
   withTimeout(5000) { while(withContext(Dispatchers.Main) { c.currentMediaItemIndex!=2 || c.currentPosition<250 })delay(10) }
   val before=withContext(Dispatchers.Main) { c.currentMediaItem!!.mediaId to c.currentPosition };release.complete(Unit);change.join()
   withContext(Dispatchers.Main) { File(app.filesDir,"r1-live-replan-diagnostic.txt").writeText("before=$before\nafter=${c.currentMediaItem!!.mediaId},${c.currentPosition}\nindex=${c.currentMediaItemIndex}\nstate=${p.state.value}\n");assertEquals(before.first,c.currentMediaItem!!.mediaId);assertTrue("Replan truncated/restarted active speech: ${before.second} -> ${c.currentPosition}",c.currentPosition>=before.second) }
  } finally { release.complete(Unit);withContext(Dispatchers.Main) { connection.release() } }
 }
 private suspend fun delayedReplan(delete:Boolean)=coroutineScope {
  val a=start("c-r1-write-A");val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  try {
   p.checkpoint={stage,id->if(stage=="replan_write" && id==a.id) { entered.complete(Unit);withContext(NonCancellable) { release.await() } } }
   val change=withContext(Dispatchers.Main) { p.speed(1.15f) };withTimeout(5000) { entered.await() }
   val file=File(app.filesDir,"last-session.json")
   if(delete) {
    val removal=async(Dispatchers.Main) { p.deleteLesson(a.id) };delay(200);release.complete(Unit);change.join();removal.await()
    assertTrue("Late replan resurrected deleted restore record",!file.exists() || Json.decodeFromString<PreparedSession>(file.readText()).lesson.id!=a.id)
   } else {
    val b=Examples.lessons.first().copy(id="c-r1-write-B");app.repository.save(b)
    withContext(Dispatchers.Main) { p.start(b,SessionSpec(b.title),1f) }
    withTimeoutOrNull(2000) { prepared() };release.complete(Unit);change.join();prepared()
    assertEquals(b.id,Json.decodeFromString<PreparedSession>(file.readText()).lesson.id)
   }
  } finally { release.complete(Unit) }
 }
 @Test fun staleReplanCannotOverwriteNewSessionFile()=runBlocking { delayedReplan(false) }
 @Test fun staleReplanCannotResurrectDeletedSessionFile()=runBlocking { delayedReplan(true) }
}
