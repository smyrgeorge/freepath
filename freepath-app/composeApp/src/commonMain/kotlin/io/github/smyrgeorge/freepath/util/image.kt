package io.github.smyrgeorge.freepath.util

import androidx.compose.ui.graphics.ImageBitmap

expect fun ByteArray.toImageBitmap(): ImageBitmap?
expect fun generateCheckerboardPng(): ByteArray
