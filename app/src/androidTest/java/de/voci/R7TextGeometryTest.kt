package de.voci

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Geometry, not paragraph allocation width: shared only by the new R7 fixtures.
 * This detects node/ancestor clipping and truncation; it does not claim to detect opaque overlays.
 */
internal fun r7TextFullyVisible(result: TextLayoutResult, node: SemanticsNode): Boolean {
    val text = result.layoutInput.text.text
    if (text.isEmpty() || result.lineCount == 0 || result.multiParagraph.didExceedMaxLines) return false
    val origin = node.positionInRoot
    val visible = node.boundsInRoot.intersect(Rect(origin.x, origin.y,
        origin.x + node.size.width, origin.y + node.size.height))
    fun contains(rect: Rect): Boolean = rect.left.isFinite() && rect.top.isFinite() &&
        rect.right.isFinite() && rect.bottom.isFinite() &&
        rect.left + origin.x >= visible.left - 0.5f &&
        rect.right + origin.x <= visible.right + 0.5f &&
        rect.top + origin.y >= visible.top - 0.5f &&
        rect.bottom + origin.y <= visible.bottom + 0.5f
    for (line in 0 until result.lineCount) {
        if (result.isLineEllipsized(line) || !contains(Rect(result.getLineLeft(line),
                result.getLineTop(line), result.getLineRight(line), result.getLineBottom(line)))) return false
    }
    for (offset in text.indices.filterNot { text[it].isWhitespace() }) {
        val line = result.getLineForOffset(offset)
        if (offset < result.getLineStart(line) || offset >= result.getLineEnd(line, visibleEnd = true) ||
            !contains(result.getBoundingBox(offset))) return false
    }
    return true
}

/** Real Compose controls, synthetic text; both normal and doubled font scale. */
class R7TextGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wrapContentParagraphWiderThanNodeIsFullyVisible() = control("wrap", true)
    @Test fun horizontalClippingIsRefused() = control("horizontal", false)
    @Test fun verticalClippingIsRefused() = control("vertical", false)
    @Test fun ellipsisIsRefused() = control("ellipsis", false)
    @Test fun maxLinesTruncationIsRefused() = control("maxLines", false)

    private fun control(kind: String, expected: Boolean) {
        val scale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                Box(Modifier.width(312.dp)) {
                    val modifier = Modifier.testTag("geometry")
                    when (kind) {
                        "wrap" -> Text("Wasser, bitte", modifier, fontSize = 16.sp, lineHeight = 24.sp)
                        "horizontal" -> Text("Wasser, bitte, dieser Text ist viel zu lang", modifier.width(40.dp),
                            fontSize = 16.sp, softWrap = false, overflow = TextOverflow.Clip)
                        "vertical" -> Text("Wasser, bitte\nNoch eine Zeile", modifier.height(8.dp), fontSize = 16.sp)
                        "ellipsis" -> Text("Wasser, bitte, dieser Text ist viel zu lang", modifier.width(48.dp),
                            fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else -> Text("Wasser\nbitte\nmehr", modifier, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Clip)
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { scale.value = fontScale }
            val results = mutableListOf<TextLayoutResult>()
            val interaction = compose.onNodeWithTag("geometry", useUnmergedTree = true)
            interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
            val node = interaction.fetchSemanticsNode()
            assertEquals(1, results.size)
            compose.runOnIdle {
                val result = results.single()
                if (kind == "wrap") {
                    assertTrue("control must reproduce wide retained paragraph", result.multiParagraph.width > node.size.width)
                    assertTrue("control must reproduce misleading overflow metric", result.hasVisualOverflow)
                }
                assertEquals("$kind at fontScale=$fontScale", expected, r7TextFullyVisible(result, node))
            }
        }
    }
}
