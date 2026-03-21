package io.github.smyrgeorge.freepath.util

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

actual fun generateCheckerboardPng(): ByteArray {
    val size = 512
    val squareSize = 64
    val surface = Surface.makeRasterN32Premul(size, size)
    val canvas = surface.canvas
    for (y in 0 until size step squareSize) {
        for (x in 0 until size step squareSize) {
            val isBlack = ((x / squareSize) + (y / squareSize)) % 2 == 0
            val paint = Paint().apply { color = if (isBlack) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            canvas.drawRect(
                Rect.makeXYWH(x.toFloat(), y.toFloat(), squareSize.toFloat(), squareSize.toFloat()),
                paint,
            )
        }
    }
    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
}
