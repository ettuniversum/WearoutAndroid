package com.juul.sensortag.features.sensor

import android.content.Context
import com.juul.tuulbox.logging.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class HeartRateEstimator private constructor(context: Context) {
    private var interpreter: Interpreter? = null
    var isInitialized = false
        private set

    // Pre-allocate direct buffers for stability and performance.
    // TFLite/LiteRT handles flattened ByteBuffers efficiently as long as shapes are declared.
    private val inputBuffer = ByteBuffer.allocateDirect(INPUT_LENGTH * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputBuffer = ByteBuffer.allocateDirect(1 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

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
        Log.info { "Initializing LiteRT Interpreter Singleton with model: $MODEL_NAME" }
        try {
            // Maximum stability configuration
            val options = Interpreter.Options().apply {
                setUseXNNPACK(false)
                setNumThreads(1)
            }
            val modelBuffer = loadModelFile(context, MODEL_NAME)
            
            val interp = Interpreter(modelBuffer, options)
            
            // Explicitly define input shape [batch: 1, length: 1000, channels: 1]
            interp.resizeInput(0, intArrayOf(1, INPUT_LENGTH, 1))
            interp.allocateTensors()
            
            interpreter = interp
            isInitialized = true
            Log.info { "LiteRT Interpreter initialized successfully with Direct Buffers and Explicit Shape" }
        } catch (t: Throwable) {
            Log.error(t) { "Failed to initialize LiteRT Interpreter: ${t.message}" }
            t.printStackTrace()
        }
    }

    /**
     * Estimates the BPM from a segment of PPG data.
     */
    fun estimateBPM(ppgData: FloatArray): Float {
        val interp = interpreter
        if (interp == null || !isInitialized) {
            Log.warn { "Interpreter not ready for inference" }
            return 0f
        }

        try {
            // Populate direct input buffer (Zero-copy native access)
            inputBuffer.clear()
            val samplesToCopy = ppgData.size.coerceAtMost(INPUT_LENGTH)
            for (i in 0 until samplesToCopy) {
                inputBuffer.putFloat(ppgData[i])
            }
            // Ensure exactly 1000 floats are provided by zero-padding if necessary
            for (i in samplesToCopy until INPUT_LENGTH) {
                inputBuffer.putFloat(0f)
            }
            inputBuffer.rewind()

            // Reset output buffer
            outputBuffer.rewind()

            // Run inference
            interp.run(inputBuffer, outputBuffer)

            // Read result from direct buffer
            outputBuffer.rewind()
            val estimatedBpm = outputBuffer.float
            Log.info { "Inference successful. Estimated BPM: $estimatedBpm" }
            return estimatedBpm
        } catch (e: Exception) {
            Log.error(e) { "Inference failed: ${e.message}" }
            return 0f
        }
    }

    private fun loadModelFile(context: Context, modelName: String): java.nio.MappedByteBuffer {
        context.assets.openFd(modelName).use { fileDescriptor ->
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                val startOffset = fileDescriptor.startOffset
                val declaredLength = fileDescriptor.declaredLength
                return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            }
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
    }
}
