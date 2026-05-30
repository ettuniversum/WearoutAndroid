package com.juul.sensortag

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BatteryStatus(
    @ProtoNumber(1) val voltage: Float,
    @ProtoNumber(2) val percentage: Int,
    @ProtoNumber(3) val isCharging: Boolean = false
)

@Serializable
data class HeartMeasurementData(
    @ProtoNumber(1) val ppgValue: Int
)

@Serializable
data class DeviceData(
    @ProtoNumber(1) val heartMeasurement: HeartMeasurementData? = null,
    @ProtoNumber(2) val batteryStatus: BatteryStatus? = null
)
