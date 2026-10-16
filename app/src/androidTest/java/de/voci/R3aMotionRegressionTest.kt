package de.voci

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import de.voci.audio.PlaybackService
import de.voci.audio.PlaybackState
import de.voci.audio.PreparedSession
import de.voci.data.Examples
import de.voci.domain.AudioCue
import de.voci.features.player.PlayerScreen
import de.voci.ui.components.AudioTransport
import de.voci.ui.theme.VociTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Compiled-only R3A tests. Real production composables, synthetic state, no audio actions. */
class R3aMotionRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reducedPlayPauseHasNoDecorativeIntermediateWidths() = playPause(reduced = true)
    @Test fun normalPlayPauseExposesIntermediateWidths_positiveControl() = playPause(reduced = false)
    @Test fun reducedExpressionHasNoDecorativeIntermediateHeights() = expression(reduced = true)
    @Test fun normalExpressionExposesIntermediateHeights_positiveControl() = expression(reduced = false)

    private fun requireNormalAnimator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertEquals("FIXTURE: Parent must set Android animator_duration_scale=1", 1f, scale, 0f)
        compose.mainClock.autoAdvance = false
        report("environment", JSONObject().put("animator_duration_scale", scale)
            .put("clock_autoAdvance", false).put("layout_direction", "LTR")
            .put("font", "production VociTheme Manrope").put("locale", context.resources.configuration.locales.toLanguageTags()))
    }

    @Composable private fun Fixture(reduced: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale), LocalLayoutDirection provides LayoutDirection.Ltr) {
            VociTheme(reduced = reduced) { Box(Modifier.requiredWidth(320.dp)) { content() } }
        }
    }

    private fun playPause(reduced: Boolean) {
        requireNormalAnimator()
        val visible = mutableStateOf(true)
        val playing = mutableStateOf(false)
        var clicks = 0
        try {
            compose.setContent {
                if (visible.value) Fixture(reduced, 1f) {
                    // R2's actual Play/Pause AnimatedContent lives inside AudioTransport.
                    AudioTransport(PlaybackState(playing = playing.value), { clicks++; playing.value = !playing.value }, {}, {})
                }
            }
            settle()
            val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and
                hasClickAction() and (hasText("Play") or hasText("Pause"))
            fun bounds() = compose.onNode(button).fetchSemanticsNode().boundsInRoot
            val trace = sample("playPause-reduced=$reduced", ::bounds) {
                compose.onNode(button).performClick()
            }
            assertEquals("FIXTURE: actual transport click was not dispatched exactly once", 1, clicks)
            compose.onNodeWithText("Pause").assertExists()
            assertTrace(trace, width = true, reduced = reduced)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = true
        }
    }

    private fun expression(reduced: Boolean) {
        requireNormalAnimator()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as VociApp
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        val playback = compose.runOnIdle { app.playback }
        // Join the existing restoration path before replacing only the public display state.
        // Parent must use a completed baseline with an empty Media3 playlist; never clear real audio.
        runBlocking { playback.restore() }
        compose.runOnIdle {
            assertNotNull("FIXTURE: Media3 service not ready", PlaybackService.localPlayer)
            assertEquals("FIXTURE: requires empty Media3 playlist; do not run during playback", 0, PlaybackService.localPlayer!!.mediaItemCount)
            assertNull("FIXTURE: audio preparation must be idle", playback.preparing.value)
        }
        val original = compose.runOnIdle { playback.state.value }
        val originalPreparing = compose.runOnIdle { playback.preparing.value }
        val short = AudioCue("r3a-short", "synthetic-short", "Sì", "Ja", "it-IT", durationMs = 1000)
        val long = AudioCue("r3a-long", "synthetic-long", "Vorrei prenotare un tavolo vicino alla finestra.",
            "Ich möchte für morgen Abend einen ruhigen Tisch direkt am Fenster reservieren. " +
                "Bitte berücksichtigen Sie, dass wir mit mehreren Gästen kommen und ausreichend Platz benötigen.",
            "it-IT", durationMs = 1000)
        val session = PreparedSession("r3a-expression-fixture", Examples.lessons.first().copy(title = "R3A Ausdruck"), listOf(short, long), false, 1f)
        try {
            val vm = compose.runOnIdle {
                VociViewModel(app, SavedStateHandle()).also { store.put("r3a", it) }
            }
            compose.runOnIdle { playback.state.value = PlaybackState(session = session) }
            compose.setContent { if (visible.value) Fixture(reduced, 2f) { PlayerScreen(session.id, vm, {}, {}) { _, _ -> error("FIXTURE: unexpected completion") } } }
            settle()
            fun bounds(): Rect {
                val text = compose.onAllNodes(hasText(short.text) or hasText(long.text), useUnmergedTree = true).fetchSemanticsNodes().first()
                val heading = compose.onNodeWithText("R3A Ausdruck", useUnmergedTree = true).fetchSemanticsNode()
                val transport = compose.onNodeWithText("Wiederholen", useUnmergedTree = true).fetchSemanticsNode()
                return compose.runOnIdle {
                    // Find Page's common Column, then its direct expression child. This is the
                    // AnimatedContent allocation, NOT the fully measured incoming Text/Column.
                    val page = ancestors(heading.layoutInfo).first { parent -> ancestors(text.layoutInfo).any { it === parent } }
                    val slot = childOf(page, text.layoutInfo)
                    val controls = childOf(page, transport.layoutInfo)
                    val rect = outerBounds(slot)
                    val controlsRect = outerBounds(controls)
                    val gap = 16f * slot.density.density
                    assertEquals("FIXTURE: measured slot must end one Page gap before the transport; wrong ancestor", rect.bottom + gap, controlsRect.top, 2f)
                    rect
                }
            }
            val trace = sample("expression-reduced=$reduced", ::bounds) {
                compose.runOnIdle { playback.state.value = playback.state.value.copy(index = 1) }
            }
            compose.onNodeWithText(long.text, useUnmergedTree = true).assertExists()
            assertEquals("FIXTURE: coordinator replaced synthetic display state", 1, playback.state.value.index)
            assertTrace(trace, width = false, reduced = reduced)
        } finally {
            try {
                compose.runOnIdle { visible.value = false }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            } finally {
                compose.runOnIdle {
                    playback.state.value = original
                    playback.preparing.value = originalPreparing
                    store.clear() // cancels viewModelScope; do not call vm.cancel(), which changes real playback.
                }
                compose.mainClock.autoAdvance = true
            }
        }
    }

    private fun ancestors(start: LayoutInfo): List<LayoutInfo> = generateSequence(start) { it.parentInfo }.toList()
    private fun childOf(parent: LayoutInfo, descendant: LayoutInfo): LayoutInfo =
        ancestors(descendant).first { it.parentInfo === parent }
    private fun outerBounds(info: LayoutInfo): Rect {
        // LayoutInfo.coordinates is the INNER coordinator in Compose 1.12.1. Walk the
        // existing modifier coordinates up to (but excluding) the parent layout. Otherwise
        // SizeTransform's outer allocation would be missed and the incoming size measured.
        var coordinates = info.coordinates
        val parentLayout = coordinates.parentLayoutCoordinates
        while (coordinates.parentCoordinates != null && coordinates.parentCoordinates !== parentLayout) {
            coordinates = coordinates.parentCoordinates!!
        }
        val topLeft = coordinates.localToRoot(Offset.Zero)
        return Rect(topLeft, coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
    }

    private data class Frame(val number: Int, val millis: Long, val bounds: Rect)
    private fun settle() {
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
    }
    private fun sample(case: String, bounds: () -> Rect, change: () -> Unit): List<Frame> {
        val start = compose.mainClock.currentTime
        val frames = mutableListOf<Frame>()
        fun capture(frame: Int) {
            val value = Frame(frame, compose.mainClock.currentTime - start, bounds())
            frames += value
            report(case, JSONObject().put("frame", frame).put("time_ms", value.millis)
                .put("phase", if (frame == 0) "initial" else if (frame <= 2) "composition_layout_bootstrap" else "sample")
                .put("left_px", value.bounds.left).put("top_px", value.bounds.top)
                .put("right_px", value.bounds.right).put("bottom_px", value.bounds.bottom)
                .put("width_px", value.bounds.width).put("height_px", value.bounds.height))
        }
        capture(0)
        change()
        repeat(100) { index ->
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle() // Android measure/layout completes without advancing the test clock.
            capture(index + 1)
        }
        report(case, JSONObject().put("target_width_px", frames.last().bounds.width)
            .put("target_height_px", frames.last().bounds.height).put("samples", frames.size))
        return frames
    }
    private fun assertTrace(frames: List<Frame>, width: Boolean, reduced: Boolean) {
        fun size(frame: Frame) = if (width) frame.bounds.width else frame.bounds.height
        val initial = size(frames.first())
        val target = size(frames.last())
        assertTrue("FIXTURE: endpoints must differ by >4 physical px; initial=$initial target=$target", abs(target - initial) > 4f)
        assertTrue("FIXTURE: target not settled in final five frames", frames.takeLast(5).all { abs(size(it) - target) <= 1f })
        val decorative = frames.filter { it.number > 2 && abs(size(it) - target) > 1f }
        if (reduced) {
            assertTrue("F02 PRODUCT: reduced motion retains decorative size changes after the two composition/layout frames; " +
                "initial=$initial target=$target intermediate=${decorative.map { it.number to size(it) }}", decorative.isEmpty())
        } else {
            assertTrue("CONTROL: fixture must observe an actual interior size after bootstrap, not just endpoints; initial=$initial target=$target",
                frames.any { it.number > 2 && size(it) > min(initial, target) + 1f && size(it) < max(initial, target) - 1f })
        }
    }
    private fun report(case: String, value: JSONObject) {
        val line = value.put("case", case).toString()
        Log.i("VOCI_R3A_MOTION", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("r3a_motion", line) })
    }
}
