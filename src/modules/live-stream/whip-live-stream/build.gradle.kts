plugins { kotlin("jvm"); `java-library` }

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":relay-gateway:command-dispatcher"))
    api(project(":live-stream:camera-stream-source"))
    api(project(":live-stream:whip-command-handler"))
    api(project(":live-stream:whip-publisher"))
    api(project(":live-stream:whip-stream-state-store"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test { useJUnitPlatform() }
