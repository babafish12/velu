package de.voci

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.LessonProvider
import de.voci.audio.*
import de.voci.data.*
import de.voci.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CorrectionPlaybackTest {
 private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
 private val p get()=app.playback
 private suspend fun prepared() { withTimeout(90000) { while(p.preparing.value!=null || p.state.value.session==null) { assertNull(p.state.value.error);delay(100) } };assertNull(p.state.value.error) }
 private suspend fun start(l:LessonContent,m:Int=1) { withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title,l.language,m),1f) };prepared() }
 @After fun cleanup()=runBlocking {
  withContext(Dispatchers.Main) { p.checkpoint={_,_->};app.tts.checkpoint={_,_->};p.cancelPreparation()?.join();if(p.state.value.playing)p.pausePlay() }
  for(l in app.db.dao().lessons().first().filter { it.id.startsWith("r1-") })p.deleteLesson(l.id)
 }
 @Test fun savedThreeMinuteReplayDoesNotRewriteOriginalAtOneMinute()=runBlocking {
  val l=Examples.lessons.first().copy(id="r1-immutable")
  app.repository.save(l);start(l,3);p.pin()
  val file=File(app.filesDir,"plans/${l.id}.json");val original=file.readBytes();val plan=p.state.value.session!!.cues
  start(l,1)
  assertArrayEquals("Saved plan is immutable",original,file.readBytes())
  assertEquals(plan,p.state.value.session!!.cues)
  start(l,3);assertEquals(plan,p.state.value.session!!.cues);assertTrue(p.offlineLesson(l.id))
 }
 @Test fun repairedPinnedAssetSurvivesCacheClear()=runBlocking {
  val cue=AudioCue("","r1-pin","Una prenotazione per domani.","Eine Reservierung für morgen.","it-IT")
  val (measured,asset)=app.tts.render(cue,true);File(asset.path).writeText("corrupt")
  val (_,repaired)=app.tts.render(measured,false)
  app.tts.clearUnpinned()
  assertTrue("Repair must preserve pin",repaired.pinned)
  assertTrue(AudioFiles.valid(File(repaired.path),repaired.bytes,repaired.sha256))
  assertNotNull(app.db.dao().audio(repaired.key))
 }
 @Test fun deletingPendingPreparationCannotPublishSessionOrPlan()=runBlocking {
  val l=Examples.lessons.first().copy(id="r1-delete-pending")
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  app.repository.save(l)
  withContext(Dispatchers.Main) {
   p.checkpoint={stage,id->if(stage=="prepare" && id==l.id) { entered.complete(Unit);withContext(NonCancellable) { release.await() } } }
   p.start(l,SessionSpec(l.title),1f)
  }
  withTimeout(10000) { entered.await() }
  val deleting=async(Dispatchers.Main) { p.deleteLesson(l.id) };delay(200);release.complete(Unit);deleting.await();delay(6000)
  assertNull(app.repository.content(l.id));assertNotEquals(l.id,p.state.value.session?.lesson?.id)
  assertFalse(File(app.filesDir,"plans/${l.id}.json").exists())
 }
 @Test fun deleteDuringRenderedFileCannotLeaveNewMetadataOrFiles()=runBlocking {
  val l=Examples.lessons.first().copy(id="r1-delete-render",items=Examples.lessons.first().items.mapIndexed { i,v->if(i==0)v.copy(target="Un tavolo per sette persone per domani.") else v })
  app.repository.save(l);val before=app.db.dao().audioFiles().map { it.key }.toSet()
  val files=File(app.filesDir,"audio").listFiles().orEmpty().map { it.name }.toSet()
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  app.tts.checkpoint={_,cue->if(cue.text==l.items.first().target) { entered.complete(Unit);withContext(NonCancellable) { release.await() } } }
  withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title),1f) }
  withTimeout(30000) { entered.await() };val deletion=async(Dispatchers.Main) { p.deleteLesson(l.id) }
  delay(100);release.complete(Unit);deletion.await();delay(300)
  assertNull(app.repository.content(l.id));assertNotEquals(l.id,p.state.value.session?.lesson?.id)
  assertTrue(app.db.dao().audioFiles().all { it.key in before })
  assertTrue("Cancelled synthesis must leave no orphan file",File(app.filesDir,"audio").listFiles().orEmpty().all { it.name in files })
 }
 @Test fun deleteAfterAudioRenameCannotLeaveOrphanFile()=runBlocking {
  val l=Examples.lessons.first().copy(id="r1-delete-renamed",items=Examples.lessons.first().items.mapIndexed { i,v->if(i==0)v.copy(target="Un tavolo per quattro persone per sabato.") else v })
  app.repository.save(l);val before=app.db.dao().audioFiles().map { it.key }.toSet()
  val files=File(app.filesDir,"audio").listFiles().orEmpty().map { it.name }.toSet()
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  app.tts.checkpoint={stage,cue->if(stage=="renamed" && cue.text==l.items.first().target) { entered.complete(Unit);withContext(NonCancellable) { release.await() } } }
  withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title),1f) }
  withTimeout(30000) { entered.await() };val deletion=async(Dispatchers.Main) { p.deleteLesson(l.id) }
  delay(100);release.complete(Unit);deletion.await();delay(300)
  assertNull(app.repository.content(l.id));assertNotEquals(l.id,p.state.value.session?.lesson?.id)
  assertTrue(app.db.dao().audioFiles().all { it.key in before })
  assertTrue("Cancelled synthesis must leave no orphan file",File(app.filesDir,"audio").listFiles().orEmpty().all { it.name in files })
 }
 @Test fun deleteDuringPlanPersistenceCannotLeaveSessionOrPlan()=runBlocking {
  val l=Examples.lessons.first().copy(id="r1-delete-persist");app.repository.save(l)
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  p.checkpoint={stage,id->if(stage=="persist" && id==l.id) { entered.complete(Unit);withContext(NonCancellable) { release.await() } } }
  withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title),1f) }
  withTimeout(60000) { entered.await() };delay(1500);val deletion=async(Dispatchers.Main) { p.deleteLesson(l.id) }
  delay(100);release.complete(Unit);deletion.await();delay(300)
  assertNull(app.repository.content(l.id));assertNotEquals(l.id,p.state.value.session?.lesson?.id)
  assertFalse(File(app.filesDir,"plans/${l.id}.json").exists())
  assertTrue("Deleted lesson must leave no session progress",app.db.dao().latestProgress()?.json?.contains(l.id)!=true)
  val session=File(app.filesDir,"last-session.json")
  assertTrue(!session.exists() || !session.readText().contains(l.id))
 }
 @Test fun staleRestoreCannotReplaceExplicitStart()=runBlocking {
  val a=Examples.lessons.first().copy(id="r1-restore-A");app.repository.save(a);start(a);p.pin();delay(1300)
  val future=withContext(Dispatchers.Main) { androidx.media3.session.MediaController.Builder(app,androidx.media3.session.SessionToken(app,android.content.ComponentName(app,PlaybackService::class.java))).buildAsync() }
  val c=withContext(Dispatchers.IO) { future.get() }
  val savedProgress=app.db.dao().latestProgress()!!
  withContext(Dispatchers.Main) { c.pause();c.clearMediaItems() };delay(700)
  app.db.dao().putProgress(savedProgress)
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  p.checkpoint={stage,_->if(stage=="restore") { entered.complete(Unit);release.await() } }
  val restore=async(Dispatchers.Main) { try { p.restore() } catch(_:CancellationException) { /* superseded restore is terminal */ } };withTimeout(10000) { entered.await() }
  val b=Examples.lessons.first().copy(id="r1-restore-B");app.repository.save(b);start(b)
  val expected=p.state.value.session!!.id;release.complete(Unit);restore.await();delay(300)
  assertEquals(expected,p.state.value.session!!.id);assertEquals(b.id,p.state.value.session!!.lesson.id)
  withContext(Dispatchers.Main) { c.release() }
 }
 @Test fun cancellationAfterDatabaseSaveBeforePublicationRollsBackNewLesson()=runBlocking {
  val id="r1-generation-persisted";app.db.dao().deleteLesson(id)
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  val provider=object:LessonProvider { override suspend fun generate(spec:SessionSpec)=Examples.lessons.first().copy(id=id) }
  val vm=withContext(Dispatchers.Main) { VociViewModel(app,SavedStateHandle(),provider) }
  val events=mutableListOf<String>();val collector=launch(Dispatchers.Main) { vm.event.collect { events+=it } }
  try {
   withContext(Dispatchers.Main) { vm.generationCheckpoint={entered.complete(Unit);withContext(NonCancellable) { release.await() } };vm.topic("Restaurant");vm.start() }
   withTimeout(5000) { entered.await() };assertNotNull(app.repository.content(id))
   withContext(Dispatchers.Main) { vm.cancel() };release.complete(Unit)
   withTimeout(5000) { while(app.repository.content(id)!=null)delay(20) }
   assertTrue(events.isEmpty());assertNotEquals(id,p.state.value.session?.lesson?.id)
  } finally { release.complete(Unit);collector.cancel();withContext(Dispatchers.Main) { vm.cancel() };app.db.dao().deleteLesson(id) }
 }
 @Test fun cancelledGenerationCannotSaveNavigateOrClearSuccessorBusy()=runBlocking {
  app.db.dao().deleteLesson("r1-generation-1");app.db.dao().deleteLesson("r1-generation-2")
  val enteredA=CompletableDeferred<Unit>();val exitA=CompletableDeferred<Unit>();val enteredB=CompletableDeferred<Unit>();val exitB=CompletableDeferred<Unit>()
  var count=0
  val provider=object:LessonProvider { override suspend fun generate(spec:SessionSpec):LessonContent { val n=++count;if(n==1) { enteredA.complete(Unit);withContext(NonCancellable) { exitA.await() } } else { enteredB.complete(Unit);exitB.await() };return Examples.lessons.first().copy(id="r1-generation-$n") } }
  val vm=withContext(Dispatchers.Main) { VociViewModel(app,SavedStateHandle(),provider).also { it.topic("Restaurant");it.start() } }
  val events=mutableListOf<String>();val collector=launch(Dispatchers.Main) { vm.event.collect { events+=it } }
  withTimeout(5000) { enteredA.await() }
  withContext(Dispatchers.Main) { vm.cancel();vm.start() };exitA.complete(Unit)
  withTimeout(5000) { enteredB.await() };delay(200)
  assertTrue("A finally must not clear B",vm.busy.value)
  assertNull(app.repository.content("r1-generation-1"));assertTrue(events.isEmpty())
  withContext(Dispatchers.Main) { vm.cancel() };exitB.complete(Unit);delay(300)
  assertNull(app.repository.content("r1-generation-2"));assertTrue(events.isEmpty());collector.cancel()
 }
}
