plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * The filter engine, guide geometry and navigation, EPG parsing and the streaming JSON reader.
 *
 * Deliberately a plain JVM module with no Android and no third-party dependencies. That is what
 * makes it unit-testable without an emulator, reusable by the offline discovery tool, and
 * compilable with nothing but kotlinc when a build environment has no access to Google's Maven.
 *
 * Nothing Android-shaped belongs in here. If a class needs a Context, it belongs in :app.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
