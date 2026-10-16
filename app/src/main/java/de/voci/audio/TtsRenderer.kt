package de.voci.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import de.voci.data.*
import de.voci.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MissingVoice(val locale:String):Exception("Sprachdaten fehlen: $locale")
class TtsRenderer(private val context:Context,private val dao:VociDao,private val reserveBytes:Long=8L*1024*1024) {
 internal var checkpoint:suspend(String,AudioCue)->Unit={_,_->}
 private val serial=Mutex()
 private val ready=CompletableDeferred<Unit>()
 private val pending=ConcurrentHashMap<String,CompletableDeferred<Unit>>()
 private val directory=File(context.filesDir,"audio").apply { mkdirs() }
 private val engine=lazy {
  TextToSpeech(context) { status-> if(status==TextToSpeech.SUCCESS) ready.complete(Unit) else ready.completeExceptionally(MissingVoice("TTS")) }.also { it.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
  override fun onStart(id:String) {}
  override fun onDone(id:String) { pending.remove(id)?.complete(Unit) }
  @Deprecated("platform") override fun onError(id:String) { pending.remove(id)?.completeExceptionally(IllegalStateException("Sprachausgabe fehlgeschlagen")) }
  override fun onError(id:String,errorCode:Int) { onError(id) }
 }) }
 }
 private val tts get()=engine.value
 internal var voiceInventory:()->Set<Voice>?={tts.voices}
 suspend fun voices(locale:String):List<Voice> {
  val active=tts;withTimeout(15000) { ready.await() }
  // Some engines publish installed locale inventories shortly after their successful init callback.
  // Wait briefly for real offline data; absent/uninstalled/network-only voices still fail closed.
  repeat(16) { attempt->
   val available=voiceInventory().orEmpty().filter { it.locale.language==Locale.forLanguageTag(locale).language && !it.isNetworkConnectionRequired && "notInstalled" !in it.features.orEmpty() }.sortedBy { it.name }
   if(available.isNotEmpty())return available
   if(attempt<15)delay(100)
  }
  return emptyList()
 }

 suspend fun render(cue:AudioCue,pin:Boolean=false):Pair<AudioCue,AudioEntity> = serial.withLock {
  val active=tts
  withTimeout(15000) { ready.await() }
  val voice=voices(cue.locale).let { list->if(cue.voice.isBlank())list.firstOrNull() else list.find { it.name==cue.voice } } ?: throw MissingVoice(cue.locale)
  val key=AudioFiles.key(cue.text,cue.locale,voice.name,tts.defaultEngine.orEmpty(),cue.rate)
  val cached=dao.audio(key)
  if(cached!=null && AudioFiles.valid(File(cached.path),cached.bytes,cached.sha256)) {
   if(pin && !cached.pinned) dao.putAudio(cached.copy(pinned=true))
   return@withLock cue.copy(key=key,voice=voice.name,durationMs=cached.durationMs) to cached
  }
  require(directory.usableSpace>reserveBytes) { "Speicher voll" }
  val temp=File(directory,"${UUID.randomUUID()}.part.wav")
  val final=File(directory,"$key.wav")
  val id=UUID.randomUUID().toString();val completion=CompletableDeferred<Unit>();pending[id]=completion
  var renamed=false;var registered=false
  try {
   check(active.setVoice(voice)==TextToSpeech.SUCCESS);check(tts.setSpeechRate(cue.rate)==TextToSpeech.SUCCESS)
   check(tts.synthesizeToFile(cue.text,Bundle(),temp,id)==TextToSpeech.SUCCESS)
   withTimeout(45000) { completion.await() };currentCoroutineContext().ensureActive()
   require(temp.length()>44)
   val duration=MediaMetadataRetriever().let { retriever-> try { retriever.setDataSource(temp.path);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?:0 } finally { retriever.release() } }
   require(duration>0);val bytes=temp.length();val hash=AudioFiles.hash(temp)
   checkpoint("synthesized",cue);currentCoroutineContext().ensureActive()
   val meta=AudioEntity(key,final.path,duration,bytes,hash,pin || cached?.pinned==true)
   // Finish this bounded file/metadata commit before cancellation can return ownership to cleanup.
   withContext(NonCancellable) { withTimeout(5000) {
    check(temp.renameTo(final));renamed=true;checkpoint("renamed",cue)
    dao.putAudio(meta);registered=true
   } }
   cue.copy(key=key,voice=voice.name,durationMs=duration) to meta
  } finally { pending.remove(id);tts.stop();temp.delete();if(renamed && !registered)final.delete() }
 }
 suspend fun clearUnpinned(protected:Set<String> = emptySet())=serial.withLock { dao.audioFiles().filterNot { it.pinned || it.key in protected }.forEach { File(it.path).delete();dao.removeAudio(it.key) };directory.listFiles()?.filter { it.name.endsWith(".part.wav") }?.forEach { it.delete() } }
 fun close() { if(engine.isInitialized()) { tts.stop();tts.shutdown() } }
 suspend fun bytes()=withContext(Dispatchers.IO) { dao.audioFiles().filter { File(it.path).exists() }.sumOf { File(it.path).length() } }
}
