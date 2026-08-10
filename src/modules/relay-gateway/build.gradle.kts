plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":relay-gateway:protocol-core"))
    api(project(":relay-gateway:connection-session"))
    api(project(":relay-gateway:outbound-publisher"))
    api(project(":relay-gateway:command-dispatcher"))
    api(project(":relay-gateway:mission-transfer"))
    api(project(":relay-gateway:transport-adapter"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}
