plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.skycommand.relay.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.skycommand.relay"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["DJI_API_KEY"] = providers.gradleProperty("DJI_API_KEY").orElse("").get()
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":relay-gateway")); implementation(project(":relay-gateway:transport-adapter")); implementation(project(":relay-gateway:protocol-core"))
    implementation(project(":device-connection")); implementation(project(":device-connection:android-dji-sdk-adapter")); implementation(project(":device-connection:android-remote-controller-adapter")); implementation(project(":device-connection:android-aircraft-adapter")); implementation(project(":device-connection:android-pairing-command-adapter")); implementation(project(":device-connection:android-pairing-status-adapter"))
    implementation(project(":telemetry")); implementation(project(":telemetry:android-flight-telemetry-adapter"))
    implementation(project(":live-stream")); implementation(project(":live-stream:android-dji-stream-adapter"))
    implementation(project(":wayline-mission")); implementation(project(":wayline-mission:android-dji-wayline-adapter")); implementation(project(":wayline-mission:android-mission-staging-adapter"))
    implementation(project(":relay-settings")); implementation(project(":relay-settings:android-settings-adapter"))
    implementation(project(":app-runtime")); implementation(project(":app-runtime:permission-coordinator")); implementation(project(":app-runtime:foreground-service")); implementation(project(":app-runtime:app-bootstrap")); implementation(project(":app-runtime:android-permission-adapter")); implementation(project(":app-runtime:android-foreground-service-adapter"))
    implementation("androidx.activity:activity-ktx:1.9.3"); implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test")); testImplementation("junit:junit:4.13.2")
}
