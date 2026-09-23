package com.shilapi.xcertplay.util

import org.bouncycastle.util.encoders.Base64

/** Base64 shared by Android 5.1 runtime code and host-side JVM tests. */
object Base64Compat {
    fun decode(value: String): ByteArray = try {
        Base64.decode(value)
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("Invalid base64", error)
    }

    fun decode(value: ByteArray): ByteArray = try {
        Base64.decode(value)
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("Invalid base64", error)
    }

    fun encode(value: ByteArray): String = Base64.toBase64String(value)
}
