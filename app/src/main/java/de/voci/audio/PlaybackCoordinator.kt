package de.voci.audio

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import de.voci.data.*
import de.voci.domain.*
import de.voci.VociApp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@Serializable data class PreparedSession(val id:String,val lesson:LessonContent,val cues:List<AudioCue>,val recall:Boolean,val speed:Float,val sourceCues:List<AudioCue> = emptyList(),val budgetMs:Long=60000,val singleItemId:String?=null,val finite:Boolean=false)
data class PlaybackState(val session:PreparedSession?=null,val index:Int=0,val positionMs:Long=0,val playedMs:Long=0,val playing:Boolean=false,val progress:Float=0f,val remainingMs:Long=0,val ended:Boolean=false,val error:String?=null,val requestId:String?=null)
class PlaybackCoordinator(private val context:Context,private val dao:VociDao,private val tts:TtsRenderer,private val scope:CoroutineScope) {
 private val app get()=context.applicationContext as VociApp
 internal var checkpoint:suspend(String,String)->Unit={_,_->}
 val state=MutableStateFlow(PlaybackState())
 val preparing=MutableStateFlow<String?>(null)
 val answers=MutableStateFlow<Map<String,Boolean>>(emptyMap())
 val uncertain=MutableStateFlow<Set<String>>(emptySet())
 private val uncertainStore=UncertainStore(PreferencesStore(context),dao)
 private val controller=CompletableDeferred<MediaController>()
 private var prepareJob:Job?=null
 private var pendingLesson:String?=null
 @Volatile private var generation=0L
 @Volatile private var adjustment=0L
 // Gestures fence preparation; only a replaced live queue fences its committed write.
 @Volatile private var adoptedAdjustment=0L
 private val adjustmentJobs=mutableSetOf<Job>()
 private val sessionWrites=Mutex()
 private val resources=Mutex()
 private class SelectionHold(val token:Long,val epoch:Long,val sessionId:String,var playing:Boolean,var gesture:Boolean=false) {
  var job:Job?=null
 }
 private var selectionHold:SelectionHold?=null
 // Source resolution and seeks may change Timeline contents without replacing the queue.
 private var playlistRevision=0L
 private class RejectedSelection(val epoch:Long,val session:PreparedSession,val playlistRevision:Long,val error:String)
 private var rejectedSelection:RejectedSelection?=null
 private var requestedSpeed=1f
 private var speedRevision=0L
 private var played=0L
 private val clock=PlayedClock()
 private val plans=File(context.filesDir,"plans").apply { mkdirs() }
 private val sessionFile=File(context.filesDir,"last-session.json")
 private var lastSave=0L
 private var persisted:SessionProgress?=null
 private val progressWrites=mutableSetOf<Job>()
 init {
  val future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()
  future.addListener({ runCatching { future.get() }.onSuccess { c->controller.complete(c);val player=requireNotNull(PlaybackService.localPlayer);player.addListener(object:Player.Listener {
   override fun onPositionDiscontinuity(old:Player.PositionInfo,new:Player.PositionInfo,reason:Int) { clock.discontinuity(old.mediaItemIndex,old.positionMs,new.mediaItemIndex,new.positionMs) }
   override fun onEvents(player:Player,events:Player.Events) { update(player) }
   override fun onTimelineChanged(timeline:Timeline,reason:Int) { if(reason==Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) { playlistRevision++;rejectedSelection=null } }
   override fun onPlayerError(error:PlaybackException) { rejectedSelection=null;state.value=state.value.copy(error="Audiodatei nicht abspielbar. Neu vorbereiten.",playing=false) }
  });scope.launch { restore();while(isActive) { update(player);delay(if(player.isPlaying)200 else 1000) } } }.onFailure { controller.completeExceptionally(it) } },ContextCompat.getMainExecutor(context))
 }
 private suspend fun own(epoch:Long) { currentCoroutineContext().ensureActive();if(epoch!=generation)throw CancellationException("Playback superseded") }
 suspend fun restore()=withContext(Dispatchers.Main.immediate) {
  val epoch=generation;controller.await();val c=requireNotNull(PlaybackService.localPlayer)
  if(c.mediaItemCount>0 || pendingLesson!=null)return@withContext
  val legacy=withContext(Dispatchers.IO) { runCatching { Json.decodeFromString<PreparedSession>(sessionFile.readText()) }.getOrNull() }?:return@withContext
  val saved=finiteSession(legacy)
  val progress=dao.latestProgress()?.let { Json.decodeFromString<SessionProgress>(it.json) }?:return@withContext
  if(progress.sessionId!=saved.id || progress.completed)return@withContext
  checkpoint("restore",saved.lesson.id)
  if(!offline(saved))return@withContext
  val items=items(saved);if(epoch!=generation)return@withContext
  if(c.mediaItemCount>0 || pendingLesson!=null)return@withContext
  try { loadUncertain(saved.lesson,progress.uncertain,epoch) }
  catch(e:CancellationException) { if(epoch!=generation)return@withContext else throw e }
  catch(_:Exception) { state.value=state.value.copy(error="Markierungen nicht geladen. Speicher prüfen und erneut öffnen.");return@withContext }
  if(c.mediaItemCount>0 || pendingLesson!=null)return@withContext
  requestedSpeed=saved.speed;played=progress.playedMs;clock.reset(played);answers.value=progress.answers
  val oldIndex=progress.mediaItemIndex?:progress.index*2
  val cue=legacy.cues.getOrNull(oldIndex/2)
  val mapped=if(saved===legacy)oldIndex else saved.cues.indexOfFirst { cue!=null && it.key==cue.key && it.itemId==cue.itemId && it.phase==cue.phase }.coerceAtLeast(0)*2+oldIndex%2
  state.value=PlaybackState(session=saved,playedMs=played,requestId=saved.id)
  c.pause();c.setMediaItems(items);c.setPlaybackSpeed(saved.speed);c.seekTo(mapped.coerceIn(0,c.mediaItemCount-1),progress.positionMs);c.prepare()
 }
 /** Legacy timer plans remain readable; reuse their ordered measured source, never their filler. */
 private fun finiteSession(s:PreparedSession):PreparedSession {
  if(s.finite || s.singleItemId!=null)return s
  val source=s.sourceCues.ifEmpty { s.cues }.distinctBy { Triple(it.itemId,it.phase,it.key) }
  return s.copy(cues=source,sourceCues=source,finite=true)
 }
 /** Returns the identity synchronously, before any preparation or navigation can suspend. */
 fun start(lesson:LessonContent,spec:SessionSpec,speed:Float,pin:Boolean=false,singleItemId:String?=null):String {
  require(singleItemId==null || lesson.items.any { it.id==singleItemId })
  if(!app.requestPlayback())return UUID.randomUUID().toString()
  val legacy=if(state.value.session?.lesson?.id==lesson.id)uncertain.value else emptySet()
  val previous=cancelPreparation();val epoch=++generation;val sessionId=UUID.randomUUID().toString()
  requestedSpeed=speed;val rateRevision=speedRevision
  pendingLesson=lesson.id;preparing.value="Sprachdaten prüfen";state.value=PlaybackState(requestId=sessionId)
  prepareJob=scope.launch {
   try {
    previous?.join();own(epoch)
    resources.withLock {
    controller.await();own(epoch);val c=requireNotNull(PlaybackService.localPlayer);c.pause();c.clearMediaItems()
    checkpoint("prepare",lesson.id);own(epoch)
    val entry=dao.lesson(lesson.id);own(epoch)
    val saved=singleItemId==null && entry?.saved==true
    val cached=withContext(Dispatchers.IO) { runCatching { Json.decodeFromString<PreparedSession>(File(plans,"${lesson.id}.json").readText()) }.getOrNull() }?.takeIf { it.singleItemId==null && it.lesson==lesson }
    val measured=mutableListOf<AudioCue>();var prebuffer:PreparedSession?=null
    val budget=if(saved && cached!=null)cached.budgetMs else spec.minutes*60000L
    val effectiveSpeed=if(rateRevision!=speedRevision)requestedSpeed else if(saved && cached!=null)cached.speed else speed
    requestedSpeed=effectiveSpeed
    if(singleItemId!=null) measured+=singleCues(lesson,singleItemId,cached?.sourceCues.orEmpty()) { own(epoch) }
    else if(cached!=null && cached.sourceCues.isNotEmpty() && offline(cached)) measured+=finiteSession(cached).sourceCues
    else {
     val dialogVoices=withContext(Dispatchers.IO) { tts.voices(lesson.language) };own(epoch)
     val source=if(cached!=null && cached.sourceCues.isNotEmpty())finiteSession(cached).sourceCues else baseCues(lesson).map { cue->if(cue.phase=="dialogue") { val speaker=lesson.dialogue.first { it.id==cue.itemId }.speaker;cue.copy(voice=dialogVoices.getOrNull(speaker)?.name?:dialogVoices.firstOrNull()?.name.orEmpty()) } else cue }
     for((i,cue) in source.withIndex()) {
      own(epoch);preparing.value="Audio ${i+1} / ${source.size}"
      measured+=withContext(Dispatchers.IO) { tts.render(cue,(pin && singleItemId==null)||saved).first };own(epoch)
      if(singleItemId==null && i==2 && !saved) {
       val partial=PreparedSession(sessionId,lesson,measured.toList(),spec.recall,effectiveSpeed,budgetMs=budget,finite=true)
       val media=items(partial);own(epoch)
       loadUncertain(lesson,legacy,epoch)
       prebuffer=partial;played=0;clock.reset();answers.value=emptyMap()
       state.value=PlaybackState(session=partial,requestId=sessionId)
       c.setMediaItems(media);c.setPlaybackSpeed(effectiveSpeed);c.prepare();c.play()
      }
     }
    }
    val plan=measured.toList()
    require(plan.isNotEmpty()) { "Audio fehlt" };own(epoch)
    val s=PreparedSession(sessionId,lesson,plan,spec.recall && singleItemId==null,effectiveSpeed,measured.toList(),if(singleItemId==null)budget else plan.sumOf { ((it.durationMs+it.pauseMs)/effectiveSpeed).toLong() },singleItemId,finite=true)
    val media=items(s);checkpoint("persist",lesson.id);own(epoch)
    writeSession(s,epoch,writePlan=singleItemId==null && (!saved || cached==null));own(epoch)
    val l=dao.lesson(lesson.id);own(epoch)
    if(l!=null && singleItemId==null)dao.putLesson(l.copy(measuredMs=if(saved)l.measuredMs else plan.sumOf { ((it.durationMs+it.pauseMs)/effectiveSpeed).toLong() },saved=l.saved||pin))
    own(epoch)
    if(prebuffer!=null && plan.take(prebuffer.cues.size)==prebuffer.cues) {
     val wasEnded=c.playbackState==Player.STATE_ENDED
     state.value=state.value.copy(session=s)
     c.addMediaItems(media.drop(prebuffer.cues.size*2))
     if(wasEnded && c.playWhenReady) { c.prepare();c.play() }
    } else {
     loadUncertain(lesson,legacy,epoch)
     played=0;clock.reset();answers.value=emptyMap()
     state.value=PlaybackState(session=s,remainingMs=plan.sumOf { ((it.durationMs+it.pauseMs)/effectiveSpeed).toLong() },requestId=sessionId)
     c.setMediaItems(media);c.setPlaybackSpeed(effectiveSpeed);c.prepare();c.play()
    }
    preparing.value=null;pendingLesson=null
    if(requestedSpeed!=effectiveSpeed)replan()
    }
   } catch(e:CancellationException) { throw e } catch(e:Exception) {
    if(epoch==generation) { PlaybackService.localPlayer?.pause();state.value=state.value.copy(playing=false,error=when(e) { is MissingVoice->"Sprachdaten fehlen (${e.locale}).";else->"Audio nicht vorbereitet. Speicher und Sprachdaten prüfen." }) }
   } finally { if(epoch==generation) { preparing.value=null;pendingLesson=null } }
  }
  return sessionId
 }
 private suspend fun loadUncertain(lesson:LessonContent,legacy:Set<String>,epoch:Long) {
  uncertainStore.load(lesson.id,legacy) { marks->if(epoch==generation)uncertain.value=marks }
  own(epoch)
 }
 private suspend fun items(s:PreparedSession):List<MediaItem> = withContext(Dispatchers.IO) {
  buildList { for(cue in s.cues) {
   ensureActive();val meta=dao.audio(cue.key)?:error("Missing file")
   add(MediaItem.Builder().setMediaId(cue.key).setUri(meta.path).setMediaMetadata(MediaMetadata.Builder().setTitle(cue.text).setArtist(s.lesson.title).build()).build())
   add(MediaItem.Builder().setMediaId("pause").setUri(silence(cue.pauseMs).path).setMediaMetadata(MediaMetadata.Builder().setTitle(cue.text).setArtist(s.lesson.title).build()).build())
  } }
 }
 private fun update(c:Player) {
  val s=state.value.session?:return
  if(s.cues.isEmpty() || c.mediaItemCount==0)return
  val idx=c.currentMediaItemIndex;val pos=c.currentPosition.coerceAtLeast(0)
  clock.sample(idx,pos,c.isPlaying,c.playbackParameters.speed);played=clock.totalMs
  val segment=(idx/2).coerceIn(0,s.cues.lastIndex)
  val before=s.cues.take(segment).sumOf { it.durationMs+it.pauseMs };val inside=if(idx%2==1)s.cues[segment].durationMs+pos else pos
  val duration=s.cues.sumOf { it.durationMs+it.pauseMs };val ended=c.playbackState==Player.STATE_ENDED && preparing.value==null && selectionHold==null && state.value.error==null
  state.value=state.value.copy(index=segment,positionMs=pos,playedMs=played,playing=c.isPlaying,progress=((before+inside).toFloat()/duration).coerceIn(0f,1f),remainingMs=if(ended)0 else ((duration-before-inside)/c.playbackParameters.speed).toLong().coerceAtLeast(0),ended=ended)
  val progress=SessionProgress(s.id,s.lesson.id,segment,pos,played,answers=answers.value,uncertain=uncertain.value,completed=ended,mediaItemIndex=idx)
  if(progress!=persisted && (System.currentTimeMillis()-lastSave>1000 || ended)) {
   lastSave=System.currentTimeMillis();persisted=progress;val epoch=generation
   val earlier=progressWrites.toList()
   val write=scope.launch(start=CoroutineStart.LAZY) { try { earlier.joinAll();if(epoch==generation)dao.putProgress(ProgressEntity(s.id,Json.encodeToString(progress))) } finally { progressWrites.remove(coroutineContext[Job]) } };progressWrites+=write;write.start()
  }
 }
 fun pausePlay() {
  if(!app.visible)return
  val epoch=generation
  scope.launch {
  own(epoch)
  selectionHold?.let {
   if(!it.playing && !app.requestPlayback())return@launch
   it.playing=!it.playing;return@launch
  }
  controller.await();own(epoch);if(!app.visible)return@launch
  val c=PlaybackService.localPlayer?:return@launch
  if(c.isPlaying)c.pause() else {
   if(c.playbackState==Player.STATE_ENDED)return@launch
   rejectedSelection?.let { rejected->
    val edit=adjustment
    if(rejected.epoch!=generation || state.value.session!==rejected.session ||
     state.value.error!=rejected.error || c.playerError!=null || playlistRevision!=rejected.playlistRevision)return@launch
    // Validate the unchanged audible queue, not the rejected item's source assets.
    val valid=try { offline(rejected.session.copy(sourceCues=emptyList())) }
     catch(e:CancellationException) { throw e } catch(_:Exception) { false }
    if(!valid)return@launch
    if(rejectedSelection!==rejected || rejected.epoch!=generation || edit!=adjustment ||
     selectionHold!=null || preparing.value!=null || state.value.session!==rejected.session ||
     state.value.error!=rejected.error || c.playerError!=null || playlistRevision!=rejected.playlistRevision ||
     c.playbackState==Player.STATE_ENDED)return@launch
    rejectedSelection=null;state.value=state.value.copy(error=null)
   }
   own(epoch)
   if(app.requestPlayback())c.play()
  }
 } }
 private fun adjust(block:suspend()->Unit):Job {
  val epoch=generation;val id=state.value.session?.id
  val job=scope.launch(start=CoroutineStart.LAZY) {
   try { own(epoch);if(!app.visible || state.value.session?.id!=id)return@launch;block() }
   catch(e:CancellationException) { throw e }
   catch(e:Exception) { if(epoch==generation && state.value.session?.id==id) { rejectedSelection=null;state.value=state.value.copy(error=if(e is MissingVoice)"Sprachdaten fehlen (${e.locale})." else "Audio konnte nicht angepasst werden. Erneut vorbereiten.") } }
   finally { adjustmentJobs.remove(coroutineContext[Job]) }
  };adjustmentJobs+=job;job.start();return job
 }
 fun repeat()=adjust { replan(repeat=true) }
 fun skip(delta:Int)=adjust {
  controller.await();val c=PlaybackService.localPlayer?:return@adjust
  check(android.os.Looper.myLooper()==c.applicationLooper)
  val index=(c.currentMediaItemIndex/2+delta)*2
  if(index in 0 until c.mediaItemCount) { c.seekTo(index,0);checkpoint("skip_seek",state.value.session!!.lesson.id);replan() }
 }
 fun speed(value:Float):Job { require(value in .75f..1.25f);requestedSpeed=value;speedRevision++;return adjust { replan() } }
 /** Main-thread drag ownership starts before the finger can outlive the old finite endpoint. */
 fun beginWheelSelection(sessionId:String):Long? {
  if(!app.visible || preparing.value!=null || state.value.session?.id!=sessionId)return null
  val c=PlaybackService.localPlayer?:return null
  check(android.os.Looper.myLooper()==c.applicationLooper)
  val hold=SelectionHold(++adjustment,generation,sessionId,selectionHold?.playing?:c.playWhenReady,gesture=true)
  selectionHold=hold;state.value=state.value.copy(ended=false);c.pause()
  return hold.token
 }
 fun cancelWheelSelection(token:Long) {
  val hold=selectionHold?.takeIf { it.token==token }?:return
  // Invalidate any preparation already committed by this gesture as well.
  adjustment++
  releaseSelection(hold)
  hold.job?.cancel()
 }
 private fun releaseSelection(hold:SelectionHold) {
  if(selectionHold!==hold)return
  selectionHold=null
  if(hold.epoch==generation && state.value.session?.id==hold.sessionId)PlaybackService.localPlayer?.let { c->
   c.playWhenReady=hold.playing;update(c)
  }
 }
 fun commitWheelSelection(token:Long,itemId:String):Job? {
  val hold=selectionHold?.takeIf { it.token==token && it.gesture && it.epoch==generation }?:return null
  hold.gesture=false
  val s=state.value.session
  if(s?.id!=hold.sessionId || s.lesson.items.none { it.id==itemId } || preparing.value!=null || hold.token!=adjustment) {
   releaseSelection(hold);return null
  }
  // Returning to the current row is not a replay or a new selection request.
  if(s.cues.getOrNull(state.value.index)?.itemId==itemId) { releaseSelection(hold);return null }
  return selectItemOwned(hold.sessionId,itemId,hold).also { hold.job=it }
 }
 /** Selection resolves stable vocabulary IDs against measured source material, never UI cue indices. */
 fun selectItem(sessionId:String,itemId:String)=selectItemOwned(sessionId,itemId,null)
 private fun selectItemOwned(sessionId:String,itemId:String,gestureHold:SelectionHold?)=adjust {
  if(gestureHold!=null && selectionHold!==gestureHold)return@adjust
  if(preparing.value!=null)return@adjust
  val initial=state.value.session?.takeIf { it.id==sessionId }?:return@adjust
  if(initial.lesson.items.none { it.id==itemId })return@adjust
  val epoch=generation;val edit=gestureHold?.token?:++adjustment
  suspend fun selectedOwner() {
   own(epoch)
   if(edit!=adjustment || state.value.session?.id!=sessionId)throw CancellationException("Selection superseded")
  }
  // Claim the old endpoint before any suspension, including waiting for resource ownership.
  val c=PlaybackService.localPlayer?:return@adjust
  check(android.os.Looper.myLooper()==c.applicationLooper)
  val initialPlaylistRevision=playlistRevision
  val hold=gestureHold?:SelectionHold(edit,epoch,sessionId,selectionHold?.playing?:c.playWhenReady)
  selectionHold=hold
  state.value=state.value.copy(ended=false)
  c.pause()
  try {
   resources.withLock {
    selectedOwner()
    val single=initial.singleItemId!=null
    val source=if(single)singleCues(initial.lesson,itemId,initial.sourceCues+initial.cues) { selectedOwner() }
     else initial.sourceCues.ifEmpty { initial.cues }.distinctBy { Triple(it.itemId,it.phase,it.key) }
    val first=source.indexOfFirst { it.itemId==itemId && it.phase=="target" }
    require(first>=0) { "Entry audio unavailable" }
    val loaded=items(initial.copy(cues=source));selectedOwner()
    val mediaByCue=source.indices.associate { (source[it].key to source[it].pauseMs) to loaded.subList(it*2,it*2+2) }
    update(c)
    val speed=requestedSpeed
    val plan=if(single)source else source.drop(first)
    val retained=if(single)(source+initial.sourceCues).distinctBy { it.itemId to it.phase } else source
    val next=initial.copy(cues=plan,sourceCues=retained,speed=speed,finite=true,
     singleItemId=if(single)itemId else null,
     budgetMs=if(single)played+plan.sumOf { ((it.durationMs+it.pauseMs)/speed).toLong() } else initial.budgetMs)
    val media=plan.flatMap { mediaByCue.getValue(it.key to it.pauseMs) }
    // Preserve real consumed time; playlist replacement is a seek, not listening.
    clock.reset(played)
    rejectedSelection=null
    state.value=state.value.copy(session=next,index=0,positionMs=0,ended=false,error=null)
    c.setMediaItems(media,0,0L);c.setPlaybackSpeed(speed);c.prepare()
    adoptedAdjustment=edit
    persisted=null;lastSave=0;update(c)
    // Once adopted, a gesture cancellation must not discard this durable snapshot.
    // This remains a child of the tracked adjustment job, including lifecycle drains.
    withContext(NonCancellable) { writeSession(next,epoch,edit) }
   }
  } catch(e:CancellationException) { throw e }
  catch(e:Exception) {
   selectedOwner() // A failed, superseded preparation must not overwrite the newer selection's error.
   if(selectionHold===hold && epoch==generation && state.value.session?.id==sessionId) {
    hold.playing=false
    val previous=state.value.error
    val recoverable=state.value.session===initial && playlistRevision==initialPlaylistRevision && c.playerError==null &&
     (previous==null || rejectedSelection?.let { it.epoch==epoch && it.session===initial && it.playlistRevision==initialPlaylistRevision && it.error==previous }==true)
    val error=if(e is MissingVoice)"Sprachdaten fehlen (${e.locale})." else "Audio konnte nicht angepasst werden. Erneut vorbereiten."
    rejectedSelection=if(recoverable)RejectedSelection(epoch,initial,initialPlaylistRevision,error) else null
    state.value=state.value.copy(playing=false,ended=false,error=if(recoverable || previous==null)error else previous)
   }
  } finally {
   // A newer selection inherits the intention and owns the hold. Cancellation/error
   // releases only this hold; a new playback request must never be resumed here.
   releaseSelection(hold)
  }
 }
 /** A saved plan is a read-only source of measured audio, independent of installed voices. */
 private suspend fun singleCues(lesson:LessonContent,itemId:String,retained:List<AudioCue>,owner:suspend()->Unit):List<AudioCue> {
  val cached=withContext(Dispatchers.IO) {
   runCatching { Json.decodeFromString<PreparedSession>(File(plans,"${lesson.id}.json").readText()) }.getOrNull()
  }?.takeIf { it.lesson==lesson && it.singleItemId==null }
  owner()
  val candidates=retained+cached?.let { it.sourceCues.ifEmpty { it.cues } }.orEmpty()
  val measured=mutableListOf<AudioCue>()
  for(expected in baseCues(lesson).filter { it.itemId==itemId && it.phase!="dialogue" }) {
   owner()
   val matches=candidates.filter { it.itemId==expected.itemId && it.phase==expected.phase &&
    it.text==expected.text && it.translation==expected.translation && it.locale==expected.locale && it.rate==expected.rate }
   var valid:AudioCue?=null
   for(cue in matches) {
    val intact=withContext(Dispatchers.IO) {
     val asset=dao.audio(cue.key)
     asset!=null && cue.durationMs>0 && cue.durationMs==asset.durationMs && cue.pauseMs>=0 &&
      AudioFiles.valid(File(asset.path),asset.bytes,asset.sha256) && runCatching {
       android.media.MediaMetadataRetriever().let { retriever->try {
        retriever.setDataSource(asset.path)
        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()==cue.durationMs
       } finally { retriever.release() } }
      }.getOrDefault(false)
    }
    owner()
    if(intact) { valid=cue;break }
   }
   measured+=valid?:withContext(Dispatchers.IO) { tts.render(expected,false).first }
   owner()
  }
  return measured
 }

