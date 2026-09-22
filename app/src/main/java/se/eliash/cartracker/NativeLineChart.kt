package se.eliash.cartracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Line chart with a gradient fill, drawn directly on a Canvas. */
@Composable
fun NativeLineChart(data: List<Double>, title: String, lineColor: Color) {
    if (data.isEmpty()) return
    val maxVal = (data.maxOrNull() ?: 10.0) + (data.maxOrNull() ?: 10.0) * 0.1
    val minVal = ((data.minOrNull() ?: 0.0) - (data.minOrNull() ?: 0.0) * 0.1).coerceAtLeast(0.0)
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                val width = size.width
                val height = size.height
                val xStep = if (data.size > 1) width / (data.size - 1) else width
                val yRange = if (maxVal == minVal) 1.0 else maxVal - minVal
                val strokePath = Path()
                val fillPath = Path()
                fillPath.moveTo(0f, height)
                data.forEachIndexed { index, value ->
                    val x = index * xStep
                    val y = height - ((value - minVal) / yRange * height).toFloat()
                    if (index == 0) { strokePath.moveTo(x, y); fillPath.lineTo(x, y) } else { strokePath.lineTo(x, y); fillPath.lineTo(x, y) }
                    drawCircle(color = lineColor, radius = 6f, center = Offset(x, y))
                }
                fillPath.lineTo(width, height)
                fillPath.close()
                drawPath(path = fillPath, brush = Brush.verticalGradient(colors = listOf(lineColor.copy(alpha = 0.4f), Color.Transparent), startY = 0f, endY = height))
                drawPath(path = strokePath, color = lineColor, style = Stroke(width = 6f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lowest: %.2f".format(Locale("sv", "SE"), data.minOrNull() ?: 0.0), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("Highest: %.2f".format(Locale("sv", "SE"), data.maxOrNull() ?: 0.0), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}
