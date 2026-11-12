plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.batterylab.personal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.batterylab.personal"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-passive-diagnostic"
    }

    // The previous active Ring / Watch implementations are preserved in
    // src/main/java but intentionally excluded from this diagnostic build.
    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src/passive/java"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Passive diagnostic intentionally has no wearable or other dependencies.
