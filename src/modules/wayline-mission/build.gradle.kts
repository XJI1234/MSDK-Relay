plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":relay-gateway:command-dispatcher"))
    api(project(":relay-gateway:mission-transfer"))
    api(project(":device-connection:dji-operation-coordinator"))
    api(project(":wayline-mission:mission-staging"))
    api(project(":wayline-mission:mission-state-store"))
    api(project(":wayline-mission:mission-uploader"))
    api(project(":wayline-mission:mission-executor"))
    api(project(":wayline-mission:mission-flight-phase"))
    api(project(":wayline-mission:wayline-command-handler"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
