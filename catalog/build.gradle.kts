plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The grading maths is deliberately kept in plain Kotlin (not GLSL) so it
    // can be verified here, on a desktop JVM, with no device or emulator.
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
