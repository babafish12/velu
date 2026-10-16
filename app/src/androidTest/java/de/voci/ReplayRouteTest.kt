package de.voci
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import de.voci.features.recall.ResultScreen
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.*
import de.voci.data.Examples
import de.voci.features.player.PlayerScreen
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ReplayRouteTest {
 @get:Rule val compose=createComposeRule()
 @Test fun requestedRouteNeverFinishesForOlderEndedSession()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
  val vm=withContext(Dispatchers.Main) { VociViewModel(app,SavedStateHandle()) }
  val old=PreparedSession("old",Examples.lessons.first(),listOf(de.voci.domain.AudioCue("fixture","v1","Il conto","Die Rechnung","it-IT",durationMs=1000)),false,1f)
  withContext(Dispatchers.Main) { app.playback.state.value=PlaybackState(session=old,ended=true);app.playback.preparing.value="Sprachdaten prüfen" }
  val finished=mutableListOf<String>()
  compose.setContent { VociTheme(false) { PlayerScreen("new-request",vm,{},{}) { id,_->finished+=id } } }
  compose.waitForIdle();assertTrue("Old ended state navigated away from requested replay",finished.isEmpty())
  withContext(Dispatchers.Main) { app.playback.state.value=PlaybackState();app.playback.preparing.value=null }
 }
 @Test fun oldResultRouteCannotDisplayNewSessionStatistics()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
  val vm=withContext(Dispatchers.Main) { VociViewModel(app,SavedStateHandle()) }
  val newer=PreparedSession("newer",Examples.lessons.first(),listOf(de.voci.domain.AudioCue("fixture","v1","Il conto","Die Rechnung","it-IT",durationMs=1000)),false,1f)
  withContext(Dispatchers.Main) { app.playback.state.value=PlaybackState(session=newer,playedMs=42000) }
  compose.setContent { VociTheme(true) { ResultScreen("older",vm,{}) } }
  compose.onNodeWithText("Gehört: 0:42").assertDoesNotExist()
  withContext(Dispatchers.Main) { app.playback.state.value=PlaybackState() }
 }

}
