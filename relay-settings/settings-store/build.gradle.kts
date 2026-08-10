plugins { kotlin("jvm"); id("java-library") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":relay-settings:endpoint-settings"))
    api(project(":relay-settings:device-identity"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
