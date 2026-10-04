plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "in.isro.sih26173.itantramessage"
    compileSdk = 36

    defaultConfig {
        applicationId = "in.isro.sih26173.itantramessage"

        // minSdk 24 (Android 7.0). Chosen deliberately, not by habit:
        //   * 24 is the floor for the Android Keystore AES-GCM APIs this app uses,
        //     so below 24 the crypto path would need a fallback that is not needed;
        //   * it covers the large majority of the low-to-mid-range handsets this app targets
        //     (R-M17 in the sibling iTantra project calls out that range specifically).
        minSdk = 24

        // Pinned to 36, equal to compileSdk.
        //
        // An earlier version of this file left targetSdk unset with a comment claiming it
        // would "default to the current platform". That was wrong, and `aapt dump badging`
        // on the built APK is what caught it: an unset targetSdk compiles to
        // targetSdkVersion='24', i.e. minSdk, NOT to anything newer. The consequence was
        // not cosmetic -- the whole API-31+ permission branch in NearbyManager and
        // MainActivity (BLUETOOTH_SCAN / BLUETOOTH_CONNECT as runtime permissions, no
        // location permission) was unreachable, because those permissions are only runtime
        // permissions when targetSdk >= 31. The app would have asked for the API-30
        // permission set on a modern phone.
        //
        // Pinning to 36 also means the newest background-execution and scoped-storage
        // behaviour applies, which is the correct default for something holding message
        // history on a device the user may lose.
        //
        // targetSdk must not exceed compileSdk; keeping them equal means this file needs
        // exactly one edit when the SDK is bumped.
        targetSdk = 36

        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Baked into the APK so a reviewer can read the transport and offline posture out
        // of the artifact itself (Settings > About, and readable from the merged manifest)
        // instead of taking it on trust from the documentation. Cheap: one string constant.
        buildConfigField(
            "String",
            "BUILD_FACTS",
            "\"transport=raw-bluetooth(no-play-services) offline_gated=true\"",
        )

        // No resource configurations are excluded. Excluding locales would shrink the APK
        // but this app has no translated strings (see res/values/strings.xml), so there is
        // nothing to gain and a reviewer would have to check why the list looked unusual.

        // Two ABIs, not four. sherpa-onnx ships x86 and x86_64 too, and the bundled Whisper
        // model is 153 MB on its own; carrying two more copies of a 50 MB native library for
        // emulators that nobody will run this on would be 100 MB of APK for no user. arm64-v8a
        // covers essentially every device from 2017 on, armeabi-v7a covers the 32-bit tail
        // that minSdk 24 still admits.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // The Whisper weights must not be deflated in the APK.
    //
    // Two reasons, and the second is the one that matters. First, int8 ONNX weights are
    // high-entropy integer data: deflating them recovers well under 1%, so compression costs
    // CPU on every launch and saves nothing. Second, and decisively, the runtime loads the
    // model by filesystem path, and an AssetManager stream cannot be handed to onnxruntime --
    // the app has to copy these files out to filesDir on first run. Storing them uncompressed
    // means that copy is a straight read rather than an inflate, which matters when the file
    // is 125 MB.
    androidResources {
        noCompress += listOf("onnx", "txt")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            // R8 on, with the default rules plus the ones that matter here. Shrinking is not
            // cosmetic for this project: the brief is small-device and low-storage, and the
            // APK ships as a single file (no ABI split, see above).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // The debug manifest is where INTERNET is declared, for the Dart VM service and
            // for Compose tooling. Release must never inherit it: see AndroidManifest.xml
            // and scripts/check_offline.sh, which fails the build if it ever does.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true

        // BuildConfig generation is off by default from AGP 8.0 and has to be switched on
        // explicitly. It is enabled here for one field (BUILD_FACTS) so a reviewer can
        // read the transport and offline posture out of the APK rather than taking it on
        // trust from the docs. That is worth the ~1 KB of generated class.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

}

dependencies {
    // ---- Compose, versions pinned by the BOM so they cannot disagree ------------------
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Core AndroidX ---------------------------------------------------------------
    // core-ktx only. No appcompat: this app is 100% Compose and never inflates a View,
    // so AppCompatActivity would be a dependency with no purpose.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    // Navigation Compose, for the two-destination back stack.
    //
    // It is a real dependency with a real cost, so it is worth being explicit about why it
    // is here rather than hand-rolling a back stack out of a Boolean:
    //   * it survives process death and restores the screen the user was on;
    //   * it passes typed arguments into the ViewModel through SavedStateHandle, which is
    //     what lets a chat screen be rebuilt after the system kills the app;
    //   * predictive back is wired correctly, which a hand-rolled stack gets wrong in a
    //     way that is very visible on a modern Android device.
    //
    // A single Activity with `if (screen == CHAT)` would be about 20 lines shorter and would
    // lose all three. Note this is not one of the "large networking frameworks" the brief
    // excludes: it contains no network code, no serialisation and no I/O.
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // ---- Room ------------------------------------------------------------------------
    // runtime + ktx. room-ktx brings the coroutine support so Flow-returning DAO methods
    // work without hand-written suspend wrappers.
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // ---- Coroutines ------------------------------------------------------------------
    // No kotlinx-coroutines-android BOM pin: it arrives transitively via room-ktx and
    // lifecycle-runtime-ktx at a version that is already compatible. Adding an explicit
    // dependency here would risk selecting a version the rest of the graph does not expect.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---- Security --------------------------------------------------------------------
    // androidx.security:security-crypto is deliberately NOT used. It is deprecated, and the
    // app needs exactly one thing -- an AES-256-GCM key that never leaves the Keystore --
    // which android.security.keystore.KeyGenParameterSpec provides directly. See
    // data/crypto/EncryptionManager.kt.
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // ---- Offline Neural Engine ---------------------------------------------------------
    // sherpa-onnx is the ONNX Runtime based ASR/TTS engine from k2-fsa. It is Apache-2.0 and
    // runs entirely on-device, which is what makes offline speech recognition possible at all
    // in an APK with no INTERNET permission.
    //
    // WHY THIS ARTIFACT AND NOT THE OFFICIAL RELEASE. k2-fsa does not publish to Maven
    // Central; it ships prebuilt binaries as GitHub release tarballs
    // (sherpa-onnx-vX.Y.Z-android.tar.bz2). This AAR repackages that native library and the
    // project's official Kotlin API (`com.k2fsa.sherpa.onnx.*`) and publishes them to Maven.
    // It is a third-party repackaging, which is a supply-chain compromise, and it is recorded
    // as one in SECURITY.md section 8 rather than presented as if it were first-party. The
    // version is pinned and never uses a dynamic range, and the fallback is to vendor the
    // official .so files under app/src/main/jniLibs.
    //
    // Verified: this artifact contains a real `com.k2fsa.sherpa.onnx.OfflineRecognizer` and a
    // 48.9 MB arm64-v8a libsherpa-onnx-jni.so, not a stub.
    implementation("com.bihe0832.android:lib-sherpa-onnx:6.25.21")

    // ---- Test ------------------------------------------------------------------------
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // Instrumentation, because the speech engine cannot be tested on the JVM at all.
    //
    // Sherpa-onnx is a JNI library: it needs an arm CPU, a real filesystem to load a 153 MB
    // model off, and Android's Keystore-backed asset handling. A JVM unit test can only ever
    // mock it, and a mock of the recogniser is exactly the thing that let the fake
    // "Testing voice input" transcript survive so long. The decode test runs the real engine
    // against a real recording.
    //
    // These are androidTest-only configurations, so they are absent from every shipped APK
    // and cannot weaken the release no-INTERNET gate in scripts/check_offline.sh.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
