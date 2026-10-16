package de.voci.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import de.voci.ui.theme.LocalReducedMotion
import de.voci.ui.theme.Motion

/** Mounted only for an owned generation request; removal has no outgoing animation. */
@Composable fun GenerationProgress(cancel:()->Unit) {
 val reduced=LocalReducedMotion.current
 val entrance=remember { Animatable(if(reduced)1f else .75f) }
 LaunchedEffect(reduced) {
  if(reduced)entrance.snapTo(1f) else entrance.animateTo(1f,tween(Motion.expression))
 }
 Surface(Modifier.fillMaxWidth(),
  shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.secondaryContainer) {
  Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   Text("Lektion wird erstellt",style=MaterialTheme.typography.titleMedium,
    modifier=Modifier.fillMaxWidth().graphicsLayer { alpha=if(reduced)1f else entrance.value }.semantics {
     contentDescription="Lektion wird erstellt";liveRegion=LiveRegionMode.Polite
    })
   if(reduced) {
    // A static, unquantified line; no determinate fraction or decorative animation.
    Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.primary).semantics {
     progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate
    })
   } else LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
   VociButton("Abbrechen",cancel,modifier=Modifier.fillMaxWidth())
  }
 }
}
