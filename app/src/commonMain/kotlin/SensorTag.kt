package com.juul.sensortag

import com.benasher44.uuid.Uuid
import com.benasher44.uuid.uuidFrom
import com.juul.kable.Bluetooth
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import com.juul.kable.logs.Logging.Level.Events
import com.juul.tuulbox.encoding.toHexString
import com.juul.tuulbox.logging.Log
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Qtpy UUID
const val adafruitUuid = "0000180d-0000-1000-8000-00805f9b34Fb"

// Function call to format characteristics
private val heartSensorServiceUuid = adafruitUuid("180d")
private val heartSensorDataUuid = adafruitUuid("2a37")
private val heartNotificationUuid = adafruitUuid("2a38")

// Client Characteristic is always standard
private val clientCharacteristicConfigUuid = Bluetooth.BaseUuid + 0x2902

private val heartNotifCharacteristic = characteristicOf(
    service = heartSensorServiceUuid,
    characteristic = heartNotificationUuid,
)

private val heartDataCharacteristic = characteristicOf(
    service = heartSensorServiceUuid,
    characteristic = heartSensorDataUuid,
)

val adafruitScanner = Scanner {
    logging {
        level = Events
    }
    //filters = listOf(Filter.Service(uuidFrom(adafruitUuid)))
    filters = null
}

val adafruitServices = listOf(
    heartSensorServiceUuid,
    heartSensorDataUuid,
    heartNotifCharacteristic,
    clientCharacteristicConfigUuid,
)

class Adafruit(
    private val peripheral: Peripheral
) : Peripheral by peripheral {

    @OptIn(ExperimentalSerializationApi::class)
    val deviceData: Flow<DeviceData> = peripheral
        .observe(heartDataCharacteristic)
        .map { bytes -> ProtoBuf.decodeFromByteArray<DeviceData>(bytes) }

    val gyro: Flow<Vector3f> = peripheral
        .observe(heartDataCharacteristic)
        .map(::Vector3f)

    /** Set period, allowable range is 100-2550 ms. */
    suspend fun writeGyroPeriod(periodMillis: Long) {
        require(periodMillis in 100..2550) { "Period must be in the range 100-2550, was $periodMillis." }

        val value = periodMillis / 1
        byteArrayOf(value.toByte())

        Log.verbose { "Writing gyro period" }
        //peripheral.write(movementPeriodCharacteristic, data, WithResponse)
        Log.info { "Writing gyro period complete" }
    }

    /** Period (in milliseconds) within the range 100-2550 ms. */
    suspend fun readGyroPeriod(): Int {
        val value = peripheral.read(heartDataCharacteristic)
        Log.info { "movement → readPeriod → value = ${value.toHexString()}" }
        return value[0].toInt() and 0xFF * 10
    }

    suspend fun enableGyro() {
        Log.info { "Enabling heart rate sensor..." }
        //peripheral.write(movementConfigCharacteristic, byteArrayOf(0x7F, 0x0), WithResponse)
        Log.info { "Gyro enabled" }
    }

    suspend fun disableGyro() {
        Log.info { "disabled gyro" }
        //peripheral.write(movementConfigCharacteristic, byteArrayOf(0x0, 0x0), WithResponse)
    }
}

private fun adafruitUuid(short16BitUuid: String): Uuid =
    uuidFrom("0000${short16BitUuid.lowercase()}-0000-1000-8000-00805f9b34fb")
// uuidFrom("f000${short16BitUuid.lowercase()}-0451-4000-b000-000000000000")

private fun characteristicOf(service: Uuid, characteristic: Uuid) =
    characteristicOf(service.toString(), characteristic.toString())
