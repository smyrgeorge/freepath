package io.github.smyrgeorge.freepath.util

import kotlin.time.Clock

fun formatRelativeTime(epochMs: Long): String {
    val deltaSec = (Clock.System.now().toEpochMilliseconds() - epochMs) / 1000
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86400}d ago"
    }
}
