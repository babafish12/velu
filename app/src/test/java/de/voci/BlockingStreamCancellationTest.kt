package de.voci
import de.voci.auth.*
import de.voci.ai.*
import de.voci.domain.SessionSpec
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual bounded local HTTP socket and real provider request path, with synthetic OAuth credentials. */
class BlockingStreamCancellationTest {
 @Test fun cancellingGenerationClosesBlockedResponseBodyPromptly()=runBlocking {
  val server=MockWebServer();server.start()
  server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
   override fun dispatch(request:RecordedRequest):MockResponse=if(request.path=="/v1/models")MockResponse().setBody("""{"models":[{"slug":"fixture","display_name":"Fixture","visibility":"list"}]}""") else MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: "+"x".repeat(2000)).throttleBody(1,1,TimeUnit.SECONDS)
  }
  val stream=AtomicReference<Call>()
  val reading=java.util.concurrent.CountDownLatch(1)
  val http=OkHttpClient.Builder().eventListener(object:EventListener() { override fun responseBodyStart(call:Call) { if(call.request().url.encodedPath=="/v1/responses")reading.countDown() }; override fun callStart(call:Call) { println("fixture start ${call.request().url.encodedPath}") }; override fun canceled(call:Call) { println("fixture canceled ${call.request().url.encodedPath}") }; override fun responseHeadersEnd(call:Call,response:Response) { println("fixture headers ${call.request().url.encodedPath} ${response.code}") };override fun callFailed(call:Call,ioe:java.io.IOException) { println("fixture failed ${call.request().url.encodedPath} $ioe") } }).readTimeout(30,TimeUnit.SECONDS).addInterceptor { chain->
   if(chain.request().url.encodedPath=="/v1/responses")stream.set(chain.call())
   chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
  }.build()
  val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  val store=object:CredentialStore {
   var data=VaultState(accounts=listOf(Registration("oaiapp_A","A","A","synthetic","",scopes=setOf("resource.invoke","chatgpt.tokens.use.direct"),expiresAt=Long.MAX_VALUE)),active="oaiapp_A")
   override fun read()=data
   override fun write(state:VaultState) { data=state }
  }
  try {
   val auth=AuthManager(null,http,owner,store)
   val generation=async(Dispatchers.IO) { ChatGptProvider(http,auth).generate(SessionSpec("Restaurant")) }
   withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));assertNotNull(server.takeRequest(5,TimeUnit.SECONDS)) }
   withContext(Dispatchers.IO) { assertTrue("Actual response body read must start",reading.await(5,TimeUnit.SECONDS)) };generation.cancel()
   withTimeout(2500) { generation.join() }
   assertTrue("Cancellation must close the actual socket Call",stream.get().isCanceled())
  } finally { stream.get()?.cancel();owner.cancel();http.dispatcher.cancelAll();server.shutdown() }
 }
}
