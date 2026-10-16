package de.voci.ui.theme

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import de.voci.R
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.unit.dp

object VociTokens {
 val background=Color(0xFFFAFAF7);val ink=Color(0xFF182126);val secondary=Color(0xFF626B70);val accent=Color(0xFF3658D8);val line=Color(0xFFE7E9E6)
 val gutter=24.dp;val gap=16.dp;val radius=12.dp
}
object Motion { const val page=210;const val select=150;const val expression=180;const val player=250;const val complete=620 }
val LocalReducedMotion=staticCompositionLocalOf { false }
@OptIn(ExperimentalTextApi::class)
@Composable fun VociTheme(reduced:Boolean=false,content:@Composable ()->Unit) {
 val context=LocalContext.current
 val systemReduced=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f
 val font=FontFamily(Font(R.font.manrope,weight=FontWeight.Normal,variationSettings=FontVariation.Settings(FontVariation.weight(400))),Font(R.font.manrope,weight=FontWeight.Medium,variationSettings=FontVariation.Settings(FontVariation.weight(500))),Font(R.font.manrope,weight=FontWeight.SemiBold,variationSettings=FontVariation.Settings(FontVariation.weight(600))))
 val defaults=Typography()
 // Explicit content direction also governs rendered paragraphs under an RTL app locale.
 val base=Typography(displaySmall=defaults.displaySmall.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),headlineLarge=defaults.headlineLarge.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),headlineMedium=defaults.headlineMedium.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),headlineSmall=defaults.headlineSmall.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),titleLarge=defaults.titleLarge.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),titleMedium=defaults.titleMedium.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),titleSmall=defaults.titleSmall.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),bodyLarge=defaults.bodyLarge.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),bodyMedium=defaults.bodyMedium.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),bodySmall=defaults.bodySmall.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),labelLarge=defaults.labelLarge.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),labelMedium=defaults.labelMedium.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr),labelSmall=defaults.labelSmall.copy(fontFamily=font,textDirection=TextDirection.ContentOrLtr))
 CompositionLocalProvider(LocalReducedMotion provides (reduced || systemReduced)) {
  MaterialTheme(colorScheme=lightColorScheme(primary=VociTokens.accent,onPrimary=Color.White,background=VociTokens.background,onBackground=VociTokens.ink,surface=Color.White,onSurface=VociTokens.ink,onSurfaceVariant=VociTokens.secondary,outlineVariant=VociTokens.line,secondaryContainer=Color(0xFFE9EDFF),onSecondaryContainer=VociTokens.accent,surfaceContainer=VociTokens.background),shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(12.dp),large=RoundedCornerShape(16.dp)),typography=base,content=content)
 }
}
