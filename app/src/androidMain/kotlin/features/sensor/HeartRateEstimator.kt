package com.juul.sensortag.features.sensor

import android.content.Context
import com.juul.tuulbox.logging.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer

class HeartRateEstimator private constructor(context: Context) {
    // Keep a strong reference to the model buffer to prevent Garbage Collection.
    // FileUtil.loadMappedFile is the robust way to load from assets.
    private val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, MODEL_NAME)

    private var interpreter: Interpreter? = null
    var isInitialized = false
        private set

    // Pre-allocate direct buffers for stability and performance.
    private val inputBuffer: FloatBuffer = ByteBuffer.allocateDirect(INPUT_LENGTH * 4).apply {
        order(ByteOrder.nativeOrder())
    }.asFloatBuffer()
    private val outputBuffer: FloatBuffer = ByteBuffer.allocateDirect(1 * 4).apply {
        order(ByteOrder.nativeOrder())
    }.asFloatBuffer()

    companion object {
        private const val MODEL_NAME = "resnet10_5gamers_quant.tflite"
        private const val INPUT_LENGTH = 1000

        @Volatile
        private var instance: HeartRateEstimator? = null

        fun getInstance(context: Context): HeartRateEstimator {
            return instance ?: synchronized(this) {
                instance ?: HeartRateEstimator(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        Log.info { "Initializing TFLite Interpreter Singleton with model: $MODEL_NAME" }
        try {
            // Stability configuration
            val options = Interpreter.Options().apply {
                setNumThreads(1)
                setUseXNNPACK(false) // Disabled to avoid "failed to create XNNPACK runtime" on some devices
            }

            val interp = Interpreter(modelBuffer, options)

            // If the model has fixed dimensions, resizeInput might not be needed.
            // But if it's dynamic, we must call it before allocateTensors.
            // We'll log the input shape to debug if needed.
            Log.info { "Model input count: ${interp.inputTensorCount}" }
            
            // interp.resizeInput(0, intArrayOf(1, INPUT_LENGTH, 1))
            interp.allocateTensors()

            interpreter = interp
            isInitialized = true
            Log.info { "TFLite Interpreter initialized successfully" }
        } catch (t: Throwable) {
            Log.error(t) { "Failed to initialize TFLite Interpreter: ${t.message}" }
        }
    }

    /**
     * Estimates the BPM from a segment of PPG data.
     */
    fun estimateBPM(normalizedWindow: FloatArray): Float {
        val interp = interpreter
        if (interp == null || !isInitialized) {
            Log.warn { "Interpreter not ready for inference" }
            return 0f
        }

        try {
            // Load the 1D FloatArray directly into the flat memory buffer
            inputBuffer.rewind()
            inputBuffer.put(normalizedWindow)
            inputBuffer.rewind()

            // Prepare output buffer
            outputBuffer.rewind()

            // Run inference safely
            interp.run(inputBuffer, outputBuffer)

            // Extract the prediction
            outputBuffer.rewind()
            val estimatedBpm = outputBuffer.get()
            return estimatedBpm
        } catch (e: Exception) {
            Log.error(e) { "Inference failed: ${e.message}" }
            return 0f
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
    }
}
