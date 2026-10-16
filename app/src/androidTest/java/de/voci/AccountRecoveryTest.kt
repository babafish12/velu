package de.voci
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.auth.*
import de.voci.features.settings.AccountScreen
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import okhttp3.OkHttpClient
import java.io.File

class AccountRecoveryTest {
 @get:Rule val compose=createComposeRule()
 @Test fun corruptCiphertextIsPreservedAndAccountScreenCanRetry()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
  val directory=File(app.noBackupFilesDir,"r1-vault-fixture").apply { mkdirs() }
  val context=object:ContextWrapper(app) { override fun getNoBackupFilesDir()=directory }
  val file=File(directory,"session.vault");val corrupt=byteArrayOf(1,2,3);file.writeBytes(corrupt)
  val owner=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  try {
   val vault=SecureVault(context);val auth=AuthManager(context,OkHttpClient(),owner,vault)
   val vm=withContext(Dispatchers.Main) { VociViewModel(app,SavedStateHandle()) }
   compose.setContent { VociTheme(false) { AccountScreen(vm,{},auth) } }
   compose.onNodeWithText("Erneut prüfen").assertIsDisplayed();assertArrayEquals(corrupt,file.readBytes())
   // Repair only this synthetic fixture, equivalent to storage becoming readable; no app/user vault reset.
   vault.write(VaultState(accounts=listOf(Registration("oaiapp_fixture","fixture","Fixture"))))
   compose.onNodeWithText("Erneut prüfen").performClick()
   compose.waitUntil(5000) { compose.onAllNodesWithText("Continue with ChatGPT").fetchSemanticsNodes().isNotEmpty() }
   assertEquals("Fixture",vault.read().accounts.single().label)
  } finally { owner.cancel();directory.deleteRecursively() }
 }
}
