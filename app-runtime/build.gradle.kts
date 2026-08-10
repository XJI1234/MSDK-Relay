plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":app-runtime:permission-coordinator"))
    api(project(":app-runtime:foreground-service"))
    api(project(":app-runtime:app-bootstrap"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
