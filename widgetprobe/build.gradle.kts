plugins {
    id("com.android.application")
}

// Standalone diagnostic: hosts Samsung's Battery widget with the public
// AppWidgetHost API and records only what the widget itself displays.
// Separate applicationId so it can sit beside the main Battery Lab app.
android {
    namespace = "com.batterylab.widgetprobe"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.batterylab.widgetprobe"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-widget-probe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
