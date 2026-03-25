package io.github.smyrgeorge.freepath.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream

actual fun generateCheckerboardPng(): ByteArray {
    val size = 512
    val squareSize = 64
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    for (y in 0 until size step squareSize) {
        for (x in 0 until size step squareSize) {
            paint.color = if (((x / squareSize) + (y / squareSize)) % 2 == 0) Color.BLACK else Color.WHITE
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + squareSize).toFloat(), (y + squareSize).toFloat(), paint)
        }
    }
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
    return baos.toByteArray()
}
