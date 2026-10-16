package de.voci

import androidx.datastore.preferences.core.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.ai.LessonProvider
import de.voci.audio.AudioFiles
import de.voci.data.*
import de.voci.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Parent executes after handback. No user credentials, clearData or model weights used. */
class R2MigrationDeviceTest {
 private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
 @Test fun legacyColdStartCannotInvokeProviderOrModifyLibrary()=runBlocking {
  val old=app.preferences.flow.first();val before=app.db.dao().lessons().first()
  var requests=0
  val provider=object:LessonProvider { override suspend fun generate(spec:SessionSpec):LessonContent { requests++;error("Unexpected provider execution") } }
  try {
   for(value in listOf("local","device","server","future-provider","")) {
    app.preferences.string("provider",value)
    val owner=ViewModelStore()
    val vm=withContext(Dispatchers.Main) { VociViewModel(app,SavedStateHandle(),provider).also { owner.put("r2",it);it.topic("Restaurant");it.start() } }
    try {
     withTimeout(5000) { vm.error.first { it!=null } }
     assertEquals("provider_confirmation",vm.diagnostics.value)
     assertEquals(0,requests);assertEquals(value,app.preferences.flow.first().provider)
     assertEquals(before,app.db.dao().lessons().first())
    } finally { withContext(Dispatchers.Main) { owner.clear() } }
   }
  } finally { app.preferences.string("provider",old.provider) }
 }
 @Test fun explicitConfirmationPreservesLegacyFilesPreferencesAndReopenedLibrary()=runBlocking {
  val dir=File(app.cacheDir,"r2-migration-${UUID.randomUUID()}").apply { mkdirs() }
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  val dbName="r2-migration-${UUID.randomUUID()}.db"
  var db=Room.databaseBuilder(app,VociDatabase::class.java,dbName).build()
  try {
   val modelMarker=File(dir,"old-model-marker.bin").apply { writeText("synthetic historical file, not model weights") }
   val audio=File(dir,"audio-fixture.bin").apply { writeBytes(ByteArray(128) { it.toByte() }) }
   val modelHash=AudioFiles.hash(modelMarker);val audioHash=AudioFiles.hash(audio)
   val raw=PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"settings.preferences_pb")})
   raw.edit { it[stringPreferencesKey("provider")]="local";it[stringPreferencesKey("server")]="https://192.0.2.10:1234";it[stringPreferencesKey("modelPath")]=modelMarker.path;it[stringPreferencesKey("modelHash")]=modelHash;it[stringPreferencesKey("unknown-key")]="preserve" }
   val original=raw.data.first();val prefs=PreferencesStore(raw)
   val lesson=Examples.lessons.first().copy(id="r2-historical-local",example=false)
   Repository(db.dao()).save(lesson,saved=true,ms=60000)
   db.dao().putAudio(AudioEntity("r2-audio",audio.path,1000,audio.length(),audioHash,true))
   val entry=db.dao().lesson(lesson.id)
   prefs.confirmChatGpt()
   assertEquals(original.toMutablePreferences().apply { this[stringPreferencesKey("provider")]="chatgpt" }.toPreferences(),raw.data.first())
   db.close();db=Room.databaseBuilder(app,VociDatabase::class.java,dbName).build()
   assertEquals(entry,db.dao().lesson(lesson.id));assertEquals(lesson,Repository(db.dao()).content(lesson.id))
   assertEquals(modelHash,AudioFiles.hash(modelMarker))
   val asset=requireNotNull(db.dao().audio("r2-audio"));assertTrue(asset.pinned);assertTrue(AudioFiles.valid(File(asset.path),asset.bytes,asset.sha256))
   // File-integrity fixture only; these synthetic bytes are never submitted to Media3/TTS.
   audio.writeText("corrupt");assertFalse(AudioFiles.valid(File(asset.path),asset.bytes,asset.sha256))
  } finally { db.close();app.deleteDatabase(dbName);scope.coroutineContext[Job]!!.cancelAndJoin();dir.deleteRecursively() }
 }
 @Test fun savedPlaybackReadBypassesGenerationGate()=runBlocking {
  val old=app.preferences.flow.first();val owner=ViewModelStore();var calls=0
  val read=CompletableDeferred<Unit>()
  try {
   app.repository.seed();app.preferences.string("provider","local")
   val before=app.db.dao().lessons().first()
   val vm=withContext(Dispatchers.Main) {
    VociViewModel(app,SavedStateHandle(),object:LessonProvider { override suspend fun generate(spec:SessionSpec):LessonContent { calls++;error("Playback must not generate") } }).also {
     owner.put("r2",it);it.generationCheckpoint={stage->if(stage=="play_read") { read.complete(Unit);throw CancellationException("Read boundary verified; no playback in this fixture") }}
     it.play(Examples.lessons.first().id)
    }
   }
   withTimeout(5000) { read.await() };assertNull(vm.error.value);assertEquals(0,calls)
   assertEquals(before,app.db.dao().lessons().first());assertEquals("local",app.preferences.flow.first().provider)
  } finally { withContext(Dispatchers.Main) { owner.clear() };app.preferences.string("provider",old.provider) }
 }
}
