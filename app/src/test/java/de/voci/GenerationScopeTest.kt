package de.voci

import de.voci.ai.*
import de.voci.data.Examples
import de.voci.data.Preferences
import de.voci.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GenerationScopeTest {
 private fun blocked(value:String)=runBlocking {
  var requests=0
  val provider=object:LessonProvider { override suspend fun generate(spec:SessionSpec):LessonContent { requests++;return Examples.lessons.first() } }
  val failure=runCatching { GenerationAccess.generate(value,provider,SessionSpec("Restaurant")) }.exceptionOrNull()
  assertEquals("Legacy preference must never reach online generation",0,requests)
  assertEquals("provider_confirmation",(failure as? ProviderFailure)?.kind)
 }
 @Test fun legacyLocalCannotSendOnlineRequest() { blocked("local") }
 @Test fun legacyDeviceCannotSendOnlineRequest() { blocked("device") }
 @Test fun legacyServerCannotSendOnlineRequest() { blocked("server") }
 @Test fun unknownProviderCannotSendOnlineRequest() { blocked("future-provider") }
 @Test fun emptyProviderCannotSendOnlineRequest() { blocked("") }
 @Test fun newInstallationExplicitlyUsesChatGpt()=runBlocking {
  var requests=0;val expected=Examples.lessons.first()
  val p=object:LessonProvider { override suspend fun generate(spec:SessionSpec):LessonContent { requests++;return expected } }
  assertEquals(expected,GenerationAccess.generate(Preferences().provider,p,SessionSpec("Restaurant")))
  assertEquals(1,requests)
 }
}
