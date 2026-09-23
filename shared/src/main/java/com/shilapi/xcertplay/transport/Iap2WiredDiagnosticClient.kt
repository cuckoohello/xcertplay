package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.session.Iap2Session

/**
 * Runs the wired pre-authentication sequence and stops after observing the first MFi request.
 *
 * It intentionally never reads the certificate and never sends AA01, so it is safe to use when
 * no local or remote MFi provider is available.
 */
class Iap2WiredDiagnosticClient(
    private val session: Iap2Session,
) {
    fun runUntilAuthenticationRequest(
        identification: Iap2IdentificationConfig,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onProgress: (String) -> Unit = {},
    ): Iap2WiredDiagnosticResult {
        require(timeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAXIMUM_TIMEOUT_MILLIS"
        }
        val deadlineNanos = deadlineAfter(timeoutMillis)
        Iap2IdentificationClient(session).identify(
            identification,
            requireRemaining(deadlineNanos),
        )
        onProgress("diagnostic iap2 identification accepted")

        var skippedFrames = 0
        while (true) {
            val incoming = session.recv(requireRemaining(deadlineNanos))
                ?: if (session.isClosed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "iAP2 channel closed before MFi request AA00",
                    )
                } else {
                    throw IphoneUsbException.TimedOut(
                        "Timed out waiting for MFi request AA00",
                    )
                }
            if (incoming.messageId == REQUEST_CERTIFICATE) {
                onProgress("diagnostic iap2 rx=0xaa00 request-certificate; stopping before AA01")
                return Iap2WiredDiagnosticResult(skippedFrames)
            }
            skippedFrames += 1
            onProgress(
                "diagnostic iap2 ignored pre-auth frame=0x" +
                    incoming.messageId.toString(16).padStart(4, '0'),
            )
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun requireRemaining(deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) {
            throw IphoneUsbException.TimedOut("Timed out waiting for MFi request AA00")
        }
        return ((remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
            .coerceAtMost(MAXIMUM_TIMEOUT_MILLIS)
    }

    private companion object {
        const val REQUEST_CERTIFICATE = 0xaa00
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

data class Iap2WiredDiagnosticResult(
    val skippedFrames: Int,
)
