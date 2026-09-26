package com.shilapi.xcertplay.transport

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * Raw SPP byte stream exposed by the E01 GOC daemon through `/dev/socket/goc_spp`.
 *
 * The daemon consumes exactly twelve ASCII Bluetooth-address characters before treating later
 * writes as SPP payload. [connect] therefore writes and flushes that handshake separately before
 * returning the stream. This class owns the LocalSocket.
 */
class GocSppTransport private constructor(
    private val socket: GocSppSocket,
    val remoteAddress: String,
) : BlockingDuplexByteStream {
    /** Diagnostic access only; do not read concurrently with [recv]. */
    val inputStream: InputStream = socket.inputStream

    /** Diagnostic access only; the address handshake is complete. Do not write concurrently with [send]. */
    val outputStream: OutputStream = socket.outputStream

    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false

    override fun send(data: ByteArray) {
        synchronized(writeLock) {
            ensureOpen()
            try {
                outputStream.write(data)
                outputStream.flush()
            } catch (failure: IOException) {
                throw failAndClose(failure)
            }
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }

        synchronized(readLock) {
            if (isClosed()) return EMPTY
            try {
                if (timeoutMillis == 0L && inputStream.available() == 0) return null
                socket.setReadTimeout(timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
                val buffer = ByteArray(maxBytes)
                return when (val count = inputStream.read(buffer)) {
                    -1 -> EMPTY
                    0 -> null
                    buffer.size -> buffer
                    else -> buffer.copyOf(count)
                }
            } catch (_: SocketTimeoutException) {
                return null
            } catch (failure: IOException) {
                if (isClosed()) return EMPTY
                throw failAndClose(failure)
            }
        }
    }

    override fun close() {
        val shouldClose = synchronized(stateLock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (shouldClose) socket.close()
    }

    private fun writeHandshake(address: String) {
        synchronized(writeLock) {
            try {
                outputStream.write(address.toByteArray(Charsets.US_ASCII))
                outputStream.flush()
            } catch (failure: IOException) {
                throw failAndClose(failure)
            }
        }
    }

    private fun ensureOpen() {
        if (isClosed()) throw IOException("GOC SPP transport is closed")
    }

    private fun isClosed(): Boolean = synchronized(stateLock) { closed }

    private fun failAndClose(failure: IOException): IOException {
        try {
            close()
        } catch (closeFailure: Throwable) {
            if (closeFailure is Error) throw closeFailure
            if (closeFailure !== failure) failure.addSuppressed(closeFailure)
        }
        return failure
    }

    companion object {
        const val SOCKET_NAME = "goc_spp"
        private const val DEFAULT_CONNECT_ATTEMPTS = 3
        private const val DEFAULT_RETRY_DELAY_MILLIS = 200L
        private val EMPTY = ByteArray(0)
        private val PLAIN_ADDRESS = Regex("^[0-9A-Fa-f]{12}$")
        private val COLON_ADDRESS = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

        /**
         * Connects to the reserved GOC socket and asks the daemon to establish SPP to [bluetoothAddress].
         *
         * Retries end once the Unix socket accepts a connection. A failed address handshake is not
         * retried because the daemon may already have started the remote SPP connection.
         */
        @JvmStatic
        fun connect(bluetoothAddress: String): GocSppTransport =
            connect(
                bluetoothAddress = bluetoothAddress,
                connectAttempts = DEFAULT_CONNECT_ATTEMPTS,
                retryDelayMillis = DEFAULT_RETRY_DELAY_MILLIS,
                socketFactory = ::AndroidGocSppSocket,
                sleeper = Thread::sleep,
            )

        internal fun connect(
            bluetoothAddress: String,
            connectAttempts: Int,
            retryDelayMillis: Long,
            socketFactory: () -> GocSppSocket,
            sleeper: (Long) -> Unit,
        ): GocSppTransport {
            require(connectAttempts > 0) { "connectAttempts must be positive" }
            require(retryDelayMillis >= 0) { "retryDelayMillis must not be negative" }
            val address = normalizeBluetoothAddress(bluetoothAddress)
            var lastFailure: IOException? = null

            for (attempt in 1..connectAttempts) {
                val socket = socketFactory()
                try {
                    socket.connectReserved(SOCKET_NAME)
                } catch (failure: IOException) {
                    closeAfterFailure(socket, failure)
                    lastFailure = failure
                    if (!isSocketNotReady(failure) || attempt == connectAttempts) break
                    try {
                        sleeper(retryDelayMillis)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Interrupted while waiting for GOC SPP socket", interrupted)
                    }
                    continue
                }

                val transport = try {
                    GocSppTransport(socket, address)
                } catch (failure: Throwable) {
                    closeAfterFailure(socket, failure)
                    throw failure
                }
                transport.writeHandshake(address)
                return transport
            }

            throw IOException(
                "Could not connect to reserved LocalSocket $SOCKET_NAME after $connectAttempts attempts",
                lastFailure,
            )
        }

        internal fun normalizeBluetoothAddress(value: String): String {
            val address = value.trim()
            require(PLAIN_ADDRESS.matches(address) || COLON_ADDRESS.matches(address)) {
                "Bluetooth address must contain exactly 12 hexadecimal digits"
            }
            return address.replace(":", "").uppercase(Locale.US)
        }

        private fun isSocketNotReady(failure: IOException): Boolean {
            var cause: Throwable? = failure
            while (cause != null) {
                val message = cause.message.orEmpty()
                if (SOCKET_NOT_READY_ERRORS.any { message.contains(it, ignoreCase = true) }) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }

        private fun closeAfterFailure(socket: Closeable, failure: Throwable) {
            try {
                socket.close()
            } catch (closeFailure: Throwable) {
                if (closeFailure is Error) throw closeFailure
                if (closeFailure !== failure) failure.addSuppressed(closeFailure)
            }
        }

        private val SOCKET_NOT_READY_ERRORS = listOf(
            "ENOENT",
            "ECONNREFUSED",
            "No such file or directory",
            "Connection refused",
        )
    }
}

internal interface GocSppSocket : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream

    fun connectReserved(socketName: String)

    fun setReadTimeout(timeoutMillis: Int)
}

private class AndroidGocSppSocket : GocSppSocket {
    private val socket = LocalSocket(LocalSocket.SOCKET_STREAM)

    override val inputStream: InputStream
        get() = socket.inputStream

    override val outputStream: OutputStream
        get() = socket.outputStream

    override fun connectReserved(socketName: String) {
        socket.connect(
            LocalSocketAddress(socketName, LocalSocketAddress.Namespace.RESERVED),
        )
    }

    override fun setReadTimeout(timeoutMillis: Int) {
        socket.soTimeout = timeoutMillis
    }

    override fun close() {
        socket.close()
    }
}
