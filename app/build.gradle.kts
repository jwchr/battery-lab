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
        versionCode = 3
        versionName = "0.3-samsung-widget-source"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
