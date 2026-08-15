plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":cross-runtime-e2e:simulation-dji-adapter"))
    implementation(project(":relay-gateway"))
    implementation(project(":device-connection"))
    implementation(project(":telemetry"))
    implementation(project(":wayline-mission"))
    implementation(project(":flight-control"))
    implementation(project(":device-settings"))
    implementation(project(":live-stream"))
    implementation(project(":relay-settings"))
    implementation(project(":runtime-diagnostics:diagnostic-core"))
    implementation(project(":runtime-diagnostics:gateway-diagnostic-publisher"))
    implementation(project(":app-runtime"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
application { mainClass.set("com.skycommand.relay.e2e.harness.RelayTestHarnessKt") }
tasks.test { useJUnitPlatform() }
