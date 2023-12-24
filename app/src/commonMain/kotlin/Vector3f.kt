package com.juul.sensortag

//import java.nio.ByteBuffer
data class Vector3f(val x: Float)
fun Vector3f(data: ByteArray) = Vector3f(
    x = data.readShort(0).toFloat(),
)
operator fun Vector3f.times(scalar: Float) = Vector3f(x * scalar)

// readShort is little endian (or less than 127)
private inline fun ByteArray.readShort(offset: Int): Short {
    val value = get(offset) and 0xff or (get(offset + 1) and 0xff shl 8)
    return value.toShort()
}

private inline infix fun Byte.and(other: Int): Int = toInt() and other
