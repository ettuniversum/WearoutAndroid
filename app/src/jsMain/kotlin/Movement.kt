package com.juul.sensortag

typealias MovementListener = (x: Float) -> Unit

@JsExport
class Movement {

    private val listeners = mutableListOf<MovementListener>()

    internal fun emit(movement: Vector3f) {
        val (x) = movement
        listeners.forEach { listener -> listener.invoke(x) }
    }

    fun addListener(listener: MovementListener) { listeners += listener }
    fun removeListener(listener: MovementListener) { listeners -= listener }
}
