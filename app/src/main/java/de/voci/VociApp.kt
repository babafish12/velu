package de.voci
import android.app.Application
import android.app.Activity
import android.os.Bundle
import androidx.room.Room
import de.voci.ai.ChatGptProvider
import de.voci.audio.*
import de.voci.auth.AuthManager
import de.voci.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class VociApp:Application() {
 // Actual Activity visibility, including ComponentActivity hosts. A cold service starts closed.
 internal var visible=false
  private set
 internal var playbackRequested=false
  private set
 private val started=mutableSetOf<Activity>()
 private val exitListeners=mutableSetOf<()->Unit>()
 internal fun observeExit(listener:()->Unit) { exitListeners+=listener }
 internal fun removeExitObserver(listener:()->Unit) { exitListeners-=listener }
 internal fun requestPlayback():Boolean {
  if(!visible)return false
  playbackRequested=true;return true
 }
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 val http by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(20,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).callTimeout(150,TimeUnit.SECONDS).build() }
 val db by lazy { Room.databaseBuilder(this,VociDatabase::class.java,"voci.db").build() }
 val repository by lazy { Repository(db.dao()) }
 val preferences by lazy { PreferencesStore(this) }
 val auth by lazy { AuthManager(this,http,scope) }
 val provider by lazy { ChatGptProvider(http,auth) }
 val tts by lazy { TtsRenderer(this,db.dao()) }
 private val playbackOwner=lazy { PlaybackCoordinator(this,db.dao(),tts,scope) }
 val playback by playbackOwner
 override fun onCreate() {
  super.onCreate()
  registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks {
   override fun onActivityStarted(activity:Activity) { started+=activity;visible=true }
   override fun onActivityStopped(activity:Activity) {
    started-=activity
    if(started.isNotEmpty() || activity.isChangingConfigurations)return
    visible=false;playbackRequested=false
    // Silence on the Activity's main-thread boundary, before asynchronous cleanup or DB writes.
    PlaybackService.localPlayer?.pause()
    if(playbackOwner.isInitialized())playback.onAppStopped()
    exitListeners.toList().forEach { it() }
   }
   override fun onActivityCreated(activity:Activity,state:Bundle?) {}
   override fun onActivityResumed(activity:Activity) {}
   override fun onActivityPaused(activity:Activity) {}
   override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) {}
   override fun onActivityDestroyed(activity:Activity) {}
  })
  scope.launch { repository.seed() }
 }
}