 private suspend fun replan(repeat:Boolean=false) {
  if(preparing.value!=null)return
  controller.await();val c=PlaybackService.localPlayer?:return;check(android.os.Looper.myLooper()==c.applicationLooper)
  val initial=state.value.session?:return
  if(initial.cues.isEmpty() || c.playbackState==Player.STATE_ENDED)return
  val epoch=generation;val edit=++adjustment
  val rejected=rejectedSelection?.takeIf { it.epoch==epoch && it.session===initial &&
   it.playlistRevision==playlistRevision && it.error==state.value.error && c.playerError==null }
  // Resolve every possible file before reading the live cue/clock. No suspension after that snapshot.
  val source=(initial.cues+initial.sourceCues).distinctBy { it.key to it.pauseMs }
  checkpoint("replan_items",initial.lesson.id)
  val loaded=items(initial.copy(cues=source));own(epoch)
  if(rejected!=null) {
   check(offline(initial.copy(cues=source,sourceCues=emptyList()))) { "Missing or damaged audio" }
   own(epoch)
  }
  if(edit!=adjustment || state.value.session?.id!=initial.id || c.playbackState==Player.STATE_ENDED)return
  val mediaByCue=source.indices.associate { (source[it].key to source[it].pauseMs) to loaded.subList(it*2,it*2+2) }
  update(c);val s=state.value.session!!;val speed=requestedSpeed
  val index=(c.currentMediaItemIndex/2).coerceIn(s.cues.indices)
  val canRepeat=repeat
  val future=s.cues.drop(index+1)
  val next=s.copy(cues=s.cues.take(index+1)+future,speed=speed)
  val media=future.flatMap { mediaByCue.getValue(it.key to it.pauseMs) }
  // Capture ownership before our queue edits invalidate the rejection marker.
  val recovered=rejected?.takeIf { rejectedSelection===it && state.value.session===it.session &&
   it.playlistRevision==playlistRevision && it.error==state.value.error && c.playerError==null }
  rejectedSelection=null
  if(canRepeat)c.seekTo(index*2,0)
  c.removeMediaItems((index+1)*2,c.mediaItemCount);c.addMediaItems(media);c.setPlaybackSpeed(speed)
  val acknowledge=recovered!=null && epoch==generation && edit==adjustment &&
   state.value.session===initial && state.value.error==recovered.error && c.playerError==null
  state.value=state.value.copy(session=next,error=if(acknowledge)null else state.value.error);update(c)
  adoptedAdjustment=edit
  withContext(NonCancellable) { writeSession(next,epoch,edit) }
 }
 private suspend fun writeSession(s:PreparedSession,epoch:Long,edit:Long?=null,writePlan:Boolean=false)=sessionWrites.withLock {
  withContext(Dispatchers.IO) {
   if(edit!=null)checkpoint("replan_write",s.lesson.id)
   own(epoch)
   if(edit!=null && (edit!=adoptedAdjustment || state.value.session!==s))throw CancellationException("Adopted session superseded")
   fun atomic(file:File,text:String) { val storage=android.util.AtomicFile(file);val output=storage.startWrite();try { output.write(text.toByteArray());storage.finishWrite(output) } catch(e:Exception) { storage.failWrite(output);throw e } }
   atomic(sessionFile,Json.encodeToString(s))
   if(writePlan && s.singleItemId==null)atomic(File(plans,"${s.lesson.id}.json"),Json.encodeToString(s))
  }
 }
 fun cancelPreparation():Job? {
  val held=selectionHold;selectionHold=null
  generation++;adjustment++
  rejectedSelection=null;adoptedAdjustment=0L
  if(held!=null && pendingLesson==null)PlaybackService.localPlayer?.let { c->
   c.playWhenReady=held.playing;update(c)
  }
  val draining=(listOfNotNull(prepareJob)+adjustmentJobs.toList()).filterNot { it.isCompleted };draining.forEach { it.cancel() }
  if(pendingLesson!=null) {
   val epoch=generation;state.value=PlaybackState()
   scope.launch { controller.await();val c=PlaybackService.localPlayer;if(generation==epoch && c!=null) { c.pause();c.clearMediaItems() } }
  }
  preparing.value=null;pendingLesson=null
  return if(draining.isEmpty())null else scope.launch(start=CoroutineStart.UNDISPATCHED) { draining.joinAll() }
 }
 /** Runs synchronously from the last visible Activity's onStop, excluding recreation. */
 internal fun onAppStopped() {
  val c=PlaybackService.localPlayer
  c?.pause()
  if(c!=null)update(c)
  val incomplete=pendingLesson!=null
  cancelPreparation()
  if(incomplete) { c?.clearMediaItems();return }
  val snapshot=state.value
  val s=snapshot.session?:return
  state.value=snapshot.copy(playing=false)
  if(c==null || c.mediaItemCount==0)return
  val progress=SessionProgress(s.id,s.lesson.id,snapshot.index,c.currentPosition.coerceAtLeast(0),snapshot.playedMs,
   answers.value,uncertain.value,snapshot.ended,c.currentMediaItemIndex)
  persisted=progress;lastSave=System.currentTimeMillis()
  val epoch=generation
  val earlier=progressWrites.toList()
  val write=scope.launch(start=CoroutineStart.LAZY) {
   try {
    earlier.joinAll();own(epoch)
    // Persist adopted selection and paused position together, after older writes drain.
    writeSession(s,epoch);own(epoch)
    dao.putProgress(ProgressEntity(s.id,Json.encodeToString(progress)))
   } catch(e:CancellationException) { throw e }
   catch(_:Exception) { if(epoch==generation)state.value=state.value.copy(error="Fortschritt nicht gespeichert. Speicher prüfen.") }
   finally { progressWrites.remove(coroutineContext[Job]) }
  }
  progressWrites+=write;write.start()
 }
 fun clearError() { rejectedSelection=null;state.value=state.value.copy(error=null) }
 suspend fun offline(s:PreparedSession):Boolean=withContext(Dispatchers.IO) { (s.cues+s.sourceCues).distinctBy { it.key }.all { cue->val a=dao.audio(cue.key);a!=null && AudioFiles.valid(File(a.path),a.bytes,a.sha256) } }
 suspend fun offlineLesson(id:String):Boolean {
  val plan=withContext(Dispatchers.IO) { runCatching { Json.decodeFromString<PreparedSession>(File(plans,"$id.json").readText()) }.getOrNull() }?:return false
  return offline(plan)
 }
 suspend fun pin(expectedId:String?=state.value.session?.id) {
  val preparingJob=prepareJob;preparingJob?.join()
  resources.withLock {
   val s=state.value.session?.takeIf { it.id==expectedId }?:error("Requested session no longer active")
   require(dao.lesson(s.lesson.id)!=null && offline(s)) { "Lesson/audio no longer available" }
   for(c in (s.cues+s.sourceCues).distinctBy { it.key })dao.audio(c.key)?.let { dao.putAudio(it.copy(pinned=true)) }
   if(s.singleItemId==null) { val lesson=requireNotNull(dao.lesson(s.lesson.id));dao.putLesson(lesson.copy(saved=true)) }
  }
 }
 suspend fun clearCache()=withContext(Dispatchers.Main.immediate) {
  if(pendingLesson!=null)cancelPreparation()?.join() else adjustmentJobs.toList().joinAll()
  resources.withLock { val protected=state.value.session?.let { (it.cues+it.sourceCues).map { cue->cue.key }.toSet() }.orEmpty();withContext(Dispatchers.IO) { tts.clearUnpinned(protected) } }
 }
 suspend fun deleteLesson(id:String)=withContext(Dispatchers.Main.immediate) {
  val affected=pendingLesson==id || state.value.session?.lesson?.id==id
  if(affected) { val previous=cancelPreparation();val epoch=generation;previous?.join();controller.await();val c=PlaybackService.localPlayer;if(epoch==generation) { c?.pause();c?.clearMediaItems();state.value=PlaybackState() } }
  resources.withLock {
   progressWrites.toList().joinAll()
   for(entry in dao.progressEntries())if(runCatching { Json.decodeFromString<SessionProgress>(entry.json).lessonId==id }.getOrDefault(false))dao.removeProgress(entry.sessionId)
   dao.deleteLesson(id)
   sessionWrites.withLock { withContext(Dispatchers.IO) {
    val last=runCatching { Json.decodeFromString<PreparedSession>(sessionFile.readText()) }.getOrNull()
    if(last?.lesson?.id==id)android.util.AtomicFile(sessionFile).delete()
    android.util.AtomicFile(File(plans,"$id.json")).delete()
   } }
   withContext(Dispatchers.IO) {
    val retained=mutableSetOf<String>()
    for(file in plans.listFiles().orEmpty()) {
     val plan=runCatching { Json.decodeFromString<PreparedSession>(file.readText()) }.getOrNull()?:continue
     if(dao.lesson(plan.lesson.id)?.saved==true)retained+=(plan.cues+plan.sourceCues).map { it.key }
    }
    for(a in dao.audioFiles())if(a.pinned && a.key !in retained)dao.putAudio(a.copy(pinned=false))
    if(pendingLesson==null)tts.clearUnpinned(state.value.session?.let { (it.cues+it.sourceCues).map { c->c.key }.toSet() }.orEmpty())
   }
  }
 }
 private fun baseCues(l:LessonContent):List<AudioCue> = buildList {
  for(v in l.items) {
   add(AudioCue("",v.id,v.target,v.translation,l.language,phase="target",rate=.85f))
   add(AudioCue("",v.id,v.translation,v.target,"de-DE",phase="meaning"))
   add(AudioCue("",v.id,v.target,v.translation,l.language,phase="repeat"))
  }
  for(v in l.items)add(AudioCue("",v.id,v.example,v.exampleTranslation,l.language,phase="example"))
  for(d in l.dialogue)add(AudioCue("",d.id,d.text,d.translation,l.language,phase="dialogue"))
 }
 private fun silence(ms:Long):File {
  val f=File(context.filesDir,"silence-$ms.wav");if(f.exists())return f
  val n=(16000*ms/1000).toInt()*2
  RandomAccessFile(f,"rw").use { r->fun intLE(v:Int) { r.writeInt(Integer.reverseBytes(v)) };fun shortLE(v:Int) { r.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt()) };r.writeBytes("RIFF");intLE(36+n);r.writeBytes("WAVEfmt ");intLE(16);shortLE(1);shortLE(1);intLE(16000);intLE(32000);shortLE(2);shortLE(16);r.writeBytes("data");intLE(n);r.write(ByteArray(n)) };return f
 }
}
