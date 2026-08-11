plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.skycommand.relay.device.pairing.command.android"; compileSdk = 35; defaultConfig { minSdk = 24 } }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":device-connection:pairing-controller"))
    implementation("com.dji:dji-sdk-v5-aircraft:5.17.0")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.17.0")
    testImplementation(kotlin("test")); testImplementation("junit:junit:4.13.2")
}
