package de.voci
import de.voci.auth.*
import de.voci.domain.ProviderState
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RecoveryRotationTest {
 @Test fun delayedRecoveryCannotReplaceDurablyRotatedCredentials()=runBlocking {
  val enteredA=CountDownLatch(1);val enteredB=CountDownLatch(1);val releaseA=CountDownLatch(1);val releaseB=CountDownLatch(1);val reads=AtomicInteger()
  val record=Registration("oaiapp_fixture","fixture","Fixture","synthetic-access","synthetic-R1",scopes=setOf("resource.invoke","chatgpt.tokens.use.direct"),expiresAt=0)
  val store=object:CredentialStore {
   @Volatile var saved=VaultState(accounts=listOf(record),active=record.client)
   override fun read():VaultState { val n=reads.getAndIncrement();if(n==0)error("unreadable fixture");val copy=saved
    if(n==1) { enteredA.countDown();check(releaseA.await(10,TimeUnit.SECONDS)) } else if(n==2) { enteredB.countDown();check(releaseB.await(10,TimeUnit.SECONDS)) };return copy }
   override fun write(state:VaultState) { saved=state }
  }
  val rotations=AtomicInteger()
  val http=OkHttpClient.Builder().addInterceptor { chain->
   val number=rotations.incrementAndGet();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture").code(if(number==1)200 else 400).body((if(number==1)"""{"token_type":"Bearer","access_token":"synthetic-A2","refresh_token":"synthetic-R2","expires_in":3600}""" else """{"error":"invalid_grant"}""").toResponseBody()).build()
  }.build()
  val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  try {
   val auth=AuthManager(null,http,owner,store);assertTrue(auth.storageUnavailable.value)
   val a=auth.retryStorage();assertTrue(enteredA.await(5,TimeUnit.SECONDS))
   val b=auth.retryStorage();assertTrue(enteredB.await(5,TimeUnit.SECONDS))
   releaseA.countDown();a.join();assertEquals(ProviderState.PLAN_AUTHORIZED,auth.state.value)
   assertEquals("synthetic-A2",auth.accessToken());assertEquals("synthetic-R2",store.saved.accounts.single().refresh)
   releaseB.countDown();b.join()
   assertEquals("synthetic-A2",auth.accessToken());assertEquals(1,rotations.get());assertEquals("synthetic-R2",store.saved.accounts.single().refresh)
  } finally { releaseA.countDown();releaseB.countDown();owner.cancel();http.dispatcher.cancelAll() }
 }
}
