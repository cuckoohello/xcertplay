package com.shilapi.xcertplay.e01

import android.annotation.SuppressLint
import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.util.Base64Compat

internal data class RemoteMfiSettings(
    val serverUrl: String,
    val bearerToken: String,
)

@SuppressLint("ApplySharedPref")
internal object E01Persistence {
    private const val PREFS = "e01_wired_carplay"
    private const val KEY_REMOTE_SERVER = "remote_mfi_server"
    private const val KEY_REMOTE_TOKEN = "remote_mfi_token"
    private const val KEY_NO_MFI_DIAGNOSTIC = "no_mfi_diagnostic"
    private const val KEY_IDENTITY_PRIVATE = "identity_private"
    private const val KEY_IDENTITY_PUBLIC = "identity_public"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_CONTROLLER_IDS = "controller_ids"
    private const val KEY_LOCKDOWN_HOST_ID = "lockdown_host_id"
    private const val KEY_LOCKDOWN_SYSTEM_BUID = "lockdown_system_buid"
    private const val KEY_LOCKDOWN_WIFI_MAC = "lockdown_wifi_mac"
    private const val KEY_LOCKDOWN_DEVICE_PUBLIC = "lockdown_device_public"
    private const val KEY_LOCKDOWN_DEVICE_CERT = "lockdown_device_cert"
    private const val KEY_LOCKDOWN_HOST_PRIVATE = "lockdown_host_private"
    private const val KEY_LOCKDOWN_HOST_CERT = "lockdown_host_cert"
    private const val KEY_LOCKDOWN_ROOT_PRIVATE = "lockdown_root_private"
    private const val KEY_LOCKDOWN_ROOT_CERT = "lockdown_root_cert"
    private const val CONTROLLER_PREFIX = "controller."

    fun loadRemoteMfi(context: Context): RemoteMfiSettings {
        val prefs = prefs(context)
        return RemoteMfiSettings(
            serverUrl = prefs.getString(KEY_REMOTE_SERVER, null).orEmpty(),
            bearerToken = prefs.getString(KEY_REMOTE_TOKEN, null).orEmpty(),
        )
    }

    fun saveRemoteMfi(context: Context, settings: RemoteMfiSettings) {
        prefs(context).edit()
            .putString(KEY_REMOTE_SERVER, settings.serverUrl.trim())
            .putString(KEY_REMOTE_TOKEN, settings.bearerToken)
            .apply()
    }

    fun loadNoMfiDiagnostic(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NO_MFI_DIAGNOSTIC, false)

