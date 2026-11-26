plugins {
    id("com.android.application")
}

android {
    // Problems found before lint joined PR CI (#64) are recorded here; only new ones fail the check.
    lint { baseline = file("lint-baseline.xml") }
    namespace = "com.batterylab.personal"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.batterylab.personal"
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "0.3-samsung-widget-source"
        testInstrumentationRunner = "com.batterylab.personal.PersistenceTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":rf"))
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
}
