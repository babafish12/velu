package de.voci

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import de.voci.data.Examples
import org.junit.*
import org.junit.Assert.*

class RtlTypographyTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Before fun requireActualRtlActivity() {
  assertEquals("Fixture requires actual Arabic process locale", "ar", java.util.Locale.getDefault().language)
  val config=compose.activity.resources.configuration
  assertEquals("Fixture requires actual Arabic app locale: ${config.locales}","ar",config.locales[0].language)
  assertEquals("Fixture requires actual RTL Activity",android.view.View.LAYOUT_DIRECTION_RTL,config.layoutDirection)
 }
 private fun layout(text:String):TextLayoutResult {
  val results=mutableListOf<TextLayoutResult>()
  compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
  val result=results.single()
  java.io.File(compose.activity.filesDir,"rtl-layout.txt").appendText("$text\tdefault=${java.util.Locale.getDefault()}\tstyle=${result.layoutInput.style}\n")
  return result
 }
 @Test fun realLessonKeepsLatinSentencePunctuationInRtlLayout() {
  val lesson=Examples.lessons.first()
  compose.onNodeWithText("Bibliothek").performClick();compose.onNodeWithText(lesson.title).performClick()
  compose.waitUntil(10000) { compose.onAllNodesWithText(lesson.items.first().target).fetchSemanticsNodes().isNotEmpty() }
  // Inspect actual rendered pixels before asking semantics for a separately calculated layout.
  val rendered=compose.onNodeWithText(lesson.items.first().translation).captureToImage()
  java.io.File(compose.activity.filesDir,"rtl-translation.png").outputStream().use { rendered.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
  val pixels=rendered.toPixelMap()
  val columns=(0 until pixels.width).map { x->(0 until pixels.height).filter { y-> val c=pixels[x,y]; c.alpha>0.9f && c.red<0.35f && c.green<0.35f && c.blue<0.35f } }
  val occupied=columns.indices.filter { columns[it].isNotEmpty() }
  assertTrue("Rendered translation must contain ink",occupied.isNotEmpty())
  val left=occupied.first();val right=occupied.last()
  val firstEnd=(left..right).firstOrNull { columns[it].isEmpty() } ?: right
  val lastStart=(right downTo left).firstOrNull { columns[it].isEmpty() } ?: left
  fun height(xs:IntRange):Int { val ys=xs.flatMap { columns[it] };return ys.max()-ys.min()+1 }
  val firstHeight=height(left..firstEnd);val lastHeight=height(lastStart..right)
  java.io.File(compose.activity.filesDir,"rtl-pixels.txt").writeText("firstGlyphHeight=$firstHeight lastGlyphHeight=$lastHeight image=${pixels.width}x${pixels.height}\n")
  assertTrue("Rendered sentence must start with capital E and end with a small period; first=$firstHeight last=$lastHeight",firstHeight>lastHeight*2)
  for(text in listOf(lesson.items.first().target,lesson.items.first().translation,lesson.items.first().example)) {
   val result=layout(text)
   assertEquals("Actual lesson paragraph must follow Latin content: $text",ResolvedTextDirection.Ltr,result.getParagraphDirection(0))
   assertTrue("Sentence-final punctuation must follow final Latin letter: $text",result.getBoundingBox(text.lastIndex).left>result.getBoundingBox(text.lastIndex-1).left)
  }
 }
 @Test fun realDurationSelectorAndArabicTextKeepTheirOwnDirection() {
  val duration=layout("1 Min")
  assertEquals("German duration must remain 1 Min",ResolvedTextDirection.Ltr,duration.getParagraphDirection(0))
  assertTrue("Duration digit must precede Latin unit",duration.getBoundingBox(0).left<duration.getBoundingBox(2).left)
  compose.onNodeWithText("Situation").performTextInput("موعد القطار.")
  assertEquals("Arabic content must remain RTL",ResolvedTextDirection.Rtl,layout("موعد القطار.").getParagraphDirection(0))
 }
}
