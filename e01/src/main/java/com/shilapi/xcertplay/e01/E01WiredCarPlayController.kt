package com.shilapi.xcertplay.e01

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.RemoteMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.Iap2WiredDiagnosticClient
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class E01ConnectionStatus(
    val stage: String,
    val detail: String,
    val failed: Boolean = false,
)

/**
 * E01-only wired pipeline. It intentionally has no Bluetooth, hotspot, local MFi, or location
 * branches so every API in this controller is valid on Android 5.1.
 */
internal class E01WiredCarPlayController(
    context: Context,
    private val remoteMfi: RemoteMfiSettings,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val identification: Iap2IdentificationConfig,
    private val media: AirPlayMediaHandler,
    private val listener: AirPlaySessionListener,
    private val reportStatus: (E01ConnectionStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord?,
    private val savePairRecord: (LockdownPairRecord) -> Unit,
    private val clearPairRecord: () -> Unit,
    private val noMfiDiagnostic: Boolean = false,
) : Closeable {
    private enum class Phase {
        IDLE,
        MFI,
        PHONE,
        REENUMERATION,
        DATA_PATHS,
        CONTROL,
        COMPLETE,
        FAILED,
        CLOSED,
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val iphoneHost = IphoneUsbHost(
        appContext,
        usbManager,
        IphoneUsbMatcher.appleVendor(),
        carPlayConfigurationId = E01_CARPLAY_CONFIGURATION_ID,
    )
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionDelivered = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)

    @Volatile private var phase = Phase.IDLE
    @Volatile private var mfi: RemoteMfiAuthenticationClient? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var controlSession: Iap2Session? = null
    @Volatile private var activeAirPlaySession: AirPlaySession? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    private var vpnLatch = CountDownLatch(1)
    private var reenumerationAttempts = 0
    private var pollGeneration = 0
    private var permissionGeneration = 0
    private var reenumerationDeadlineNanos = 0L
    private var permissionReceiver: Closeable? = null
    private var attachReceiver: Closeable? = null

    private data class OpenedNcm(
        val bridge: NcmUsbBridge,
        val carPlayUsbInterfaceNumber: Int,
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            if (!closed.get()) fail("network", IOException("CarPlay VPN service disconnected"))
        }
    }

    private val forwardingListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            activeAirPlaySession = session
            status("airplay", "CarPlay active")
            listener.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeAirPlaySession === session) activeAirPlaySession = null
            listener.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            listener.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            listener.onDeviceInfo(session, info)
        }

        override fun onHostUiRequested(session: AirPlaySession) {
            listener.onHostUiRequested(session)
        }

        override fun onCommand(
            session: AirPlaySession,
            type: String,
            params: Map<String, Any?>,
        ) {
            listener.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debug(message)
            listener.onDebugLog(message)
        }
    }

    fun start() {
        check(noMfiDiagnostic || remoteMfi.serverUrl.isNotBlank()) {
            "Remote MFi server URL is required"
        }
        if (closed.get() || phase != Phase.IDLE) return
        permissionReceiver = iphoneHost.registerPermissionReceiver(::onPermissionResult)
        attachReceiver = iphoneHost.registerAttachReceiver(::onIphoneAttached)
        if (noMfiDiagnostic) {
            status("diagnostic", "Remote MFi bypassed")
            startPhoneDiscovery()
            return
        }
        phase = Phase.MFI
        bindVpn()
        if (!vpnBound) return
        status("mfi", "Checking Remote MFi")
        worker.execute(::prepareMfi)
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        val session = activeAirPlaySession ?: return false
        return try {
            touchWorker.execute { session.sendTouch(contacts) }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        phase = Phase.CLOSED
        pollGeneration += 1
        permissionGeneration += 1
        permissionReceiver.closeQuietly()
        permissionReceiver = null
        attachReceiver.closeQuietly()
        attachReceiver = null
        touchWorker.shutdownNow()
        Thread(
            {
                controlSession.closeQuietly()
                controlSession = null
                mux.closeQuietly()
                mux = null
                vpnService?.detach()
                unbindVpn()
                worker.shutdownNow()
            },
            "e01-carplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun prepareMfi() {
        try {
            val client = RemoteMfiAuthenticationClient(
                serverAddress = remoteMfi.serverUrl,
                token = remoteMfi.bearerToken.takeIf(String::isNotEmpty),
            )
            client.reset()
            val protocolMajor = client.protocolMajor()
            if (closed.get() || phase != Phase.MFI) return
            mfi = client
            debug("remote MFi ready protocolMajor=$protocolMajor")
            status("mfi", "Remote MFi ready")
            startPhoneDiscovery()
        } catch (error: Throwable) {
            fail("mfi", error)
        }
    }

    private fun startPhoneDiscovery() {
        if (closed.get()) return
        phase = Phase.PHONE
        reenumerationAttempts = 0
        status("usb", "Searching for iPhone")
        mainHandler.post(::checkPhoneAvailability)
    }

    private fun checkPhoneAvailability() {
        if (closed.get() || phase != Phase.PHONE) return
        val devices = iphoneHost.discover()
        val device = devices.firstOrNull { e01Configuration(it) != null }
            ?: devices.firstOrNull()
        if (device == null) {
            status("usb", "Connect iPhone by USB")
            schedulePhonePoll()
            return
        }
        debug(
            "iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                "pid=0x${device.productId.toString(16)}",
        )
        requestPermission(device)
    }

    private fun requestPermission(device: UsbDevice) {
        if (closed.get()) return
        mainHandler.post {
            if (closed.get()) return@post
            try {
                permissionDelivered.set(false)
                when (val request = iphoneHost.requestPermission(device)) {
                    is IphoneUsbHost.PermissionRequest.AlreadyGranted ->
                        onPermissionResult(IphoneUsbHost.PermissionResult.Granted(request.device))

                    is IphoneUsbHost.PermissionRequest.Requested -> {
                        status("usb", "Grant iPhone USB access")
                        pollPermission(device)
                    }
                }
            } catch (error: Throwable) {
                fail("usb", error)
            }
        }
    }

    private fun pollPermission(device: UsbDevice) {
        val generation = ++permissionGeneration
        val deadlineNanos = System.nanoTime() +
            PERMISSION_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        val poll = object : Runnable {
            override fun run() {
                if (
                    closed.get() ||
                    generation != permissionGeneration ||
                    phase !in setOf(Phase.PHONE, Phase.REENUMERATION)
                ) {
                    return
                }
                if (usbManager.hasPermission(device)) {
                    onPermissionResult(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    fail("usb", IOException("iPhone USB permission timed out"))
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(poll, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun onPermissionResult(result: IphoneUsbHost.PermissionResult) {
        if (closed.get()) return
        when (result) {
            is IphoneUsbHost.PermissionResult.Denied ->
                fail("usb", IOException("iPhone USB permission was denied"))

            is IphoneUsbHost.PermissionResult.Granted -> {
                if (!permissionDelivered.compareAndSet(false, true)) return
                permissionGeneration += 1
                val configuration = e01Configuration(result.device)
                when {
                    configuration != null -> openDataPaths(result.device)
                    phase == Phase.PHONE -> requestReenumeration(result.device)
                    phase == Phase.REENUMERATION -> scheduleReenumerationPoll()
                    else -> Unit
                }
            }
        }
    }

    private fun requestReenumeration(device: UsbDevice) {
        if (reenumerationAttempts >= MAXIMUM_REENUMERATION_ATTEMPTS) {
            fail("usb", IOException("iPhone did not expose the CarPlay USB configuration"))
            return
        }
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        reenumerationDeadlineNanos = System.nanoTime() +
            REENUMERATION_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        status("usb", "Switching iPhone to CarPlay USB mode")
        iphoneHost.requestCarPlayReenumerationAsync(device, worker) { result ->
            when (result) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    debug("USB 0x52 sent; waiting for CarPlay descriptors")
                    status("usb", "Waiting for USB re-enumeration")
                    mainHandler.postDelayed(
                        ::checkReenumeratedPhone,
                        REENUMERATION_INITIAL_DELAY_MILLIS,
                    )
                }

                is IphoneUsbHost.TransitionResult.Failed -> fail("usb", result.error)
            }
        }
    }

    private fun checkReenumeratedPhone() {
        if (closed.get() || phase != Phase.REENUMERATION) return
        val device = iphoneHost.discover()
            .firstOrNull { e01Configuration(it) != null }
        if (device != null) {
            requestPermission(device)
            return
        }
        if (System.nanoTime() < reenumerationDeadlineNanos) {
            scheduleReenumerationPoll()
            return
        }
        if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {
            phase = Phase.PHONE
            status("usb", "Retrying CarPlay USB switch")
            checkPhoneAvailability()
        } else {
            fail("usb", IOException("USB re-enumeration timed out after two attempts"))
        }
    }

    private fun schedulePhonePoll() {
        val generation = ++pollGeneration
        mainHandler.postDelayed(
            {
                if (!closed.get() && phase == Phase.PHONE && generation == pollGeneration) {
                    checkPhoneAvailability()
                }
            },
            DEVICE_POLL_INTERVAL_MILLIS,
        )
    }

    private fun scheduleReenumerationPoll() {
        mainHandler.postDelayed(::checkReenumeratedPhone, REENUMERATION_POLL_INTERVAL_MILLIS)
    }

    private fun onIphoneAttached(device: UsbDevice) {
        if (closed.get()) return
        when (phase) {
            Phase.PHONE -> requestPermission(device)
            Phase.REENUMERATION -> {
                if (e01Configuration(device) != null) requestPermission(device)
            }
            else -> Unit
        }
    }

    private fun openDataPaths(device: UsbDevice) {
        if (phase == Phase.DATA_PATHS || phase == Phase.CONTROL || closed.get()) return
        phase = Phase.DATA_PATHS
        pollGeneration += 1
        status("usb", "Opening USBMUX and NCM")
        iphoneHost.openIap2UsbSessionAsync(device, worker) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail("usb", result.error)
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    try {
                        val ncm = openNcm(device)
                        runStack(
                            result.session,
                            ncm.bridge,
                            ncm.carPlayUsbInterfaceNumber,
                        )
                    } catch (error: Throwable) {
                        result.session.closeQuietly()
                        fail("usb", error)
                    }
                }
            }
        }
    }

    private fun openNcm(device: UsbDevice): OpenedNcm {
        val configuration = e01Configuration(device)
            ?: throw IphoneUsbException.Protocol("CarPlay USB configuration disappeared")
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("CarPlay configuration has no NCM function")
        debug(
            "NCM config=${configuration.id} control=${function.control.id}/" +
                "${function.control.alternateSetting} data=${function.data.id}/" +
                "${function.data.alternateSetting}",
        )
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open iPhone NCM connection")
        return OpenedNcm(
            bridge = NcmUsbBridge.open(connection, function),
            carPlayUsbInterfaceNumber = function.data.id,
        )
    }

    private fun runStack(
        usbSession: Iap2UsbSession,
        ncm: NcmUsbBridge,
        carPlayUsbInterfaceNumber: Int,
    ) {
        phase = Phase.CONTROL
        var usbOwnedLocally = true
        var ncmOwnedLocally = true
        try {
            if (closed.get()) return
            val openedMux = Iap2UsbMuxHost.open(usbSession)
            usbOwnedLocally = false
            mux = openedMux
            debug("USBMUX version 2 ready")

            status("lockdown", "Pairing with iPhone")
            val pairingClient = LockdownPairingClient(openedMux)
            val saved = loadPairRecord()
            var pairRecord = saved ?: pairNewRecord(pairingClient)
            debug(if (saved == null) "new Lockdown pair record saved" else "saved Lockdown pair record loaded")

            status("lockdown", "Opening com.apple.carkit.service")
            val carKit = LockdownCarKitClient(openedMux)
            val carKitStream = try {
                carKit.open(pairRecord, ACCESSORY_LABEL)
            } catch (error: Throwable) {
                if (!isInvalidPairRecord(error)) throw error
                debug("saved Lockdown record rejected; pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKit.open(pairRecord, ACCESSORY_LABEL)
            }
            val csm = Iap2Session.open(
                carKitStream,
                traceContext = "e01-wired",
                onTrace = ::debug,
            )
            controlSession = csm
            debug("carkit iAP2 control channel ready")

            val effectiveIdentification = identification.copy(
                carPlayUsbInterfaceNumber = carPlayUsbInterfaceNumber,
            )
            if (noMfiDiagnostic) {
                status("diagnostic", "Waiting for MFi request AA00")
                val result = Iap2WiredDiagnosticClient(csm).runUntilAuthenticationRequest(
                    identification = effectiveIdentification,
                    timeoutMillis = DIAGNOSTIC_TIMEOUT_MILLIS,
                    onProgress = { debug(it) },
                )
                debug(
                    "diagnostic PASS received AA00 skippedFrames=${result.skippedFrames}; " +
                        "AA01 was not sent",
                )
                controlSession = null
                csm.closeQuietly()
                mux = null
                openedMux.closeQuietly()
                phase = Phase.COMPLETE
                status("diagnostic", "PASS: AA00 received; stopped before AA01")
                return
            }

            val hostMac = ncm.hostMac ?: DEFAULT_HOST_MAC
            status("network", "Attaching NCM and AirPlay")
            attachVpn(ncm, hostMac)
            ncmOwnedLocally = false

            val authenticator = mfi
                ?: throw IOException("Remote MFi client is unavailable")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(LINK_LOCAL_ADDRESS),
                airPlayPort = airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = hostMac.macString(),
            )
            status("iap2", "Starting wired CarPlay")
            val result = Iap2WiredControlClient(
                csm,
                Iap2MfiAuthenticationClient(authenticator),
            ).run(
                identification = effectiveIdentification,
                endpoint = endpoint,
                availableCurrentMilliAmps = AVAILABLE_CURRENT_MILLIAMPS,
                timeoutMillis = CONTROL_LOOP_TIMEOUT_MILLIS,
                onProgress = { debug(it) },
            )
            if (!closed.get()) {
                when (result.terminal) {
                    Iap2WiredControlTerminal.TIMED_OUT ->
                        fail("iap2", IOException("Wired CarPlay control window timed out"))

                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        fail("iap2", IOException("Wired CarPlay control channel closed"))
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) {
                if (!ncmOwnedLocally) vpnService?.detach()
                fail("carplay", error)
            }
        } finally {
            if (usbOwnedLocally) usbSession.closeQuietly()
            if (ncmOwnedLocally) ncm.closeQuietly()
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = ACCESSORY_LABEL,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = closed::get,
        ).pairRecord.also(savePairRecord)

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray) {
        val service = awaitVpnService()
            ?: throw IOException("Could not bind CarPlay VPN service")
        val authenticator = mfi
            ?: throw IOException("Remote MFi client is unavailable")
        when (
            val result = service.attach(
                ncm = ncm,
                linkLocal = LINK_LOCAL_ADDRESS,
                hostMac = hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = authenticator,
                listener = forwardingListener,
                media = media,
            )
        ) {
            CarPlayVpnService.AttachResult.Started -> debug("NCM/VPN AirPlay listener ready")
            CarPlayVpnService.AttachResult.AlreadyStarted ->
                throw IOException("CarPlay VPN service already has an attachment")

            is CarPlayVpnService.AttachResult.Failed -> throw IOException(result.message)
        }
    }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound || closed.get()) return
        vpnBound = true
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (error: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
            fail("network", error)
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: RuntimeException) {
            // Service may already be disconnected.
        }
        vpnService = null
    }

    private fun isInvalidPairRecord(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause.message?.contains("InvalidPairRecord", ignoreCase = true) == true) return true
            cause = cause.cause
        }
        return false
    }

    private fun e01Configuration(device: UsbDevice) =
        IphoneCarPlayConfiguration.find(device, E01_CARPLAY_CONFIGURATION_ID)

    private fun status(stage: String, detail: String) {
        if (closed.get()) return
        debug("STEP $stage: $detail")
        mainHandler.post {
            if (!closed.get()) reportStatus(E01ConnectionStatus(stage, detail))
        }
    }

    private fun fail(stage: String, error: Throwable) {
        if (closed.get()) return
        phase = Phase.FAILED
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "$stage failed: $message", error)
        mainHandler.post {
            if (!closed.get()) reportStatus(E01ConnectionStatus(stage, message, failed = true))
        }
    }

    private fun debug(message: String) {
        Log.i(TAG, message)
        listener.onDebugLog(message)
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun AutoCloseable?.closeQuietly() {
        try {
            this?.close()
        } catch (error: Throwable) {
            Log.w(TAG, "resource teardown failed", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-e01"
        const val ACCESSORY_LABEL = "xcertplay-e01"
        const val E01_CARPLAY_CONFIGURATION_ID = 6
        const val LINK_LOCAL_ADDRESS = "fe80::2"
        const val AVAILABLE_CURRENT_MILLIAMPS = 2400
        const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        const val DIAGNOSTIC_TIMEOUT_MILLIS = 60_000L
        const val CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60_000L
        const val VPN_BIND_TIMEOUT_MILLIS = 10_000L
        const val DEVICE_POLL_INTERVAL_MILLIS = 2_000L
        const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        const val PERMISSION_TIMEOUT_MILLIS = 120_000L
        const val REENUMERATION_INITIAL_DELAY_MILLIS = 1_000L
        const val REENUMERATION_POLL_INTERVAL_MILLIS = 750L
        const val REENUMERATION_TIMEOUT_MILLIS = 20_000L
        const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val DEFAULT_HOST_MAC = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)
    }
}
