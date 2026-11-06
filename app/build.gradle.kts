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
        versionCode = 14
        versionName = "1.13"
    }

    // Fixed debug signing key, added in 1.5: every GitHub runner used to
    // generate its own temporary debug keystore, so each build had a
    // different signature and Android refused to install a new version
    // over an old one ("cannot install if already installed"). This
    // keystore (app/debug.keystore, standard debug password "android")
    // is committed so all builds share one signature. It is a DEBUG
    // key only, never a release key: anyone can create a debug key,
    // and it must not be used for a Play Store release.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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
    // Material vector icons for the bottom bar and buttons (1.13):
    // the user asked to stop using phone emoji for the app icons.
    // Vector icons render the same on every device, unlike emoji.
    // Must be -extended, not -core: the first 1.13 build failed
    // because core does NOT contain Movie, Mic, Pause, Download,
    // FolderOpen or Description (only Edit, PlayArrow and Share
    // resolved from core). Extended contains all of them.
    implementation("androidx.compose.material:material-icons-extended")
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
    // IMPORTANT, learned from the 1.4 export failure: the non-GPL
    // "full" variant does NOT contain libx264 (x264 is GPL), so its
    // FFmpeg build has no -preset option at all and the export died
    // with "Unrecognized option 'preset'". The "full-gpl" variant does
    // contain libx264 (verified: its libavcodec strings list libx264
    // and its configure line has --enable-gpl --enable-libx264), so
    // -c:v libx264 -preset veryfast -crf 23 works. GPL code is now
    // part of this debug app; that is fine for a free personal build
    // but must be honoured if the app is ever distributed publicly.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:6.0.3")
    // REQUIRED, and the root cause of the export NoClassDefFoundError:
    // the maintained fork ships its AAR as a prebuilt file, so its
    // published POM declares no dependencies at all, and Gradle never
    // pulls in smart-exception even though FFmpegKitConfig's static
    // initializer calls Exceptions.registerRootPackage on first use.
    // Without this line the project compiles, then the very first
    // FFmpegKit call dies with NoClassDefFoundError for
    // FFmpegKitConfig. This jar (plus its transitive
    // smart-exception-common) fixes it. Verified present on Maven
    // Central: com.arthenica:smart-exception-java:0.2.1.
    implementation("com.arthenica:smart-exception-java:0.2.1")

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
