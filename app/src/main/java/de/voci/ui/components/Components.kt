package de.voci.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import de.voci.audio.PlaybackState
import de.voci.ui.theme.*

@Composable fun VociButton(label:String,onClick:()->Unit,enabled:Boolean=true,modifier:Modifier=Modifier) { Button(onClick,modifier.heightIn(min=52.dp),enabled=enabled,shape=MaterialTheme.shapes.medium) { Text(label) } }
@Composable fun Page(title:String,back:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=VociTokens.gutter),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  Row(Modifier.fillMaxWidth().padding(top=16.dp),verticalAlignment=Alignment.CenterVertically) {
   if(back!=null) TextButton(back,Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) { Text("Zurück") }
   Text(title,style=MaterialTheme.typography.headlineMedium,modifier=Modifier.semantics { heading() })
  }
  content();Spacer(Modifier.height(24.dp))
 }
}
@Composable fun ChoiceRow(values:List<Pair<String,String>>,selected:String,onSelect:(String)->Unit) {
 FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
  for((id,label) in values) {
   val border by androidx.compose.animation.core.animateDpAsState(if(id==selected)2.dp else 1.dp,tween(if(LocalReducedMotion.current)0 else Motion.select),label="Auswahl")
   FilterChip(selected=id==selected,onClick={onSelect(id)},label={Text(label)},modifier=Modifier.heightIn(min=48.dp),border=BorderStroke(border,if(id==selected)VociTokens.accent else VociTokens.secondary),colors=FilterChipDefaults.filterChipColors(containerColor=Color.White,labelColor=VociTokens.ink,selectedContainerColor=VociTokens.accent,selectedLabelColor=Color.White))
  }
 }
}
val languages=listOf("it-IT" to "Italienisch","es-ES" to "Spanisch","en-GB" to "Englisch","fr-FR" to "Französisch")
@Composable fun LanguageSelector(selected:String,onSelect:(String)->Unit) { ChoiceRow(languages,selected,onSelect) }
@Composable fun DurationSelector(selected:Int,onSelect:(Int)->Unit) { ChoiceRow(listOf("1" to "1 Min","3" to "3 Min","5" to "5 Min"),selected.toString()) { onSelect(it.toInt()) } }
@Composable fun ContentAmountSelector(selected:Int,onSelect:(Int)->Unit) { ChoiceRow(listOf("5" to "5 Einträge","10" to "10 Einträge","20" to "20 Einträge"),selected.toString()) { onSelect(it.toInt()) } }
@Composable fun InlineError(text:String?,actionLabel:String="Schliessen",action:()->Unit={}) {
 if(text!=null) Column(Modifier.fillMaxWidth().semantics { liveRegion=LiveRegionMode.Polite }) { Text(text,color=MaterialTheme.colorScheme.error);TextButton(action) { Text(actionLabel) } }
}
@Composable fun SettingRow(title:String,subtitle:String?=null,click:()->Unit) {
 Column { Column(Modifier.fillMaxWidth().clickable(onClick=click).heightIn(min=64.dp).padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
  Text(title,style=MaterialTheme.typography.titleMedium);if(subtitle!=null)Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=VociTokens.secondary)
 };HorizontalDivider(color=VociTokens.line) }
}
fun time(ms:Long):String="%d:%02d".format(ms/60000,ms/1000%60)
@Composable fun AudioTransport(p:PlaybackState,toggle:()->Unit,repeat:()->Unit,skip:(Int)->Unit) {
 val transition=if(LocalReducedMotion.current)0 else Motion.select
 FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
  OutlinedButton({skip(-1)},Modifier.heightIn(min=48.dp)) { Text("Zurück") }
  Button(toggle,Modifier.heightIn(min=56.dp)) { AnimatedContent(p.playing,transitionSpec={
   (fadeIn(tween(transition)) togetherWith fadeOut(tween(transition)))
    .using(if(transition==0)null else SizeTransform { _,_->tween(Motion.select) })
  },label="Wiedergabe") { playing->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) { Icon(androidx.compose.ui.res.painterResource(if(playing)de.voci.R.drawable.ic_pause else de.voci.R.drawable.ic_play_arrow),contentDescription=null);Text(if(playing)"Pause" else "Play") } } }
  OutlinedButton({skip(1)},Modifier.heightIn(min=48.dp)) { Text("Weiter") }
 }
}
@Composable fun MiniPlayer(p:PlaybackState,toggle:()->Unit,open:()->Unit) {
 val s=p.session?:return
 if(p.ended)return
 Surface(color=Color.White,tonalElevation=0.dp) {
  Column {
   Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(start=24.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
    Text(s.lesson.title,Modifier.weight(1f),maxLines=1,style=MaterialTheme.typography.titleSmall)
    TextButton(toggle,Modifier.heightIn(min=48.dp)) { Text(if(p.playing)"Pause" else "Play") }
   }
   LinearProgressIndicator(progress={ p.progress },modifier=Modifier.fillMaxWidth().height(2.dp))
  }
 }
}
@Composable fun NavGlyph(kind:Int) {
 val resources=listOf(de.voci.R.drawable.ic_graphic_eq,de.voci.R.drawable.ic_category,de.voci.R.drawable.ic_library_books,de.voci.R.drawable.ic_tune)
 Icon(androidx.compose.ui.res.painterResource(resources[kind]),contentDescription=null,modifier=Modifier.size(24.dp))
}
