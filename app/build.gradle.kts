plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vidreal.translator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vidreal.translator"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // With the Kotlin 2.0 compose plugin, the compiler version is tied
    // to the Kotlin version automatically; do not set it manually.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // On-device translation, no API key needed. Models download on first use.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // FFmpeg with libass, for burning Arabic subtitles into the video.
    // This is a large native library; first build downloads it from Maven.
    // NOTE: com.arthenica:ffmpeg-kit-* was retired and its binaries were
    // removed from Maven Central in April 2025, so it can never resolve
    // on a clean CI build. dev.ffmpegkit-maintained is the drop-in
    // replacement: same package (com.arthenica.ffmpegkit) and same API,
    // so no call sites change. 6.0.3 keeps the original FFmpeg 6.0 line.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:6.0.3")

    // Vosk offline speech recognition, from Maven Central (no local AAR
    // needed). The AAR must be declared with @aar, otherwise Gradle
    // resolves a plain jar without the native libvosk.so libraries.
    // VoskTranscriber still loads org.vosk classes by reflection, so a
    // missing library degrades to manual caption entry instead of a crash.
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    // The speech model itself is NOT bundled: VoskTranscriber downloads
    // it in-app on first use (English ~40 MB, Arabic ~318 MB) into
    // filesDir/models/<lang>/, then transcription works fully offline.
}
