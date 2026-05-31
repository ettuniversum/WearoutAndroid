package com.juul.sensortag.features.sensor

import android.content.Context
import android.content.res.AssetFileDescriptor
import com.google.android.gms.tflite.java.TfLite
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import com.juul.tuulbox.logging.Log

class HeartRateEstimator(context: Context) {
    private var interpreter: Interpreter? = null
    var isInitialized = false
        private set

    companion object {
        private const val MODEL_NAME = "resnet10_5gamers_quant.tflite"
        private const val INPUT_LENGTH = 1000
    }

    init {
        TfLite.initialize(context).addOnSuccessListener {
            try {
                val options = Interpreter.Options().apply {
                    setNumThreads(2)
                }
                interpreter = Interpreter(loadModelFile(context, MODEL_NAME), options)
                isInitialized = true
                Log.info { "TFLite Interpreter initialized successfully via Play Services" }
            } catch (e: Exception) {
                Log.error(e) { "Failed to initialize TFLite Interpreter" }
            }
        }.addOnFailureListener { e ->
            Log.error(e) { "Failed to initialize TfLite via Play Services" }
        }
    }

    /**
     * Loads the TFLite model from the assets directory.
     */
    private fun loadModelFile(context: Context, modelName: String): java.nio.MappedByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Estimates BPM from a 10-second preprocessed PPG signal window (1000 float samples).
     *
     * @param ppgWindow Preprocessed (band-pass filtered & z-score normalized) signal array.
     * @return Estimated BPM (float).
     */
    fun estimateBPM(ppgWindow: FloatArray): Float {
        val interp = interpreter
        if (interp == null || !isInitialized) {
            Log.warn { "Interpreter not ready for inference" }
            return 0f
        }

        if (ppgWindow.size != INPUT_LENGTH) {
            throw IllegalArgumentException("Input signal must have exactly $INPUT_LENGTH samples.")
        }

        // TFLite expects shape: [batch_size, sequence_length, channels] -> [1, 1000, 1]
        val inputBuffer = Array(1) { Array(INPUT_LENGTH) { FloatArray(1) } }
        for (i in 0 until INPUT_LENGTH) {
            inputBuffer[0][i][0] = ppgWindow[i]
        }

        val outputBuffer = Array(1) { FloatArray(1) }
        interp.run(inputBuffer, outputBuffer)

        return outputBuffer[0][0]
    }

    /**
     * Release TFLite resources when done.
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
    }
}
