@file:OptIn(ExperimentalTime::class)

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
import com.juul.sensortag.Sample
import com.juul.sensortag.peripheralScope
import com.juul.tuulbox.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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

    private val periodProgress = AtomicInteger()

    private var startTime: TimeMark? = null

    val INPUT_LENGTH = 1000
    val data = adafruit.deviceData
        // flow combination occuring with time and sensor stream
        .onStart { startTime = TimeSource.Monotonic.markNow() }
        .scan(emptyList<Sample>()) { accumulator, value ->
            val t = startTime!!.elapsedNow().inWholeMilliseconds / 1000f
            val ppg = value.heartMeasurement?.ppgValue?.toFloat() ?: 0f
            accumulator.takeLast(100) + Sample(t, ppg)
        }
        .filter { it.size > 3 }
        .flowOn(Dispatchers.Main)

    private val _ppgSignal = MutableStateFlow<List<Float>>(emptyList())
    val ppgSignal = _ppgSignal.asStateFlow()

    init {
        hrEstimator = HeartRateEstimator.getInstance(application)
        viewModelScope.enableAutoReconnect()
        observePpgForInference()

        viewModelScope.launch {
            data.collect { samples ->
                _ppgSignal.value = samples.map { it.x }
            }
        }
    }

    private fun observePpgForInference() {
        adafruit.deviceData
            .map { it.heartMeasurement?.ppgValue?.toFloat() ?: 0f }
            .onEach { ppg ->
                ppgBuffer.add(ppg)
                if (ppgBuffer.size % 100 == 0) {
                    Log.verbose { "PPG Buffer accumulation: ${ppgBuffer.size}/${INPUT_LENGTH}" }
                }
                if (ppgBuffer.size >= INPUT_LENGTH) {
                    val rawWindow = ppgBuffer.take(1000).toFloatArray()
                    // Center the clean peaks
                    val normalizedWindow = zScoreNormalize(rawWindow)
                    // Safe C++ Tensor Inference
                    val bpm = hrEstimator.estimateBPM(normalizedWindow)
                    _estimatedBpm.value = bpm
                    ppgBuffer.clear()
                }
            }
            .launchIn(viewModelScope)
    }


    /**
     * Core IIR Difference Equation (Equivalent to Python's scipy.signal.lfilter)
     * Uses strictly primitive math and zero internal object allocations.
     */
    fun applyIIRFilter(input: FloatArray, b: FloatArray, a: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        val a0 = a[0]
        val bLen = b.size
        val aLen = a.size

        for (i in input.indices) {
            var sum = 0f

            // Feedforward calculation (b coefficients * past inputs)
            for (j in 0 until bLen) {
                if (i >= j) {
                    sum += b[j] * input[i - j]
                }
            }

            // Feedback calculation (a coefficients * past outputs)
            for (j in 1 until aLen) {
                if (i >= j) {
                    sum -= a[j] * output[i - j]
                }
            }

            output[i] = sum / a0
        }

        return output
    }

    /**
     * Zero-Phase Forward-Backward Filter (Equivalent to Python's scipy.signal.filtfilt)
     * Uses in-place array reversals to prevent massive GC sweeps.
     */
    fun zeroPhaseFilter(input: FloatArray, b: FloatArray, a: FloatArray): FloatArray {
        // 1. Forward Pass
        val forwardOutput = applyIIRFilter(input, b, a)

        // 2. Reverse the array IN-PLACE (Generates zero garbage)
        forwardOutput.reverse()

        // 3. Backward Pass
        val backwardOutput = applyIIRFilter(forwardOutput, b, a)

        // 4. Reverse the array IN-PLACE back to normal time
        backwardOutput.reverse()

        return backwardOutput
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


private suspend fun Adafruit.writeGyroPeriodProgress(progress: Int) {
    val period = progress / 100f * (2550 - 100) + 100
    Log.verbose { "period = $period" }
    //writeGyroPeriod(period.toLong())
}
