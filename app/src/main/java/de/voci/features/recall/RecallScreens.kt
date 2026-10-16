package de.voci.features.recall
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.*
import de.voci.VociViewModel
import de.voci.ui.components.*
import de.voci.ui.theme.LocalReducedMotion

@Composable fun RecallScreen(id:String,vm:VociViewModel,back:()->Unit,result:()->Unit) {
 val p by vm.app.playback.state.collectAsStateWithLifecycle();val answers by vm.answers.collectAsStateWithLifecycle();val questions=if(p.session?.singleItemId!=null)emptyList() else p.session?.lesson?.questions.orEmpty();val q=questions.firstOrNull { it.id !in answers }
 Page("Kurz prüfen",back) {
  if(p.session?.id!=id) { Text("Diese Sitzung ist nicht mehr aktiv.");return@Page }
  if(q==null) VociButton("Ergebnis",result) else {
   Text(q.prompt,style=MaterialTheme.typography.headlineSmall)
   q.options.forEachIndexed { i,text->OutlinedButton({vm.answer(q,i)},Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(text) } }
   TextButton(result) { Text("Überspringen") }
  }
 }
}
@Composable fun ResultScreen(id:String,vm:VociViewModel,back:()->Unit) {
 val p by vm.app.playback.state.collectAsStateWithLifecycle();val answers by vm.answers.collectAsStateWithLifecycle();val uncertain by vm.uncertain.collectAsStateWithLifecycle();val lessons by vm.lessons.collectAsStateWithLifecycle();val saved=lessons.find { it.id==p.session?.lesson?.id }?.saved==true;val error by vm.error.collectAsStateWithLifecycle();var animated by rememberSaveable(id) { mutableStateOf(false) }
 val reduced=LocalReducedMotion.current
 val composition by rememberLottieComposition(LottieCompositionSpec.Asset("complete.json"))
 Page("Sprint beendet",back) {
  if(p.session?.id!=id) { Text("Diese Sitzung ist nicht mehr aktiv.");return@Page }
  if(!reduced && !animated)LottieAnimation(composition,iterations=1,modifier=Modifier.size(40.dp))
  LaunchedEffect(id) { kotlinx.coroutines.delay(650);animated=true }
  InlineError(error,"Schliessen",{vm.error.value=null})
  Text("Gehört: ${time(p.playedMs)}")
  if(answers.isNotEmpty())Text("${answers.values.count { it }} von ${answers.size} Antworten richtig")
  VociButton("Nochmals",{vm.replay()},modifier=Modifier.fillMaxWidth())
  if(uncertain.isNotEmpty())OutlinedButton(vm::repeatUncertain) { Text("Unsichere wiederholen") }
  if(p.session?.singleItemId==null)TextButton({vm.saveSession(id)},enabled=!saved) { Text(if(saved)"Gespeichert" else "Speichern") }
 }
}
