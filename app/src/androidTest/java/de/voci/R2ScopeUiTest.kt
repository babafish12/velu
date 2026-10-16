package de.voci

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.voci.features.settings.RetiredGenerationScreen
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compiled only by writer. Parent runs on the in-place updated APK. */
class R2ScopeUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun settingsHaveNoGenerationOrModelManagementEntry() {
  compose.onNodeWithText("Einstellungen").performClick()
  for(label in listOf("Lokale KI","Eigener Server","Auf diesem Gerät","Qwen herunterladen","Eigene GGUF-Datei importieren"))compose.onNodeWithText(label).assertDoesNotExist()
  compose.onNodeWithText("Konto").assertIsDisplayed();compose.onNodeWithText("Audio").assertIsDisplayed();compose.onNodeWithText("Speicher").assertIsDisplayed()
 }
 @Test fun accountRequiresExplicitMigrationAndRetainsLibrary()=runBlocking {
  val app=compose.activity.application as VociApp;val old=app.preferences.flow.first()
  try {
   app.preferences.string("provider","local");val before=app.db.dao().lessons().first()
   compose.onNodeWithText("Einstellungen").performClick();compose.onNodeWithText("Konto").performClick()
   compose.onNodeWithText("ChatGPT für neue Lektionen verwenden").performScrollTo().assertIsDisplayed()
   assertEquals("local",app.preferences.flow.first().provider)
   compose.onNodeWithText("ChatGPT für neue Lektionen verwenden").performClick()
   compose.waitUntil(5000) { runBlocking { app.preferences.flow.first().provider=="chatgpt" } }
   assertEquals(old.server,app.preferences.flow.first().server);assertEquals(old.modelPath,app.preferences.flow.first().modelPath);assertEquals(old.modelHash,app.preferences.flow.first().modelHash)
   assertEquals(before,app.db.dao().lessons().first())
   compose.onNodeWithText("ChatGPT für neue Lektionen verwenden").assertDoesNotExist()
  } finally { app.preferences.string("provider",old.provider) }
 }
 @Test fun legacyDestinationExplainsRemovalWithoutActions() {
  compose.activityRule.scenario.onActivity { activity->activity.setContentForR2Fixture() }
  compose.onNodeWithText("Lokale Erstellung ist in dieser Version nicht verfügbar. Gespeicherte Lektionen bleiben erhalten.").assertIsDisplayed()
  for(label in listOf("ChatGPT für neue Lektionen verwenden","Auf diesem Gerät","Qwen herunterladen","Modell entfernen"))compose.onNodeWithText(label).assertDoesNotExist()
  compose.onNodeWithText("Zurück").assertIsDisplayed()
 }
}
private fun MainActivity.setContentForR2Fixture() {
 setContent { MaterialTheme { RetiredGenerationScreen({}) } }
}
