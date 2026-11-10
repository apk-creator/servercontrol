package app.servercontrol

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

private val ItemHeight = 40.dp
private val ItemGap = 1.dp
private val ItemShape = RoundedCornerShape(20.dp)

/**
 * Side menu that is only as wide as its entries need.
 * Long-press an entry and drag it: the other entries slide aside, the order is saved on release.
 */
@Composable
fun SideMenu(state: AppState, sections: List<Section>, onSelect: (Section) -> Unit) {
    var items by remember(sections) { mutableStateOf(sections) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val step = with(LocalDensity.current) { (ItemHeight + ItemGap).toPx() }
    val colors = MaterialTheme.colorScheme
    val dragging = dragIndex >= 0
    val target = if (dragging) (dragIndex + (dragOffset / step).roundToInt()).coerceIn(0, items.lastIndex) else -1

    Surface(
        Modifier.fillMaxHeight().width(IntrinsicSize.Max),
        color = colors.surfaceContainerHigh,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
    ) {
        // stay clear of the status bar / navigation bar (Android draws edge-to-edge)
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState(), enabled = !dragging)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(ItemGap),
        ) {
            items.forEachIndexed { i, s ->
                key(s) {
                    val isDragged = i == dragIndex
                    // neighbours make room for the dragged entry; snap back without animation after the drop
                    val shift = when {
                        !dragging || isDragged -> 0f
                        i in (dragIndex + 1)..target -> -step
                        i in target until dragIndex -> step
                        else -> 0f
                    }
                    val animatedShift by animateFloatAsState(shift, if (dragging) spring(stiffness = 600f) else snap(), label = "menuShift")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ItemHeight)
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragged) dragOffset.coerceIn(-i * step, (items.lastIndex - i) * step) else animatedShift
                                shadowElevation = if (isDragged) 6.dp.toPx() else 0f
                                scaleX = if (isDragged) 1.03f else 1f
                                scaleY = if (isDragged) 1.03f else 1f
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
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragIndex = items.indexOf(s); dragOffset = 0f },
                                    onDragEnd = {
                                        val from = dragIndex
                                        val to = (from + (dragOffset / step).roundToInt()).coerceIn(0, items.lastIndex)
                                        if (from >= 0 && to != from) {
                                            items = items.toMutableList().apply { add(to, removeAt(from)) }
                                            // hidden sections (modules not enabled) keep their place after the visible ones
                                            state.setSectionOrder(items + state.orderedSections().filter { it !in items })
                                        }
                                        dragIndex = -1
                                        dragOffset = 0f
                                    },
                                    onDragCancel = { dragIndex = -1; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                }
                            }
                            .clickable(enabled = !dragging) { onSelect(s) }
                            .padding(start = 12.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(s.icon(), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(s.title, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        // sign out: bottom left, apart from the sortable entries
        androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        Row(
            Modifier
                .padding(6.dp)
                .fillMaxWidth()
                .height(ItemHeight)
                .clip(ItemShape)
                .clickable(enabled = !dragging) { state.logout() }
                .padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.Logout, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Abmelden", maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
        }
    }
}
