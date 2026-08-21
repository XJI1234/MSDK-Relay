plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.skycommand.relay.stream.whip.android"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":live-stream:whip-publisher"))
    implementation("io.getstream:stream-webrtc-android:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
