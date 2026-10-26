package com.denoya.xenorush

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Plug-in point for a later Crashlytics adapter. The default path writes a local file
 * and does not talk to Firebase.
 */
fun interface CrashReporter {
    fun report(threadName: String, error: Throwable)
}

/**
 * Saves the last uncaught exception under app storage and can hand it to the share sheet.
 * Assign [reporter] to forward the same crash somewhere else (Crashlytics) when that SDK exists.
 */
object CrashLog {
    const val FILE_NAME = "last-crash.txt"
    var reporter: CrashReporter? = null
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { write(app, thread.name, error) } catch (_: Exception) {}
            try { reporter?.report(thread.name, error) } catch (_: Exception) {}
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun has(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0L }

    fun read(context: Context): String = try { file(context).readText() } catch (_: Exception) { "" }

    fun write(context: Context, threadName: String, error: Throwable) {
        val text = buildString {
            appendLine("XenoRush GP ${Changelog.VERSION}")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("thread $threadName")
            appendLine(Log.getStackTraceString(error))
        }
        file(context).writeText(text)
    }

    /** @return false when there is nothing to share. */
    fun share(activity: Activity): Boolean {
        val text = read(activity)
        if (text.isBlank()) return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "XenoRush GP crash (v${Changelog.VERSION})")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(send, "Share crash log"))
        return true
    }
}
