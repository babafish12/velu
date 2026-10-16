package de.voci

import androidx.datastore.preferences.core.*
import de.voci.ai.GenerationAccess
import de.voci.data.PreferencesStore
import de.voci.ai.ProviderFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real Preferences DataStore files; no Android mock preferences and no network. */
class PreferenceMigrationTest {
 @get:Rule val temp=TemporaryFolder()
 private fun legacy(value:String)=runBlocking {
  val file=temp.newFolder().resolve("settings.preferences_pb")
  var scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  var raw=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
  val original=preferencesOf(stringPreferencesKey("provider") to value,
   stringPreferencesKey("server") to "https://192.0.2.10:1234",
   stringPreferencesKey("modelPath") to "/private/models/old.gguf",
   stringPreferencesKey("modelHash") to "a".repeat(64),
   stringPreferencesKey("future-key") to "preserve",intPreferencesKey("minutes") to 5,
   booleanPreferencesKey("recall") to true,floatPreferencesKey("speed") to .85f)
  try {
   raw.updateData { original }
   val store=PreferencesStore(raw)
   assertEquals(value,store.flow.first().provider)
   assertEquals("provider_confirmation",(runCatching { GenerationAccess.requireChatGpt(store.flow.first().provider) }.exceptionOrNull() as? ProviderFailure)?.kind)
   assertEquals(original,raw.data.first()) // Reading/gating does not migrate or erase anything.
   store.confirmChatGpt()
   assertEquals(original.toMutablePreferences().apply { this[stringPreferencesKey("provider")]="chatgpt" }.toPreferences(),raw.data.first())
   store.confirmChatGpt() // Idempotent explicit confirmation.
   scope.coroutineContext[Job]!!.cancelAndJoin()
   scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
   raw=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
   val reopened=PreferencesStore(raw).flow.first()
   assertEquals("chatgpt",reopened.provider);assertEquals("/private/models/old.gguf",reopened.modelPath)
   assertEquals("a".repeat(64),reopened.modelHash);assertEquals("https://192.0.2.10:1234",reopened.server)
   assertEquals(5,reopened.minutes);assertTrue(reopened.recall);assertEquals(.85f,reopened.speed)
   assertEquals("preserve",raw.data.first()[stringPreferencesKey("future-key")])
  } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
 }
 @Test fun localPreferencesPreservedUntilExplicitConfirmation() { legacy("local") }
 @Test fun devicePreferencesPreservedUntilExplicitConfirmation() { legacy("device") }
 @Test fun serverPreferencesPreservedUntilExplicitConfirmation() { legacy("server") }
 @Test fun unknownPreferencesPreservedUntilExplicitConfirmation() { legacy("future-provider") }
 @Test fun emptyPreferencesPreservedUntilExplicitConfirmation() { legacy("") }
 @Test fun freshFileDefaultsToChatGptWithoutWritingOtherKeys()=runBlocking {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  try {
   val raw=PreferenceDataStoreFactory.create(scope=scope,produceFile={temp.root.resolve("fresh.preferences_pb")})
   assertEquals("chatgpt",PreferencesStore(raw).flow.first().provider);assertTrue(raw.data.first().asMap().isEmpty())
  } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
 }
 @Test fun missingProviderWithLegacyConfigurationFailsClosed()=runBlocking {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  try {
   val raw=PreferenceDataStoreFactory.create(scope=scope,produceFile={temp.root.resolve("legacy.preferences_pb")})
   raw.edit { it[stringPreferencesKey("modelPath")]="/private/models/old.gguf" }
   val original=raw.data.first()
   val p=PreferencesStore(raw).flow.first()
   assertEquals("provider_confirmation",(runCatching { GenerationAccess.requireChatGpt(p.provider) }.exceptionOrNull() as? ProviderFailure)?.kind)
   assertEquals(original,raw.data.first())
  } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
 }
}
