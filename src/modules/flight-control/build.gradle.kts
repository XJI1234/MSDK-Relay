plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":relay-gateway:command-dispatcher"))
    api(project(":flight-control:flight-command-handler"))
    api(project(":flight-control:dji-flight-adapter"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
