package de.voci

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.PlaybackService
import de.voci.audio.PlaybackState
import de.voci.audio.PreparedSession
import de.voci.domain.AudioCue
import de.voci.domain.LessonContent
import de.voci.domain.VocabItem
import de.voci.features.player.PlayerScreen
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** UI-composition-only: real PlayerScreen + coordinator public display-state seam, known paused
 * single-item state, EMPTY actual player queue. Durations below are synthetic, never audio evidence.
 * Parent must isolate unfinished sessions. Real TTS/Media3 positive control is R7ForegroundPlaybackTest.
 */
class R7PlayerControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun playerRemovesOnlyTranslationAndRepeatControls() = r7UiFixture(compose) { app, vm ->
        val playback = compose.runOnIdle { app.playback }
        compose.waitUntil(10_000) { compose.runOnIdle { PlaybackService.localPlayer != null } }
        runBlocking { withContext(Dispatchers.Main) { withTimeout(10_000) { playback.restore() } } }
        val before = compose.runOnIdle {
            assertNull("FIXTURE: Parent must isolate unfinished sessions", playback.state.value.session)
            assertNull("FIXTURE: no pending preparation", playback.preparing.value)
            assertEquals("FIXTURE: empty actual player queue", 0, PlaybackService.localPlayer!!.mediaItemCount)
            assertFalse("FIXTURE: no existing play intent", PlaybackService.localPlayer!!.playWhenReady)
            playback.state.value
        }
        val item = VocabItem("water", "Water, please", "Wasser, bitte", "Still water, please.", "Stilles Wasser, bitte.")
        val lesson = LessonContent("r7-ui-only", "R7 Player", "en-GB", listOf(item))
        val cue = AudioCue("r7-ui-only-cue", item.id, item.target, item.translation, "en-GB", durationMs = 1000)
        val session = PreparedSession("r7-ui-only-session", lesson, listOf(cue), false, 1f,
            budgetMs = 1650, singleItemId = item.id)
        val visible = mutableStateOf(true)
        val diagnostics = R7PlayerLayoutDiagnostics(app.cacheDir)
        var failure: Throwable? = null
        try {
            compose.runOnIdle { playback.state.value = PlaybackState(session = session, remainingMs = 1650) }
            compose.setContent { if (visible.value) VociTheme(reduced = true) {
                PlayerScreen(session.id, vm, {}, {}) { _, _ -> throw AssertionError("FIXTURE: unexpected completion") }
            } }
            // Column composes all controls; absence checks need no scroll and have an exact RED label.
            for (text in listOf("Übersetzung ausblenden", "Übersetzung zeigen", "Wiederholen")) {
                assertTrue("R7 PLAYER CONTROLS PRODUCT: forbidden control '$text' remains",
                    compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
            }
            compose.onNodeWithContentDescription("Radsprache wechseln").performScrollTo()
                .assertIsDisplayed().assertHasClickAction()
            for (text in listOf("Play", "Weiter", "Audio")) {
                compose.onNodeWithText(text).performScrollTo().assertIsDisplayed().assertHasClickAction()
            }
            // Page Back precedes transport Previous in this screen's composition order.
            val backControls = compose.onAllNodesWithText("Zurück")
            assertEquals("R7 PLAYER CONTROLS PRODUCT: retain Page Back and transport Previous", 2,
                backControls.fetchSemanticsNodes().size)
            backControls[1].performScrollTo().assertIsDisplayed().assertHasClickAction()
            compose.onNode(hasContentDescription("Als unsicher markieren: ${item.target}") or
                hasContentDescription("Unsicher-Markierung entfernen: ${item.target}")).performScrollTo()
                .assertIsDisplayed().assertHasClickAction()
            for (text in listOf(item.target, item.translation)) {
                val results = mutableListOf<TextLayoutResult>()
                var displayedProbe = false
                try {
                    compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
                    displayedProbe = true
                    assertTrue("R7 PLAYER CONTROLS PRODUCT: readable paired text '$text'", results.isNotEmpty())
                    results.forEach { result ->
                        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
                        compose.runOnIdle {
                            assertTrue("R7 PLAYER CONTROLS PRODUCT: unclipped paired text '$text'",
                                r7TextFullyVisible(result, node))
                        }
                    }
                } finally {
                    // Observe this same scrolled synthetic screen even when the original assertion fails.
                    runCatching { diagnostics.observe(compose, text, results, displayedProbe) }
                        .exceptionOrNull()?.let(diagnostics.problems::add)
                }
            }
            // Display-state change only; never claim this is a successful Play command.
            compose.runOnIdle { playback.state.value = playback.state.value.copy(playing = true) }
            compose.onNodeWithText("Pause").performScrollTo().assertIsDisplayed().assertHasClickAction()
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            // Keep the original throwable/stack, and retain every diagnostic or cleanup failure.
            val problems = diagnostics.problems
            fun cleanup(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let(problems::add) }
            cleanup {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
            }
            cleanup { compose.runOnIdle { playback.state.value = before } }
            cleanup { diagnostics.writeReport(failure) }
            cleanup { diagnostics.reportPath() }
            val original = failure
            if (original != null) problems.forEach { original.addSuppressed(it) }
            else if (problems.isNotEmpty()) {
                problems.drop(1).forEach { problems.first().addSuppressed(it) }
                throw problems.first()
            }
        }
    }
}

/** Owned, bounded synthetic UI evidence only. Never reads auth, preferences or lesson files.
 * Intentionally retained for Parent; screenshots are taken at the probes, never after cleanup.
 */
