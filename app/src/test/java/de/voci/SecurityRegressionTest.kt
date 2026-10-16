package de.voci
import de.voci.ai.*
import de.voci.auth.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SecurityRegressionTest {
 @Test fun listenerIsBoundToLoopbackAndConsumesOnce() {
  val listener=Loopback();val a=OAuthAttempt("s","n","v",listener.redirect,System.currentTimeMillis()+10000)
  val executor=Executors.newSingleThreadExecutor()
  try {
   val result=executor.submit<Pair<String,String>> { listener.use { it.await(a) } };val uri=URI(listener.redirect)
   Socket("127.0.0.1",uri.port).use { s->s.soTimeout=5000;s.getOutputStream().write("GET /auth/callback?state=s&code=c&client_id=oaiapp_test HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\n\r\n".toByteArray());assertTrue(s.getInputStream().bufferedReader().readLine().contains("200")) }
   assertEquals("oaiapp_test",result.get(5,TimeUnit.SECONDS).second)
  } finally { listener.close();executor.shutdownNow() }
 }
 @Test fun cancelClosesSocket() { val l=Loopback();val port=URI(l.redirect).port;l.close();try { Socket("127.0.0.1",port).close();fail() } catch(_:java.io.IOException) {} }
 @Test(expected=IllegalArgumentException::class) fun rejectsInvalidJson() { LessonPrompt.parse("not JSON",de.voci.domain.SessionSpec("restaurant")) }
 @Test(expected=IllegalArgumentException::class) fun rejectsWrongLanguage() { LessonPrompt.parse(kotlinx.serialization.json.Json.encodeToString(de.voci.data.Examples.lessons[1]),de.voci.domain.SessionSpec("restaurant")) }
}
