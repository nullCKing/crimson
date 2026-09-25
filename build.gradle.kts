import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
}

/**
 * Proves an APK will install on every Fire TV before anyone sideloads it.
 *
 * "There was a problem parsing the package" on a Fire TV means one of these was wrong, and none of
 * them is visible without looking: the manifest must parse (aapt2), minSdk must reach Fire OS 5
 * (Android 5.1, API 22 â€” the 2nd-gen Fire TV box and Stick), the v1 JAR signature must be present
 * and valid at that API level because v2 is only read from Android 7, the archive must be
 * 4-byte aligned with `resources.arsc` stored uncompressed (Android 11+ refuses anything else when
 * targetSdk â‰¥ 30), and it must not be marked debuggable or test-only.
 */
abstract class VerifyApk @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    @get:InputFiles
    abstract val apks: ConfigurableFileCollection

    @get:Internal
    abstract val buildTools: DirectoryProperty

    /** The oldest Android any Fire TV runs: Fire OS 5 is Android 5.1. */
    @get:Input
    abstract val oldestFireOsApi: Property<Int>

    @TaskAction
    fun verify() {
        val files = apks.files.filter { it.name.endsWith(".apk") }
        if (files.isEmpty()) throw GradleException("No APK to verify")
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val tools = buildTools.get().asFile
        fun tool(name: String, ext: String) = File(tools, if (windows) "$name$ext" else name).absolutePath
        fun run(vararg args: String): Pair<Int, String> {
            val out = java.io.ByteArrayOutputStream()
            val result = exec.exec {
                commandLine(*args)
                standardOutput = out
                errorOutput = out
                isIgnoreExitValue = true
            }
            return result.exitValue to out.toString()
        }

        for (apk in files) {
            val problems = ArrayList<String>()

            val (badgingExit, badging) = run(tool("aapt2", ".exe"), "dump", "badging", apk.absolutePath)
            if (badgingExit != 0) problems += "aapt2 cannot parse it:\n$badging"
            val minSdk = Regex("(?:minSdkVersion|sdkVersion):'(\\d+)'").find(badging)?.groupValues?.get(1)?.toIntOrNull()
            if (minSdk == null) problems += "no minSdk in the manifest"
            else if (minSdk > oldestFireOsApi.get()) problems += "minSdk $minSdk is above Fire OS 5 (API ${oldestFireOsApi.get()})"
            if ("application-debuggable" in badging) problems += "it is debuggable"
            if ("testOnly='-1'" in badging || "testOnly='true'" in badging) problems += "it is test-only"

            val (signExit, sign) = run(
                tool("apksigner", ".bat"), "verify", "--verbose",
                "--min-sdk-version", (minSdk ?: oldestFireOsApi.get()).toString(), apk.absolutePath,
            )
            if (signExit != 0) problems += "signature does not verify:\n$sign"
            if (!sign.contains("v1 scheme (JAR signing): true")) problems += "no v1 (JAR) signature, which Android 5-6 require"
            if (!sign.contains("v2 scheme (APK Signature Scheme v2): true")) problems += "no v2 signature"

            val (alignExit, align) = run(tool("zipalign", ".exe"), "-c", "-p", "4", apk.absolutePath)
            if (alignExit != 0) problems += "not 4-byte aligned:\n$align"

            ZipFile(apk).use { zip ->
                val arsc = zip.getEntry("resources.arsc")
                if (arsc == null) problems += "no resources.arsc"
                else if (arsc.method != ZipEntry.STORED) problems += "resources.arsc is compressed"
            }

            val (_, certs) = run(tool("apksigner", ".bat"), "verify", "--print-certs", apk.absolutePath)
            if ("CN=Android Debug" in certs) logger.warn("${apk.name} is signed with the DEBUG key: it will not update an installed release.")

            if (problems.isNotEmpty()) {
                throw GradleException("${apk.name} would not install on every Fire TV:\n- " + problems.joinToString("\n- "))
            }
            logger.lifecycle("${apk.name}: parses, minSdk $minSdk, v1+v2 signed, aligned â€” installs on Fire OS 5 and later")
        }
    }
}

val androidSdk: File = run {
    val local = rootProject.file("local.properties")
    val fromLocal = if (local.exists()) Properties().apply { local.inputStream().use { load(it) } }.getProperty("sdk.dir") else null
    File(fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "")
}

val verifyReleaseApk = tasks.register<VerifyApk>("verifyReleaseApk") {
    group = "verification"
    description = "Checks the release APK parses, is signed and aligned, and installs on Fire OS 5+"
    dependsOn(":app:assembleRelease")
    apks.from(fileTree("app/build/outputs/apk/release") { include("*.apk") })
    buildTools.set(File(androidSdk, "build-tools/35.0.0"))
    oldestFireOsApi.set(22)
}

/**
 * Builds the signed release APK, proves it installs on every Fire TV, and copies it into dist/,
 * so "build the release" is one command. See README: `./gradlew release`.
 */
tasks.register<Copy>("release") {
    group = "build"
    description = "Builds and verifies the signed release APK and copies it to dist/"
    dependsOn(verifyReleaseApk)
    from(layout.projectDirectory.dir("app/build/outputs/apk/release"))
    include("*.apk")
    into(layout.projectDirectory.dir("dist"))
}
