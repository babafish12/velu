package de.voci.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner

/** Keep the host/entry owners composed, but stop hidden lifecycle consumers and never place
 * their content. Hidden pages have no hit targets or accessibility subtree. Obsolete session
 * entries are separately popped, not retained here as invisible tombstones.
 */
@Composable
internal fun NavigationHostVisibility(visible: Boolean, content: @Composable () -> Unit) {
    val lifecycle = rememberLifecycleOwner(
        maxLifecycle = if (visible) Lifecycle.State.RESUMED else Lifecycle.State.CREATED,
    )
    CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
        Layout(content = content, modifier = if (visible) Modifier else Modifier.clearAndSetSemantics {}) { measurables, constraints ->
            val children = measurables.map { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (visible) children.forEach { it.placeRelative(0, 0) }
            }
        }
    }
}
