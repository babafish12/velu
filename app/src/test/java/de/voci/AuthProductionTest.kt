package de.voci

import de.voci.auth.*
import de.voci.ai.*
import de.voci.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AuthProductionTest {
 class Store(var data:VaultState):CredentialStore {
  var fail=false
  override fun read()=data
  override fun write(state:VaultState) { if(fail)error("injected disk failure");data=state }
 }
 private fun account(id:String,expired:Boolean=false)=Registration("oaiapp_$id",id,id,"access-$id","R1-$id",scopes=setOf("resource.invoke","chatgpt.tokens.use.direct"),expiresAt=if(expired)0 else Long.MAX_VALUE)
 private fun store(expired:Boolean=false)=Store(VaultState(accounts=listOf(account("A",expired),account("B")),active="oaiapp_A"))
 private class Transport(val block:(Request)->Pair<Int,String>):Interceptor {
  val requests=java.util.concurrent.CopyOnWriteArrayList<Request>()
  override fun intercept(chain:Interceptor.Chain):Response { val r=chain.request();requests+=r;val (code,body)=block(r);return Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build() }
 }
 private val catalog="""{"models":[{"slug":"fixture","display_name":"Fixture","visibility":"list"}]}"""
 private val rotation="""{"token_type":"Bearer","access_token":"A2","refresh_token":"R2-A","expires_in":3600}"""
 private suspend fun reached(l:CountDownLatch) { withContext(Dispatchers.Default) { check(l.await(5,TimeUnit.SECONDS)) { "Transport barrier not reached" } } }
 @Test fun queuedSelectionCannotUndoLogout()=runTest {
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val t=Transport { if(it.url.encodedPath.endsWith("/token")) { entered.countDown();check(release.await(5,TimeUnit.SECONDS));200 to rotation } else 500 to "{}" }
  val s=store(true);val auth=AuthManager(null,OkHttpClient.Builder().addInterceptor(t).build(),backgroundScope,s)
  val refresh=backgroundScope.launch { runCatching { auth.accessToken() } };runCurrent();reached(entered)
  auth.select("oaiapp_B");runCurrent();auth.logout();runCurrent();release.countDown();refresh.join();runCurrent()
  assertEquals(ProviderState.SIGNED_OUT,auth.state.value)
  assertTrue(runCatching { auth.accessToken() }.isFailure)
 }
 @Test fun retainedSignedOutRegistrationNeverBecomesIdentityVerified()=runTest {
  val s=store();val t=Transport { 500 to "{}" };val auth=AuthManager(null,OkHttpClient.Builder().addInterceptor(t).build(),backgroundScope,s)
  auth.logout();runCurrent();runCatching { ChatGptProvider(OkHttpClient.Builder().addInterceptor(t).build(),auth).generate(SessionSpec("Restaurant")) }
  assertEquals(ProviderState.SIGNED_OUT,auth.state.value)
  assertTrue(t.requests.none { it.url.host=="api.openai.com" })
 }
 @Test fun oldCatalogCannotPostUsingNewAccount()=runTest {
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val t=Transport { if(it.url.encodedPath=="/v1/models") { entered.countDown();check(release.await(5,TimeUnit.SECONDS));200 to catalog } else 500 to "{}" }
  val http=OkHttpClient.Builder().addInterceptor(t).build();val auth=AuthManager(null,http,backgroundScope,store())
  val job=backgroundScope.launch { runCatching { ChatGptProvider(http,auth).generate(SessionSpec("Restaurant")) } };runCurrent();reached(entered)
  auth.select("oaiapp_B");runCurrent();release.countDown();job.join()
  assertEquals(0,t.requests.count { it.url.encodedPath=="/v1/responses" })
  assertEquals(ProviderState.PLAN_AUTHORIZED,auth.state.value)
 }
 @Test fun staleCatalogErrorCannotChangeNewAccountState()=runTest {
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val t=Transport { entered.countDown();check(release.await(5,TimeUnit.SECONDS));429 to "{}" }
  val http=OkHttpClient.Builder().addInterceptor(t).build();val auth=AuthManager(null,http,backgroundScope,store())
  val job=backgroundScope.launch { runCatching { ChatGptProvider(http,auth).models() } };runCurrent();reached(entered)
  auth.select("oaiapp_B");runCurrent();release.countDown();job.join()
  assertEquals(ProviderState.PLAN_AUTHORIZED,auth.state.value)
 }
 @Test fun rotationSurvivesCallerCancellation()=runTest {
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val t=Transport { entered.countDown();check(release.await(5,TimeUnit.SECONDS));200 to rotation }
  val s=store(true);val auth=AuthManager(null,OkHttpClient.Builder().addInterceptor(t).build(),backgroundScope,s)
  val job=backgroundScope.launch { auth.accessToken() };runCurrent();reached(entered);job.cancel();release.countDown();job.join()
  withContext(Dispatchers.Default) { delay(100) };runCurrent()
  assertEquals("R2-A",s.data.accounts.first().refresh)
 }
 @Test fun rotationSurvivesAccountSwitch()=runTest {
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val t=Transport { entered.countDown();check(release.await(5,TimeUnit.SECONDS));200 to rotation }
  val s=store(true);val auth=AuthManager(null,OkHttpClient.Builder().addInterceptor(t).build(),backgroundScope,s)
  val job=backgroundScope.launch { runCatching { auth.accessToken() } };runCurrent();reached(entered);auth.select("oaiapp_B");runCurrent();release.countDown();job.join();runCurrent()
  assertEquals("R2-A",s.data.accounts.first().refresh)
  assertEquals("B",auth.activeLabel.value)
 }
 @Test fun failedDurableLogoutNeverClaimsSuccessAcrossRestart()=runTest {
  val s=store();s.fail=true
  val failures=mutableListOf<Throwable>()
  val owner=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler)+CoroutineExceptionHandler { _,e->failures+=e })
  val http=OkHttpClient.Builder().addInterceptor(Transport { 500 to "{}" }).build()
  val auth=AuthManager(null,http,owner,s);auth.logout();runCurrent()
  val restarted=AuthManager(null,http,backgroundScope,s)
  assertFalse("No successful logout with reusable persisted credentials",auth.state.value==ProviderState.SIGNED_OUT && runCatching { restarted.accessToken() }.isSuccess)
  assertTrue(auth.message.value.orEmpty().contains("nicht gespeichert"))
  runCatching { ChatGptProvider(http,auth).generate(SessionSpec("Restaurant")) }
  assertEquals("Failed durable logout remains visibly unavailable",ProviderState.DISCONNECTED,auth.state.value)
  assertTrue(failures.isEmpty());owner.cancel()
 }
 @Test fun allocationCancelledBeforeReturnClosesActualSocket()=runTest {
  var allocated:Loopback?=null
  lateinit var auth:AuthManager
  auth=AuthManager(null,OkHttpClient(),backgroundScope,store(),StandardTestDispatcher(testScheduler)) {
   Loopback().also { allocated=it;auth.cancelLogin() }
  }
  auth.signIn();runCurrent()
  val listener=requireNotNull(allocated)
  try { assertTrue("Allocated listener leaked",runCatching { java.net.Socket("127.0.0.1",java.net.URI(listener.redirect).port).close() }.isFailure) }
  finally { listener.close() }
 }
 @Test fun logoutDuringRotationCannotRestoreCredentials()=runTest {
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val t=Transport { if(it.url.encodedPath.endsWith("/token")) { entered.countDown();check(release.await(5,TimeUnit.SECONDS));200 to rotation } else 500 to "{}" }
  val s=store(true);val http=OkHttpClient.Builder().addInterceptor(t).build();val auth=AuthManager(null,http,backgroundScope,s)
  val job=backgroundScope.launch { runCatching { auth.accessToken() } };runCurrent();reached(entered);auth.logout();release.countDown();job.join()
  assertEquals("",s.data.accounts.first().refresh);assertEquals(ProviderState.SIGNED_OUT,auth.state.value)
  assertEquals(ProviderState.SIGNED_OUT,AuthManager(null,http,backgroundScope,s).state.value)
 }
 @Test fun invalidGrantRetainsRegistrationAndStaysSignedOut()=runTest {
  val s=store(true);val t=Transport { 400 to "{\"error\":\"invalid_grant\"}" };val http=OkHttpClient.Builder().addInterceptor(t).build()
  val auth=AuthManager(null,http,backgroundScope,s)
  runCatching { auth.accessToken() };runCatching { ChatGptProvider(http,auth).generate(SessionSpec("Restaurant")) }
  assertEquals(ProviderState.SIGNED_OUT,auth.state.value);assertEquals(2,s.data.accounts.size)
  assertEquals("access-B",s.data.accounts.last().access);assertTrue(t.requests.none { it.url.host=="api.openai.com" })
 }
 @Test fun providerRejectsCompletedMissingContractsForAllBudgets()=runTest {
  for(minutes in listOf(1,3,5)) {
   val n=when(minutes) { 1->4;3->7;else->11 }
   val items=(1..n).map { VocabItem("v$it","Frase $it","Bedeutung $it","Frase lunga $it","Längerer Satz $it") }
   val base=LessonContent("fixture","Restaurant","it-IT",items,listOf(DialogueTurn("d1",0,"Buongiorno","Guten Tag"),DialogueTurn("d2",1,"Buonasera","Guten Abend")),listOf(RecognitionQuestion("q1","v1","Uno",listOf("Eins","Zwei"),0),RecognitionQuestion("q2","v2","Due",listOf("Zwei","Drei"),0)))
   for(lesson in listOf(base.copy(items=items.take(1),questions=emptyList()),base.copy(dialogue=emptyList()),base.copy(questions=emptyList()),base.copy(language="es-ES"))) {
    val output=Json.encodeToString(lesson)
    val stream="data: {\"type\":\"response.output_text.delta\",\"delta\":${Json.encodeToString(output)}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
    val t=Transport { if(it.url.encodedPath.endsWith("models"))200 to catalog else 200 to stream };val http=OkHttpClient.Builder().addInterceptor(t).build()
    val auth=AuthManager(null,http,backgroundScope,store())
    assertTrue(runCatching { ChatGptProvider(http,auth).generate(SessionSpec("Restaurant",minutes=minutes,recall=true)) }.isFailure)
    assertEquals(2,t.requests.size);assertTrue(t.requests.all { it.header("Authorization")=="Bearer access-A" })
   }
  }
 }
 @Test fun completedGeneratedLessonRequiresAllRequestedComponents() {
  val item=VocabItem("v1","Il conto","Die Rechnung","Il conto, per favore.","Die Rechnung, bitte.")
  val incomplete=LessonContent("lesson","Restaurant","it-IT",listOf(item))
  val terminal=listOf("data: "+"""{"type":"response.output_text.delta","delta":${Json.encodeToString(Json.encodeToString(incomplete))}}""", "", "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}", "")
  val output=ResponseStream.read(terminal.asSequence())
  assertTrue(runCatching { LessonPrompt.parse(output,SessionSpec("Restaurant",recall=true)) }.isFailure)
 }
}
