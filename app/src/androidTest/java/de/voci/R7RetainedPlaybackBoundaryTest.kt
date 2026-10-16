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

/** Retained source assertions; Parent compilation/execution pending. */
class R7RetainedPlaybackBoundaryTest {
 private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
 private val p get()=app.playback
 private suspend fun prepared() { withTimeout(90000) { while(p.preparing.value!=null || p.state.value.session==null) { assertNull(p.state.value.error);delay(50) } } }
 private suspend fun vm()=withContext(Dispatchers.Main) { VociViewModel(app,SavedStateHandle()) }
 @Test fun r7ClearCachePreservesFullActiveSourcePoolForReplan()=r7RetainedFixture { _, _ ->
  val base=Examples.lessons.first();val l=base.copy(id="r7-retained-cache-${java.util.UUID.randomUUID()}",items=(1..6).map { i->VocabItem("v$i","Vorrei un tavolo per $i persone, per favore.","Ich möchte bitte einen Tisch für $i Personen.","Vorrei prenotare un tavolo per $i persone per domani sera, vicino alla finestra, se possibile, grazie.","Ich möchte für morgen Abend einen Tisch für $i Personen reservieren, möglichst am Fenster, danke.") },dialogue=emptyList(),questions=emptyList())
  app.repository.save(l);withContext(Dispatchers.Main) { p.start(l,SessionSpec("Restaurant"),1f) };prepared();withContext(Dispatchers.Main) { if(p.state.value.playing)p.pausePlay() }
  val s=p.state.value.session!!;assertEquals("Complete finite initial plan",s.sourceCues,s.cues)
  val vm=vm();try { withContext(Dispatchers.Main) { vm.clearCache().join() } } finally { withContext(Dispatchers.Main) { vm.cancel();androidx.lifecycle.ViewModelStore().apply { put("retained",vm);clear() } } }
  for(cue in s.sourceCues) { val asset=app.db.dao().audio(cue.key);assertNotNull("Active source deleted: ${cue.phase}",asset);assertTrue(AudioFiles.valid(File(asset!!.path),asset.bytes,asset.sha256)) }
  withContext(Dispatchers.Main) { p.speed(.85f).join();p.repeat() };delay(500);assertTrue(p.offline(p.state.value.session!!));assertNull(p.state.value.error)
 }
}
