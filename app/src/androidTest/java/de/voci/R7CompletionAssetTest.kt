package de.voci

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.RenderMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled-asset parsing and software rendering; no Activity, storage writes or network.
 * Added for Parent execution. Python generator checks do not execute these Android tests.
 */
@RunWith(AndroidJUnit4::class)
class R7CompletionAssetTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun bundledGeometryHasExpectedSchemaBoundsAndDuration() {
        val context = instrumentation.targetContext
        val json = context.assets.open("complete.json").bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }
        assertEquals("VOCI completion", json.getString("nm"))
        assertTrue(json.getString("v").matches(Regex("\\d+\\.\\d+\\.\\d+")))
        assertEquals(80, json.getInt("w"))
        assertEquals(80, json.getInt("h"))
        assertEquals(60.0, json.getDouble("fr"), 0.0)
        assertEquals(0.0, json.getDouble("ip"), 0.0)
        assertEquals(36.0, json.getDouble("op"), 0.0)
        assertEquals(0, json.getInt("ddd"))
        assertEquals(0, json.getJSONArray("assets").length())
        assertLocalGeometry(json)
        val layers = json.getJSONArray("layers")
        assertEquals(2, layers.length())
        for (index in 0 until layers.length()) {
            val layer = layers.getJSONObject(index)
            assertEquals("only drawn shape layers", 4, layer.getInt("ty"))
            assertEquals(0, layer.getInt("ip"))
            assertEquals(36, layer.getInt("op"))
            assertTrue(layer.getJSONArray("shapes").length() > 0)
        }

        val composition = loadComposition()
        // Lottie converts JSON canvas units to device pixels using system density.
        val expectedPixels = (80 * Resources.getSystem().displayMetrics.density).toInt()
        assertEquals(0, composition.bounds.left)
        assertEquals(0, composition.bounds.top)
        assertEquals(expectedPixels, composition.bounds.width())
        assertEquals(expectedPixels, composition.bounds.height())
        assertEquals(60f, composition.frameRate, 0.001f)
        assertEquals(0f, composition.startFrame, 0.001f)
        // Lottie treats op as exclusive and subtracts 0.01 frame when parsing.
        assertEquals(36f, composition.endFrame, 0.02f)
        assertEquals(600f, composition.duration, 1f)
        assertEquals(2, composition.layers.size)
        assertTrue(composition.images.isEmpty())
    }

    @Test fun realDrawableRendersDistinctFramesAndHoldsCompletedState() {
        val composition = loadComposition()
        instrumentation.runOnMainSync {
            val drawable = LottieDrawable()
            drawable.setRenderMode(RenderMode.SOFTWARE)
            assertTrue(drawable.setComposition(composition))
            drawable.setBounds(0, 0, 80, 80)

            fun render(frame: Int): IntArray {
                val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.TRANSPARENT)
                    drawable.setFrame(frame)
                    drawable.draw(Canvas(bitmap))
                    return IntArray(80 * 80).also {
                        bitmap.getPixels(it, 0, 80, 0, 0, 80, 80)
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            val start = render(0)
            val middle = render(18)
            val end = render(35)
            for ((label, pixels) in listOf("start" to start, "middle" to middle, "end" to end)) {
                assertTrue("$label must draw visible pixels", pixels.count { Color.alpha(it) > 0 } > 100)
                assertTrue("$label must draw blue geometry", pixels.count {
                    Color.alpha(it) > 200 && Color.blue(it) > Color.green(it) &&
                        Color.green(it) > Color.red(it)
                } > 100)
                for (y in 0 until 80) for (x in 0 until 80) {
                    if (x < 4 || x >= 76 || y < 4 || y >= 76) {
                        assertEquals("$label must leave canvas margins clear", 0, Color.alpha(pixels[y * 80 + x]))
                    }
                }
            }
            assertFalse("growth/reveal must change the middle frame", start.contentEquals(middle))
            assertFalse("middle must differ from completion", middle.contentEquals(end))
            assertFalse("completion must differ from the beginning", start.contentEquals(end))
            assertTrue("ring must visibly grow", end.count { Color.alpha(it) > 0 } >
                start.count { Color.alpha(it) > 0 })
            fun whitePixels(pixels: IntArray) = pixels.count {
                Color.alpha(it) > 200 && Color.red(it) > 240 &&
                    Color.green(it) > 240 && Color.blue(it) > 240
            }
            assertEquals("check starts hidden", 0, whitePixels(start))
            assertTrue("completed check must be visibly white", whitePixels(end) > 40)
            assertTrue("final frames must hold a quiet success state", render(28).contentEquals(end))
            assertFalse("manual rendering must not start playback", drawable.isAnimating)
        }
    }

    private fun loadComposition(): LottieComposition {
        val result = LottieCompositionFactory.fromAssetSync(instrumentation.targetContext, "complete.json")
        return result.value ?: throw AssertionError("Real Lottie asset parsing failed", result.exception)
    }

    private fun assertLocalGeometry(value: Any?) {
        when (value) {
            is JSONObject -> for (key in value.keys()) {
                assertFalse("no external references, fonts or glyphs", key in setOf("refId", "u", "url", "fonts", "chars"))
                assertLocalGeometry(value.get(key))
            }
            is JSONArray -> for (index in 0 until value.length()) assertLocalGeometry(value.get(index))
            is String -> {
                assertFalse("no URLs", value.contains("://"))
                assertFalse("no embedded images", value.startsWith("data:"))
            }
        }
    }
}
