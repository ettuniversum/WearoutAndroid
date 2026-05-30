package com.juul.sensortag

import com.benasher44.uuid.uuidFrom
import com.juul.kable.Bluetooth
import com.juul.kable.Filter
import com.juul.kable.Options
import com.juul.kable.State.Disconnected
import com.juul.kable.requestPeripheral
import com.juul.sensortag.adafruitServices
import com.juul.tuulbox.logging.ConsoleLogger
import com.juul.tuulbox.logging.ConstantTagGenerator
import com.juul.tuulbox.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@JsExport
class Script {

    init {
        Log.tagGenerator = ConstantTagGenerator(tag = "Adafruit")
        Log.dispatcher.install(ConsoleLogger)
    }

    private val scope = CoroutineScope(Job())
    private var connection: Job? = null

    val availability = BluetoothAvailability(Bluetooth.availability).apply { launchIn(scope) }
    val status = Status()
    val movement = Movement()

    private val options = Options(
        filters = listOf(Filter.Service(uuidFrom(adafruitUuid))),
        optionalServices = adafruitServices,
    )

    fun connect(): Unit {
        disconnect() // Clean up previous connection, if any.

        connection = scope.launch {
            val adafruit = Adafruit(requestPeripheral(options).await())
            adafruit.establishConnection()
            enableAutoReconnect(adafruit)

            try {
                adafruit.gyro.collect(movement::emit)
            } finally {
                adafruit.disconnect()
            }
        }.apply {
            invokeOnCompletion { cause ->
                Log.info { "invokeOnCompletion $cause" }
                status.emit("Disconnected")
            }
        }
    }

    fun disconnect() {
        connection?.cancel()
        connection = null
    }

    private suspend fun Adafruit.establishConnection(): Unit = coroutineScope {
        status.emit("Connecting")
        connect()
        //enableGyro()
        status.emit("Connected")
    }

    private fun CoroutineScope.enableAutoReconnect(
        adafruit: Adafruit
    ) = adafruit.state.onEach { state ->
        Log.info { "State: ${state::class.simpleName}" }
        if (state is Disconnected) {
            Log.info { "Waiting 5 seconds to reconnect..." }
            delay(5_000L)
            adafruit.establishConnection()
        }
    }.launchIn(this)
}
