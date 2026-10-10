package com.shilapi.xcertplay.mfi

/** Common certificate/signing contract for local software MFi authentication. */
interface MfiAuthenticator {
    fun protocolMajor(): Int

    fun readCertificate(
        maximumOutputLength: Int = DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH,
    ): ByteArray

    fun signChallenge(challenge: ByteArray): ByteArray

    companion object {
        const val DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH = 65_525
    }
}

sealed class MfiException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MfiInvalidDataException(message: String, cause: Throwable? = null) : MfiException(message, cause)
