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
import androidx.compose.foundation.layout.Box
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

/** key of the side menu order in [app.servercontrol.ui.UiOrder] */
const val MENU_ORDER_KEY = "menu"

/**
 * Side menu that is only as wide as its entries need.
 * Long-press an entry and drag it (same mechanism as the cards); the order is saved on release.
 */
@Composable
fun SideMenu(state: AppState, sections: List<Section>, onSelect: (Section) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxHeight().width(IntrinsicSize.Max),
        color = colors.surfaceContainerHigh,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
    ) {
        // stay clear of the status bar / navigation bar (Android draws edge-to-edge)
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))) {
        app.servercontrol.ui.CardColumn(MENU_ORDER_KEY, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)) {
            sections.forEach { s ->
                card(s.name) {
                    Row(
                        Modifier
                            .padding(vertical = ItemGap / 2)
                            .fillMaxWidth()
                            .height(ItemHeight)
                            .clip(ItemShape)
                            .background(if (state.section == s) colors.secondaryContainer else colors.surfaceContainerHigh, ItemShape)
                            .then(app.servercontrol.ui.LocalCardDragHandle.current)
                            .clickable { onSelect(s) }
                            .padding(start = 12.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(s.icon(), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(s.title, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                        // dot: updates available
                        if (s == Section.UPDATES && state.updateReport?.summary?.isNotEmpty() == true) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(app.servercontrol.ui.StatusWarn))
                        }
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
                .clickable { state.logout() }
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
