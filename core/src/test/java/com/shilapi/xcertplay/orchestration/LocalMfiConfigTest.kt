package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalMfiConfigTest {
    @Test
    fun acceptsContentUrisForLocalMfiDocuments() {
        val config = config(
            certificateUri = "content://documents/certificate",
            privateKeyUri = "content://documents/private-key",
        )

        assertEquals("content://documents/certificate", config.localMfiCertificateUri)
        assertEquals("content://documents/private-key", config.localMfiPrivateKeyUri)
    }

    @Test
    fun rejectsNonContentCertificateUri() {
        assertThrows(IllegalArgumentException::class.java) {
            config(certificateUri = "file:///sdcard/certificate.p7b")
        }
    }

    @Test
    fun rejectsNonContentPrivateKeyUri() {
        assertThrows(IllegalArgumentException::class.java) {
            config(privateKeyUri = "")
        }
    }

    private fun config(
        certificateUri: String = "content://documents/certificate",
        privateKeyUri: String = "content://documents/private-key",
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        localMfiCertificateUri = certificateUri,
        localMfiPrivateKeyUri = privateKeyUri,
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
    )
}
