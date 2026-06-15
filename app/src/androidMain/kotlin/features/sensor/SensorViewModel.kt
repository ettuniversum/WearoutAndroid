package com.juul.sensortag.features.sensor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juul.kable.Bluetooth
import com.juul.kable.Bluetooth.Availability.Available
import com.juul.kable.Bluetooth.Availability.Unavailable
import com.juul.kable.ConnectionLostException
import com.juul.kable.NotReadyException
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.peripheral
import com.juul.sensortag.Adafruit
import com.juul.sensortag.peripheralScope
import com.juul.tuulbox.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val reconnectDelay = 1.seconds

sealed class ViewState {

    data object BluetoothUnavailable : ViewState()

    data object Connecting : ViewState()

    data class Connected(
        val rssi: Int,
        val ppgValue: Int,
        val batteryPercentage: Int
    ) : ViewState()

    data object Disconnecting : ViewState()

    data object Disconnected : ViewState()
}

val ViewState.label: String
    get() = when (this) {
        ViewState.BluetoothUnavailable -> "Bluetooth unavailable"
        ViewState.Connecting -> "Connecting"
        is ViewState.Connected -> "Connected"
        ViewState.Disconnecting -> "Disconnecting"
        ViewState.Disconnected -> "Disconnected"
        else -> throw AssertionError()
    }

