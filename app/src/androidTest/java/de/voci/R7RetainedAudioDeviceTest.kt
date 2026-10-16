package de.voci
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.*
import de.voci.data.*
import de.voci.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Retained source assertions; Parent compilation/execution pending. */
class R7RetainedAudioDeviceTest {
 @Test fun r7ActualSystemTtsMeasuredAssetsAndBudget()=r7RetainedFixture { _, _ ->
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
  val summary=StringBuilder()
  for(l in Examples.lessons) {
   val cues=mutableListOf<AudioCue>()
   for(v in l.items) {
    val cue=AudioCue("",v.id,v.target,v.translation,l.language)
    val start=System.nanoTime();val (measured,asset)=app.tts.render(cue,true)
    assertTrue(measured.durationMs>0);assertTrue(AudioFiles.valid(File(asset.path),asset.bytes,asset.sha256));cues+=measured
    summary.append("${l.language}\t${asset.durationMs}\t${asset.bytes}\t${(System.nanoTime()-start)/1000000}\n")
   }
   assertEquals("Every finite source item rendered once, in order",l.items.map { it.id },cues.map { it.itemId })
   assertEquals(l.items.map { it.target },cues.map { it.text })
   assertEquals(cues.size,cues.map { it.itemId }.distinct().size)
   assertTrue(cues.all { it.key.isNotBlank() && it.durationMs>0 })
  }
  File(app.filesDir,"r7-retained-tts.tsv").writeText(summary.toString())
 }
}
