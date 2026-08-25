package org.atovio.tars

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AndroidLogStore {
    private const val DIRECTORY = "log"
    private const val FILE_NAME = "android.log"
    private val lock = Any()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun file(context: Context): File = File(context.filesDir, "$DIRECTORY/$FILE_NAME")

    fun append(context: Context, message: String) {
        synchronized(lock) {
            val target = file(context)
            target.parentFile?.mkdirs()
            target.appendText("${format.format(Date())} $message\n", Charsets.UTF_8)
        }
    }
}
