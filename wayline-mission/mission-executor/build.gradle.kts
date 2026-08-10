plugins { kotlin("jvm"); id("java-library") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":wayline-mission:mission-state-store"))
    api(project(":device-connection:dji-operation-coordinator"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
