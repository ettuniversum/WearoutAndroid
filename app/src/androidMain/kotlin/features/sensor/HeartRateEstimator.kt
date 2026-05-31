package com.juul.sensortag.features.sensor

import android.content.Context
import android.content.res.AssetFileDescriptor
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

class HeartRateEstimator(context: Context) {
    private var interpreter: Interpreter? = null

    companion object {
        private const val MODEL_NAME = "resnet10_5gamers_quant.tflite"
        private const val INPUT_LENGTH = 1000
    }

    init {
        val options = Interpreter.Options().apply {
            // Optional: Use GPU delegate or multiple threads if supported
            setNumThreads(2)
        }
        interpreter = Interpreter(loadModelFile(context, MODEL_NAME), options)
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
        if (ppgWindow.size != INPUT_LENGTH) {
            throw IllegalArgumentException("Input signal must have exactly $INPUT_LENGTH samples (10 seconds at 100Hz).")
        }

        // TFLite expects shape: [batch_size, sequence_length, channels] -> [1, 1000, 1]
        // Create 3D input buffer: 1 batch, 1000 time steps, 1 channel
        val inputBuffer = Array(1) { Array(INPUT_LENGTH) { FloatArray(1) } }
        for (i in 0 until INPUT_LENGTH) {
            inputBuffer[0][i][0] = ppgWindow[i]
        }

        // TFLite output expects shape: [batch_size, output_features] -> [1, 1]
        val outputBuffer = Array(1) { FloatArray(1) }

        // Run inference
        interpreter?.run(inputBuffer, outputBuffer)

        // Return regressed BPM
        return outputBuffer[0][0]
    }

    /**
     * Release TFLite resources when done.
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
