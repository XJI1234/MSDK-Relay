plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":relay-gateway:protocol-core"))
    api(project(":live-stream:whip-stream-config"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
