plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":relay-gateway:command-dispatcher"))
    api(project(":camera-photo:photo-command-handler"))
    api(project(":camera-photo:photo-executor"))
    api(project(":camera-photo:photo-media-publisher"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
