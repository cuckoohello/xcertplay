package com.shilapi.xcertplay.e01

import android.util.Log
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rotating on-disk log for the E01 wired CarPlay flow.
 *
 * Two files at most: `<file>` (active) and `<file>.1` (previous). Both are capped at
 * [maxBytes]. When the active file crosses the cap, it is renamed to `<file>.1` (replacing any
 * existing rotation) and a new active file is opened. Total disk footprint is therefore bounded
 * by `2 * maxBytes`. Callers stream lines with [append]; timestamps are prefixed automatically.
 *
 * The class is thread-safe. IO errors are swallowed after a single retry so a log failure never
 * takes the CarPlay session down; the last IO error is reported to logcat and made available via
 * [lastErrorMessage] for the UI.
 */
internal class E01LogFile(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : Closeable {
    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var written: Long = 0
    private var closed = false
    @Volatile private var lastError: String? = null

    init {
        require(maxBytes > MIN_BYTES) { "maxBytes must be > $MIN_BYTES" }
    }

    val lastErrorMessage: String? get() = lastError

    fun open(header: String) {
        synchronized(lock) {
            if (closed) return
            file.parentFile?.mkdirs()
            openWriter()
            appendLocked("---- $header ----")
        }
    }

    fun append(line: String) {
        synchronized(lock) {
            if (closed) return
            if (writer == null) openWriter()
            appendLocked(line)
        }
    }

    fun snapshot(): String = synchronized(lock) {
        val active = if (file.isFile) file.readText(StandardCharsets.UTF_8) else ""
        val rotated = rotatedFile().takeIf { it.isFile }?.readText(StandardCharsets.UTF_8).orEmpty()
        (rotated + active).trimStart('\n', ' ')
    }

    fun clear() {
        synchronized(lock) {
            runCatching { writer?.close() }
            writer = null
            written = 0
            file.delete()
            rotatedFile().delete()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { writer?.close() }
            writer = null
        }
    }

    fun activeFile(): File = file
    fun rotatedFile(): File = File(file.parentFile, "${file.name}.1")

    private fun openWriter() {
        try {
            val existing = if (file.isFile) file.length() else 0L
            writer = BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8),
            )
            written = existing
        } catch (error: IOException) {
            reportError("open", error)
            writer = null
        }
    }

    private fun appendLocked(line: String) {
        val output = writer ?: return
        val stamped = "${TIMESTAMP.format(Date())} $line"
        try {
            output.appendLine(stamped)
            output.flush()
            written += (stamped.length + 1).toLong()
            if (written >= maxBytes) rotateLocked()
        } catch (error: IOException) {
            reportError("append", error)
            runCatching { output.close() }
            writer = null
        }
    }

    private fun rotateLocked() {
        runCatching { writer?.close() }
        writer = null
        val previous = rotatedFile()
        previous.delete()
        // renameTo will fail if the target still exists on some filesystems; delete first.
        if (!file.renameTo(previous)) {
            // As a fallback, drop the active file so we do not exceed the cap on next open.
            file.delete()
        }
        try {
            writer = BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, false), StandardCharsets.UTF_8),
            )
            written = 0
        } catch (error: IOException) {
            reportError("rotate", error)
            writer = null
        }
    }

    private fun reportError(stage: String, error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        lastError = "log $stage: $message"
        Log.w(TAG, lastError, error)
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 512 * 1024 // 512 KiB per file → 1 MiB total.
        private const val MIN_BYTES: Long = 4 * 1024
        private const val TAG = "xcertplay-e01-log"
        private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
