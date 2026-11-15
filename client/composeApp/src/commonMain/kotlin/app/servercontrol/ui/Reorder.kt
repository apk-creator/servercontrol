package app.servercontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Order of tabs and cards that the user changed by long-press + drag, per screen key.
 * Filled from the vault at sign-in; [onChange] saves it back.
 */
object UiOrder {
    val orders = mutableStateMapOf<String, List<String>>()
    var onChange: (Map<String, List<String>>) -> Unit = {}

    fun <T> sort(key: String, items: List<T>, id: (T) -> String): List<T> {
        val stored = orders[key] ?: return items
        val byId = items.associateBy(id)
        return stored.mapNotNull { byId[it] } + items.filter { id(it) !in stored }
    }

    fun set(key: String, ids: List<String>) {
        // keep ids that are not visible right now (e.g. a card shown only in some states) at the end
        orders[key] = ids + (orders[key].orEmpty() - ids.toSet())
        onChange(orders.toMap())
    }
}

/**
 * Drag state shared by [CardColumn] (vertical) and [ReorderTabRow] (horizontal):
 * swaps neighbours when the dragged item passes half of them and scrolls when the finger
 * rests near the edge of the visible area.
 */
private class DragReorder {
    var dragId by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)
    var live by mutableStateOf<List<String>?>(null)
    val sizes = mutableStateMapOf<String, Int>()
    var pointer by mutableFloatStateOf(0f)          // finger position, root coordinates (drag axis)
    var itemStart = mutableStateMapOf<String, Float>() // item start in root coordinates
    var viewStart by mutableFloatStateOf(0f)
    var viewEnd by mutableFloatStateOf(0f)

    fun start(id: String, order: List<String>) { dragId = id; offset = 0f; live = order }
    fun stop() { dragId = null; offset = 0f; live = null }

    fun move(delta: Float) {
        val id = dragId ?: return
        offset += delta
        val cur = live ?: return
        val i = cur.indexOf(id)
        if (offset > 0 && i < cur.lastIndex) {
            val next = sizes[cur[i + 1]] ?: 0
            if (offset > next / 2f) { live = cur.toMutableList().apply { add(i + 1, removeAt(i)) }; offset -= next }
        } else if (offset < 0 && i > 0) {
            val prev = sizes[cur[i - 1]] ?: 0
            if (-offset > prev / 2f) { live = cur.toMutableList().apply { add(i - 1, removeAt(i)) }; offset += prev }
        }
    }
}

/** while dragging: scroll when the finger is within [edge] px of the start or end of the visible area */
@Composable
private fun AutoScroll(d: DragReorder, scroll: ScrollState?, edge: Float) {
    LaunchedEffect(d.dragId, scroll) {
        if (d.dragId == null || scroll == null) return@LaunchedEffect
        while (d.dragId != null) {
            val speed = when {
                d.pointer < d.viewStart + edge -> -(edge - (d.pointer - d.viewStart)).coerceAtMost(edge) / 3f
                d.pointer > d.viewEnd - edge -> (edge - (d.viewEnd - d.pointer)).coerceAtMost(edge) / 3f
                else -> 0f
            }
            if (speed != 0f) {
                val used = scroll.scrollBy(speed)
                // the content moved under the finger: keep the dragged item under it
                if (used != 0f) d.move(used)
            }
            delay(16)
        }
    }
}

/** Modifier for the title row of a card inside a [CardColumn]: long-press there and drag. */
val LocalCardDragHandle = compositionLocalOf<Modifier> { Modifier }

class CardListScope {
    internal val items = mutableListOf<Pair<String, @Composable () -> Unit>>()
    /** a card (or any block) with a stable [id]; long-press its title row to move it */
    fun card(id: String, content: @Composable () -> Unit) {
        items += id to content
    }
}

/**
 * Column of cards the user can rearrange: long-press a card's title row and drag it up or down.
 * The order is saved under [orderKey].
 */
