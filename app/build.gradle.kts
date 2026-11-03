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
        versionCode = 1
        versionName = "1.0"
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
    implementation("com.arthenica:ffmpeg-kit-full:6.0-2")

    // Vosk offline speech recognition (optional AAR, see README section 3):
    // Download vosk-android AAR from https://github.com/alphacep/vosk-android
    // and put it in app/libs/, then uncomment the next line:
    // implementation(files("libs/vosk-android.aar"))
    // The code in VoskTranscriber loads Vosk by reflection, so the project
    // still compiles without the AAR. Without it, the app offers manual
    // caption entry and editing, and transcription after the AAR + model
    // are added.
}
