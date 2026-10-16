package de.voci.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.voci.R
import de.voci.ui.theme.LocalReducedMotion
import de.voci.ui.theme.Motion

@Composable fun UncertainStar(target:String,checked:Boolean,enabled:Boolean=true,onToggle:()->Unit) {
 val reduced=LocalReducedMotion.current
 val scale=remember { Animatable(1f) }
 var previous by remember { mutableStateOf(checked) }
 val targetColor=if(!enabled)MaterialTheme.colorScheme.onSurface.copy(alpha=.38f)
  else if(checked)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
 val color by animateColorAsState(targetColor,tween(if(reduced)0 else Motion.select),label="Markierung")
 LaunchedEffect(checked,reduced) {
  val changed=previous!=checked;previous=checked
  if(reduced || !changed)scale.snapTo(1f)
  else { scale.animateTo(1.12f,tween(60));scale.animateTo(1f,tween(Motion.select)) }
 }
 IconToggleButton(checked=checked,onCheckedChange={onToggle()},enabled=enabled,
  modifier=Modifier.size(48.dp).semantics {
   contentDescription=if(checked)"Unsicher-Markierung entfernen: $target" else "Als unsicher markieren: $target"
  }) {
  Icon(painterResource(if(checked)R.drawable.ic_uncertain_star_filled else R.drawable.ic_uncertain_star_outline),
   contentDescription=null,tint=if(reduced)targetColor else color,modifier=Modifier.size(24.dp).graphicsLayer {
    scaleX=if(reduced)1f else scale.value;scaleY=scaleX
   })
 }
}
