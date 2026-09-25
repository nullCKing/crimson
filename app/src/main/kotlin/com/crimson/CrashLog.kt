package com.crimson

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash, so it can be shown on the next launch.
 *
 * A Fire TV has no easy way to read logcat without a computer and adb; a crash that only happens
 * on a viewer's device and account is otherwise invisible. The trace is written to the app's own
 * files before the default handler kills the process, shown once on the next launch, then deleted.
 */
object CrashLog {

    private const val TAG = "CrimsonCrash"
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val runtime = Runtime.getRuntime()
                file.writeText(
                    buildString {
                        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
                        append("Crimson ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
                        append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
                        append(" · heap ").append(runtime.totalMemory() / 1_048_576).append('/').append(runtime.maxMemory() / 1_048_576).append(" MB")
                        append(" · thread ").append(thread.name).append('\n')
                        append(trace)
                    },
                )
                Log.e(TAG, "crash recorded", error)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash, if the app crashed since it was last shown. */
    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /**
     * The part of a trace worth reading on a TV: the header, every exception line, and the first
     * few frames under each — the app's own frames are the ones that say where.
     */
    fun summary(report: String, framesPerCause: Int = 8): String {
        val out = ArrayList<String>()
        var frames = 0
        for (line in report.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("at ")) {
                if (frames < framesPerCause || trimmed.startsWith("at com.crimson")) {
                    out += "    $trimmed"
                    frames++
                }
            } else if (trimmed.startsWith("...")) {
                continue
            } else if (trimmed.isNotEmpty()) {
                out += trimmed
                frames = 0
            }
        }
        return out.joinToString("\n")
    }
}
