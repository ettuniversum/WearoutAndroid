package com.juul.sensortag

import com.juul.kable.State.Disconnected
import com.juul.kable.logs.Logging.Level.Data
import com.juul.kable.peripheral
import com.juul.tuulbox.logging.ConsoleLogger
import com.juul.tuulbox.logging.ConstantTagGenerator
import com.juul.tuulbox.logging.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

fun main() = runBlocking<Unit> {
    Log.tagGenerator = ConstantTagGenerator(tag = "Adafruit")
    Log.dispatcher.install(ConsoleLogger)

    Log.info { "Searching for Adafruit..." }
    val advertisement = adafruitScanner.advertisements.first()
    Log.info { "Found $advertisement" }

    val peripheral = peripheral(advertisement) {
        logging {
            level = Data
        }
    }
    val adafruit = Adafruit(peripheral)

    adafruit.gyro.onEach { rotation ->
        Log.info { rotation.toString() }
    }.launchIn(this)

    suspend fun connect() {
        Log.info { "Connecting..." }
        peripheral.connect()
        Log.info { "Connected" }

        adafruit.readGyroPeriod()
        adafruit.enableGyro()
    }

    Log.info { "Configuring auto connector" }
    peripheral.state.onEach { state ->
        Log.info { "Received state: $state" }
        if (state is Disconnected) {
            try {
                Log.verbose { "Attempting connection" }
                connect()
            } catch (e: Exception) {
                Log.error(e) { "Connect failed." }
                throw e
            }
            Log.verbose { "Waiting to reconnect" }
            delay(2.seconds) // Throttle reconnects so we don't hammer the system if connection immediately drops.
        }
    }.launchIn(this).apply {
        invokeOnCompletion { cause ->
            Log.warn(cause) { "Auto connector complete" }
        }
    }
}
