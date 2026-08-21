plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":live-stream:encoded-video"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
