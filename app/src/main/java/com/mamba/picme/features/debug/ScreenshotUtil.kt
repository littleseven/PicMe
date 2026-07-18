package com.mamba.picme.features.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenshotUtil {
    private const val SCREENSHOT_DIR = "PoLang_Debug_Screenshots"

    fun captureAndSave(view: View, context: Context): String? {
        return try {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)

            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), SCREENSHOT_DIR)
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "screenshot_$timestamp.png")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()

            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
