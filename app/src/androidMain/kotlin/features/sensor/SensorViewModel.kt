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
import com.juul.sensortag.Sample
import com.juul.sensortag.Adafruit
import com.juul.sensortag.DeviceData
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
import kotlin.math.absoluteValue
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
    private val scope = CoroutineScope(peripheralScope.coroutineContext + Job(peripheralScope.coroutineContext.job))

    private val peripheral = scope.peripheral(macAddress) {
        autoConnectIf(autoConnect::value)
    }
    private val adafruit = Adafruit(peripheral)
    private val state = combine(Bluetooth.availability, peripheral.state, ::Pair)

    private val hrEstimator = HeartRateEstimator(application)

    private val _estimatedBpm = MutableStateFlow<Float?>(null)
    val estimatedBpm = _estimatedBpm.asStateFlow()

    private val ppgBuffer = mutableListOf<Float>()

    private val periodProgress = AtomicInteger()

    private var startTime: TimeMark? = null

    val data = adafruit.deviceData
        // flow combination occuring with time and sensor stream
        .onStart { startTime = TimeSource.Monotonic.markNow() }
        .scan(emptyList<Sample>()) { accumulator, value ->
            val t = startTime!!.elapsedNow().inWholeMilliseconds / 1000f
            val ppg = value.heartMeasurement?.ppgValue?.toFloat() ?: 0f
            accumulator.takeLast(50) + Sample(t, ppg)
        }
        .filter { it.size > 3 }
        .flowOn(Dispatchers.Main)

    init {
        viewModelScope.enableAutoReconnect()
        observePpgForInference()
    }

    private fun observePpgForInference() {
        adafruit.deviceData
            .map { it.heartMeasurement?.ppgValue?.toFloat() ?: 0f }
            .onEach { ppg ->
                ppgBuffer.add(ppg)
                if (ppgBuffer.size >= 1000) {
                    val window = ppgBuffer.take(1000).toFloatArray()
                    // Remove first 500 samples for 50% overlap or just clear for non-overlapping
                    // The prompt says "collect 10-second segments", implying non-overlapping or specific windowing.
                    // Let's do a simple sliding window or non-overlapping for now.
                    // For simplicity, let's clear the buffer to collect a fresh 10s.
                    ppgBuffer.clear()

                    val normalizedWindow = zScoreNormalize(window)
                    _estimatedBpm.value = hrEstimator.estimateBPM(normalizedWindow)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun zScoreNormalize(data: FloatArray): FloatArray {
        val mean = data.average().toFloat()
        val std = Math.sqrt(data.map { Math.pow((it - mean).toDouble(), 2.0) }.sum() / data.size).toFloat()
        return if (std != 0f) {
            data.map { (it - mean) / std }.toFloatArray()
        } else {
            data
        }
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
