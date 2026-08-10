plugins { kotlin("jvm"); id("java-library") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":device-connection:dji-operation-coordinator"))
    api(project(":live-stream:stream-config-validator"))
    api(project(":live-stream:stream-state-store"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
