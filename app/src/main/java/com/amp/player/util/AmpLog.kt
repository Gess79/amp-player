package com.amp.player.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight on-device logger. Writes to app filesDir/logs/amp.log (rotated).
 * Also mirrors to logcat so USB debugging still works when available.
 */
object AmpLog {
    private const val TAG = "AMP"
    private const val MAX_BYTES = 512 * 1024
    private const val LOG_NAME = "amp.log"
    private const val LOG_OLD_NAME = "amp.log.1"

    private val dirRef = AtomicReference<File?>(null)
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "amp-log").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val dir = File(context.applicationContext.filesDir, "logs").also { it.mkdirs() }
        dirRef.set(dir)
        i("App", "AmpLog init")
    }

    fun i(tag: String, message: String) = write('I', tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        write('W', tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        write('E', tag, message, throwable)

    fun currentLogFile(): File? {
        val dir = dirRef.get() ?: return null
        return File(dir, LOG_NAME)
    }

    /** Merged current + rotated log for sharing. */
    fun exportSnapshot(context: Context): File? {
        val dir = dirRef.get() ?: return null
        val outDir = File(context.cacheDir, "logs").also { it.mkdirs() }
        val out = File(outDir, "amp-debug.txt")
        return runCatching {
            out.bufferedWriter().use { writer ->
                val old = File(dir, LOG_OLD_NAME)
                if (old.exists()) {
                    writer.appendLine("--- rotated ---")
                    old.forEachLine { writer.appendLine(it) }
                }
                val cur = File(dir, LOG_NAME)
                if (cur.exists()) {
                    writer.appendLine("--- current ---")
                    cur.forEachLine { writer.appendLine(it) }
                }
            }
            out
        }.getOrNull()
    }

    fun clear() {
        writer.execute {
            val dir = dirRef.get() ?: return@execute
            File(dir, LOG_NAME).delete()
            File(dir, LOG_OLD_NAME).delete()
        }
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("Crash", "Uncaught on ${thread.name}", throwable)
            // Best-effort flush before handing off
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(level: Char, tag: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(message)
            if (throwable != null) {
                append('\n')
                append(stackTraceOf(throwable))
            }
        }
        when (level) {
            'E' -> Log.e(TAG, "$tag: $message", throwable)
            'W' -> Log.w(TAG, "$tag: $message", throwable)
            else -> Log.i(TAG, "$tag: $message")
        }
        writer.execute {
            val dir = dirRef.get() ?: return@execute
            runCatching {
                val file = File(dir, LOG_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val old = File(dir, LOG_OLD_NAME)
                    old.delete()
                    file.renameTo(old)
                }
                FileOutputStream(file, true).bufferedWriter().use { out ->
                    out.appendLine(line)
                }
            }
        }
    }

    private fun stackTraceOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
