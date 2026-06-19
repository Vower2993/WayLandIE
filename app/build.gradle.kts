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

        // Pin the NDK version so AGP doesn't try to auto-download a
        // different one. Matches the version in the GitHub Actions
        // workflow and the README's manual install instructions.
        ndkVersion = "26.1.10909125"

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
            // Pin to 3.22.1 — matches the version installed by the
            // GitHub Actions workflow and is the AGP 8.5 default.
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
            assets {
                srcDirs("src/main/assets")
            }
        }
    }
}

dependencies {
    // AndroidX — minimal. We deliberately avoid AppCompat/Material to keep the
    // APK small and avoid pulling in transitive deps that touch the GPU path.
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.activity:activity:1.9.0")

    // Pure-Java XZ decompression — Android doesn't ship an `xz` binary,
    // so we can't use `tar -xJf` or `xz -dc | tar`. This library lets us
    // decompress .tar.xz entirely in Java.
    implementation("org.tukaani:xz:1.10")

    // Force Kotlin stdlib to a consistent version. androidx.activity:1.9.0
    // pulls in the modern kotlin-stdlib:1.8.22 (which absorbed the old
    // jdk7/jdk8 splits), but other AndroidX deps transitively pull in the
    // older kotlin-stdlib-jdk7/jdk8:1.6.21 splits, causing duplicate class
    // errors at the checkDebugDuplicateClasses task. Constraints force the
    // old splits up to 1.8.22 so they no longer duplicate the merged-in
    // classes from the main stdlib.
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-common:1.8.22")
    }
}
