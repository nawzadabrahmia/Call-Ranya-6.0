plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "edu.uor.lancall"
    compileSdk = 34

    defaultConfig {
        applicationId = "edu.uor.lancall"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "6.0"

        // Internet mode (optional). Filled from GitHub Secrets at build time – never commit keys.
        fun env(n: String) = (System.getenv(n) ?: "").replace("\"", "").trim()
        buildConfigField("String", "SUPABASE_URL", "\"${env("SUPABASE_URL").trimEnd('/')}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${env("SUPABASE_KEY")}\"")
        buildConfigField("String", "TURN_URLS", "\"${env("TURN_URLS")}\"")
        buildConfigField("String", "TURN_USER", "\"${env("TURN_USER")}\"")
        buildConfigField("String", "TURN_PASS", "\"${env("TURN_PASS")}\"")
    }

    // applicationId stays "edu.uor.lancall" and the keystore stays the same, so Call Ranya 6.0 installs as an
    // UPDATE over UoR Call 5.x (no uninstall; contacts, chats and identity keys are kept).
    // Fixed key so every GitHub build installs as an UPDATE over the previous one
    signingConfigs {
        getByName("debug") {
            storeFile = file("uorcall.keystore")
            storePassword = "uorcall123"
            keyAlias = "uorcall"
            keyPassword = "uorcall123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QR codes (generate + scan)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // WebRTC: voice + video calls, screen sharing (LAN and internet)
    implementation("io.getstream:stream-webrtc-android:1.3.9")
    // Internet signalling (Supabase Realtime + REST)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
