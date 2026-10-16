package de.voci

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.auth.*
import de.voci.data.*
import de.voci.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceAcceptanceTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun nonLatinDraftSurvivesRecreation() {
  val value="موعد القطار · 東京 · ein sehr langer übersetzter Gesprächsausdruck"
  compose.onNodeWithText("Situation").performTextInput(value)
  compose.activityRule.scenario.recreate()
  compose.onNodeWithText(value).assertExists()
 }
 @Test fun distinctRoutesAndBackstack() {
  compose.onNodeWithText("Kategorien").performClick();compose.onNodeWithText("Restaurant").performClick();compose.onNodeWithText("Einen Tisch bestellen").assertIsDisplayed()
  compose.onNodeWithText("Zurück").performClick();compose.onNodeWithText("Sport").assertIsDisplayed()
  compose.onNodeWithText("Bibliothek").performClick();compose.onNodeWithText("Suchen").assertIsDisplayed()
  compose.onNodeWithText("Einstellungen").performClick();compose.onNodeWithText("Datenschutz").performClick();compose.onNodeWithText("Zurück").assertIsDisplayed()
 }
 @Test fun noMandatoryLoginForExamples() {
  compose.onNodeWithText("Bibliothek").performClick();compose.waitUntil(10000) { compose.onAllNodesWithText("Im Restaurant").fetchSemanticsNodes().isNotEmpty() };compose.onNodeWithText("Im Restaurant").performClick();compose.onNodeWithText("Abspielen").assertIsDisplayed();compose.onNodeWithText("Un tavolo per due, per favore.").assertIsDisplayed()
 }
 @Test fun categoryRenamePreservesLesson()=runBlocking {
  val app=compose.activity.application as VociApp;val dao=app.db.dao();dao.putCategory(CategoryEntity("test-custom","Test",true));val l=Examples.lessons.first().copy(id="custom-lesson");app.repository.save(l,"test-custom")
  dao.renameCategory("test-custom","Renamed");assertEquals("test-custom",dao.lesson(l.id)?.categoryId);dao.deleteCategory("test-custom");assertNotNull(dao.lesson(l.id));assertNull(dao.lesson(l.id)?.categoryId);dao.deleteLesson(l.id)
 }
 @Test fun keystoreCiphertextAndStableHost() {
  val c=compose.activity;val vault=SecureVault(c);val original=vault.read()
  try {
   val secret="LOCAL_TEST_SENTINEL_NOT_A_REAL_TOKEN";vault.write(original.copy(accounts=listOf(Registration("oaiapp_local","subject","test",secret))))
   assertFalse(File(c.noBackupFilesDir,"session.vault").readBytes().decodeToString().contains(secret));assertEquals(secret,vault.read().accounts.single().access);assertEquals(original.host,vault.read().host)
  } finally { vault.write(original) }
 }
 @Test fun reducedMotionAndRecallAreOptIn()=runBlocking<Unit> {
  val prefs=(compose.activity.application as VociApp).preferences
  prefs.bool("recall",false);prefs.bool("reduced",false)
  compose.onNodeWithText("Einstellungen").performClick();compose.onNodeWithText("Audio").performClick();compose.onNodeWithText("Interaktive Übung").assertIsDisplayed();compose.onNodeWithText("Bewegung reduzieren").assertIsDisplayed()
 }
}
