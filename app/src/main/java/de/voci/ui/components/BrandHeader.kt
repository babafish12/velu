package de.voci.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.voci.R
import de.voci.ui.theme.LocalReducedMotion

/** The caller owns the one-shot claim across navigation and Activity recreation. */
@Composable fun BrandHeader(intro:Boolean) {
 val appName=stringResource(R.string.app_name)
 val reduced=LocalReducedMotion.current
 val entrance=remember { Animatable(if(intro && !reduced)0f else 1f) }
 LaunchedEffect(reduced) {
  if(reduced)entrance.snapTo(1f) else entrance.animateTo(1f,tween(280))
 }
 Row(Modifier.graphicsLayer {
  val fraction=if(reduced)1f else entrance.value
  alpha=fraction;scaleX=.96f+.04f*fraction;scaleY=scaleX
 },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
  Image(painterResource(R.drawable.velu_brand),contentDescription=appName,modifier=Modifier.size(40.dp))
  Text(appName,modifier=Modifier.weight(1f,fill=false),style=MaterialTheme.typography.titleLarge)
 }
}
