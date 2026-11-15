package app.servercontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One line of a chart. */
// colours like Cockpit's metrics page
val ChartCpu = Color(0xFF2B7BD6)
val ChartMem = Color(0xFF8A5CC2)
val ChartIn = Color(0xFF2B7BD6)
val ChartNetIn = Color(0xFF3E9C5C)
val ChartOut = Color(0xFFE8833A)

class ChartSeries(val values: List<Double>, val color: Color, val fill: Boolean = false)

/**
 * Small line chart like Cockpit's load graphs: [series] share one y axis from 0 to
 * [maxY] (null = largest value). The newest value is on the right; [capacity] points fill the width.
 */
@Composable
fun LineChart(series: List<ChartSeries>, capacity: Int, maxY: Double? = null, height: Dp = 72.dp) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val top = maxY ?: (series.maxOfOrNull { s -> s.values.maxOrNull() ?: 0.0 } ?: 0.0).coerceAtLeast(1.0) * 1.1
    Canvas(Modifier.fillMaxWidth().height(height).padding(vertical = 2.dp)) {
        val w = size.width
        val h = size.height
        // grid: 0, 50, 100 %
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        listOf(0f, 0.5f, 1f).forEach { f ->
            drawLine(grid, Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1f, pathEffect = if (f == 1f) null else dash)
        }
        val step = w / (capacity - 1).coerceAtLeast(1)
        series.forEach { s ->
            if (s.values.size < 2) return@forEach
            val start = w - step * (s.values.size - 1)
            val path = Path()
            s.values.forEachIndexed { i, v ->
                val x = start + i * step
                val y = h - (v / top).toFloat().coerceIn(0f, 1f) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (s.fill) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(w, h)
                    lineTo(start, h)
                    close()
                }
                drawPath(area, s.color.copy(alpha = 0.15f))
            }
            drawPath(path, s.color, style = Stroke(width = 2.5f))
        }
    }
}

/** Title, current value(s) and the chart. */
@Composable
fun ChartBlock(title: String, value: String, series: List<ChartSeries>, capacity: Int, maxY: Double? = null, index: Int = -1, scale: ((Double) -> String)? = null) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (index >= 0) stripe(index) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f))
            Text(value)
        }
        LineChart(series, capacity, maxY)
        // scaled to the period's range: largest value left, smallest right
        if (scale != null) {
            val all = series.flatMap { it.values }
            Row(Modifier.fillMaxWidth()) {
                Text("${scale(all.maxOrNull() ?: 0.0)} Max.", Modifier.weight(1f), fontSize = TextSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${scale(all.minOrNull() ?: 0.0)} Min.", fontSize = TextSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
