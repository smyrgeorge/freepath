package io.github.smyrgeorge.freepath.util

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun ByteArray.toImageBitmap(): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(this, 0, this.size)?.asImageBitmap() }.getOrNull()
