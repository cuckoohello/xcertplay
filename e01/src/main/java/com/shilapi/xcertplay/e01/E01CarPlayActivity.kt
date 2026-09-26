package com.shilapi.xcertplay.e01

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.mfi.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.URL
import java.util.ArrayDeque

class E01CarPlayActivity : Activity(), SurfaceHolder.Callback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()

    private lateinit var identity: AirPlayIdentity
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusBar: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var logView: TextView
    private lateinit var settingsOverlay: View
    private lateinit var diagnosticSwitch: Switch
    private lateinit var localMfiSwitch: Switch
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText

    private var currentSurface: Surface? = null
    private var mediaSink: AndroidMediaSink? = null
    private var controller: E01WiredCarPlayController? = null
    private var vpnConsentPending = false
    private var restartGeneration = 0
    private var screenActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val bootstrapError = runCatching { E01Bootstrap.ensure(this) }.exceptionOrNull()
        identity = E01Persistence.loadIdentity(this)
        setContentView(buildContentView())
        applyFullscreen()

        val settings = E01Persistence.loadRemoteMfi(this)
        val target = E01Persistence.loadMfiTarget(this)
        serverInput.setText(settings.serverUrl)
        tokenInput.setText(settings.bearerToken)
        diagnosticSwitch.isChecked = E01Persistence.loadNoMfiDiagnostic(this)
        localMfiSwitch.isChecked = target == MfiTarget.LOCAL
        updateMfiInputsEnabled()
        appendLog("E01 wired host ready")
        if (bootstrapError != null) {
            appendLog("Local MFi identity unavailable: ${bootstrapError.message ?: bootstrapError.javaClass.simpleName}")
            if (localMfiSwitch.isChecked) {
                showSettings(true)
                updateStatus(
                    "config",
                    "Local MFi identity is missing",
                    failed = true,
                )
                return
            }
        }
        if (!diagnosticSwitch.isChecked && !localMfiSwitch.isChecked && settings.serverUrl.isBlank()) {
            showSettings(true)
            updateStatus("config", "Remote MFi server is required", failed = true)
        } else {
            ensureVpnAndStart()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (controller == null && !vpnConsentPending) ensureVpnAndStart()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onBackPressed() {
        if (settingsOverlay.visibility == View.VISIBLE) {
            showSettings(false)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        restartGeneration += 1
        controller?.close()
        controller = null
        mediaSink?.close()
        mediaSink = null
        currentSurface = null
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        currentSurface = holder.surface
        attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        currentSurface = holder.surface
        attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mediaSink?.clearSurface(SCREEN_TYPE_MAIN, holder.surface)
        mediaSink?.clearSurface(SCREEN_TYPE_ALT, holder.surface)
        if (currentSurface === holder.surface) currentSurface = null
    }

    @Deprecated("Uses the platform activity result API for Android 5.1 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_REQUEST_CODE) return
        vpnConsentPending = false
        if (resultCode == RESULT_OK) {
            appendLog("VPN permission granted")
            restartController()
        } else {
            updateStatus("network", "VPN permission denied", failed = true)
        }
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
        }

        surfaceView = CarPlaySurfaceView(this).apply {
            holder.addCallback(this@E01CarPlayActivity)
            setZOrderOnTop(false)
            isFocusable = true
            isClickable = true
            setOnTouchListener { view, event ->
                forwardTouch(view, event)
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                true
            }
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        statusBar = buildStatusBar()
        root.addView(
            statusBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(48),
                Gravity.TOP,
            ),
        )

        logView = TextView(this).apply {
            setTextColor(TEXT_SECONDARY)
            textSize = 11f
            maxLines = 5
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = solidDrawable(Color.argb(185, 10, 12, 14), 4)
        }
        root.addView(
            logView,
            FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.68f).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply {
                setMargins(dp(12), 0, 0, dp(12))
            },
        )

        root.addView(
            iconButton(
                icon = android.R.drawable.ic_menu_manage,
                description = "Connection settings",
                onClick = { showSettings(true) },
            ),
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END),
        )

        settingsOverlay = buildSettingsOverlay().apply {
            visibility = View.GONE
        }
        root.addView(
            settingsOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return root
    }

    private fun buildStatusBar(): View {
        statusDot = View(this).apply {
            background = solidDrawable(STATUS_WAITING, 5)
        }
        statusText = TextView(this).apply {
            text = "Starting"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(54), 0)
            setBackgroundColor(Color.argb(205, 12, 15, 18))
            addView(
                statusDot,
                LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    marginEnd = dp(10)
                },
            )
            addView(
                statusText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                iconButton(
                    icon = android.R.drawable.ic_popup_sync,
                    description = "Reconnect",
                    onClick = { restartController() },
                ),
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
        }
    }

    private fun buildSettingsOverlay(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(220, 0, 0, 0))
            isClickable = true
        }
        val panelWidth = minOf(dp(430), (resources.displayMetrics.widthPixels * 0.52f).toInt())
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(24))
            setBackgroundColor(PANEL)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                panel,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        overlay.addView(
            scroll,
            FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END),
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            label("E01 Wired CarPlay", 22f, Color.WHITE),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            iconButton(
                icon = android.R.drawable.ic_menu_close_clear_cancel,
                description = "Close settings",
                onClick = { showSettings(false) },
            ),
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        panel.addView(header)

        panel.addView(sectionLabel("MODE"))
        diagnosticSwitch = Switch(this).apply {
            text = "No-MFi diagnostics"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, _ -> updateMfiInputsEnabled() }
        }
        panel.addView(
            diagnosticSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            ),
        )
        localMfiSwitch = Switch(this).apply {
            text = "Use local offline MFi identity"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, _ -> updateMfiInputsEnabled() }
        }
        panel.addView(
            localMfiSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            ),
        )

        panel.addView(sectionLabel("REMOTE MFI"))
        panel.addView(fieldLabel("Server URL"))
        serverInput = editText(
            hint = "http://192.168.1.10:8080",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        panel.addView(
            serverInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            ),
        )

        panel.addView(fieldLabel("Bearer token"))
        tokenInput = editText(
            hint = "Optional",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ).apply {
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        panel.addView(
            tokenInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            ),
        )

        panel.addView(
            actionButton("Save and connect", ACCENT) { saveAndConnect() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            ).apply {
                topMargin = dp(22)
            },
        )
        panel.addView(
            actionButton("Clear iPhone pairing", DANGER) { confirmClearTrust() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply {
                topMargin = dp(12)
            },
        )
        panel.addView(
            label(
                "Display: 1280 x 720, H.264, 30 fps\nTransport: USB config 6 + NCM\nMicrophone: off",
                12f,
                TEXT_SECONDARY,
            ).apply {
                setPadding(0, dp(22), 0, 0)
            },
        )
        return overlay
    }

    private fun saveAndConnect() {
        val server = serverInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString()
        val diagnosticMode = diagnosticSwitch.isChecked
        val useLocalMfi = localMfiSwitch.isChecked
        if (!diagnosticMode && !useLocalMfi) {
            val validationError = validateServer(server)
            if (validationError != null) {
                serverInput.error = validationError
                return
            }
        }
        E01Persistence.saveRemoteMfi(this, RemoteMfiSettings(server, token))
        E01Persistence.saveNoMfiDiagnostic(this, diagnosticMode)
        E01Persistence.saveMfiTarget(
            this,
            if (useLocalMfi) MfiTarget.LOCAL else MfiTarget.REMOTE,
        )
        appendLog(
            when {
                diagnosticMode -> "No-MFi diagnostic mode enabled"
                useLocalMfi -> "Local offline MFi enabled"
                else -> "Remote MFi settings saved"
            },
        )
        showSettings(false)
        ensureVpnAndStart()
    }

    private fun validateServer(server: String): String? {
        if (server.isBlank()) return "Server URL is required"
        if ('\u0000' in server) return "Server URL contains an invalid character"
        return try {
            val url = URL(server)
            if (url.protocol != "http" && url.protocol != "https") {
                "Use an http:// or https:// URL"
            } else if (url.host.isNullOrBlank()) {
                "Server host is required"
            } else {
                null
            }
        } catch (_: Exception) {
            "Enter a valid server URL"
        }
    }

    private fun confirmClearTrust() {
        AlertDialog.Builder(this)
            .setTitle("Clear pairing?")
            .setMessage("The iPhone trust prompt and AirPlay pairing will be required again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                E01Persistence.clearTrust(this)
                appendLog("Stored iPhone pairing cleared")
                showSettings(false)
                restartController()
            }
            .show()
    }

    private fun ensureVpnAndStart() {
        val settings = E01Persistence.loadRemoteMfi(this)
        val diagnosticMode = E01Persistence.loadNoMfiDiagnostic(this)
        val target = E01Persistence.loadMfiTarget(this)
        if (!diagnosticMode && target == MfiTarget.REMOTE && settings.serverUrl.isBlank()) {
            showSettings(true)
            updateStatus("config", "Remote MFi server is required", failed = true)
            return
        }
        if (diagnosticMode) {
            restartController()
            return
        }
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            restartController()
        } else if (!vpnConsentPending) {
            vpnConsentPending = true
            updateStatus("network", "Approve the VPN connection")
            @Suppress("DEPRECATION")
            startActivityForResult(consent, VPN_REQUEST_CODE)
        }
    }

    private fun restartController() {
        val settings = E01Persistence.loadRemoteMfi(this)
        val diagnosticMode = E01Persistence.loadNoMfiDiagnostic(this)
        val target = E01Persistence.loadMfiTarget(this)
        if (!diagnosticMode && target == MfiTarget.REMOTE && settings.serverUrl.isBlank()) {
            showSettings(true)
            updateStatus("config", "Remote MFi server is required", failed = true)
            return
        }
        val generation = ++restartGeneration
        val oldController = controller
        controller = null
        oldController?.close()
        mediaSink?.close()
        mediaSink = null
        screenActive = false
        statusBar.visibility = View.VISIBLE
        logView.visibility = View.VISIBLE
        updateStatus("startup", if (oldController == null) "Starting wired CarPlay" else "Restarting wired CarPlay")
        mainHandler.postDelayed(
            {
                if (generation == restartGeneration && !isFinishing) {
                    startController(settings, diagnosticMode, target, generation)
                }
            },
            if (oldController == null) 0L else RESTART_DELAY_MILLIS,
        )
    }

    private fun startController(
        settings: RemoteMfiSettings,
        diagnosticMode: Boolean,
        target: MfiTarget,
        generation: Int,
    ) {
        val sink = AndroidMediaSink(
            surface = null,
            videoWidth = DISPLAY_WIDTH,
            videoHeight = DISPLAY_HEIGHT,
            preferSoftwareHevcDecoder = false,
            advancedAudioChannelMapping = false,
            onScreenStreamActiveChanged = { _, active ->
                runOnUiThread {
                    if (generation == restartGeneration) setScreenActive(active)
                }
            },
        )
        mediaSink = sink
        currentSurface?.let(::attachSurface)
        val media = CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = false,
        )
        val next = E01WiredCarPlayController(
            context = this,
            mfiTarget = target,
            remoteMfi = settings,
            airPlayConfig = createAirPlayConfig(),
            identity = identity,
            pairings = E01Persistence.loadPairings(this),
            identification = createIdentification(),
            media = media,
            listener = createSessionListener(generation),
            reportStatus = { status ->
                if (generation == restartGeneration) {
                    updateStatus(status.stage, status.detail, status.failed)
                }
            },
            loadPairRecord = { E01Persistence.loadLockdownRecord(this) },
            savePairRecord = { E01Persistence.saveLockdownRecord(this, it) },
            clearPairRecord = { E01Persistence.clearLockdownRecord(this) },
            noMfiDiagnostic = diagnosticMode,
        )
        controller = next
        try {
            next.start()
        } catch (error: Throwable) {
            updateStatus(
                "startup",
                error.message ?: error.javaClass.simpleName,
                failed = true,
            )
        }
    }

    private fun createAirPlayConfig(): AirPlayConfig = AirPlayConfig(
        deviceName = "xcertplay E01",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(
            widthPixels = DISPLAY_WIDTH,
            heightPixels = DISPLAY_HEIGHT,
            fps = DISPLAY_FPS,
        ),
        hevc = false,
        microphone = false,
        manufacturer = "Geely",
        model = "E01",
        oemLabel = "Geometry C",
    )

    private fun createIdentification(): Iap2IdentificationConfig = Iap2IdentificationConfig(
        name = "xcertplay E01",
        modelIdentifier = "emx8816aa",
        manufacturer = "Geely",
        serialNumber = "E01-XCERTPLAY",
        firmwareVersion = "GE13",
        hardwareVersion = "E01",
        // The controller replaces this with config 6's discovered NCM data interface.
        carPlayUsbInterfaceNumber = 0,
        language = "zh",
        locationInformationEnabled = false,
    )

    private fun createSessionListener(generation: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (generation == restartGeneration) {
                        updateStatus("airplay", "CarPlay active")
                    }
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (generation == restartGeneration) {
                        setScreenActive(false)
                        updateStatus("airplay", "Session ended; reconnect when ready", failed = true)
                    }
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (generation == restartGeneration) {
                        setScreenActive(false)
                        updateStatus("network", message, failed = true)
                    }
                }
            }

            override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
                appendLog("iPhone connected: ${info.name} (${info.model})")
            }

            override fun onHostUiRequested(session: AirPlaySession) {
                appendLog("iPhone requested host UI")
            }

            override fun onDebugLog(message: String) {
                appendLog(message)
            }
        }

    private fun forwardTouch(view: View, event: MotionEvent) {
        val contacts = CarPlayTouchMapper.contacts(event, view.width, view.height)
        controller?.sendTouch(contacts)
    }

    private fun updateMfiInputsEnabled() {
        if (
            !::serverInput.isInitialized ||
            !::tokenInput.isInitialized ||
            !::diagnosticSwitch.isInitialized ||
            !::localMfiSwitch.isInitialized
        ) {
            return
        }
        val diagnostic = diagnosticSwitch.isChecked
        val local = localMfiSwitch.isChecked
        val remoteEnabled = !diagnostic && !local
        serverInput.isEnabled = remoteEnabled
        tokenInput.isEnabled = remoteEnabled
        serverInput.alpha = if (remoteEnabled) 1f else 0.45f
        tokenInput.alpha = if (remoteEnabled) 1f else 0.45f
    }

    private fun attachSurface(surface: Surface) {
        if (!surface.isValid) return
        mediaSink?.setSurface(SCREEN_TYPE_MAIN, surface)
        mediaSink?.setSurface(SCREEN_TYPE_ALT, surface)
    }

    private fun setScreenActive(active: Boolean) {
        screenActive = active
        if (settingsOverlay.visibility == View.VISIBLE) return
        statusBar.visibility = if (active) View.GONE else View.VISIBLE
        logView.visibility = if (active) View.GONE else View.VISIBLE
    }

    private fun updateStatus(stage: String, detail: String, failed: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updateStatus(stage, detail, failed) }
            return
        }
        statusText.text = "$stage  |  $detail"
        val statusColor = when {
            failed -> DANGER
            stage == "airplay" -> ACCENT
            else -> STATUS_WAITING
        }
        statusDot.background = solidDrawable(statusColor, 5)
        if (failed) {
            statusBar.visibility = View.VISIBLE
            logView.visibility = View.VISIBLE
        }
        appendLog("$stage: $detail")
    }

    private fun appendLog(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { appendLog(message) }
            return
        }
        logLines.addLast(message)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }

    private fun showSettings(show: Boolean) {
        settingsOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            statusBar.visibility = if (screenActive) View.GONE else View.VISIBLE
            logView.visibility = if (screenActive) View.GONE else View.VISIBLE
            applyFullscreen()
        }
    }

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = description
            setColorFilter(Color.WHITE)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = solidDrawable(Color.argb(150, 18, 22, 26), 4)
            setOnClickListener { onClick() }
        }

    private fun actionButton(text: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            background = solidDrawable(color, 4)
            setOnClickListener { onClick() }
        }

    private fun editText(hint: String, inputType: Int): EditText =
        EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setTextColor(Color.WHITE)
            setHintTextColor(TEXT_SECONDARY)
            textSize = 14f
            backgroundTintList = ColorStateList.valueOf(ACCENT)
            setSingleLine(true)
        }

    private fun sectionLabel(text: String): TextView =
        label(text, 12f, ACCENT).apply {
            setPadding(0, dp(24), 0, dp(8))
        }

    private fun fieldLabel(text: String): TextView =
        label(text, 12f, TEXT_SECONDARY).apply {
            setPadding(0, dp(12), 0, 0)
        }

    private fun label(text: String, size: Float, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    private fun solidDrawable(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }

    private fun applyFullscreen() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private class CarPlaySurfaceView(context: Context) : SurfaceView(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private companion object {
        const val VPN_REQUEST_CODE = 1001
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val DISPLAY_WIDTH = 1280
        const val DISPLAY_HEIGHT = 720
        const val DISPLAY_FPS = 30
        const val MAX_LOG_LINES = 5
        const val RESTART_DELAY_MILLIS = 2_500L
        val BACKGROUND = Color.rgb(10, 12, 14)
        val PANEL = Color.rgb(22, 26, 30)
        val TEXT_SECONDARY = Color.rgb(178, 188, 196)
        val ACCENT = Color.rgb(35, 145, 105)
        val DANGER = Color.rgb(184, 55, 55)
        val STATUS_WAITING = Color.rgb(232, 167, 49)
    }
}
