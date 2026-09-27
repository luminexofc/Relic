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

    // QR encode/decode for sharing recipes. Pure Java, no Android APIs, so the
    // full string -> QR -> string round trip is testable here too.
    implementation(libs.zxing.core)
}

tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
