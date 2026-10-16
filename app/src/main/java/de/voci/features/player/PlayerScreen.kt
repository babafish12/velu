package de.voci.features.player

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.voci.VociViewModel
import de.voci.ui.components.*
import de.voci.ui.theme.*
import java.util.Locale

@Composable fun PlayerScreen(sessionId:String,vm:VociViewModel,back:()->Unit,audio:()->Unit,finished:(String,Boolean)->Unit) {
 val actual by vm.app.playback.state.collectAsStateWithLifecycle()
 val p=if(actual.session?.id==sessionId || actual.requestId==sessionId)actual else de.voci.audio.PlaybackState()
 val preparing by vm.app.playback.preparing.collectAsStateWithLifecycle()
 val uncertain by vm.uncertain.collectAsStateWithLifecycle()
 val preferences by vm.prefs.collectAsStateWithLifecycle()
 val markError by vm.error.collectAsStateWithLifecycle()
 val reduced=LocalReducedMotion.current
 LaunchedEffect(sessionId,p.ended,p.session?.id) { vm.claimCompletion(sessionId)?.let { finished(it.sessionId,it.recall) } }
 Page(p.session?.lesson?.title?:"Player",back) {
  if(preparing!=null && actual.requestId==sessionId) { Text(preparing!!);LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(vm::cancel) { Text("Abbrechen") } }
  InlineError(p.error,"Sprachdaten installieren",vm::voiceSettings)
  InlineError(markError,action={vm.error.value=null})
  val s=p.session
  if(s!=null) {
   val cue=s.cues.getOrNull(p.index)
   val item=s.lesson.items.firstOrNull { it.id==cue?.itemId }
   var wheelBusy by remember(s.id) { mutableStateOf(false) }
   var wheelTranslationFirst by remember(s.id) { mutableStateOf(preferences.wheelTranslationFirst) }
   val targetLanguage=remember(s.lesson.language) {
    Locale.forLanguageTag(s.lesson.language).getDisplayLanguage(Locale.GERMAN).ifBlank { s.lesson.language }
   }
   LinearProgressIndicator(progress={p.progress},modifier=Modifier.fillMaxWidth())
   WheelLanguageControl(targetLanguage,wheelTranslationFirst,enabled=preparing==null && !wheelBusy,
    onSwap={if(preparing==null && !wheelBusy)vm.toggleWheelLanguage()})
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
    EntryWheel(s.lesson.items,item?.id,s.id,preparing==null,
     onDragStart={vm.beginWheelSelection(s.id)},onCommit=vm::commitWheelSelection,
     onCancel=vm::cancelWheelSelection,onSelect={vm.selectItem(s.id,it)},modifier=Modifier.weight(1f),
     translationFirst=preferences.wheelTranslationFirst,
     onPresentationChanged={busy,translationFirst->wheelBusy=busy;wheelTranslationFirst=translationFirst})
    if(item!=null)UncertainStar(item.target,item.id in uncertain) { vm.toggleUncertain(s.lesson.id,item.id) }
   }
   // Meaning, example and dialogue cues remain the actual spoken text, not a base-word substitute.
   AnimatedContent(cue,transitionSpec={
    (fadeIn(tween(if(reduced)0 else Motion.expression)) togetherWith fadeOut(tween(if(reduced)0 else Motion.expression)))
     .using(if(reduced)null else SizeTransform { _,_->tween(Motion.expression) })
   },label="Ausdruck") { spoken->
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
     val entry=s.lesson.items.firstOrNull { it.id==spoken?.itemId }
     val primary=if(wheelTranslationFirst)entry?.translation else entry?.target
     val opposite=if(wheelTranslationFirst)entry?.target else entry?.translation
     val utterance=spoken?.text
     // Only omit speech already visible in the wheel; examples/dialogue remain their own cue.
     if(!utterance.isNullOrBlank() && utterance!=primary)
      Text(utterance,style=MaterialTheme.typography.headlineLarge)
     val secondary=opposite?.takeIf { it.isNotBlank() && it!=primary && it!=utterance }
     if(secondary!=null)Text(secondary,style=MaterialTheme.typography.titleMedium,color=VociTokens.secondary)
     val supplemental=spoken?.translation?.takeIf { it.isNotBlank() && it!=primary && it!=utterance && it!=secondary }
     if(supplemental!=null)Text(supplemental,style=MaterialTheme.typography.titleMedium,color=VociTokens.secondary)
    }
   }
   AudioTransport(p,vm.app.playback::pausePlay,vm.app.playback::repeat,vm.app.playback::skip)
   TextButton(audio) { Text("Audio") }
  }
 }
}

@Composable private fun WheelLanguageControl(targetLanguage:String,translationFirst:Boolean,enabled:Boolean,onSwap:()->Unit) {
 val primary=if(translationFirst)"Deutsch" else targetLanguage
 val other=if(translationFirst)targetLanguage else "Deutsch"
 // Immediate label changes also honor reduced motion; no animated geometry or wheel rotation.
 OutlinedButton(onClick=onSwap,enabled=enabled,shape=MaterialTheme.shapes.medium,
  contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),
  modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics {
   contentDescription="Radsprache wechseln"
   stateDescription="$primary im Rad"
  }) {
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
   Text("Radsprache wechseln",style=MaterialTheme.typography.labelMedium)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
    Text("$primary im Rad",style=MaterialTheme.typography.titleSmall)
    Text(other,style=MaterialTheme.typography.bodyMedium,color=VociTokens.secondary)
   }
  }
  Spacer(Modifier.width(12.dp))
  Text("⇄",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.clearAndSetSemantics { })
 }
}
