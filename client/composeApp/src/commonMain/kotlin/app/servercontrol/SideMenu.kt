package app.servercontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private val ItemHeight = 48.dp
private val ItemGap = 2.dp
private val ItemShape = RoundedCornerShape(24.dp)

/**
 * Side menu that is only as wide as its entries need.
 * Long-press an entry and drag it to change the order (saved in the encrypted vault).
 */
@Composable
fun SideMenu(state: AppState, sections: List<Section>, onSelect: (Section) -> Unit) {
    // working copy that is reordered live while dragging
    var items by remember(sections) { mutableStateOf(sections) }
    var dragging by remember { mutableStateOf<Section?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val colors = MaterialTheme.colorScheme

    Surface(
        Modifier.fillMaxHeight().width(IntrinsicSize.Max),
        color = colors.surfaceContainerLow,
        shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(ItemGap),
        ) {
            items.forEach { s ->
                key(s) {
                    val isDragged = dragging == s
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ItemHeight)
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragged) dragOffset else 0f
                                shadowElevation = if (isDragged) 8.dp.toPx() else 0f
                                shape = ItemShape
                                clip = true
                            }
                            .background(
                                when {
                                    isDragged -> colors.surfaceContainerHighest
                                    state.section == s -> colors.secondaryContainer
                                    else -> Color.Transparent
                                },
                                ItemShape,
                            )
                            .pointerInput(s) {
                                val step = (ItemHeight + ItemGap).toPx()
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragging = s; dragOffset = 0f },
                                    onDragEnd = {
                                        dragging = null
                                        dragOffset = 0f
                                        // keep hidden sections (modules not enabled) after the visible ones
                                        state.setSectionOrder(items + state.orderedSections().filter { it !in items })
                                    },
                                    onDragCancel = { dragging = null; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    var idx = items.indexOf(s)
                                    while (dragOffset > step / 2 && idx < items.lastIndex) {
                                        items = items.toMutableList().apply { add(idx + 1, removeAt(idx)) }
                                        idx++
                                        dragOffset -= step
                                    }
                                    while (dragOffset < -step / 2 && idx > 0) {
                                        items = items.toMutableList().apply { add(idx - 1, removeAt(idx)) }
                                        idx--
                                        dragOffset += step
                                    }
                                }
                            }
                            .clickable { onSelect(s) }
                            .padding(start = 16.dp, end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(s.icon(), null)
                        Spacer(Modifier.width(12.dp))
                        Text(s.title, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
