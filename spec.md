# Wearout Android - Heart Rate Estimation Specification

## TFLite Model Initialization

The application must successfully initialize the TensorFlow Lite (TFLite) ResNet model for heart rate estimation upon startup.

### Requirements

1. **Model Loading**:
    - The model file `resnet10_5gamers_quant.tflite` must be loaded from the application's assets.
    - Loading must be performed using `org.tensorflow.lite.support.common.FileUtil` to ensure robust memory mapping.
    - A strong reference to the `MappedByteBuffer` must be maintained at the class level to prevent premature garbage collection of the native memory.

2. **Interpreter Configuration**:
    - The `org.tensorflow.lite.Interpreter` must be configured with `setNumThreads(1)` for optimal performance and stability on mobile processors.
    - `setUseXNNPACK(false)` must be explicitly set to ensure compatibility across all supported device architectures and prevent "failed to create XNNPACK runtime" errors.

3. **Memory Management**:
    - Input and output buffers must be pre-allocated as `DirectBuffer` objects (`FloatBuffer`) to ensure stability and performance during inference.
    - The input buffer length must be fixed at `1000` floats.

4. **Success Verification**:
    - The `isInitialized` state of the `HeartRateEstimator` must be set to `true` only after the interpreter has successfully allocated its tensors.
    - Initialization failures must be caught and logged, and the system must remain in a safe (non-initialized) state without crashing.

## Troubleshooting and Stability

### JNI Monitor Lock Violations

If a `SIGABRT` or `JNI DETECTED ERROR IN APPLICATION: Still holding a locked object on JNI end` occurs during startup, follow these steps:

1.  **Disable Android Studio Instrumentation**:
    -   Go to **Settings > Editor > Live Edit** and disable "Live Edit".
    -   Avoid using "Apply Changes" or "Apply Code Changes" when modifying `HeartRateEstimator.kt` or its dependencies.
    -   *Reason*: These tools can interfere with the monitor locks held during the JNI-intensive TFLite initialization process.

2.  **Perform a Clean Build**:
    -   Run `./gradlew clean assembleDebug --no-configuration-cache` to ensure all stale instrumentation is cleared from the APK.

