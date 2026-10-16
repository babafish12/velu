package de.voci
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.data.Examples
import de.voci.domain.SessionSpec
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class SavedRecallTest {
 @Test fun recallOptInAndOutDoNotRewriteSavedAudioPlan()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp;val p=app.playback
  val l=Examples.lessons.first().copy(id="r1-saved-recall");app.repository.save(l)
  suspend fun prepare(recall:Boolean) {
   withContext(Dispatchers.Main) { p.start(l,SessionSpec(l.title,l.language,1,recall),1f) }
   withTimeout(60000) { while(p.preparing.value!=null || p.state.value.session==null) { assertNull(p.state.value.error);delay(30) } }
  }
  try {
   prepare(false);p.pin();val file=File(app.filesDir,"plans/${l.id}.json");val original=file.readBytes()
   prepare(true);assertTrue("User explicitly enabled optional recall",p.state.value.session!!.recall);assertArrayEquals(original,file.readBytes())
   prepare(false);assertFalse("Recall may never be forced by a saved session",p.state.value.session!!.recall);assertArrayEquals(original,file.readBytes())
  } finally { p.deleteLesson(l.id) }
 }
}
