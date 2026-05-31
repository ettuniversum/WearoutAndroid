plugins {
    id("com.android.application")
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(11)

    androidTarget()
    js {
        browser()
        binaries.executable()
    }
    macosX64 {
        binaries {
            executable {
                baseName = "adafruit"
                entryPoint = "com.juul.sensortag.main"
            }
        }
    }
    macosArm64 {
        binaries {
            executable {
                baseName = "adafruit"
                entryPoint = "com.juul.sensortag.main"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.coroutines.core)
                implementation(libs.kable)
                implementation(libs.tuulbox.logging)
                implementation(libs.tuulbox.encoding)
                implementation(libs.tuulbox.coroutines)
                implementation(libs.kotlinx.serialization.protobuf)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.bundles.compose)
                implementation(libs.bundles.accompanist)
                implementation(libs.exercise.annotations)
                implementation(libs.bundles.krayon)
                implementation(libs.play.services.tflite.java)
                implementation(libs.play.services.tflite.binaries)
            }
        }

        val nativeDarwinMain by creating {
            dependsOn(commonMain)
        }

        val macosX64Main by getting {
            dependsOn(nativeDarwinMain)
        }

        val macosArm64Main by getting {
            dependsOn(nativeDarwinMain)
        }
    }
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    compileSdk = libs.versions.android.compile.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.min.get().toInt()
        targetSdk = libs.versions.android.target.get().toInt()
    }

    namespace = "com.juul.sensortag"

    androidResources {
        noCompress += "tflite"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources.excludes.add("/META-INF/versions/*/previous-compilation-data.bin")
    }
}

dependencies {
    add("kspAndroid", libs.exercise.compile)
}
