package de.voci.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import de.voci.VociViewModel
import de.voci.domain.ProviderState
import de.voci.ui.components.*

@Composable fun SettingsScreen(open:(String)->Unit) {
 Page("Einstellungen") { listOf("account" to "Konto","audio" to "Audio","storage" to "Speicher","privacy" to "Datenschutz","licenses" to "Lizenzen").forEach { (id,name)->SettingRow(name,click={open(id)}) } }
}
@Composable fun AccountScreen(vm:VociViewModel,back:()->Unit,auth:de.voci.auth.AuthManager=vm.app.auth) {
 val state by auth.state.collectAsStateWithLifecycle();val accounts by auth.accounts.collectAsStateWithLifecycle();val label by auth.activeLabel.collectAsStateWithLifecycle();val message by auth.message.collectAsStateWithLifecycle();val models by vm.models.collectAsStateWithLifecycle();val selected by vm.selectedModel.collectAsStateWithLifecycle()
 val prefs by vm.prefs.collectAsStateWithLifecycle();val migrationError by vm.error.collectAsStateWithLifecycle()
 val unavailable by auth.storageUnavailable.collectAsStateWithLifecycle()
 if(unavailable) { Page("Konto",back) { InlineError(message,"Erneut prüfen",auth::retryStorage) };return }
 Page("Konto",back) {
  if(prefs.provider!="chatgpt") {
   Text("Neue Lektionen werden mit ChatGPT erstellt. Dabei gehen Situation und Sprache an OpenAI. Gespeicherte Lektionen bleiben ohne Anmeldung verfügbar.")
   VociButton("ChatGPT für neue Lektionen verwenden",{vm.confirmChatGpt()})
   InlineError(migrationError,"Schliessen",{vm.error.value=null})
  }
  Text(when(state) { ProviderState.SIGNED_OUT->"Abgemeldet";ProviderState.SIGNING_IN->"Anmeldung läuft";ProviderState.IDENTITY_VERIFIED->"Identität bestätigt · Plan-Freigabe fehlt";ProviderState.PLAN_AUTHORIZED->"Plan freigegeben · Anfrage noch ungeprüft";ProviderState.READY->"ChatGPT verbunden";ProviderState.LIMIT->"Limit erreicht";ProviderState.DISCONNECTED->"Verbindung unterbrochen" })
  if(label.isNotEmpty())Text(label)
  accounts.forEach { a->SettingRow(a.label,click={auth.select(a.client)}) }
  VociButton("Continue with ChatGPT",{auth.signIn(accounts.find { it.label==label }?.client)},state!=ProviderState.SIGNING_IN,Modifier.fillMaxWidth())
  if(accounts.isNotEmpty())TextButton({auth.signIn()}) { Text("Weiteres Konto") }
  if(state==ProviderState.SIGNING_IN)TextButton(auth::cancelLogin) { Text("Anmeldung abbrechen") }
  if(state==ProviderState.IDENTITY_VERIFIED)TextButton({auth.signIn(accounts.find { it.label==label }?.client,true)}) { Text("Plan-Nutzung freigeben") }
  if(state!=ProviderState.SIGNED_OUT && state!=ProviderState.SIGNING_IN) {
   TextButton(vm::loadModels,enabled=prefs.provider=="chatgpt") { Text("Modelle laden") }
   Column(Modifier.selectableGroup()) {
    models.forEach { m->
     Row(Modifier.fillMaxWidth().heightIn(min=56.dp)
      .selectable(selected==m.slug,role=Role.RadioButton,onClick={vm.model(m.slug)})
      .semantics { text=AnnotatedString(m.label) }
      .padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
      RadioButton(selected==m.slug,onClick=null)
      // The selectable row owns the visible name in both semantics trees; do not announce it twice.
      Text(m.label,Modifier.weight(1f).padding(start=16.dp).clearAndSetSemantics {})
     }
    }
   }
   TextButton(auth::logout) { Text("Abmelden") }
  }
  TextButton({vm.openUrl("https://chatgpt.com/settings/usage")}) { Text("Nutzung verwalten") }
  InlineError(message,"Schliessen",{auth.message.value=null})
 }
}
@Composable fun AudioSettingsScreen(vm:VociViewModel,back:()->Unit) {
 val p by vm.prefs.collectAsStateWithLifecycle()
 Page("Audio",back) {
  Text("Geschwindigkeit");ChoiceRow(listOf("0.85" to "0,85×","1.0" to "1×","1.15" to "1,15×"),p.speed.toString()) { vm.speed(it.toFloat()) }
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text("Interaktive Übung",Modifier.weight(1f));Switch(p.recall,{vm.bool("recall",it)},Modifier.semantics { contentDescription="Interaktive Übung umschalten" }) }
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text("Bewegung reduzieren",Modifier.weight(1f));Switch(p.reduced,{vm.bool("reduced",it)},Modifier.semantics { contentDescription="Reduzierte Bewegung umschalten" }) }
  SettingRow("Sprachdaten installieren",click=vm::voiceSettings)
 }
}
/** Compatibility destination for a restored pre-R2 back stack; no provider or model actions. */
@Composable fun RetiredGenerationScreen(back:()->Unit) {
 Page("Einstellungen",back) { Text("Lokale Erstellung ist in dieser Version nicht verfügbar. Gespeicherte Lektionen bleiben erhalten.") }
}
@Composable fun StorageScreen(vm:VociViewModel,back:()->Unit) {
 val bytes by vm.storageBytes.collectAsStateWithLifecycle();LaunchedEffect(Unit) { vm.refreshStorage() }
 Page("Speicher",back) { Text("Audiodateien: ${bytes/1024} KiB");VociButton("Ungespeichertes Audio löschen",vm::clearCache) }
}
@Composable fun PrivacyScreen(back:()->Unit) {
 Page("Datenschutz",back) {
  Text("Lektionen, Fortschritt und Audio bleiben auf diesem Gerät. Neue ChatGPT-Lektionen senden Situation und Sprache nach deiner Anmeldung an OpenAI. Android-Systemstimmen werden nur ohne Netzwerkbedarf verwendet.")
  Text("Gespeicherte Audiodateien sind offline abspielbar. Diese Version erstellt keine Lektionen mit lokaler KI. Kein automatischer Anbieterwechsel, keine Analyse- oder Werbedienste, kein Mikrofon.")
  Text("Zugangsdaten sind durch Android Keystore geschützt und von Backups ausgeschlossen. Abmelden entfernt lokale Tokens und versucht, die erneuerbare Sitzung zu widerrufen.")
 }
}
