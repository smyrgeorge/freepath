package io.github.smyrgeorge.freepath.util

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual fun generateCheckerboardPng(): ByteArray {
    val size = 512
    val squareSize = 64
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    for (y in 0 until size step squareSize) {
        for (x in 0 until size step squareSize) {
            g.color = if (((x / squareSize) + (y / squareSize)) % 2 == 0) Color.BLACK else Color.WHITE
            g.fillRect(x, y, squareSize, squareSize)
        }
    }
    g.dispose()
    val baos = ByteArrayOutputStream()
    ImageIO.write(image, "PNG", baos)
    return baos.toByteArray()
}
