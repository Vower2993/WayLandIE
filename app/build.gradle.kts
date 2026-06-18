plugins {
    id("com.android.application")
}

android {
    namespace = "io.waylandie.display"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.waylandie.display"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0-no-root"

        // Bundle the Linux runtime installer scripts as app assets.
        // The SetupWizardActivity extracts them to app-external storage and
        // then triggers Termux RUN_COMMAND intents to execute them.
        assets.srcDirs("src/main/assets")

        // Build all the ABIs the project supports. arm64-v8a is the only one
        // the original native code was written for, but armeabi-v7a and
        // x86_64 are also emitted so emulator / older device users can
        // at least boot the Java fallback path.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // Aggressive optimization for the dmabuf/Vulkan presenter.
                // -O3 + LTO + fast-math matters for the per-frame presenter.
                cppFlags += listOf("-std=c17", "-Wall", "-Wextra", "-Wno-unused-parameter")
                cFlags += listOf("-std=c17", "-Wall", "-Wextra", "-Wno-unused-parameter")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-33"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false

            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                    cFlags += listOf("-O2", "-g")
                }
            }
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false

            // Force-enable the same hardening flags the native code expects.
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                    cFlags += listOf(
                        "-O3",
                        "-flto",
                        "-ffunction-sections",
                        "-fdata-sections",
                        "-fvisibility=hidden",
                        "-fno-unwind-tables",
                        "-fno-asynchronous-unwind-tables"
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true   // required by extractNativeLibs=true in manifest
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
            res.srcDirs("src/main/res")
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    // AndroidX — minimal. We deliberately avoid AppCompat/Material to keep the
    // APK small and avoid pulling in transitive deps that touch the GPU path.
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.activity:activity:1.9.0")
}
