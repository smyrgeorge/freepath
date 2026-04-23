package io.github.smyrgeorge.freepath.core.util

import io.github.smyrgeorge.log4k.Appender
import io.github.smyrgeorge.log4k.Level
import io.github.smyrgeorge.log4k.LoggingEvent
import io.github.smyrgeorge.log4k.impl.extensions.format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object InMemoryLoggingAppender : Appender<LoggingEvent> {
    const val MAX_ENTRIES: Int = 1024

    override val name: String = "InMemoryLoggingAppender"

    private val _logs: MutableStateFlow<List<Entry>> = MutableStateFlow(emptyList())
    val logs: StateFlow<List<Entry>> = _logs

    data class Entry(
        val id: Long,
        val timestamp: String,
        val level: Level,
        val thread: String,
        val logger: String,
        val message: String,
        val throwable: String?,
    )

    override suspend fun append(event: LoggingEvent) {
        val entry = Entry(
            id = event.id,
            timestamp = shortTime(event.timestamp.toString()),
            level = event.level,
            thread = event.thread,
            logger = compactLogger(event.logger),
            message = event.message.format(event.arguments),
            throwable = event.throwable?.stackTraceToString(),
        )
        val current = _logs.value
        val next = if (current.size >= MAX_ENTRIES) {
            current.drop(current.size - MAX_ENTRIES + 1) + entry
        } else {
            current + entry
        }
        _logs.value = next
    }

    private fun shortTime(iso: String): String {
        // Converts e.g. "2026-04-21T12:34:56.789Z" -> "12:34:56.789"
        val tIdx = iso.indexOf('T')
        if (tIdx < 0) return iso
        val tail = iso.substring(tIdx + 1)
        val zIdx = tail.indexOf('Z')
        return if (zIdx < 0) tail else tail.substring(0, zIdx)
    }

    private fun compactLogger(logger: String): String {
        if (logger.length <= 36) return logger
        val parts = logger.split('.')
        if (parts.size < 3) return logger
        val head = parts.take(parts.size - 2).joinToString(".") { it.firstOrNull()?.toString() ?: "" }
        return head + "." + parts.takeLast(2).joinToString(".")
    }
}
