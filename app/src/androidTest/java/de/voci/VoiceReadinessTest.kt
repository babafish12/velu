package de.voci
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.*
import de.voci.domain.AudioCue
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class VoiceReadinessTest {
 @Test fun transientEmptyInventoryWaitsForRealOfflineVoiceAndSynthesizes()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
  val renderer=TtsRenderer(app,app.db.dao())
  try {
   withTimeout(5000) { while(renderer.voices("it-IT").isEmpty())delay(100) }
   val actual=renderer.voiceInventory;var first=true
   renderer.voiceInventory={if(first) { first=false;emptySet() } else actual()}
   val (cue,asset)=renderer.render(AudioCue("","readiness","Un bicchiere d'acqua naturale per favore.","Ein Glas stilles Wasser bitte.","it-IT"))
   assertTrue(cue.durationMs>0);assertTrue(AudioFiles.valid(File(asset.path),asset.bytes,asset.sha256));assertNotNull(app.db.dao().audio(asset.key))
  } finally { renderer.close() }
 }
 @Test fun coldInstalledItalianVoiceIsReadyBeforeFirstRender()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
  val renderer=TtsRenderer(app,app.db.dao())
  try {
   val start=System.nanoTime();val voices=renderer.voices("it-IT")
   File(app.filesDir,"r1-cold-italian-voices.txt").writeText("ready_ms=${(System.nanoTime()-start)/1000000}\n"+voices.joinToString("\n") { "${it.name} network=${it.isNetworkConnectionRequired}" })
   assertTrue("Installed offline Italian prerequisite on cold service",voices.isNotEmpty())
  } finally { renderer.close() }
 }
}
