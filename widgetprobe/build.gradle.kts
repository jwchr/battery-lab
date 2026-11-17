plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Standalone diagnostic: hosts Samsung's Battery widget with the public
// AppWidgetHost API and records only what the widget itself displays.
// Separate applicationId so it can sit beside the main Battery Lab app.
android {
    namespace = "com.batterylab.widgetprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.batterylab.widgetprobe"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-widget-probe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
