package de.voci
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.*
import de.voci.navigation.VociNavigation
import de.voci.navigation.NavigationSchema
import de.voci.ui.theme.VociTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import de.voci.update.*
class MainActivity:ComponentActivity() {
 private lateinit var updater: UpdateViewModel
 private val updateSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
  updater.controller.settingsReturned()
 }
 private val updateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
  updater.controller.installerReturned()
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  // Reject legacy transient state BEFORE ComponentActivity creates saved-state owners.
  val current = savedInstanceState?.takeIf { NavigationSchema.accepts(it.getInt(NavigationSchema.KEY, -1)) }
  super.onCreate(current);enableEdgeToEdge(statusBarStyle=androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),navigationBarStyle=androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))
  updater = ViewModelProvider(this)[UpdateViewModel::class.java]
  setContent { val vm:VociViewModel=viewModel();val prefs by vm.prefs.collectAsState();VociTheme(prefs.reduced) { UpdateLayout(updater.controller) { VociNavigation(vm) } } }
 }
 override fun onResume() {
  super.onResume()
  updater.controller.resume(AndroidUpdateHost(this, { updateSettings.launch(it) }, { updateInstaller.launch(it) }))
 }
 override fun onPause() { updater.controller.pause(); super.onPause() }
 override fun onStop() { try { updater.controller.stop(isChangingConfigurations) } finally { super.onStop() } }
 override fun onSaveInstanceState(outState: Bundle) {
  super.onSaveInstanceState(outState)
  outState.putInt(NavigationSchema.KEY, NavigationSchema.CURRENT)
 }
}
