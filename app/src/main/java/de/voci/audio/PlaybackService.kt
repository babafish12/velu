package de.voci.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import de.voci.MainActivity
import de.voci.VociApp

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService:MediaSessionService() {
 // Same-process planning reads/mutates the service-owned player on its application looper.
 // A MediaController is asynchronous and can extrapolate a cue boundary before the player reaches it.
 internal companion object { var localPlayer:androidx.media3.common.Player?=null;private set }

 private var session:MediaSession?=null
 override fun onCreate() {
  super.onCreate()
  val engine=ExoPlayer.Builder(this).build().apply {
   setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),true)
   setHandleAudioBecomingNoisy(true)
  }
  val app=application as VociApp
  val player=object:ForwardingPlayer(engine) {
   // Gate the actual player too: queued asynchronous controller commands cannot race onStop.
   override fun play() { if(app.visible && app.playbackRequested)super.play() }
   override fun setPlayWhenReady(playWhenReady:Boolean) {
    super.setPlayWhenReady(playWhenReady && app.visible && app.playbackRequested)
   }
  }
  localPlayer=player
  session=MediaSession.Builder(this,player).setCallback(object:MediaSession.Callback {
   @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
   override fun onConnect(session:MediaSession,controller:MediaSession.ControllerInfo):MediaSession.ConnectionResult = if(controller.packageName==packageName || controller.isTrusted) super.onConnect(session,controller) else MediaSession.ConnectionResult.reject()
   @Suppress("DEPRECATION")
   override fun onPlayerCommandRequest(session:MediaSession,controller:MediaSession.ControllerInfo,playerCommand:Int):Int =
    if(app.visible && app.playbackRequested)SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_PERMISSION_DENIED
  }).setSessionActivity(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
 }
 override fun onGetSession(controllerInfo:MediaSession.ControllerInfo)=session
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  // Only the in-app bound controller owns this service. A media-button/service start cannot
  // restore an old queue or leave a cold externally started foreground service waiting.
  super.onStartCommand(null,flags,startId)
  if(!(application as VociApp).visible)localPlayer?.pause()
  stopSelf(startId)
  return START_NOT_STICKY
 }
 // This is a bound, visible-app player. Do not publish notification/lock-screen transport or
 // promote it to a foreground service. Media3's supported notification override owns this policy.
 override fun onUpdateNotification(session:MediaSession,startInForegroundRequired:Boolean) {
  stopForeground(STOP_FOREGROUND_REMOVE)
 }
 override fun onDestroy() { localPlayer=null;session?.run { player.release();release() };session=null;super.onDestroy() }
}