private class R7PlayerLayoutDiagnostics(cacheDir: File) {
    private val directory = File(cacheDir, "r7-player-controls-${UUID.randomUUID()}")
    private val observations = JSONArray()
    val problems = mutableListOf<Throwable>()

    init { check(directory.mkdir()) { "DIAGNOSTIC: cannot create owned layout directory" } }

    fun observe(
        compose: ComposeContentTestRule,
        text: String,
        results: List<TextLayoutResult>,
        displayedProbe: Boolean
    ) {
        val index = observations.length()
        if (index >= 2) return
        val observation = JSONObject().put("index", index).put("syntheticText", text)
            .put("displayedProbeCompleted", displayedProbe).put("layoutResultCount", results.size)
        observations.put(observation)
        fun record(stage: String, block: () -> Unit) {
            try { block() } catch (t: Throwable) {
                problems += t
                observation.put(stage + "ErrorType", t.javaClass.name.take(160))
            }
        }
        record("geometry") {
            observation.put("layouts", JSONArray().apply {
                results.take(2).forEach { put(layout(it)) }
            }).put("omittedLayoutResults", (results.size - 2).coerceAtLeast(0))
            val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            compose.runOnIdle {
                observation.put("node", bounds(node)).put("root", bounds(root))
            }
        }
        if (displayedProbe) record("screenshot") {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: error("DIAGNOSTIC: uiAutomation returned no screenshot")
            try {
                val name = "layout-$index.png"
                File(directory, name).outputStream().use {
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                        "DIAGNOSTIC: PNG encoding failed"
                    }
                }
                observation.put("screenshot", name).put("screenshotWidthPx", screenshot.width)
                    .put("screenshotHeightPx", screenshot.height)
            } finally { screenshot.recycle() }
        }
    }

    fun writeReport(failure: Throwable?) {
        val report = JSONObject().put("schemaVersion", 1)
            .put("fixture", "synthetic PlayerScreen r7-ui-only; paused single item; empty actual player queue")
            .put("evidence", "visual UI/layout only; no audio or acceptance-pass evidence")
            .put("geometryUnits", "px except density/fontScale ratios; fontSize/lineHeight include TextUnit")
            .put("originalFailureType", failure?.javaClass?.name?.take(160) ?: JSONObject.NULL)
            .put("diagnosticAndCleanupErrorTypes", JSONArray(problems.map { it.javaClass.name.take(160) }))
            .put("observations", observations)
        var bytes = report.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size >= 32 * 1024) {
            // Retain valid bounded JSON even if an unexpected layout exceeds the detail budget.
            report.remove("observations")
            report.put("observationCount", observations.length())
                .put("observationDetailsOmitted", "report exceeded 32KiB cap")
            bytes = report.toString().toByteArray(Charsets.UTF_8)
        }
        File(directory, "report.json").writeBytes(bytes)
    }

    fun reportPath() {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("r7_player_layout_artifact", "cache/${directory.name}")
        })
    }

    private fun bounds(node: SemanticsNode) = JSONObject()
        .put("boundsInRoot", rect(node.boundsInRoot)).put("boundsInWindow", rect(node.boundsInWindow))
        .put("sizeWidthPx", node.size.width).put("sizeHeightPx", node.size.height)
        .put("positionOnScreenX", node.positionOnScreen.x).put("positionOnScreenY", node.positionOnScreen.y)

    private fun rect(value: Rect) = JSONObject().put("left", value.left).put("top", value.top)
        .put("right", value.right).put("bottom", value.bottom)

    private fun layout(result: TextLayoutResult): JSONObject {
        val input = result.layoutInput
        val constraints = input.constraints
        return JSONObject().put("hasVisualOverflow", result.hasVisualOverflow)
            .put("didOverflowWidth", result.didOverflowWidth).put("didOverflowHeight", result.didOverflowHeight)
            .put("sizeWidthPx", result.size.width).put("sizeHeightPx", result.size.height)
            .put("paragraphWidthPx", result.multiParagraph.width).put("paragraphHeightPx", result.multiParagraph.height)
            .put("paragraphDidExceedMaxLines", result.multiParagraph.didExceedMaxLines)
            .put("lineCount", result.lineCount).put("firstBaselinePx", result.firstBaseline)
            .put("lastBaselinePx", result.lastBaseline)
            .put("constraints", JSONObject().put("minWidth", constraints.minWidth).put("maxWidth", constraints.maxWidth)
                .put("minHeight", constraints.minHeight).put("maxHeight", constraints.maxHeight))
            .put("density", input.density.density).put("fontScale", input.density.fontScale)
            .put("fontSize", input.style.fontSize.toString()).put("lineHeight", input.style.lineHeight.toString())
            .put("layoutDirection", input.layoutDirection.name).put("maxLines", input.maxLines)
            .put("softWrap", input.softWrap).put("overflow", input.overflow.toString())
            .put("omittedLines", (result.lineCount - 16).coerceAtLeast(0))
            .put("lines", JSONArray().apply {
                for (line in 0 until minOf(result.lineCount, 16)) {
                    put(JSONObject().put("index", line).put("start", result.getLineStart(line))
                        .put("end", result.getLineEnd(line)).put("visibleEnd", result.getLineEnd(line, visibleEnd = true))
                        .put("left", result.getLineLeft(line)).put("top", result.getLineTop(line))
                        .put("right", result.getLineRight(line)).put("bottom", result.getLineBottom(line))
                        .put("baseline", result.getLineBaseline(line)).put("ellipsized", result.isLineEllipsized(line)))
                }
            })
    }
}
