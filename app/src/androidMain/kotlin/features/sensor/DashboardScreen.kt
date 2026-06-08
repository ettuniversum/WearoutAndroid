package com.juul.sensortag.features.sensor

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juul.sensortag.AppTheme
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.compose.component.shape.shader.verticalGradient
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import com.patrykandpatrick.vico.core.entry.ChartEntryModel
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf

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

                // 3. Modern Chart: PPG Signal Visualizer
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
            // Connection indicator pill
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
    // Subtle pulsing animation for the heart icon
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
            Text(text = title, style = MaterialTheme.typography.overline, color = MaterialTheme.colors.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PpgSignalChartCard(data: List<Float>) {
    val chartEntryModelProducer = remember { ChartEntryModelProducer() }

    LaunchedEffect(data) {
        if (data.isNotEmpty()) {
            val entries = data.mapIndexed { index, amplitude ->
                entryOf(x = index.toFloat(), y = amplitude)
            }
            chartEntryModelProducer.setEntries(listOf(entries))
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(24.dp),
        backgroundColor = MaterialTheme.colors.surface,
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Live PPG Signal (${data.size})", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            val axisValuesOverrider = remember {
                object : AxisValuesOverrider<ChartEntryModel> {
                    override fun getMinY(model: ChartEntryModel): Float =
                        model.minY - (model.maxY - model.minY).coerceAtLeast(1f) * 0.2f

                    override fun getMaxY(model: ChartEntryModel): Float =
                        model.maxY + (model.maxY - model.minY).coerceAtLeast(1f) * 0.2f
                }
            }

            Chart(
                modifier = Modifier.fillMaxSize(),
                chart = lineChart(
                    axisValuesOverrider = axisValuesOverrider,
                    lines = listOf(
                        LineChart.LineSpec(
                            lineColor = Color.Yellow.toArgb(),
                            lineThicknessDp = 3f,
                        )
                    )
                ),
                chartModelProducer = chartEntryModelProducer,
                startAxis = rememberStartAxis(guideline = null),
                chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = false)
            )
        }
    }
}
