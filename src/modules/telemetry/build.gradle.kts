plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":device-connection:device-state-store"))
    api(project(":telemetry:snapshot-assembler"))
    api(project(":telemetry:telemetry-command-handler"))
    api(project(":telemetry:telemetry-publisher"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
