package de.voci
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.auth.*
import de.voci.domain.ProviderState
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class VaultFailureTest {
 @Test fun atomicWriteFailureIsTruthfulAfterFreshManagerAndPreservesOtherAccount()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext
  val dir=File(app.noBackupFilesDir,"r1-atomic-vault").apply { mkdirs() }
  val context=object:ContextWrapper(app) { override fun getNoBackupFilesDir()=dir }
  val owner=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val http=OkHttpClient.Builder().addInterceptor { c->Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(500).message("local fixture").body("{}".toResponseBody()).build() }.build()
  try {
   val original=VaultState(accounts=listOf(Registration("oaiapp_A","A","A","synthetic-A","refresh-A",scopes=setOf("resource.invoke","chatgpt.tokens.use.direct"),expiresAt=Long.MAX_VALUE),Registration("oaiapp_B","B","B","synthetic-B")),active="oaiapp_A")
   SecureVault(context).write(original)
   val failing=SecureVault(context) { throw java.io.IOException("injected commit failure") }
   val auth=AuthManager(context,http,owner,failing)
   withContext(Dispatchers.Main) { auth.logout() }
   assertNotEquals(ProviderState.SIGNED_OUT,auth.state.value)
   assertTrue(auth.message.value.orEmpty().contains("nicht gespeichert"))
   val restored=SecureVault(context).read();assertEquals(original,restored)
   val fresh=AuthManager(context,http,owner,SecureVault(context))
   assertEquals(ProviderState.PLAN_AUTHORIZED,fresh.state.value)
   assertEquals("synthetic-B",restored.accounts.last().access)
  } finally { owner.cancel();dir.deleteRecursively() }
 }
}
