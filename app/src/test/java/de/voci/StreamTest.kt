package de.voci
import de.voci.ai.*
import org.junit.Assert.*
import org.junit.Test
class StreamTest {
 private val delta="data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"
 private val complete="data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}"
 @Test fun topLevelQuotaCodeRetained() {
  try { ResponseStream.read(sequenceOf("data: {\"type\":\"error\",\"code\":\"subscription_sharing_usage_limit_exceeded\"}",""));fail("Must fail") }
  catch(e:ProviderFailure) { assertEquals("subscription_sharing_usage_limit_exceeded",e.kind) }
 }
 @Test fun structuredAdmissionPreservesDiagnostics() {
  val e=ProviderErrors.http(403,"{\"error\":{\"code\":\"subscription_sharing_user_not_eligible\",\"param\":\"model\"}}","req_local")
  assertEquals("subscription_sharing_user_not_eligible",e.kind);assertEquals(403,e.httpStatus);assertEquals("req_local",e.requestId);assertEquals("model",e.param);assertEquals("error",e.bodyShape)
 }
 @Test fun detailOnlyAdmissionDoesNotPretendStableCode() { val e=ProviderErrors.http(503,"{\"detail\":\"temporary\"}",null);assertEquals("http_503",e.kind);assertEquals("detail",e.bodyShape) }
 @Test fun terminalSuccess() { assertEquals("hello",ResponseStream.read(sequenceOf(delta,"",complete,""))) }
 @Test(expected=ProviderFailure::class) fun truncated() { ResponseStream.read(sequenceOf(delta,"")) }
 @Test(expected=ProviderFailure::class) fun incomplete() { ResponseStream.read(sequenceOf("data: {\"type\":\"response.incomplete\"}","")) }
 @Test(expected=ProviderFailure::class) fun quotaAfterDelta() { ResponseStream.read(sequenceOf(delta,"","data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}","")) }
 @Test(expected=ProviderFailure::class) fun emptyCompleted() { ResponseStream.read(sequenceOf(complete,"")) }
}
