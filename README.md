# Wearout

**Wearout** is a modern Android application designed for real-time heart rate monitoring and signal
analysis. It provides seamless connectivity to BLE pulse sensors (such as the Bluno Beetle Arduino
chip) and leverages advanced on-device machine learning for heart rate estimation.

![Dashboard Interface](artwork/dashboard.png)

## Key Features

### 1. Modern Dashboard Interface

The application features a sleek, data-driven dashboard built entirely with **Jetpack Compose**.

* **Hero BPM Display**: A prominent heart rate indicator with a smooth pulsing animation synced to
  the detected rhythm.
* **Real-time PPG Visualization**: A high-performance live chart powered by the **Vico charting
  library**, displaying the raw PPG (Photoplethysmogram) signal with gradient fills and smooth line
  interpolation.
* **Device Telemetry**: Real-time monitoring of ESP32 battery levels and connection status via a
  dynamic pill indicator.

### 2. LiteRT (TensorFlow Lite) Integration

Wearout utilizes the latest **LiteRT** (formerly TensorFlow Lite) runtime for high-efficiency,
on-device inference.

* **1D ResNet Model**: Employs a specialized 1D Residual Network (`resnet10_5gamers_quant.tflite`)
  to process 1000-point signal windows for accurate heart rate estimation.
* **Native Stability**:
    * **Memory Mapping**: Uses `FileUtil` for robust, zero-copy model loading from assets.
    * **CPU Optimization**: Configured with optimized threading and **XNNPACK** explicitly managed
      for maximum compatibility across various Android architectures (including MediaTek and
      Qualcomm).
    * **JNI Safety**: Implements a private lock mechanism to ensure stable initialization even when
      Android Studio instrumentation tools (like Live Edit) are active.

### 3. Kable BLE Communication

Connectivity is powered by the [Kable] library, providing a clean, coroutine-based API for Bluetooth
Low Energy.

* **Reactive State**: Device state, battery levels, and sensor data are collected as a continuous
  stream and propagated through the `ViewModel` to the Compose UI.
* **Auto-reconnect**: Intelligent reconnection logic ensures monitoring continues even if the
  physical link is briefly interrupted.

## Build and Installation

### Requirements

* **Android SDK**: Compiled against API 35.
* **Kotlin**: 2.2.10
* **Compose**: 1.5.4 (with Material 3 components)

### Build Instructions

The project can be built via [Android Studio] or from the command line:

```bash
./gradlew installDebug
```

> **Note on LiteRT Stability**: If you encounter `SIGABRT` crashes during development while using "
> Live Edit" or "Apply Changes", perform a clean build:
> ```bash
> ./gradlew clean assembleDebug --no-configuration-cache
> ```

## Technical Architecture

* **UI Layer**: Jetpack Compose (Material 3).
* **Charting**: Vico (Compose-m3 integration).
* **ML Layer**: LiteRT 2.1.5 + LiteRT Support.
* **Concurrency**: Kotlin Coroutines & Flow.
* **Dependency Injection**: ViewModel with custom Factories for Activity-specific state.

## Legal Disclaimer

This codebase is intended for **Pulse Wave Morphology** analysis and wellness tracking purposes
only. It provides tools for exploring physiological signal data and general wellness monitoring.

**IMPORTANT**: This application and its associated code are **NOT** intended to be used for medical
advice, diagnosis, treatment, or clinical decision-making. Always seek the advice of a qualified
healthcare provider with any questions you may have regarding a medical condition.

# License

### MIT License

Copyright (c) 2024 Wearout Authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

### Previous License (JUUL Labs, Inc.)

[Android Studio]: https://developer.android.com/studio

[Kable]: https://github.com/JuulLabs/kable

[Web Bluetooth API: Browser compatibility]: https://developer.mozilla.org/en-US/docs/Web/API/Web_Bluetooth_API

[badge-android]: http://img.shields.io/badge/platform-android-6EDB8D.svg?style=flat

[badge-ios]: http://img.shields.io/badge/platform-ios-CDCDCD.svg?style=flat

[badge-js]: http://img.shields.io/badge/platform-js-F8DB5D.svg?style=flat

[badge-jvm]: http://img.shields.io/badge/platform-jvm-DB413D.svg?style=flat

[badge-linux]: http://img.shields.io/badge/platform-linux-2D3F6C.svg?style=flat

[badge-windows]: http://img.shields.io/badge/platform-windows-4D76CD.svg?style=flat

[badge-mac]: http://img.shields.io/badge/platform-macos-111111.svg?style=flat
