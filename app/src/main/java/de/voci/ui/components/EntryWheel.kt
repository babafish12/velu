package de.voci.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.voci.domain.VocabItem
import de.voci.ui.theme.LocalReducedMotion
import de.voci.ui.theme.VociTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Presentation and gesture lifetime only; the coordinator owns audio, intention and completion. */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun EntryWheel(
    entries:List<VocabItem>, currentId:String?, sessionId:String, enabled:Boolean,
    onDragStart:()->Long?, onCommit:suspend(Long,String)->Unit, onCancel:(Long)->Unit,
    onSelect:(String)->Unit, modifier:Modifier=Modifier,
    translationFirst:Boolean=false,
    onPresentationChanged:(interacting:Boolean,translationFirst:Boolean)->Unit={_,_->}
) {
    if(entries.isEmpty())return
    key(sessionId) {
        val state=rememberLazyListState(entries.indexOfFirst { it.id==currentId }.coerceAtLeast(0))
        val reduced=LocalReducedMotion.current
        val decay=rememberSplineBasedDecay<Float>()
        val fling=remember(state,decay,reduced) {
            snapFlingBehavior(SnapLayoutInfoProvider(state,SnapPosition.Center),decay,tween(if(reduced)0 else 180))
        }
        val density=LocalDensity.current
        val measurer=rememberTextMeasurer()
        val style=MaterialTheme.typography.headlineLarge
        val start by rememberUpdatedState(onDragStart)
        val commit by rememberUpdatedState(onCommit)
        val cancel by rememberUpdatedState(onCancel)
        val select by rememberUpdatedState(onSelect)
        val latestEntries by rememberUpdatedState(entries)
        val presentationChanged by rememberUpdatedState(onPresentationChanged)
        var token by remember { mutableStateOf<Long?>(null) }
        var settling by remember { mutableStateOf<Job?>(null) }
        var dragging by remember { mutableStateOf(false) }
        var idleTranslationFirst by remember { mutableStateOf(translationFirst) }
        val interacting=dragging || token!=null || state.isScrollInProgress
        // A delayed preference emission must not resize rows during an owned gesture/fling.
        val displayedTranslationFirst=if(interacting)idleTranslationFirst else translationFirst
        SideEffect {
            if(!interacting)idleTranslationFirst=displayedTranslationFirst
            presentationChanged(interacting,displayedTranslationFirst)
        }

        DisposableEffect(sessionId) {
            onDispose { settling?.cancel();token?.let(cancel) }
        }
        LaunchedEffect(state) {
            state.interactionSource.interactions.collect { interaction ->
                when(interaction) {
                    is DragInteraction.Start -> {
                        dragging=true
                        presentationChanged(true,idleTranslationFirst)
                        // Inherit intention without briefly resuming the previous hold.
                        val previous=token
                        token=start()
                        settling?.cancel()
                        previous?.let(cancel)
                    }
                    is DragInteraction.Stop -> {
                        dragging=false
                        val owned=token
                        if(owned!=null)settling=launch {
                            // Stop precedes fling completion. Never commit its initial row.
                            withFrameNanos { }
                            snapshotFlow { state.isScrollInProgress }.first { !it }
                            val layout=state.layoutInfo
                            val center=(layout.viewportStartOffset+layout.viewportEndOffset)/2f
                            val nearest=layout.visibleItemsInfo.minByOrNull { abs(it.offset+it.size/2f-center) }
                            try {
                                val item=nearest?.let { latestEntries.getOrNull(it.index) }
                                if(item!=null)commit(owned,item.id)
                            } finally {
                                cancel(owned)
                                if(token==owned)token=null
                            }
                        }
                    }
                    is DragInteraction.Cancel -> {
                        dragging=false
                        settling?.cancel();token?.let(cancel);token=null
                    }
                    else -> Unit
                }
            }
        }
        BoxWithConstraints(modifier) {
            // Measure the visible language at the actual width/font scale, including long German rows.
            val width=with(density) { (maxWidth-16.dp).roundToPx().coerceAtLeast(1) }
            val heightPx=remember(entries,displayedTranslationFirst,width,style,density,measurer) {
                entries.maxOf { measurer.measure(if(displayedTranslationFirst)it.translation else it.target,style,constraints=Constraints(maxWidth=width)).size.height }
            }
            val rowHeight=with(density) { heightPx.toDp() }.plus(24.dp).coerceAtLeast(64.dp)
            LaunchedEffect(currentId,token,rowHeight,displayedTranslationFirst) {
                if(token==null && !dragging) {
                    val index=entries.indexOfFirst { it.id==currentId }
                    // Immediate alignment is neither a user selection nor an automatic spin.
                    if(index>=0)state.scrollToItem(index)
                }
            }
            LazyColumn(
                state=state,flingBehavior=fling,userScrollEnabled=enabled,
                contentPadding=PaddingValues(vertical=rowHeight),
                modifier=Modifier.fillMaxWidth().height(rowHeight*3).semantics { contentDescription="Eintragsrad" }
            ) {
                itemsIndexed(entries,key={_,item->item.id}) { index,item ->
                    val label=if(displayedTranslationFirst)item.translation else item.target
                    Box(Modifier.fillMaxWidth().height(rowHeight)
                        .selectable(selected=item.id==currentId,enabled=enabled,role=Role.RadioButton,onClick={select(item.id)})
                        .semantics { contentDescription="Eintrag auswählen: $label" }
                        .padding(horizontal=8.dp),contentAlignment=Alignment.CenterStart) {
                        Text(label,style=style,color=VociTokens.ink,modifier=Modifier.fillMaxWidth().graphicsLayer {
                            val layout=state.layoutInfo
                            val info=layout.visibleItemsInfo.firstOrNull { it.index==index }
                            val center=(layout.viewportStartOffset+layout.viewportEndOffset)/2f
                            val distance=if(info==null)0f else ((info.offset+info.size/2f-center)/info.size).coerceIn(-1.5f,1.5f)
                            rotationX=if(reduced)0f else -distance*24f
                            scaleX=if(reduced)1f else 1f-abs(distance)*.08f
                            scaleY=scaleX
                            alpha=if(reduced)1f else 1f-abs(distance)*.22f
                            cameraDistance=12f*this.density
                        })
                    }
                }
            }
        }
    }
}
