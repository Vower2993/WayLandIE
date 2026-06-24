plugins {
    id("com.android.application")
}

android {
    namespace = "io.waylandie.display"
    compileSdk = 34

    // =================================================================
    // SIGNING CONFIG — use the repo's debug.keystore so ALL CI builds
    // are signed with the SAME key. Without this, each CI runner
    // auto-generates its own debug keystore, and Android refuses to
    // install a new APK over an existing one ("app not installed")
    // because the signatures don't match.
    //
    // The keystore at app/keystore/debug.keystore is committed to the
    // repo (see .gitignore — it's explicitly whitelisted with !app/keystore/debug.keystore).
    // It uses the standard Android debug keystore credentials:
    //   alias: androiddebugkey
    //   password: android
    // =================================================================
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "io.waylandie.display"
        // CRITICAL: targetSdk MUST be 28 (Android 9).
        // Android 10+ (API 29+) enforces W^X (writable XOR executable) on
        // app data directories for apps with targetSdk >= 29. This blocks
        // execve() of binaries in getFilesDir() — which is where Wine lives.
        //
        // The native launcher (libwine_launcher.so) is in nativeLibraryDir
        // (where exec is allowed), but it can't exec the wine binary from
        // getFilesDir() because W^X checks the TARGET of execve, not the
        // caller. PRoot would bypass this via ptrace but has 2-5x overhead.
        //
        // Winlator and Termux both use targetSdk=28 for this exact reason.
        // With targetSdk=28, W^X is bypassed even on Android 14+ devices.
        //
        // Side effects (all acceptable):
        //   - foregroundServiceType manifest attr ignored (services still run)
        //   - POST_NOTIFICATIONS not requested (notifications always shown)
        //   - Scoped storage not enforced (we have MANAGE_EXTERNAL_STORAGE)
        //   - OnBackInvokedCallback no-ops (already guarded with SDK_INT)
        // All API 33+ features are guarded with Build.VERSION.SDK_INT checks.
        minSdk = 26
        targetSdk = 28
        // Bump versionCode with each build so Android always treats new CL
        // builds as an update (not a same-version install which some Android
        // versions reject). Use a timestamp-based code that always increases.
        // Format: YYMMDDHH (e.g. 26062403 = 2026-06-24 03:xx)
        // Uses System.currentTimeMillis() which is available in Kotlin DSL
        // without imports. Divides by 3600000 (ms per hour) to get an
        // always-increasing hour-based version code.
        versionCode = (System.currentTimeMillis() / 3600000).toInt()
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

    // CRITICAL: Don't let AAPT2 recompress the rootfs tarball.
    // A 164MB .tar.gz being recompressed can corrupt the asset or make it
    // inaccessible via AssetManager.open(). noCompress prevents this.
    androidResources {
        noCompress += listOf("tar.gz", "tar.xz", "tar.zst", "tzst", "zip")
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

    // Pure-Java zstd + tar decompression — Winlator's .wcp/.tzst files use
    // zstd compression. Apache Commons Compress provides ZstdCompressorInputStream
    // + TarArchiveInputStream for reliable extraction. This is the same library
    // winlator (StevenMXZ/Winlator-Ludashi) uses for all its .tzst packages.
    implementation("org.apache.commons:commons-compress:1.27.1")
    // zstd-jni is the native bindings that commons-compress uses for zstd
    implementation("com.github.luben:zstd-jni:1.5.6-2@aar")

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
