plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":relay-gateway:command-dispatcher"))
    api(project(":device-settings:settings-command-handler"))
    api(project(":device-settings:settings-executor"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
