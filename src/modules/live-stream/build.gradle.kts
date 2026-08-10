plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":relay-gateway:command-dispatcher"))
    api(project(":live-stream:stream-state-store"))
    api(project(":live-stream:dji-stream-adapter"))
    api(project(":live-stream:stream-command-handler"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
