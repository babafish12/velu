package de.voci

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import de.voci.audio.AudioFiles
import de.voci.audio.PlaybackService
import de.voci.data.Examples
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real MainActivity journey; no state injection/reset between the two user starts.
 * Parent runs alone with offline English, Italian and German voices. Source-first only.
 */
class R7SessionSequenceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun naturalCompletionThenSprintThenSecondLessonOwnsFreshQueue() = r7RetainedFixture { app, scenario ->
        val first = Examples.lessons.single { it.id == "example-it" }
        val second = Examples.lessons.single { it.id == "example-restaurant" }
        val item = first.items.last()
        fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
        fun tab(text: String) = compose.onNode(hasText(text) and hasClickAction()).performClick()
        suspend fun await(timeout: Long = 60_000, condition: () -> Boolean) {
            withTimeout(timeout) { while (!withContext(Dispatchers.Main) { condition() }) delay(25) }
        }
        tab("Bibliothek")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(first.title).fetchSemanticsNodes().isNotEmpty() }
        click(first.title)
        compose.onNodeWithContentDescription("Eintrag abspielen: ${item.target}").performScrollTo().performClick()
        val p = app.playback
        await {
            assertNull(p.state.value.error)
            p.preparing.value == null && p.state.value.session?.singleItemId == item.id && PlaybackService.localPlayer?.isPlaying == true
        }
        val initial = requireNotNull(p.state.value.session)
        assertEquals(first.id, initial.lesson.id)
        assertEquals(listOf("target", "meaning", "repeat", "example"), initial.cues.map { it.phase })
        assertTrue(initial.cues.all { it.itemId == item.id && it.durationMs > 0 })
        // Wait the actual measured pass: never seek, skip, shorten audio or fabricate ended.
        val duration = initial.cues.sumOf { it.durationMs + it.pauseMs } / initial.speed
        await(duration.toLong() + 15_000) {
            assertNull(p.state.value.error)
            p.state.value.ended && PlaybackService.localPlayer!!.playbackState == Player.STATE_ENDED
        }
        compose.onNodeWithText("Sprint beendet").assertIsDisplayed()
        withContext(Dispatchers.Main) {
            assertFalse(PlaybackService.localPlayer!!.isPlaying)
            assertFalse(p.state.value.playing)
            assertNull(p.preparing.value)
        }
        // Actual app toolbar back and bottom tab; no navigator/VM/coordinator command.
        click("Zurück")
        tab("Sprint")
        compose.onNodeWithText("Situation").assertIsDisplayed()
        compose.onNodeWithText("Sprint beendet").assertDoesNotExist()
        compose.onNodeWithText("Diese Sitzung ist nicht mehr aktiv.").assertDoesNotExist()
        tab("Bibliothek")
        // The tab restores the first lesson's detail and scroll position; verify it before leaving.
        compose.onNode(hasText(first.title) and isHeading()).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Abspielen") and hasClickAction()).assertExists().assertIsEnabled()
        compose.onNodeWithContentDescription("Eintrag abspielen: ${item.target}")
            .assertExists().assertIsEnabled().assertHasClickAction()
        click("Zurück")
        compose.onNode(hasText("Bibliothek") and isHeading()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Suchen").performScrollTo().assertIsDisplayed().assert(hasSetTextAction())
        click(second.title)
        click("Abspielen")
        await(90_000) {
            assertNull(p.state.value.error)
            p.preparing.value == null && p.state.value.session?.lesson?.id == second.id && PlaybackService.localPlayer!!.isPlaying
        }
        val next = requireNotNull(p.state.value.session)
        assertNotEquals(initial.id, next.id)
        assertNull(next.singleItemId)
        assertEquals(next.id, p.state.value.requestId)
        assertEquals(next.sourceCues, next.cues)
        assertEquals(second.items.map { it.id }, next.cues.filter { it.phase == "target" }.map { it.itemId })
        assertFalse(p.state.value.ended)
        assertTrue(p.answers.value.isEmpty())
        compose.onNodeWithContentDescription("Eintragsrad").assertExists()
        compose.onNodeWithText("Sprint beendet").assertDoesNotExist()
        compose.onNodeWithText("Diese Sitzung ist nicht mehr aktiv.").assertDoesNotExist()
        val assets = next.cues.map { cue ->
            val asset = requireNotNull(app.db.dao().audio(cue.key))
            assertTrue(cue.durationMs > 0)
            assertTrue(AudioFiles.valid(File(asset.path), asset.bytes, asset.sha256))
            asset
        }
        withContext(Dispatchers.Main) {
            val player = requireNotNull(PlaybackService.localPlayer)
            assertEquals(next.cues.flatMap { listOf(it.key, "pause") }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            next.cues.indices.forEach { i -> assertEquals(assets[i].path, player.getMediaItemAt(i * 2).localConfiguration?.uri?.toString()) }
            assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
        }
        // Real background lifecycle still silences the second queue and returning cannot autoplay.
        scenario.moveToState(Lifecycle.State.CREATED)
        withContext(Dispatchers.Main) { assertFalse(PlaybackService.localPlayer!!.isPlaying); assertFalse(PlaybackService.localPlayer!!.playWhenReady) }
        scenario.moveToState(Lifecycle.State.RESUMED)
        delay(1_200)
        withContext(Dispatchers.Main) {
            assertFalse(PlaybackService.localPlayer!!.isPlaying)
            assertFalse(PlaybackService.localPlayer!!.playWhenReady)
            assertFalse(p.state.value.playing)
            assertEquals(next.id, p.state.value.session?.id)
        }
    }
}
