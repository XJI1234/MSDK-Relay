plugins { id("com.android.application") version "8.7.0" }

android {
    namespace = "com.skycommand.networkprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.skycommand.networkprobe"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Match the relay's Kotlin runtime so the probe reuses the same cached version.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    constraints { implementation("org.jetbrains.kotlin:kotlin-stdlib-common:2.1.0") }
}
