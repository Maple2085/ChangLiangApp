package com.changliang.screenon

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把未捕获异常写到 下载/DSH-Go/ChangLiang-crash.txt。
 * 目标设备是 Android 16，所以走 MediaStore，不需要存储权限。
 */
object CrashLog {
    private const val TAG = "ChangLiangCrash"
    private const val FILE_NAME = "ChangLiang-crash.txt"
    private const val RELATIVE_PATH = "Download/DSH-Go"

    @Volatile
    var lastCrash: String? = null
        private set

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                report(appContext, thread, throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun report(context: Context, thread: Thread, throwable: Throwable): String {
        val text = buildText(thread, throwable)
        lastCrash = text
        Log.e(TAG, text)
        save(context.applicationContext, text)
        return text
    }

    fun buildText(thread: Thread, throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("time: $time")
            appendLine("thread: ${thread.name}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            append(writer.toString())
        }
    }

    private fun save(context: Context, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                try {
                    resolver.delete(
                        collection,
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        arrayOf(FILE_NAME, RELATIVE_PATH)
                    )
                } catch (_: Throwable) {
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                    return
                }
            }
        } catch (_: Throwable) {
        }

        try {
            val dir = File(context.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            File(dir, "crash.txt").writeText(text)
        } catch (_: Throwable) {
        }
    }
}