@Composable
fun CardColumn(
    orderKey: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    scroll: Boolean = true,
    scrollState: ScrollState? = null,
    build: CardListScope.() -> Unit,
) {
    val scope = CardListScope().apply(build)
    val ordered = UiOrder.sort(orderKey, scope.items) { it.first }
    val order by rememberUpdatedState(ordered.map { it.first })
    val d = remember(orderKey) { DragReorder() }
    val ids = d.live ?: order
    val content = ordered.associate { it }
    val sc = if (scroll) scrollState ?: rememberScrollState() else null
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    AutoScroll(d, sc, edge)
    val col = if (sc != null) modifier.verticalScroll(sc, enabled = d.dragId == null) else modifier
    Column(col.onGloballyPositioned { c ->
        val top = c.positionInRoot().y
        d.viewStart = top; d.viewEnd = top + c.size.height
    }) {
        ids.forEach { id ->
            val block = content[id] ?: return@forEach
            key(id) {
                val dragged = id == d.dragId
                val handle = Modifier.pointerInput(id, orderKey) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos -> d.start(id, order); d.pointer = (d.itemStart[id] ?: 0f) + pos.y },
                        // positions are local to the (moved) card
                        onDragEnd = { d.live?.let { UiOrder.set(orderKey, it) }; d.stop() },
                        onDragCancel = { d.stop() },
                    ) { change, amount ->
                        change.consume()
                        d.pointer = (d.itemStart[id] ?: 0f) + d.offset + change.position.y
                        d.move(amount.y)
                    }
                }
                Box(
                    Modifier
                        .onSizeChanged { d.sizes[id] = it.height }
                        .onGloballyPositioned { d.itemStart[id] = it.positionInRoot().y }
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragged) d.offset else 0f
                            shadowElevation = if (dragged) 8.dp.toPx() else 0f
                            alpha = if (dragged) 0.92f else 1f
                        },
                ) {
                    CompositionLocalProvider(LocalCardDragHandle provides handle) { block() }
                }
            }
        }
    }
}

/**
 * Tab row like the Nextcloud tabs; long-press a tab and drag it sideways to move it.
 * Tabs are addressed by their title; the order is saved under [orderKey].
 * [fixedLast] is an extra tab (e.g. "+") that always stays at the right end.
 */
@Composable
fun ReorderTabRow(orderKey: String, tabs: List<String>, selected: String, fixedLast: String? = null, onSelect: (String) -> Unit) {
    val ordered = UiOrder.sort(orderKey, tabs) { it }
    val order by rememberUpdatedState(ordered)
    val d = remember(orderKey) { DragReorder() }
    val ids = d.live ?: ordered
    val colors = MaterialTheme.colorScheme
    val sc = rememberScrollState()
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    AutoScroll(d, sc, edge)
    @Composable
    fun TabLabel(t: String, sel: Boolean) {
        Text(
            t,
            Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
            color = if (sel) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
        )
        Box(Modifier.height(3.dp).fillMaxWidth().padding(horizontal = 12.dp).background(if (sel) colors.primary else colors.surface))
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { c -> val l = c.positionInRoot().x; d.viewStart = l; d.viewEnd = l + c.size.width }
                .horizontalScroll(sc, enabled = d.dragId == null)
                .padding(horizontal = 8.dp),
        ) {
            ids.forEach { t ->
                key(t) {
                    val dragged = t == d.dragId
                    Column(
                        Modifier
                            .onSizeChanged { d.sizes[t] = it.width }
                            .onGloballyPositioned { d.itemStart[t] = it.positionInRoot().x }
                            .zIndex(if (dragged) 1f else 0f)
                            .graphicsLayer {
                                translationX = if (dragged) d.offset else 0f
                                shadowElevation = if (dragged) 6.dp.toPx() else 0f
                            }
                            .background(if (dragged) colors.surfaceContainerHighest else colors.surface)
                            .pointerInput(t, orderKey) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { pos -> d.start(t, order); d.pointer = (d.itemStart[t] ?: 0f) + pos.x },
                                    onDragEnd = { d.live?.let { UiOrder.set(orderKey, it) }; d.stop() },
                                    onDragCancel = { d.stop() },
                                ) { change, amount ->
                                    change.consume()
                                    d.pointer = (d.itemStart[t] ?: 0f) + d.offset + change.position.x
                                    d.move(amount.x)
                                }
                            }
                            .clickable(enabled = d.dragId == null) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { TabLabel(t, t == selected) }
                }
            }
            if (fixedLast != null) Column(Modifier.clickable { onSelect(fixedLast) }, horizontalAlignment = Alignment.CenterHorizontally) {
                TabLabel(fixedLast, fixedLast == selected)
            }
        }
        HorizontalDivider()
    }
}

/** Tabs by title + content: remembers the selected tab, first tab of the user's order by default. */
@Composable
fun TabbedScreen(orderKey: String, tabs: List<String>, initial: String? = null, fixedLast: String? = null, content: @Composable ColumnScope.(String) -> Unit) {
    val all = tabs + listOfNotNull(fixedLast)
    var sel by remember(orderKey) { mutableStateOf(initial ?: UiOrder.sort(orderKey, tabs) { it }.firstOrNull() ?: fixedLast ?: "") }
    LaunchedEffect(initial) { if (initial != null && initial in all) sel = initial }
    if (sel !in all) sel = UiOrder.sort(orderKey, tabs) { it }.firstOrNull() ?: fixedLast ?: ""
    Column(Modifier.fillMaxSize()) {
        ReorderTabRow(orderKey, tabs, sel, fixedLast) { sel = it }
        Column(Modifier.weight(1f).fillMaxWidth()) { content(sel) }
    }
}
