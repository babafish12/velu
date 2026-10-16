package de.voci

import de.voci.auth.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** R1: an unrelated local request must not terminate a legitimate browser login. */
class CallbackIsolationTest {
 private val valid="http://127.0.0.1:1234/auth/callback?state=secret&code=ok&client_id=oaiapp_test"
 private fun attempt(client:String?=null)=OAuthAttempt("secret","nonce","pkce","http://127.0.0.1:1234/auth/callback",1000,client)
 private fun rejected(block:()->Unit) { try { block();fail("Expected rejection") } catch(_:IllegalArgumentException) {} }
 @Test fun invalidRequestsPreservePendingAttempt() {
  val invalid=listOf(valid.replace("state=secret","state=wrong"),"$valid&state=secret",valid.replace("/auth/callback","/wrong"),valid.replace("127.0.0.1","localhost"),valid.replace(":1234",":1235"),"$valid#fragment",valid.replace("code=ok","code="),valid.replace("oaiapp_test","dynamic_agent_client"),valid.substringBefore("&client_id"),valid.replace("state=secret","state=%ZZ"))
  for(bad in invalid) { val a=attempt();rejected { a.consume(bad,0) };assertEquals("ok" to "oaiapp_test",a.consume(valid,1)) }
 }
 @Test fun wrongReturningClientPreservesPendingAttempt() { val a=attempt("oaiapp_test");rejected { a.consume(valid.replace("oaiapp_test","oaiapp_other"),0) };assertEquals("ok",a.consume(valid,1).first) }
 @Test fun matchingCancellationIsTerminal() { val a=attempt();rejected { a.consume(valid.substringBefore("&code")+"&error=access_denied",0) };rejected { a.consume(valid,1) } }
 @Test fun expirationIsTerminal() { val a=attempt();rejected { a.consume(valid,1000) };rejected { a.consume(valid,1001) } }
 @Test fun acceptedCodeCannotBeReplayed() { val a=attempt();a.consume(valid,0);rejected { a.consume(valid,1) } }
 private fun listenerScenario(before:(Int)->Unit) {
  val listener=Loopback();val port=URI(listener.redirect).port;val executor=Executors.newSingleThreadExecutor()
  val a=OAuthAttempt("secret","n","v",listener.redirect,System.currentTimeMillis()+10000)
  try {
   val result=executor.submit<Pair<String,String>> { listener.use { it.await(a) } }
   before(port)
   assertTrue(request(port,"secret").contains("200"))
   assertEquals("ok" to "oaiapp_test",result.get(5,TimeUnit.SECONDS))
  } finally { listener.close();executor.shutdownNow() }
 }
 private fun request(port:Int,state:String):String=Socket("127.0.0.1",port).use { socket->
  socket.soTimeout=5000;socket.getOutputStream().write("GET /auth/callback?state=$state&code=ok&client_id=oaiapp_test HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray());socket.getInputStream().bufferedReader().readLine().orEmpty()
 }
 @Test fun listenerRejectsWrongStateThenAcceptsValidCode()=listenerScenario { port->assertTrue(request(port,"wrong").contains("400")) }
 @Test fun listenerSurvivesPeerClosingPartialHeader()=listenerScenario { port->Socket("127.0.0.1",port).use { it.getOutputStream().write("GET /auth/callback?state=secret".toByteArray()) } }
 @Test fun listenerSurvivesIdleSocketTimeout()=listenerScenario { port->Socket("127.0.0.1",port).use { it.soTimeout=4000;runCatching { it.getInputStream().read() } } }
 @Test fun cancellationClosesAnAcceptedIdlePeerPromptly() {
  val listener=Loopback();val executor=Executors.newSingleThreadExecutor();val a=OAuthAttempt("secret","n","v",listener.redirect,System.currentTimeMillis()+10000)
  try {
   val result=executor.submit<Pair<String,String>> { listener.use { it.await(a) } }
   Socket("127.0.0.1",URI(listener.redirect).port).use { socket->
    socket.getOutputStream().write("GET /auth/callback".toByteArray());Thread.sleep(100);listener.close()
    try { result.get(1,TimeUnit.SECONDS);fail("Cancelled listener must not accept") } catch(_:java.util.concurrent.ExecutionException) {}
   }
  } finally { listener.close();executor.shutdownNow() }
 }
 @Test fun matchingCancellationTerminatesListener() {
  val listener=Loopback();val executor=Executors.newSingleThreadExecutor();val a=OAuthAttempt("secret","n","v",listener.redirect,System.currentTimeMillis()+10000)
  try {
   val result=executor.submit<Pair<String,String>> { listener.use { it.await(a) } }
   assertTrue(request(URI(listener.redirect).port,"secret&error=access_denied").contains("200"))
   try { result.get(1,TimeUnit.SECONDS);fail("Cancellation is terminal") } catch(e:java.util.concurrent.ExecutionException) { assertTrue(e.cause is IllegalArgumentException) }
   assertTrue(a.consumed)
  } finally { listener.close();executor.shutdownNow() }
 }

}
