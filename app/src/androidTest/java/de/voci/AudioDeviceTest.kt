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

@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
 @Test fun storagePressureRejectsBeforeWriting()=runBlocking<Unit> {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
  val renderer=TtsRenderer(app,app.db.dao(),Long.MAX_VALUE)
  val before=File(app.filesDir,"audio").listFiles().orEmpty().map { it.name }.toSet()
  val metadataBefore=app.db.dao().audioFiles()
  try {
   val voices=renderer.voices("de-DE");assertTrue("Installed German offline voice prerequisite",voices.isNotEmpty())
   File(app.filesDir,"r1-offline-voices.txt").writeText(voices.joinToString("\n") { "${it.name} ${it.locale} network=${it.isNetworkConnectionRequired} features=${it.features}" })
   try { renderer.render(AudioCue("","pressure","Speicherprobe ${System.nanoTime()}","","de-DE"));fail("Must reject under reserved-space threshold") }
   catch(e:IllegalArgumentException) { assertEquals("Speicher voll",e.message) }
   assertEquals(before,File(app.filesDir,"audio").listFiles().orEmpty().map { it.name }.toSet())
   assertEquals(metadataBefore,app.db.dao().audioFiles())
  } finally { renderer.close() }
 }

 @Test fun missingOfflineVoiceCreatesNoFileOrMetadata()=runBlocking<Unit> {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
  val renderer=TtsRenderer(app,app.db.dao());val before=app.db.dao().audioFiles()
  val files=File(app.filesDir,"audio").listFiles().orEmpty().map { it.name }.toSet()
  try {
   try { renderer.render(AudioCue("","missing","Test","","zz-ZZ"));fail("Missing voice must fail") }
   catch(e:MissingVoice) { assertEquals("zz-ZZ",e.locale) }
   assertEquals(before,app.db.dao().audioFiles());assertEquals(files,File(app.filesDir,"audio").listFiles().orEmpty().map { it.name }.toSet())
  } finally { renderer.close() }
 }

 @Test fun actualSystemTtsMeasuredAssetsAndBudget()=runBlocking {
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
   for(minutes in listOf(1,3,5)) { val p=Planner.fit(cues,minutes*60000L);val duration=p.sumOf { it.durationMs+it.pauseMs };assertTrue(duration in (minutes*54000L)..(minutes*60000L));summary.append("plan\t${l.language}\t$minutes\t$duration\n") }
  }
  File(app.filesDir,"tts-evidence.tsv").writeText(summary.toString())
 }
}
