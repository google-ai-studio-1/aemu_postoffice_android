package com.example.core.common

import java.util.Locale

class ByteKey(val bytes: ByteArray) {
    private val cachedHash: Int = bytes.contentHashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteKey) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = cachedHash

    fun toMacString(): String = macBytesToString(bytes)
}

fun macBytesToString(mac: ByteArray, offset: Int = 0): String {
    if (mac.size < offset + 6) {
        return "00:00:00:00:00:00"
    }
    return String.format(
        Locale.US,
        "%02x:%02x:%02x:%02x:%02x:%02x",
        mac[offset].toInt() and 0xFF,
        mac[offset + 1].toInt() and 0xFF,
        mac[offset + 2].toInt() and 0xFF,
        mac[offset + 3].toInt() and 0xFF,
        mac[offset + 4].toInt() and 0xFF,
        mac[offset + 5].toInt() and 0xFF
    )
}

fun readLeUInt16(buf: ByteArray, offset: Int): Int {
    return (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8)
}

fun readLeInt32(buf: ByteArray, offset: Int): Int {
    return (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
        ((buf[offset + 2].toInt() and 0xFF) shl 16) or
        ((buf[offset + 3].toInt() and 0xFF) shl 24)
}

fun readLeUInt32(buf: ByteArray, offset: Int): Long {
    return readLeInt32(buf, offset).toLong() and 0xFFFFFFFFL
}

fun writeLeUInt16(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

fun writeLeInt32(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

fun readBeUInt16(buf: ByteArray, offset: Int): Int {
    return ((buf[offset].toInt() and 0xFF) shl 8) or
        (buf[offset + 1].toInt() and 0xFF)
}

fun readBeInt32(buf: ByteArray, offset: Int): Int {
    return ((buf[offset].toInt() and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl 8) or
        (buf[offset + 3].toInt() and 0xFF)
}

fun readBeUInt32(buf: ByteArray, offset: Int): Long {
    return readBeInt32(buf, offset).toLong() and 0xFFFFFFFFL
}

fun writeBeUInt16(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 1] = (value and 0xFF).toByte()
}

fun writeBeInt32(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = ((value ushr 24) and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 3] = (value and 0xFF).toByte()
}

fun extractNullTerminatedString(buf: ByteArray, offset: Int, maxLen: Int): String {
    var end = 0
    while (end < maxLen && buf[offset + end].toInt() != 0) {
        end++
    }
    return String(buf, offset, end, Charsets.UTF_8)
}

class MutableByteQueue(initialCapacity: Int = 1024) {
    private var data = ByteArray(initialCapacity)
    private var head = 0
    private var tail = 0

    val size: Int
        get() = tail - head

    fun isEmpty(): Boolean = head == tail

    fun clear() {
        head = 0
        tail = 0
    }

    fun append(src: ByteArray, offset: Int = 0, length: Int = src.size - offset) {
        if (length <= 0) return
        ensureCapacity(length)
        System.arraycopy(src, offset, data, tail, length)
        tail += length
    }

    fun appendByte(b: Byte) {
        ensureCapacity(1)
        data[tail++] = b
    }

    fun peekByte(index: Int): Byte = data[head + index]

    fun peekBytes(dest: ByteArray, destOffset: Int = 0, srcOffset: Int = 0, length: Int = dest.size - destOffset) {
        System.arraycopy(data, head + srcOffset, dest, destOffset, length)
    }

    fun copyRange(srcOffset: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        System.arraycopy(data, head + srcOffset, out, 0, length)
        return out
    }

    fun toByteArray(): ByteArray = copyRange(0, size)

    fun backingArray(): ByteArray = data

    fun headOffset(): Int = head

    fun consume(count: Int) {
        if (count <= 0) return
        val actual = minOf(count, size)
        head += actual
        if (head == tail) {
            head = 0
            tail = 0
        } else if (head >= data.size / 2 && head >= 4096) {
            compact()
        }
    }

    private fun compact() {
        val currentSize = size
        if (currentSize > 0) {
            System.arraycopy(data, head, data, 0, currentSize)
        }
        head = 0
        tail = currentSize
    }

    private fun ensureCapacity(extra: Int) {
        if (data.size - tail >= extra) return
        if (head > 0 && data.size - size >= extra) {
            compact()
            return
        }
        val needed = size + extra
        var newCap = maxOf(data.size * 2, 1024)
        while (newCap < needed) {
            newCap *= 2
        }
        val next = ByteArray(newCap)
        val currentSize = size
        if (currentSize > 0) {
            System.arraycopy(data, head, next, 0, currentSize)
        }
        data = next
        head = 0
        tail = currentSize
    }
}
