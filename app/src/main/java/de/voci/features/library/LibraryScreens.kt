package de.voci.features.library
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import de.voci.VociViewModel
import de.voci.domain.LessonContent
import de.voci.ui.components.*

@Composable fun LibraryScreen(vm:VociViewModel,detail:(String)->Unit) {
 val lessons by vm.lessons.collectAsStateWithLifecycle();val query by vm.query.collectAsStateWithLifecycle();val filter by vm.filter.collectAsStateWithLifecycle();val offline by vm.offlineIds.collectAsStateWithLifecycle()
 LaunchedEffect(Unit) { vm.refreshStorage() }
 Page("Bibliothek") {
  OutlinedTextField(query,vm::query,label={Text("Suchen")},modifier=Modifier.fillMaxWidth(),singleLine=true)
  ChoiceRow(listOf("" to "Alle","it-IT" to "IT","es-ES" to "ES","en-GB" to "EN"),filter,vm::filter)
  val visible=lessons.filter { it.title.contains(query,true) && (filter.isEmpty() || it.language==filter) }
  if(visible.isEmpty())Text("Keine Lektionen gefunden.")
  visible.forEach { l->
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
    Column(Modifier.weight(1f).clickable { detail(l.id) }.padding(vertical=16.dp)) {
     Text(l.title,style=MaterialTheme.typography.titleMedium)
     Text(listOfNotNull(if(l.id.startsWith("example"))"Beispiel" else null,l.language,if(l.measuredMs>0)time(l.measuredMs) else "Audio fehlt",if(l.id in offline)"Offline" else null).joinToString(" · "),style=MaterialTheme.typography.bodySmall)
    }
    TextButton({vm.play(l.id)},Modifier.heightIn(min=48.dp)) { Text("Play") }
   };HorizontalDivider()
  }
 }
}
@Composable fun LessonDetailScreen(id:String,vm:VociViewModel,back:()->Unit) {
 var lesson by remember(id) { mutableStateOf<LessonContent?>(null) };var deleting by remember { mutableStateOf(false) }
 val marks by vm.libraryUncertain.collectAsStateWithLifecycle();val error by vm.error.collectAsStateWithLifecycle()
 LaunchedEffect(id,vm) { lesson=vm.libraryLesson(id) }
 Page(lesson?.title?:"Lektion",back) {
  InlineError(error,action={vm.error.value=null})
  lesson?.let { l->
   if(l.example)Text("Beispiel · ${l.language}")
   VociButton("Abspielen",{vm.play(id)},modifier=Modifier.fillMaxWidth())
   l.items.forEach { v->
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
     Text(v.target,style=MaterialTheme.typography.titleMedium)
     Text(v.translation)
     Text(v.example,style=MaterialTheme.typography.bodySmall)
     Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
      // Measure the wrapped label at the full inner width, including its multiline height.
      TextButton({vm.playItem(id,v.id)},Modifier.weight(1f).heightIn(min=48.dp).height(IntrinsicSize.Min).semantics { contentDescription="Eintrag abspielen: ${v.target}" }) {
       Text("Eintrag abspielen",Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.Center,softWrap=true)
      }
      UncertainStar(v.target,v.id in marks[id].orEmpty(),enabled=marks.containsKey(id)) { vm.toggleUncertain(id,v.id) }
     }
    };HorizontalDivider()
   }
   TextButton({deleting=true}) { Text("Lektion löschen") }
  }
 }
 if(deleting)AlertDialog(onDismissRequest={deleting=false},text={Text("Lektion löschen?")},confirmButton={TextButton({vm.deleteLesson(id);back()}) { Text("Löschen") }},dismissButton={TextButton({deleting=false}) { Text("Abbrechen") }})
}
