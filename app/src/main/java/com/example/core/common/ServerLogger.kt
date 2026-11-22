package com.example.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogCategory {
    SYSTEM,
    RELAY,
    ADHOCCTL,
    ENET,
    HTTP,
    CONFIG
}

enum class LogLevel {
    INFO,
    WARN,
    ERROR
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val category: LogCategory,
    val level: LogLevel,
    val message: String
)

object ServerLogger {
    private const val MAX_ENTRIES = 1500
    private val lock = Any()
    private val ringBuffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val idCounter = AtomicLong(1L)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }

    fun log(category: LogCategory, message: String, level: LogLevel = LogLevel.INFO) {
        val ts = timeFormatter.get()?.format(Date()) ?: ""
        val entry = LogEntry(
            id = idCounter.getAndIncrement(),
            timestamp = ts,
            category = category,
            level = level,
            message = message.trimEnd()
        )
        synchronized(lock) {
            if (ringBuffer.size >= MAX_ENTRIES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(entry)
            _logs.value = ringBuffer.toList()
        }
    }

    fun info(category: LogCategory, message: String) {
        log(category, message, LogLevel.INFO)
    }

    fun warn(category: LogCategory, message: String) {
        log(category, message, LogLevel.WARN)
    }

    fun error(category: LogCategory, message: String) {
        log(category, message, LogLevel.ERROR)
    }

    fun clear() {
        synchronized(lock) {
            ringBuffer.clear()
            _logs.value = emptyList()
        }
    }

    fun exportText(): String {
        val snapshot = synchronized(lock) { ringBuffer.toList() }
        val sb = StringBuilder(snapshot.size * 64)
        for (entry in snapshot) {
            sb.append(entry.timestamp)
                .append(" [")
                .append(entry.category.name)
                .append("] [")
                .append(entry.level.name)
                .append("] ")
                .append(entry.message)
                .append('\n')
        }
        return sb.toString()
    }
}