    fun saveNoMfiDiagnostic(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_NO_MFI_DIAGNOSTIC, enabled)
            .apply()
    }

    fun loadIdentity(context: Context): AirPlayIdentity {
        val prefs = prefs(context)
        val privateKey = prefs.getString(KEY_IDENTITY_PRIVATE, null)
        val publicKey = prefs.getString(KEY_IDENTITY_PUBLIC, null)
        val pairingId = prefs.getString(KEY_PAIRING_ID, null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            try {
                return AirPlayIdentity(
                    privateKey = Base64Compat.decode(privateKey),
                    publicKey = Base64Compat.decode(publicKey),
                    pairingId = pairingId,
                )
            } catch (_: IllegalArgumentException) {
                // Replace corrupt identity data below.
            }
        }
        return AirPlayIdentity.generate().also { identity ->
            prefs.edit()
                .putString(KEY_IDENTITY_PRIVATE, Base64Compat.encode(identity.privateKey))
                .putString(KEY_IDENTITY_PUBLIC, Base64Compat.encode(identity.publicKey))
                .putString(KEY_PAIRING_ID, identity.pairingId)
                .commit()
        }
    }

    fun loadPairings(context: Context): PairingStore {
        val prefs = prefs(context)
        val store = PairingStore { identifier, key -> savePairing(context, identifier, key) }
        for (identifier in prefs.getStringSet(KEY_CONTROLLER_IDS, emptySet()).orEmpty()) {
            val encoded = prefs.getString(CONTROLLER_PREFIX + identifier, null) ?: continue
            try {
                store.save(identifier, Base64Compat.decode(encoded))
            } catch (_: IllegalArgumentException) {
                // Ignore only the corrupt controller entry.
            }
        }
        return store
    }

    fun loadLockdownRecord(context: Context): LockdownPairRecord? {
        val prefs = prefs(context)
        return try {
            LockdownPairRecord.restore(
                hostId = prefs.getString(KEY_LOCKDOWN_HOST_ID, null) ?: return null,
                systemBuid = prefs.getString(KEY_LOCKDOWN_SYSTEM_BUID, null) ?: return null,
                wifiMacAddress = prefs.getString(KEY_LOCKDOWN_WIFI_MAC, null) ?: return null,
                devicePublicKeyPem = decodeRequired(prefs.getString(KEY_LOCKDOWN_DEVICE_PUBLIC, null)),
                deviceCertificatePem = decodeRequired(prefs.getString(KEY_LOCKDOWN_DEVICE_CERT, null)),
                hostPrivateKeyPem = decodeRequired(prefs.getString(KEY_LOCKDOWN_HOST_PRIVATE, null)),
                hostCertificatePem = decodeRequired(prefs.getString(KEY_LOCKDOWN_HOST_CERT, null)),
                rootPrivateKeyPem = decodeRequired(prefs.getString(KEY_LOCKDOWN_ROOT_PRIVATE, null)),
                rootCertificatePem = decodeRequired(prefs.getString(KEY_LOCKDOWN_ROOT_CERT, null)),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) {
        prefs(context).edit()
            .putString(KEY_LOCKDOWN_HOST_ID, record.hostId)
            .putString(KEY_LOCKDOWN_SYSTEM_BUID, record.systemBuid)
            .putString(KEY_LOCKDOWN_WIFI_MAC, record.wifiMacAddress)
            .putString(KEY_LOCKDOWN_DEVICE_PUBLIC, Base64Compat.encode(record.devicePublicKeyPem))
            .putString(KEY_LOCKDOWN_DEVICE_CERT, Base64Compat.encode(record.deviceCertificatePem))
            .putString(KEY_LOCKDOWN_HOST_PRIVATE, Base64Compat.encode(record.hostPrivateKeyPem))
            .putString(KEY_LOCKDOWN_HOST_CERT, Base64Compat.encode(record.hostCertificatePem))
            .putString(KEY_LOCKDOWN_ROOT_PRIVATE, Base64Compat.encode(record.rootPrivateKeyPem))
            .putString(KEY_LOCKDOWN_ROOT_CERT, Base64Compat.encode(record.rootCertificatePem))
            .commit()
    }

    fun clearTrust(context: Context) {
        val prefs = prefs(context)
        val editor = prefs.edit()
        for (identifier in prefs.getStringSet(KEY_CONTROLLER_IDS, emptySet()).orEmpty()) {
            editor.remove(CONTROLLER_PREFIX + identifier)
        }
        editor.remove(KEY_CONTROLLER_IDS)
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .commit()
    }

    fun clearLockdownRecord(context: Context) {
        prefs(context).edit()
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .commit()
    }

    private fun savePairing(context: Context, identifier: String, key: ByteArray) {
        val prefs = prefs(context)
        val identifiers = prefs.getStringSet(KEY_CONTROLLER_IDS, emptySet())
            .orEmpty()
            .toMutableSet()
        identifiers.add(identifier)
        prefs.edit()
            .putString(CONTROLLER_PREFIX + identifier, Base64Compat.encode(key))
            .putStringSet(KEY_CONTROLLER_IDS, identifiers)
            .commit()
    }

    private fun decodeRequired(encoded: String?): ByteArray =
        Base64Compat.decode(requireNotNull(encoded))

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
