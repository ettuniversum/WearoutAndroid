package com.juul.sensortag.features.sensor

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juul.sensortag.AppTheme

@Composable
fun DashboardScreen(
    currentBpm: Int,
    connectionStatus: String,
    batteryLevel: Int,
    ppgSignalData: List<Float>
) {
    AppTheme {
        Scaffold(
            topBar = { WearoutTopBar(connectionStatus) },
            backgroundColor = MaterialTheme.colors.background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 1. Hero Section: Pulsing Heart Rate
                HeroBpmDisplay(bpm = currentBpm)

                // 2. Secondary Metrics: Battery & Status Cards
                MetricsRow(batteryLevel = batteryLevel)

                // 3. Custom Canvas Chart: PPG Signal Visualizer
                PpgSignalChartCard(data = ppgSignalData)
            }
        }
    }
}

@Composable
fun WearoutTopBar(status: String) {
    TopAppBar(
        title = { Text("Wearout Monitor", fontWeight = FontWeight.Bold) },
        actions = {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (status == "Connected") Color(0xFF4CAF50) else Color(0xFFE57373),
                modifier = Modifier.padding(end = 16.dp)
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.overline,
                    color = Color.White
                )
            }
        }
    )
}

@Composable
fun HeroBpmDisplay(bpm: Int) {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        backgroundColor = MaterialTheme.colors.surface,
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Heart",
                tint = Color(0xFFE53935),
                modifier = Modifier
                    .size(48.dp)
                    .scale(scale)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = bpm.toString(),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colors.onSurface
                )
                Text(
                    text = " BPM",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
                )
            }
            Text(
                text = "LiteRT 1D ResNet Inference",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary
            )
        }
    }
}

@Composable
fun MetricsRow(batteryLevel: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        MetricCard(
            title = "ESP32 Battery",
            value = "$batteryLevel%",
            modifier = Modifier.weight(1f)
        )
        MetricCard(
            title = "Model Target",
            value = "HRV & Vol",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        backgroundColor = MaterialTheme.colors.surface,
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.overline,
                color = MaterialTheme.colors.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PpgSignalChartCard(data: List<Float>) {
    val primaryColor = MaterialTheme.colors.primary
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(24.dp),
        backgroundColor = MaterialTheme.colors.surface,
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Live PPG Signal",
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            RealTimePpgChart(
                data = data,
                color = primaryColor,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun RealTimePpgChart(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        // 1. Calculate the dynamic Y-range (Adaptive Scaling)
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        var hasValidData = false

        for (value in data) {
            if (!value.isNaN()) {
                if (value < minY) minY = value
                if (value > maxY) maxY = value
                hasValidData = true
            }
        }

        if (!hasValidData) return@Canvas

        // Add 10% padding to the range
        val range = (maxY - minY).coerceAtLeast(1f)
        val drawMinY = minY - (range * 0.1f)
        val drawMaxY = maxY + (range * 0.1f)
        val drawRange = drawMaxY - drawMinY

        val width = size.width
        val height = size.height
        val path = Path()
        var isFirstPoint = true

        // 2. Draw the path with NaN gap handling
        data.forEachIndexed { index, value ->
            val x = (index.toFloat() / (data.size - 1)) * width
            
            if (value.isNaN()) {
                isFirstPoint = true // Break the path at NaN
            } else {
                // Normalize Y to 0..1 based on drawRange, then scale to height
                // Note: Canvas Y is top-down, so we subtract from height
                val normalizedY = (value - drawMinY) / drawRange
                val y = height - (normalizedY * height)

                if (isFirstPoint) {
                    path.moveTo(x, y)
                    isFirstPoint = false
                } else {
                    path.lineTo(x, y)
                }
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        )
    }
}
