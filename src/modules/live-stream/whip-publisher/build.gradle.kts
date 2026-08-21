plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":live-stream:encoded-video"))
    api(project(":live-stream:whip-stream-config"))
    api(project(":live-stream:whip-stream-state-store"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