class AdafruitViewModel(
    application: Application,
    macAddress: String
) : AndroidViewModel(application) {

    private val autoConnect = MutableStateFlow(false)

    // Intermediary scope needed until https://github.com/JuulLabs/kable/issues/577 is resolved.
    private val scope =
        CoroutineScope(peripheralScope.coroutineContext + Job(peripheralScope.coroutineContext.job))

    private val peripheral = scope.peripheral(macAddress) {
        autoConnectIf(autoConnect::value)
    }
    private val adafruit = Adafruit(peripheral)
    private val state = combine(Bluetooth.availability, peripheral.state, ::Pair)


    private val _estimatedBpm = MutableStateFlow<Float?>(null)
    val estimatedBpm = _estimatedBpm.asStateFlow()
    private val hrEstimator: HeartRateEstimator
    private val ppgBuffer = mutableListOf<Float>()

    private val PPG_MAX_SAMPLES = 1000
    private val periodProgress = AtomicInteger()

    private val UI_WINDOW_MAX_SAMPLES = 200
    private val ERASE_BAR_WIDTH = 8

    // Initialize a fixed array filled with NaN (Vico will ignore NaN and leave a gap)
    private val sweepBuffer = FloatArray(UI_WINDOW_MAX_SAMPLES) { Float.NaN }
    private var penIndex = 0

    // --- DC BLOCKER STATE VARIABLES ---
    private var prevX = 0f
    private var prevY = 0f
    private val filterR = 0.95f // 95% filter strength is optimal for PPG

    /**
     * Standard DSP Difference Equation: y[n] = x[n] - x[n-1] + R * y[n-1]
     * Strips the massive DC baseline and slow drift.
     */
    private fun removeDcOffset(sample: Float): Float {
        val filteredY = sample - prevX + (filterR * prevY)
        prevX = sample
        prevY = filteredY
        return filteredY
    }

    val ppgSignal: StateFlow<List<Float>> = adafruit.deviceData
        .mapNotNull { it.heartMeasurement?.ppgValue?.toFloat() }
        .filter { it > 100f }
        .map { rawSample -> removeDcOffset(rawSample) }
        .map { filteredValue ->
            // 1. Write the new value at the "pen"
            sweepBuffer[penIndex] = filteredValue

            // 2. Erase the data just ahead of the pen to create the visual gap
            for (i in 1..ERASE_BAR_WIDTH) {
                val clearIndex = (penIndex + i) % UI_WINDOW_MAX_SAMPLES
                sweepBuffer[clearIndex] = Float.NaN
            }

            // 3. Move the pen forward, wrapping back to 0 at the edge
            penIndex = (penIndex + 1) % UI_WINDOW_MAX_SAMPLES

            // 4. Return a List copy to trigger Jetpack Compose recomposition
            sweepBuffer.toList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        hrEstimator = HeartRateEstimator.getInstance(application)
        viewModelScope.enableAutoReconnect()
        observePpgForInference()
    }

    private fun observePpgForInference() {
        adafruit.deviceData
            .map { it.heartMeasurement?.ppgValue?.toFloat() ?: 0f }
            .onEach { ppg ->
                ppgBuffer.add(ppg)
                if (ppgBuffer.size % 50 == 0) {
                    Log.verbose { "PPG Buffer accumulation: ${ppgBuffer.size}/${PPG_MAX_SAMPLES}" }
                }
                if (ppgBuffer.size >= PPG_MAX_SAMPLES) {
                    // Take the last 100 samples for the 100Hz model
                    val rawWindow10Hz = ppgBuffer.takeLast(1000).toFloatArray()
                    // Filter the noise and upsample to 100Hz (1000 samples)
                    val upsampledWindow100Hz = filterAndUpsample(rawWindow10Hz, targetSize = 1000)
                    // Center the clean peaks
                    val normalizedWindow = zScoreNormalize(upsampledWindow100Hz)
                    // Safe C++ Tensor Inference
                    val bpm = hrEstimator.estimateBPM(normalizedWindow)
                    _estimatedBpm.value = bpm
                    ppgBuffer.clear()
                }
            }
            .launchIn(viewModelScope)
    }


    /**
     * Applies a Low-Pass Filter (EMA) to 10Hz data, then upsamples it
     * to the target size using a Catmull-Rom Cubic Spline.
     */
    fun filterAndUpsample(input: FloatArray, targetSize: Int): FloatArray {
        if (input.isEmpty()) return FloatArray(targetSize)

        // STEP 1: Exponential Moving Average (EMA) Filter
        // Alpha controls smoothing. 1.0 = no smoothing. 0.3 = heavy smoothing.
        // This destroys the dicrotic notch so the spline doesn't double-count peaks.
        val alpha = 0.3f
        val filtered = FloatArray(input.size)
        filtered[0] = input[0]
        for (i in 1 until input.size) {
            filtered[i] = alpha * input[i] + (1 - alpha) * filtered[i - 1]
        }

        // STEP 2: Catmull-Rom Cubic Spline Interpolation
        val output = FloatArray(targetSize)
        // The step size to map 100 points across 1000 points
        val ratio = (filtered.size - 1).toFloat() / (targetSize - 1).toFloat()

        for (i in 0 until targetSize) {
            val exactPosition = i * ratio
            val index = exactPosition.toInt()
            val t = exactPosition - index // The fractional distance between two points

            // Grab the 4 points needed to calculate the cubic curve
            // coerceAtLeast and coerceAtMost prevent array out-of-bounds errors at the edges
            val p0 = filtered[(index - 1).coerceAtLeast(0)]
            val p1 = filtered[index]
            val p2 = filtered[(index + 1).coerceAtMost(filtered.size - 1)]
            val p3 = filtered[(index + 2).coerceAtMost(filtered.size - 1)]

            // The Catmull-Rom Spline Polynomial Math
            val t2 = t * t
            val t3 = t2 * t
            val interpolatedValue = 0.5f * (
                    (2f * p1) +
                            (-p0 + p2) * t +
                            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                            (-p0 + 3f * p1 - 3f * p2 + p3) * t3
                    )

            output[i] = interpolatedValue
        }

        return output
    }

    fun zScoreNormalize(window: FloatArray): FloatArray {
        if (window.isEmpty()) return window

        // 1. Calculate Mean (No boxing)
        var sum = 0f
        for (value in window) {
            sum += value
        }
        val mean = sum / window.size

        // 2. Calculate Standard Deviation (No map, no Math.pow)
        var varianceSum = 0f
        for (value in window) {
            val diff = value - mean
            varianceSum += diff * diff // Much faster than Math.pow
        }
        val variance = varianceSum / window.size
        val stdDev = kotlin.math.sqrt(variance.toDouble()).toFloat()

        // 3. Safe Epsilon
        val safeStdDev = if (stdDev > 0f) stdDev else 1e-8f

        // 4. Output Array (Allocated exactly once, as primitives)
        val normalizedWindow = FloatArray(window.size)
        for (i in window.indices) {
            normalizedWindow[i] = (window[i] - mean) / safeStdDev
        }

        return normalizedWindow
    }

    private fun CoroutineScope.enableAutoReconnect() {
        state.filter { (bluetoothAvailability, connectionState) ->
            bluetoothAvailability == Available && connectionState is State.Disconnected
        }.onEach {
            ensureActive()
            Log.info { "Waiting $reconnectDelay to reconnect..." }
            delay(reconnectDelay)
            connect()
        }.launchIn(this)
    }

    private fun CoroutineScope.connect() {
        launch {
            Log.debug { "Connecting" }
            try {
                peripheral.connect()
                autoConnect.value = true
                //adafruit.enableGyro()
                //adafruit.writeGyroPeriodProgress(periodProgress.get())
            } catch (e: ConnectionLostException) {
                autoConnect.value = false
                Log.warn(e) { "Connection attempt failed" }
            }
        }
    }

    val viewState: Flow<ViewState> = state
        .flatMapLatest { (bluetoothAvailability, state) ->
            if (bluetoothAvailability is Unavailable) {
                return@flatMapLatest flowOf(ViewState.BluetoothUnavailable)
            }
            when (state) {
                is State.Connecting -> flowOf(ViewState.Connecting)
                // Combining RSSI and sensor data
                State.Connected -> combine(
                    peripheral.remoteRssi(),
                    adafruit.deviceData
                ) { rssi, deviceData ->
                    ViewState.Connected(
                        rssi,
                        deviceData.heartMeasurement?.ppgValue ?: 0,
                        deviceData.batteryStatus?.percentage ?: 0
                    )
                }

                State.Disconnecting -> flowOf(ViewState.Disconnecting)
                is State.Disconnected -> flowOf(ViewState.Disconnected)
            }
        }

    fun setPeriod(progress: Int) {
        periodProgress.set(progress)
        viewModelScope.launch {
            //adafruit.writeGyroPeriodProgress(progress)
        }
    }

    override fun onCleared() {
        hrEstimator.close()
        peripheralScope.launch {
            viewModelScope.coroutineContext.job.join()
            peripheral.disconnect()
            scope.cancel()
        }
    }
}

private fun Peripheral.remoteRssi() = flow {
    while (true) {
        val rssi = rssi()
        Log.debug { "RSSI: $rssi" }
        emit(rssi)
        delay(1_000L)
    }
}.catch { cause ->
    // todo: Investigate better way of handling this failure case.
    // When disconnecting, we may attempt to read `rssi` causing a `NotReadyException` but the hope is that `remoteRssi`
    // Flow would already be cancelled by the time the `Peripheral` is "not ready" (doesn't seem to be the case).
    if (cause !is NotReadyException) throw cause
}

