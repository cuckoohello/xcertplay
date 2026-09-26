package com.shilapi.xcertplay.transport

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GocSppTransportTest {
    @Test
    fun normalizesPlainAndColonSeparatedBluetoothAddresses() {
        assertEquals(
            "AABBCCDDEEFF",
            GocSppTransport.normalizeBluetoothAddress("aa:bb:cc:dd:ee:ff"),
        )
        assertEquals(
            "0123456789AB",
            GocSppTransport.normalizeBluetoothAddress(" 0123456789ab "),
        )
    }

    @Test
    fun rejectsMalformedBluetoothAddressesBeforeOpeningSocket() {
        var socketsCreated = 0

        listOf(
            "",
            "AA:BB:CC:DD:EE",
            "AA-BB-CC-DD-EE-FF",
            "AA:BB:CC:DD:EE:GG",
            "AABBCCDDEEFF00",
        ).forEach { address ->
            expectFailure<IllegalArgumentException> {
                GocSppTransport.connect(
                    bluetoothAddress = address,
                    connectAttempts = 1,
                    retryDelayMillis = 0,
                    socketFactory = {
                        socketsCreated++
                        FakeGocSppSocket()
                    },
                    sleeper = {},
                )
            }
        }

        assertEquals(0, socketsCreated)
    }

    @Test
    fun writesAndFlushesAddressHandshakeBeforeFirstPayload() {
        val socket = FakeGocSppSocket(inputBytes = byteArrayOf(0x11, 0x22, 0x33))
        val transport = connect(socket, "aa:bb:cc:dd:ee:ff")

        assertEquals(GocSppTransport.SOCKET_NAME, socket.connectedSocketName)
        assertSame(socket.inputStream, transport.inputStream)
        assertSame(socket.outputStream, transport.outputStream)
        assertEquals("AABBCCDDEEFF", transport.remoteAddress)
        assertEquals(1, socket.recordingOutput.flushCount)
        assertEquals(1, socket.recordingOutput.writes.size)
        assertArrayEquals(
            "AABBCCDDEEFF".toByteArray(Charsets.US_ASCII),
            socket.recordingOutput.writes[0],
        )

        val marker = byteArrayOf(
            0xff.toByte(),
            0x55,
            0x02,
            0x00,
            0xee.toByte(),
            0x10,
        )
        transport.send(marker)

        assertEquals(2, socket.recordingOutput.flushCount)
        assertEquals(2, socket.recordingOutput.writes.size)
        assertArrayEquals(marker, socket.recordingOutput.writes[1])
        assertArrayEquals(byteArrayOf(0x11, 0x22), transport.recv(2, 50))
        assertEquals(50, socket.readTimeoutMillis)
    }

    @Test
    fun retriesOnlyFailuresBeforeSocketConnection() {
        val unavailable = FakeGocSppSocket(
            connectFailure = IOException("connect failed: ENOENT (No such file or directory)"),
        )
        val connected = FakeGocSppSocket()
        val sockets = ArrayDeque(listOf(unavailable, connected))
        val delays = mutableListOf<Long>()

        val transport = GocSppTransport.connect(
            bluetoothAddress = "AABBCCDDEEFF",
            connectAttempts = 2,
            retryDelayMillis = 25,
            socketFactory = { sockets.removeFirst() },
            sleeper = { delays += it },
        )

        assertEquals(1, unavailable.closeCount)
        assertEquals(listOf(25L), delays)
        assertEquals(1, connected.connectCount)
        assertEquals(1, connected.recordingOutput.writes.size)
        transport.close()
    }

    @Test
    fun doesNotRetryPermissionFailure() {
        val denied = FakeGocSppSocket(connectFailure = IOException("connect failed: EACCES"))
        val unused = FakeGocSppSocket()
        val sockets = ArrayDeque(listOf(denied, unused))

        expectFailure<IOException> {
            GocSppTransport.connect(
                bluetoothAddress = "AABBCCDDEEFF",
                connectAttempts = 2,
                retryDelayMillis = 25,
                socketFactory = { sockets.removeFirst() },
                sleeper = { fail("Permission failure must not be retried") },
            )
        }

        assertEquals(1, denied.closeCount)
        assertEquals(0, unused.connectCount)
        assertEquals(1, sockets.size)
    }

    @Test
    fun doesNotRetryAfterHandshakeWriteStarts() {
        val handshakeFailure = IOException("handshake failed")
        val first = FakeGocSppSocket(outputFailure = handshakeFailure)
        val unused = FakeGocSppSocket()
        val sockets = ArrayDeque(listOf(first, unused))

        val thrown = expectFailure<IOException> {
            GocSppTransport.connect(
                bluetoothAddress = "AABBCCDDEEFF",
                connectAttempts = 2,
                retryDelayMillis = 0,
                socketFactory = { sockets.removeFirst() },
                sleeper = {},
            )
        }

        assertSame(handshakeFailure, thrown)
        assertEquals(1, first.closeCount)
        assertEquals(0, unused.connectCount)
        assertEquals(1, sockets.size)
    }

    @Test
    fun closeIsIdempotentAndClosedStreamRejectsWrites() {
        val socket = FakeGocSppSocket()
        val transport = connect(socket, "AABBCCDDEEFF")

        transport.close()
        transport.close()

        assertEquals(1, socket.closeCount)
        assertTrue(transport.recv(16, 0)!!.isEmpty())
        expectFailure<IOException> {
            transport.send(byteArrayOf(1))
        }
    }

    private fun connect(socket: FakeGocSppSocket, address: String): GocSppTransport =
        GocSppTransport.connect(
            bluetoothAddress = address,
            connectAttempts = 1,
            retryDelayMillis = 0,
            socketFactory = { socket },
            sleeper = {},
        )

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        error("unreachable")
    }

    private class FakeGocSppSocket(
        inputBytes: ByteArray = ByteArray(0),
        private val connectFailure: IOException? = null,
        outputFailure: IOException? = null,
    ) : GocSppSocket {
        override val inputStream: InputStream = ByteArrayInputStream(inputBytes)
        val recordingOutput = RecordingOutputStream(outputFailure)
        override val outputStream: OutputStream = recordingOutput

        var connectedSocketName: String? = null
        var connectCount = 0
        var closeCount = 0
        var readTimeoutMillis = -1

        override fun connectReserved(socketName: String) {
            connectCount++
            connectedSocketName = socketName
            connectFailure?.let { throw it }
        }

        override fun setReadTimeout(timeoutMillis: Int) {
            readTimeoutMillis = timeoutMillis
        }

        override fun close() {
            closeCount++
        }
    }

    private class RecordingOutputStream(
        private val failure: IOException?,
    ) : OutputStream() {
        val writes = mutableListOf<ByteArray>()
        var flushCount = 0

        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            failure?.let { throw it }
            writes += bytes.copyOfRange(offset, offset + length)
        }

        override fun flush() {
            failure?.let { throw it }
            flushCount++
        }
    }
}
