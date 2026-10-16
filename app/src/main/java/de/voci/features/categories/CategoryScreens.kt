package de.voci.features.categories
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import de.voci.VociViewModel
import de.voci.ui.components.*

@Composable fun CategoriesScreen(vm:VociViewModel,open:(String)->Unit) {
 val categories by vm.categories.collectAsStateWithLifecycle();var adding by rememberSaveable { mutableStateOf(false) };var name by rememberSaveable { mutableStateOf("") }
 Page("Kategorien") {
  categories.forEach { c->SettingRow(c.name,click={open(c.id)}) }
  TextButton({adding=true}) { Text("Neue Kategorie") }
 }
 if(adding)AlertDialog(onDismissRequest={adding=false},title={Text("Neue Kategorie")},text={OutlinedTextField(name,{name=it.take(60)},label={Text("Name")})},confirmButton={TextButton({vm.newCategory(name);adding=false;name=""},enabled=name.isNotBlank()) { Text("Anlegen") }},dismissButton={TextButton({adding=false}) { Text("Abbrechen") }})
}
@Composable fun CategoryDetailScreen(id:String,vm:VociViewModel,back:()->Unit,lesson:(String)->Unit,sprint:()->Unit) {
 val categories by vm.categories.collectAsStateWithLifecycle();val lessons by vm.lessons.collectAsStateWithLifecycle();val c=categories.find { it.id==id }
 var rename by rememberSaveable { mutableStateOf(false) };var name by rememberSaveable { mutableStateOf("") };var deleting by rememberSaveable { mutableStateOf(false) }
 Page(c?.name?:"Kategorie",back) {
  val scenarios=when(id) { "restaurant"->listOf("Einen Tisch bestellen","Essen bestellen","Die Rechnung bezahlen");"sport"->listOf("Beim Fussball","Über das Spiel sprechen");"it"->listOf("Einen Fehler besprechen","Eine Änderung prüfen");"travel"->listOf("Am Bahnhof","Nach dem Weg fragen");"work"->listOf("Im Teammeeting","Einen Termin finden");else->listOf("Eine kurze Unterhaltung") }
  scenarios.forEach { topic->SettingRow(topic,click={vm.scenario(topic,id);sprint()}) }
  lessons.filter { it.categoryId==id }.forEach { l->SettingRow(l.title,if(l.id.startsWith("example"))"Beispiel" else null) { lesson(l.id) } }
  if(c?.custom==true) {
   TextButton({name=c.name;rename=true}) { Text("Umbenennen") }
   TextButton({deleting=true}) { Text("Kategorie löschen") }
  }
 }
 if(rename)AlertDialog(onDismissRequest={rename=false},title={Text("Umbenennen")},text={OutlinedTextField(name,{name=it.take(60)},label={Text("Name")})},confirmButton={TextButton({vm.renameCategory(id,name);rename=false},enabled=name.isNotBlank()) { Text("Speichern") }},dismissButton={TextButton({rename=false}) { Text("Abbrechen") }})
 if(deleting)AlertDialog(onDismissRequest={deleting=false},text={Text("Kategorie löschen? Lektionen bleiben erhalten.")},confirmButton={TextButton({vm.deleteCategory(id);deleting=false;back()}) { Text("Löschen") }},dismissButton={TextButton({deleting=false}) { Text("Abbrechen") }})
}
