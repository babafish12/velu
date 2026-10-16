package de.voci.features.sprint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.voci.VociViewModel
import de.voci.ui.components.*
import de.voci.ui.theme.VociTokens

@Composable fun SprintScreen(vm:VociViewModel,account:()->Unit) {
 val auth by vm.app.auth.state.collectAsStateWithLifecycle()
 val diagnostics by vm.diagnostics.collectAsStateWithLifecycle();var details by remember { mutableStateOf(false) }
 val p by vm.prefs.collectAsStateWithLifecycle();val topic by vm.topic.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle();val error by vm.error.collectAsStateWithLifecycle()
 val generating by vm.generationBusy.collectAsStateWithLifecycle()
 val intro=remember(vm) { vm.claimBrandIntro() }
 val scroll=rememberScrollState()
 // Reveal work + cancel together, without an animated scroll or a clock-dependent wait.
 LaunchedEffect(generating) { if(generating)scroll.scrollTo(0) }
 Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal=VociTokens.gutter),
  verticalArrangement=Arrangement.spacedBy(16.dp)) {
  FlowRow(Modifier.fillMaxWidth().padding(top=16.dp),horizontalArrangement=Arrangement.SpaceBetween,
   verticalArrangement=Arrangement.spacedBy(8.dp)) {
   Text("Sprint",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.semantics { heading() })
   BrandHeader(intro)
  }
  if(generating)GenerationProgress(vm::cancel)
  else if(busy)TextButton(vm::cancel) { Text("Abbrechen") }
  OutlinedTextField(topic,vm::topic,label={Text("Situation")},placeholder={Text("Zum Beispiel: im Restaurant")},modifier=Modifier.fillMaxWidth(),maxLines=3)
  Text("Sprache",style=MaterialTheme.typography.labelLarge);LanguageSelector(p.language,vm::language)
  Text("Inhaltsmenge",style=MaterialTheme.typography.labelLarge);ContentAmountSelector(p.entryCount,vm::entryCount)
  Text("Neue Wörter und Wendungen",style=MaterialTheme.typography.bodySmall)
  VociButton("Starten",{vm.start()},!busy && topic.isNotBlank(),Modifier.fillMaxWidth())
  if(auth==de.voci.domain.ProviderState.SIGNED_OUT) {
   Text("Nicht bei ChatGPT angemeldet.")
   TextButton(account) { Text("Bei ChatGPT anmelden") }
  }
  InlineError(error,"Konto öffnen",account)
  if(error!=null && diagnostics!=null)TextButton({details=true}) { Text("Details") }
  Spacer(Modifier.height(24.dp))
 }
 if(details)AlertDialog(onDismissRequest={details=false},text={Text(diagnostics.orEmpty())},confirmButton={TextButton({details=false}) { Text("Schliessen") }})
}
