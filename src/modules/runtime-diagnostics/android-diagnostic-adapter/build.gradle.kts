plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.skycommand.relay.diagnostics.android"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":runtime-diagnostics:diagnostic-core"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
