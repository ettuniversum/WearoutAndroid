package com.juul.sensortag.features.sensor

import android.content.Context
import com.juul.tuulbox.logging.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class HeartRateEstimator private constructor(context: Context) {
    // Keep a strong reference to the model buffer to prevent Garbage Collection.
    // If this is local to 'init', the native memory becomes invalid and causes SIGABRT.
    private val tfliteModelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, MODEL_NAME)
    private val modelBuffer: MappedByteBuffer = loadModelFile(context, MODEL_NAME)

    private var interpreter: Interpreter? = null
    var isInitialized = false
        private set

    // Pre-allocate direct buffers for stability and performance.
    private val inputBuffer: FloatBuffer = ByteBuffer.allocateDirect(1000 * 4).apply {
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
        Log.info { "Initializing LiteRT Interpreter Singleton with model: $MODEL_NAME" }
        try {
            // Maximum stability configuration
            val options = Interpreter.Options().apply {
                setUseXNNPACK(false)
                setNumThreads(1)
            }

            // The interpreter uses the class-level 'modelBuffer' strong reference
            val interp = Interpreter(modelBuffer, options)

            // Explicitly define input shape [batch: 1, length: 1000, channels: 1]
            interp.resizeInput(0, intArrayOf(1, INPUT_LENGTH, 1))
            interp.allocateTensors()

            interpreter = interp
            isInitialized = true
            Log.info { "LiteRT Interpreter initialized successfully with Strong Reference and Direct Buffers" }
        } catch (t: Throwable) {
            Log.error(t) { "Failed to initialize LiteRT Interpreter: ${t.message}" }
            t.printStackTrace()
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
            inputBuffer.rewind() // Always rewind before feeding to the model!

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

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
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
