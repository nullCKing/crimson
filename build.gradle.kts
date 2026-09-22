plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
}

/**
 * Copies the signed release APK into dist/, so "build the release" is one command.
 * See README: `./gradlew release`.
 */
tasks.register<Copy>("release") {
    group = "build"
    description = "Builds the signed release APK and copies it to dist/"
    dependsOn(":app:assembleRelease")
    from(layout.projectDirectory.dir("app/build/outputs/apk/release"))
    include("*.apk")
    into(layout.projectDirectory.dir("dist"))
}
