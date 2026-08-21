plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(kotlin("test")); testImplementation("org.junit.jupiter:junit-jupiter:5.11.4") }
tasks.test { useJUnitPlatform() }
