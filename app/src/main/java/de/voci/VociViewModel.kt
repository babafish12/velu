package de.voci

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.voci.ai.*
import de.voci.data.*
import de.voci.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class VociViewModel @JvmOverloads constructor(application:Application,private val savedState:SavedStateHandle,private val providerOverride:LessonProvider?=null):AndroidViewModel(application) {
 internal var generationCheckpoint:suspend(String)->Unit={}
 val app=application as VociApp
 val prefs=app.preferences.flow.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences(provider=""))
 val lessons=app.repository.lessons.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val categories=app.repository.categories.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
 val topic=savedState.getStateFlow("topic","")
 val query=savedState.getStateFlow("query","")
 val filter=savedState.getStateFlow("filter","")
 val error=MutableStateFlow<String?>(null)
 val diagnostics=MutableStateFlow<String?>(null)
 val busy=MutableStateFlow(false)
 private val generating=MutableStateFlow(false)
 val generationBusy=generating.asStateFlow()
 /** Activity-scoped saved state: consumed on entry, including reduced motion, never a preference. */
 fun claimBrandIntro():Boolean {
  if(savedState.get<Boolean>("brandIntroShown")==true)return false
  savedState["brandIntroShown"]=true;return true
 }
 val models=MutableStateFlow<List<ModelOption>>(emptyList())
 val selectedModel=MutableStateFlow<String?>(null)
 val storageBytes=MutableStateFlow(0L)
 val offlineIds=MutableStateFlow<Set<String>>(emptySet())
 val answers=app.playback.answers
 val uncertain=app.playback.uncertain
 private val uncertainStore=UncertainStore(app.preferences,app.db.dao())
 val libraryUncertain=app.preferences.uncertainFlow.retryWhen { cause,_->
  if(cause is java.io.IOException) {
   error.value="Markierungen nicht geladen. Lektion erneut öffnen und Speicher prüfen."
   delay(1000);true
  } else false
 }.catch {
  error.value="Markierungen nicht geladen. Lektion erneut öffnen und Speicher prüfen."
 }.stateIn(viewModelScope,SharingStarted.Eagerly,emptyMap())
 val event=MutableSharedFlow<String>(extraBufferCapacity=1)
 private val completionDelivery=de.voci.navigation.CompletionDelivery(
  { savedState.get<ArrayList<String>>("deliveredCompletions")?.toList().orEmpty() },
  { savedState["deliveredCompletions"]=ArrayList(it) })
 fun claimCompletion(expectedId:String?)=completionDelivery.claim(app.playback.state.value,expectedId)
 private var generation:Job?=null
 private var requestEpoch=0L
 private var requestedLesson:String?=null
 private val exitListener:()->Unit={ cancelRequest() }
 init { app.observeExit(exitListener) }
 override fun onCleared() { app.removeExitObserver(exitListener);super.onCleared() }
 fun topic(v:String) { savedState["topic"]=v.take(240) }
 fun query(v:String) { savedState["query"]=v }
 fun filter(v:String) { savedState["filter"]=v }
 fun language(v:String)=viewModelScope.launch { app.preferences.string("language",v) }
 fun minutes(v:Int)=viewModelScope.launch { app.preferences.minutes(v) }
 fun entryCount(v:Int)=viewModelScope.launch { app.preferences.entryCount(v) }
 fun bool(k:String,v:Boolean)=viewModelScope.launch { app.preferences.bool(k,v) }
 /** Display preference only: never changes a prepared session or touches playback. */
 fun toggleWheelLanguage()=viewModelScope.launch {
  try { app.preferences.toggleWheelLanguage() }
  catch(e:CancellationException) { throw e }
  catch(_:Exception) { error.value="Radsprache nicht gespeichert. Erneut versuchen." }
 }
 /** Only explicit Account action migrates generation. No generation or catalog request is started here. */
 fun confirmChatGpt()=viewModelScope.launch {
  try { app.preferences.confirmChatGpt();error.value=null }
  catch(e:CancellationException) { throw e }
  catch(_:Exception) { error.value="Umstellung nicht gespeichert. Speicher prüfen und erneut versuchen." }
 }
 fun speed(v:Float)=viewModelScope.launch { app.preferences.speed(v);app.playback.speed(v) }
 fun scenario(text:String,category:String) { topic(text);savedState["category"]=category }
 private suspend fun own(epoch:Long) { currentCoroutineContext().ensureActive();if(epoch!=requestEpoch)throw CancellationException("Request superseded") }
 private fun request(lessonId:String?=null,isGeneration:Boolean=false,block:suspend(Long)->Unit) {
  if(!app.visible)return
  val previous=generation;this.cancel();val epoch=requestEpoch;requestedLesson=lessonId
  busy.value=true;error.value=null;diagnostics.value=null
  generation=viewModelScope.launch {
   try { previous?.join();own(epoch);generating.value=isGeneration;block(epoch) }
   catch(e:CancellationException) { throw e }
   catch(e:Exception) { if(epoch==requestEpoch) {
    (e as? ProviderFailure)?.let { diagnostics.value=listOfNotNull(it.httpStatus?.let { status->"HTTP $status" },it.kind,it.param,it.requestId,it.bodyShape).joinToString("\n") }
    error.value=when((e as? ProviderFailure)?.kind) { "subscription_sharing_user_not_eligible","http_403"->"Plan-Nutzung ist für dieses Konto oder diese Region nicht verfügbar. Konto und Freigabe prüfen.";"subscription_sharing_unsupported_capability"->"Anfrage wird vom Modell nicht unterstützt. Anderes verfügbares Modell wählen.";"provider_confirmation"->"Neue Lektionen benötigen ChatGPT. Verwendung zuerst im Konto bestätigen.";"signed_out"->"Für neue Lektionen bei ChatGPT anmelden.";"plan_permission"->"ChatGPT-Plan noch nicht freigegeben.";"limit","subscription_sharing_usage_limit_exceeded"->"Limit erreicht. Gespeicherte Lektionen bleiben verfügbar.";else->"Lektion nicht erstellt. Verbindung und ChatGPT-Konto prüfen." }
   } } finally { if(epoch==requestEpoch) { generating.value=false;busy.value=false;requestedLesson=null } }
  }
 }
 fun start(category:String?=savedState["category"]) {
  if(busy.value || topic.value.isBlank())return
  request(isGeneration=true) { epoch->
   // Read durable preferences before any provider use, including a cold start before stateIn emits.
   val p=app.preferences.flow.first();own(epoch)
   val spec=SessionSpec(topic.value,p.language,p.minutes,p.recall,entryCount=p.entryCount)
   val lesson=GenerationAccess.generate(p.provider,providerOverride?:app.provider,spec);own(epoch)
   saveAndPlayOwned(lesson,category,spec,p.speed,epoch)
  }
 }
 private fun cancelRequest() { requestEpoch++;generation?.cancel();generating.value=false;busy.value=false;requestedLesson=null }
 fun cancel() { cancelRequest();app.playback.cancelPreparation() }
 private suspend fun saveAndPlayOwned(l:LessonContent,category:String?,spec:SessionSpec,speed:Float,epoch:Long) {
  requestedLesson=l.id
  val existed=app.db.dao().lesson(l.id)!=null;var published=false
  try { own(epoch);app.repository.save(l,category);generationCheckpoint("saved");own(epoch);playOwned(l,spec,speed,epoch);published=true }
  finally { if(!published && !existed)withContext(NonCancellable) { withTimeout(5000) { app.db.dao().deleteLesson(l.id) } } }
 }
 private suspend fun playOwned(l:LessonContent,spec:SessionSpec,speed:Float,epoch:Long,singleItemId:String?=null) {
  own(epoch);answers.value=emptyMap()
  val id=app.playback.start(l,spec,speed,singleItemId=singleItemId);own(epoch);event.emit("player:$id")
 }
 fun play(id:String) { request(id) { epoch->
  val l=app.repository.content(id)?:return@request;generationCheckpoint("play_read");own(epoch)
  playOwned(l,SessionSpec(l.title,l.language,prefs.value.minutes,prefs.value.recall),prefs.value.speed,epoch)
 } }
 fun playItem(lessonId:String,itemId:String) { request(lessonId) { epoch->
  val l=app.repository.content(lessonId)?:return@request;own(epoch)
  require(l.items.any { it.id==itemId })
  playOwned(l,SessionSpec(l.title,l.language,prefs.value.minutes,false),prefs.value.speed,epoch,itemId)
 } }
 fun replay() {
  val s=app.playback.state.value.session?:return
  if(s.singleItemId!=null)playItem(s.lesson.id,s.singleItemId) else play(s.lesson.id)
 }
 fun selectItem(sessionId:String,itemId:String) { app.playback.selectItem(sessionId,itemId) }
 fun beginWheelSelection(sessionId:String)=app.playback.beginWheelSelection(sessionId)
 suspend fun commitWheelSelection(token:Long,itemId:String) { app.playback.commitWheelSelection(token,itemId)?.join() }
 fun cancelWheelSelection(token:Long) { app.playback.cancelWheelSelection(token) }
 fun newCategory(name:String)=viewModelScope.launch { runCatching { app.repository.newCategory(name) }.onFailure { error.value="Name fehlt oder ist zu lang." } }
 fun renameCategory(id:String,name:String)=viewModelScope.launch { runCatching { app.repository.rename(id,name) }.onFailure { error.value="Name fehlt oder ist zu lang." } }
 fun deleteCategory(id:String)=viewModelScope.launch { app.db.dao().deleteCategory(id) }
 fun deleteLesson(id:String)=app.scope.launch {
  try {
   val earlier=if(requestedLesson==id)generation else null;if(earlier!=null)this@VociViewModel.cancel()
   earlier?.join();app.playback.deleteLesson(id);uncertainStore.clear(id);refreshStorage()
  } catch(e:CancellationException) { throw e }
  catch(_:Exception) { error.value="Lektion nicht vollständig gelöscht. Erneut versuchen." }
 }
 fun saveSession(expectedId:String?=app.playback.state.value.session?.id)=viewModelScope.launch {
  runCatching {
   val session=app.playback.state.value.session?.takeIf { it.id==expectedId }?:error("Session changed")
   val stored=app.repository.content(session.lesson.id);generationCheckpoint("save_read");requireNotNull(stored)
   app.playback.pin(expectedId);refreshStorage()
  }.onFailure { error.value="Diese Sitzung konnte nicht gespeichert werden. Erneut öffnen und versuchen." }
 }

 fun refreshStorage()=viewModelScope.launch {
  storageBytes.value=app.tts.bytes()
  offlineIds.value=lessons.value.filter { app.playback.offlineLesson(it.id) }.map { it.id }.toSet()
 }
 fun clearCache()=viewModelScope.launch { app.playback.clearCache();refreshStorage() }

 private fun liveUncertain(lessonId:String):Set<String> =
  if(app.playback.state.value.session?.lesson?.id==lessonId)uncertain.value else emptySet()
 suspend fun libraryLesson(id:String):LessonContent? {
  val lesson=app.repository.content(id)?:return null
  try { uncertainStore.load(id,liveUncertain(id)) }
  catch(e:CancellationException) { throw e }
  catch(_:Exception) { error.value="Markierungen nicht geladen. Lektion erneut öffnen und Speicher prüfen." }
  return lesson
 }
 fun toggleUncertain(lessonId:String,itemId:String) { changeUncertain(lessonId,itemId,addOnly=false) }
 private fun changeUncertain(lessonId:String,itemId:String,addOnly:Boolean) {
  // The app owns a short durable edit even when navigation clears this ViewModel.
  app.scope.launch {
   try {
    uncertainStore.change(lessonId,itemId,addOnly,{liveUncertain(lessonId)}) { marks->
     if(app.playback.state.value.session?.lesson?.id==lessonId) {
      // Keep other live, session-scoped marks; only this item's toggle removes a mark.
      val merged=uncertain.value+marks
      uncertain.value=if(itemId in marks)merged else merged-itemId
     }
    }
   } catch(e:CancellationException) { throw e }
   catch(_:Exception) { error.value="Markierung nicht gespeichert. Speicher prüfen und erneut versuchen." }
  }
 }
 fun answer(q:RecognitionQuestion,option:Int) { answers.value=answers.value+(q.id to (q.answer==option));if(q.answer!=option)markUncertain(q.itemId) }
 fun markUncertain(id:String) {
  val lesson=app.playback.state.value.session?.lesson?:return
  if(lesson.items.any { it.id==id })changeUncertain(lesson.id,id,addOnly=true)
 }
 fun repeatUncertain() {
  val s=app.playback.state.value.session?:return
  val marked=uncertain.value.toSet()
  val items=s.lesson.items.filter { it.id in marked };if(items.isEmpty())return
  if(s.singleItemId!=null && items.size==1) { playItem(s.lesson.id,items.single().id);return }
  val l=s.lesson.copy(id=java.util.UUID.randomUUID().toString(),items=items,dialogue=emptyList(),questions=s.lesson.questions.filter { it.itemId in marked })
  request { epoch->saveAndPlayOwned(l,null,SessionSpec(l.title,l.language,prefs.value.minutes),prefs.value.speed,epoch) }
 }
 fun loadModels()=viewModelScope.launch { runCatching { GenerationAccess.requireChatGpt(app.preferences.flow.first().provider);app.provider.models() }.onSuccess { models.value=it }.onFailure { error.value="Modellliste nicht geladen. Konto und Verbindung prüfen." } }
 fun model(slug:String) { selectedModel.value=slug;app.provider.selectedModel=slug }
 fun voiceSettings() { runCatching { app.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { app.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
 fun openUrl(url:String) { app.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
