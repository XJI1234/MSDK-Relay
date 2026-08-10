plugins { kotlin("jvm"); id("java-library") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":relay-gateway:protocol-core"))
    api(project(":wayline-mission:mission-staging"))
    api(project(":wayline-mission:wpmz-generator"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
