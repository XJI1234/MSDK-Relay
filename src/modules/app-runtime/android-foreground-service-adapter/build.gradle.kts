plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skycommand.relay.runtime.service.android"
    compileSdk = 35

    defaultConfig { minSdk = 24 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":app-runtime:foreground-service"))
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
