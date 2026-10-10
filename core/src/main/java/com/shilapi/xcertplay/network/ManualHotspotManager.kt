package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Attaches to a hotspot that is already running on this device.
 *
 * The hotspot remains owned by the system. This manager only uses public APIs to locate its
 * interface and observe connection data. The caller-supplied credentials remain authoritative;
 * unavailable channel information is reported to iAP2 as zero ("auto").
 */
class ManualHotspotManager(
    context: Context,
    ssid: String,
    passphrase: String,
    band: ManualHotspotBand,
    channel: Int,
    security: ManualHotspotSecurity,
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val expectedSsid = ssid
    private val passphrase = passphrase
    private val expectedBand = band
    private val expectedChannel = channel
    private val expectedSecurity = security.toIap2Security()

    @Volatile
    private var closed = false

    init {
        require(expectedSsid.isNotBlank()) { "ssid must not be blank" }
        require('\u0000' !in expectedSsid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        require(passphrase.isEmpty() || passphrase.length in 8..63) {
            "passphrase must be empty or between 8 and 63 characters"
        }
        require(channel in 0..196) { "channel must be 0 or in 1..196" }
        require(expectedSecurity == Iap2WirelessSecurity.NONE || passphrase.isNotEmpty()) {
            "passphrase is required for secured manual hotspots"
        }
        require(expectedSecurity != Iap2WirelessSecurity.NONE || passphrase.isEmpty()) {
            "passphrase must be empty for open manual hotspots"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ManualHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        var lastReason = "local hotspot interface was not found"
        while (true) {
            check(!closed) { "ManualHotspotManager is closed" }
            val localInterface = findLocalHotspotInterface()
            if (localInterface != null) {
                val connectionFrequency = frequencyFromConnectionInfo()
                val scanFrequency = frequencyFromScanResult(localInterface)
                val channel = observedManualHotspotChannel(
                    apChannel = 0,
                    connectionFrequencyMHz = connectionFrequency,
                    scanFrequencyMHz = scanFrequency,
                    apFrequencyMHz = null,
                )
                val frequencyMHz = when {
                    connectionFrequency != null -> connectionFrequency
                    scanFrequency != null -> scanFrequency
                    else -> null
                }

                if (channel == 0) {
                    Log.w(
                        TAG,
                        "Could not read the active hotspot channel from Android public APIs; " +
                            "reporting iAP2 channel 0 (auto)",
                    )
                }
                return WirelessHotspotInfo(
                    ssid = expectedSsid,
                    passphrase = passphrase,
                    security = expectedSecurity,
                    channel = channel,
                    frequencyMHz = frequencyMHz,
                    bssid = localInterface.hardwareAddress,
                    interfaceName = localInterface.name,
                    hostAddress = localInterface.hostAddress,
                    bandLabel = when (expectedBand) {
                        ManualHotspotBand.GHZ_2_4 -> "2.4 GHz"
                        ManualHotspotBand.GHZ_5 -> "5 GHz"
                        ManualHotspotBand.AUTO ->
                            frequencyMHz?.let(::bandLabel) ?: "Auto"
                    },
                    backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
                )
            }

            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for the manual hotspot: " +
                        lastReason,
                )
            }
            sleep(minOf(remainingNanos, INTERFACE_POLL_NANOS))
        }
    }

    override fun close() {
        closed = true
    }

    private fun findLocalHotspotInterface(): LocalHotspotInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        val primaryInterface = activeNetworkInterfaceName()
        return Collections.list(interfaces)
            .asSequence()
            .filter { isUsableInterface(it, primaryInterface) }
            .mapNotNull { networkInterface ->
                networkInterface.hotspotAddress()?.let { address ->
                    LocalHotspotInterface(
                        name = networkInterface.name,
                        hostAddress = address,
                        hardwareAddress = networkInterface.hardwareAddress?.toMacAddressString(),
                        score = interfaceScore(networkInterface.name, address),
                    )
                }
            }
            .maxByOrNull(LocalHotspotInterface::score)
    }

    private fun activeNetworkInterfaceName(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return connectivityManager?.activeNetwork
            ?.let { connectivityManager.getLinkProperties(it)?.interfaceName }
    }

    private fun isUsableInterface(
        networkInterface: NetworkInterface,
        primaryInterface: String?,
    ): Boolean = try {
        networkInterface.name != primaryInterface &&
            !networkInterface.isLoopback &&
            networkInterface.isUp &&
            EXCLUDED_INTERFACE_PREFIXES.none { networkInterface.name.startsWith(it) }
    } catch (_: SocketException) {
        false
    }

    private fun interfaceScore(name: String, address: InetAddress): Int {
        var score = when {
            name.startsWith("ap") || name.contains("softap", ignoreCase = true) -> 100
            name.startsWith("p2p") -> 80
            name.startsWith("wlan") -> 70
            else -> 0
        }
        if (address is Inet4Address) {
            val bytes = address.address
            when {
                bytes[0] == 192.toByte() && bytes[1] == 168.toByte() -> score += 30
                address.isSiteLocalAddress -> score += 20
            }
        }
        if (address is Inet6Address && address.isLinkLocalAddress) score += 15
        return score
    }

    private fun NetworkInterface.hotspotAddress(): InetAddress? {
        var ipv6: Inet6Address? = null
        for (address in Collections.list(inetAddresses)) {
            if (address is Inet4Address && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress
            ) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress) {
                if (ipv6 == null) {
                    ipv6 = if (address.scopeId == index) {
                        address
                    } else {
                        try {
                            Inet6Address.getByAddress(null, address.address, this)
                        } catch (_: UnknownHostException) {
                            null
                        }
                    }
                }
            }
        }
        return ipv6
    }

    @Suppress("DEPRECATION")
    private fun frequencyFromConnectionInfo(): Int? {
        val connectionInfo = try {
            wifiManager.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (unquote(connectionInfo.ssid) != expectedSsid) return null
        return connectionInfo.frequency.takeIf { it > 0 }
    }

    @Suppress("DEPRECATION")
    private fun frequencyFromScanResult(localInterface: LocalHotspotInterface): Int? {
        val localBssid = localInterface.hardwareAddress ?: return null
        val scanResults = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            return null
        }
        return scanResults.firstOrNull { result ->
            result.SSID == expectedSsid &&
                result.BSSID.equals(localBssid, ignoreCase = true) &&
                result.frequency > 0
        }?.frequency
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun sleep(nanos: Long) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the manual hotspot", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private class LocalHotspotInterface(
        val name: String,
        val hostAddress: InetAddress,
        val hardwareAddress: String?,
        val score: Int,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val INTERFACE_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(250)
        val EXCLUDED_INTERFACE_PREFIXES = listOf(
            "lo",
            "dummy",
            "rmnet",
            "r_rmnet",
            "tun",
            "ppp",
            "sit",
            "ip6",
            "bond",
        )
    }
}

private fun ManualHotspotSecurity.toIap2Security(): Iap2WirelessSecurity = when (this) {
    ManualHotspotSecurity.OPEN -> Iap2WirelessSecurity.NONE
    ManualHotspotSecurity.WPA2 -> Iap2WirelessSecurity.WPA_WPA2
    ManualHotspotSecurity.WPA3_TRANSITION -> Iap2WirelessSecurity.WPA3_TRANSITION
    ManualHotspotSecurity.WPA3 -> Iap2WirelessSecurity.WPA3_ONLY
}
