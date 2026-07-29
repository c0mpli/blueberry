plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.blueberry"

    // Current AndroidX (core-ktx 1.19, lifecycle 2.11) requires compiling against 37+.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.blueberry"
        minSdk = 29
        // Deliberately behind compileSdk: 36 is what the arm64 emulator image runs, so it is the
        // only runtime behaviour that actually gets verified here. Raise it when a 37 image ships.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        ndk {
            // The phone and the emulator are both arm64. Building x86_64 as well roughly doubles a
            // llama.cpp build for a target nothing here runs on.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // Force the native side to Release even for the debug APK.
                    //
                    // AGP passes CMAKE_BUILD_TYPE=Debug for the debug variant, which appends
                    // `-O0 -g` to every ggml source and overrides any -O3 set here. Unoptimised
                    // matmul kernels are tens of times slower: a 351-token prefill that should
                    // take about a second took over 45 seconds of four cores at 100%, which
                    // reads as a hang rather than as slowness. Debuggable Java, optimised native.
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }

    ndkVersion = "30.0.15729638"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The router is a plain-Kotlin island inside an Android module. RouterPurityTest enforces that by
// reading the sources, so it needs to know where they live.
tasks.withType<Test>().configureEach {
    systemProperty("blueberry.router.src", file("src/main/java/com/blueberry/router").absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    // Library only, no compiler plugin: the grammar guarantees the JSON shape, so this only ever
    // parses to a JsonElement tree and never needs @Serializable classes.
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
