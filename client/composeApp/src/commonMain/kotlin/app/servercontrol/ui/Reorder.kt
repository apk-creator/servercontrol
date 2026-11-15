package app.servercontrol.ui

import androidx.compose.foundation.background
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
fun CardColumn(orderKey: String, modifier: Modifier = Modifier.fillMaxSize(), scroll: Boolean = true, build: CardListScope.() -> Unit) {
    val scope = CardListScope().apply(build)
    val ordered = UiOrder.sort(orderKey, scope.items) { it.first }
    var dragId by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var live by remember { mutableStateOf<List<String>?>(null) } // order while dragging
    val heights = remember { mutableStateMapOf<String, Int>() }
    val ids = live ?: ordered.map { it.first }
    val content = ordered.associate { it }
    val col = if (scroll) modifier.verticalScroll(rememberScrollState(), enabled = dragId == null) else modifier
    Column(col) {
        ids.forEach { id ->
            val block = content[id] ?: return@forEach
            key(id) {
                val dragged = id == dragId
                val handle = Modifier.pointerInput(id, orderKey) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragId = id; offset = 0f; live = ordered.map { it.first } },
                        onDragEnd = {
                            live?.let { UiOrder.set(orderKey, it) }
                            dragId = null; offset = 0f; live = null
                        },
                        onDragCancel = { dragId = null; offset = 0f; live = null },
                    ) { change, amount ->
                        change.consume()
                        offset += amount.y
                        val cur = live ?: return@detectDragGesturesAfterLongPress
                        val i = cur.indexOf(id)
                        if (offset > 0 && i < cur.lastIndex) {
                            val next = heights[cur[i + 1]] ?: 0
                            if (offset > next / 2f) {
                                live = cur.toMutableList().apply { add(i + 1, removeAt(i)) }
                                offset -= next
                            }
                        } else if (offset < 0 && i > 0) {
                            val prev = heights[cur[i - 1]] ?: 0
                            if (-offset > prev / 2f) {
                                live = cur.toMutableList().apply { add(i - 1, removeAt(i)) }
                                offset += prev
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .onSizeChanged { heights[id] = it.height }
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragged) offset else 0f
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
 */
@Composable
fun ReorderTabRow(orderKey: String, tabs: List<String>, selected: String, onSelect: (String) -> Unit) {
    val ordered = UiOrder.sort(orderKey, tabs) { it }
    var dragId by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var live by remember { mutableStateOf<List<String>?>(null) }
    val widths = remember { mutableStateMapOf<String, Int>() }
    val ids = live ?: ordered
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), enabled = dragId == null).padding(horizontal = 8.dp)) {
            ids.forEach { t ->
                key(t) {
                    val dragged = t == dragId
                    val sel = t == selected
                    Column(
                        Modifier
                            .onSizeChanged { widths[t] = it.width }
                            .zIndex(if (dragged) 1f else 0f)
                            .graphicsLayer {
                                translationX = if (dragged) offset else 0f
                                shadowElevation = if (dragged) 6.dp.toPx() else 0f
                            }
                            .background(if (dragged) colors.surfaceContainerHighest else colors.surface)
                            .pointerInput(t, orderKey) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragId = t; offset = 0f; live = ordered },
                                    onDragEnd = {
                                        live?.let { UiOrder.set(orderKey, it) }
                                        dragId = null; offset = 0f; live = null
                                    },
                                    onDragCancel = { dragId = null; offset = 0f; live = null },
                                ) { change, amount ->
                                    change.consume()
                                    offset += amount.x
                                    val cur = live ?: return@detectDragGesturesAfterLongPress
                                    val i = cur.indexOf(t)
                                    if (offset > 0 && i < cur.lastIndex) {
                                        val w = widths[cur[i + 1]] ?: 0
                                        if (offset > w / 2f) {
                                            live = cur.toMutableList().apply { add(i + 1, removeAt(i)) }; offset -= w
                                        }
                                    } else if (offset < 0 && i > 0) {
                                        val w = widths[cur[i - 1]] ?: 0
                                        if (-offset > w / 2f) {
                                            live = cur.toMutableList().apply { add(i - 1, removeAt(i)) }; offset += w
                                        }
                                    }
                                }
                            }
                            .clickable(enabled = dragId == null) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            t,
                            Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sel) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Box(Modifier.height(3.dp).fillMaxWidth().padding(horizontal = 12.dp).background(if (sel) colors.primary else colors.surface))
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

/** Tabs by title + content: remembers the selected tab, first tab of the user's order by default. */
@Composable
fun TabbedScreen(orderKey: String, tabs: List<String>, initial: String? = null, content: @Composable ColumnScope.(String) -> Unit) {
    var sel by remember(orderKey) { mutableStateOf(initial ?: UiOrder.sort(orderKey, tabs) { it }.first()) }
    androidx.compose.runtime.LaunchedEffect(initial) { if (initial != null && initial in tabs) sel = initial }
    if (sel !in tabs) sel = UiOrder.sort(orderKey, tabs) { it }.first()
    Column(Modifier.fillMaxSize()) {
        ReorderTabRow(orderKey, tabs, sel) { sel = it }
        Column(Modifier.weight(1f).fillMaxWidth()) { content(sel) }
    }
}
