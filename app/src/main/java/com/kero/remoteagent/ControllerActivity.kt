package com.kero.remoteagent

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.sqrt

class ControllerActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var scrollContent: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var remoteScreen: ImageView
    private lateinit var cameraVideoView: TextureView
    private lateinit var controllerServerUrl: EditText
    private lateinit var controllerPairCode: EditText
    private lateinit var controllerConnect: Button
    private lateinit var btnDeviceSettings: Button
    private lateinit var btnOpenWhatsApp: Button

    private var cameraDecoder: MediaCodec? = null
    private var cameraDecoderSurface: Surface? = null
    private var cameraDecoderThread:
        android.os.HandlerThread? = null
    private var cameraDecoderHandler:
        android.os.Handler? = null
    private var pendingCameraVideoConfig:
        JSONObject? = null

    private var audioTrack:
        AudioTrack? = null

    // ============================================================
    // إدارة الأجهزة
    // ============================================================

    private var selectedDeviceId:
        String? = null

    // ✅ الجهاز المطلوب فتحه تلقائيًا بعد الاتصال
    private var autoOpenDeviceId:
        String? = null

    private var lastDeviceList =
        JSONArray()

    private val incomingFiles =
        mutableMapOf<
            String,
            ByteArrayOutputStream
        >()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_controller
        )

        // ✅ استقبل الجهاز المطلوب من شاشة الدخول
        autoOpenDeviceId =
            intent.getStringExtra(
                "autoDeviceId"
            )

        selectedDeviceId =
            autoOpenDeviceId

        initViews()
        setupNavigation()
        setupScreenControl()
        setupCameraVideoDecoder()

        RemoteConnection
            .setControllerStatusListener { status ->

                runOnUiThread {

                    when {

                        status.contains("فشل") ||
                        status.contains("قطع") ||
                        status.contains("غير متصل") -> {

                            updateStatus(
                                "● غير متصل",
                                0xFFFF5252.toInt()
                            )
                        }

                        status.contains("متصل") -> {

                            updateStatus(
                                "● متصل بالسيرفر",
                                0xFF4CAF50.toInt()
                            )

                            requestDeviceList()
                        }

                        else -> {

                            updateStatus(
                                status,
                                0xFFFF9800.toInt()
                            )
                        }
                    }
                }
            }

        loadSavedSettings()
        ensureDeviceListSection()
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun setDeviceSettingsVisible(
        visible: Boolean
    ) {

        btnDeviceSettings.visibility =
            if (visible)
                View.VISIBLE
            else
                View.GONE
    }

    private fun getDeviceName(
        deviceId: String?
    ): String {

        if (
            deviceId.isNullOrBlank()
        ) {

            return "الجهاز"
        }

        // الأول: الاسم المحلي
        val localName =
            DevicePreferences.getName(
                this,
                deviceId,
                ""
            )

        if (
            localName.isNotBlank()
        ) {

            return localName
        }

        // الثاني: الاسم من السيرفر
        for (
            i in 0 until lastDeviceList.length()
        ) {

            val device =
                lastDeviceList.optJSONObject(
                    i
                )
                    ?: continue

            if (
                device.optString(
                    "deviceId"
                ) == deviceId
            ) {

                return device.optString(
                    "deviceName",
                    "الجهاز"
                )
            }
        }

        return "الجهاز"
    }

    // ============================================================
    // التهيئة
    // ============================================================

    private fun initViews() {

        drawerLayout =
            findViewById(
                R.id.drawerLayout
            )

        scrollContent =
            findViewById(
                R.id.scrollContent
            )

        tvStatus =
            findViewById(
                R.id.tvStatus
            )

        remoteScreen =
            findViewById(
                R.id.remoteScreen
            )

        cameraVideoView =
            findViewById(
                R.id.cameraVideoView
            )

        controllerServerUrl =
            findViewById(
                R.id.controllerServerUrl
            )

        controllerPairCode =
            findViewById(
                R.id.controllerPairCode
            )

        controllerConnect =
            findViewById(
                R.id.controllerConnect
            )

        btnDeviceSettings =
            findViewById(
                R.id.btnDeviceSettings
            )

        btnOpenWhatsApp =
            findViewById(
                R.id.btnOpenWhatsApp
            )

        setDeviceSettingsVisible(
            false
        )

        val btnMenu =
            findViewById<Button>(
                R.id.btnMenu
            )

        // ضغطة قصيرة: فتح القائمة
        btnMenu.setOnClickListener {

            drawerLayout.openDrawer(
                GravityCompat.START
            )
        }

        // ✅ ضغطة طويلة: تسجيل خروج
        btnMenu.setOnLongClickListener {

            showLogoutDialog()

            true
        }

        controllerConnect
            .setOnClickListener {

                connectController()
            }

        // ========================================================
        // Device management
        // ========================================================

        btnDeviceSettings
            .setOnClickListener {

                showDeviceSettingsDialog()
            }

        btnOpenWhatsApp
            .setOnClickListener {

                sendCommand(
                    "open_whatsapp"
                )
            }

        // ========================================================
        // Screen
        // ========================================================

        findViewById<Button>(
            R.id.btnStartScreen
        ).setOnClickListener {

            sendCommand(
                "start_screen"
            )
        }

        findViewById<Button>(
            R.id.btnStopScreen
        ).setOnClickListener {

            sendCommand(
                "stop_screen"
            )
        }

        // ========================================================
        // Navigation
        // ========================================================

        findViewById<Button>(
            R.id.btnBack
        ).setOnClickListener {

            sendNav(
                "back"
            )
        }

        findViewById<Button>(
            R.id.btnHome
        ).setOnClickListener {

            sendNav(
                "home"
            )
        }

        findViewById<Button>(
            R.id.btnRecents
        ).setOnClickListener {

            sendNav(
                "recents"
            )
        }

        // ========================================================
        // Camera
        // ========================================================

        findViewById<Button>(
            R.id.btnCameraStartBack
        ).setOnClickListener {

            sendCommand(
                "camera_start_back"
            )
        }

        findViewById<Button>(
            R.id.btnCameraStartFront
        ).setOnClickListener {

            sendCommand(
                "camera_start_front"
            )
        }

        findViewById<Button>(
            R.id.btnCameraStop
        ).setOnClickListener {

            sendCommand(
                "camera_stop"
            )
        }

        findViewById<Button>(
            R.id.btnCameraSwitch
        ).setOnClickListener {

            sendCommand(
                "camera_switch"
            )
        }

        // ========================================================
        // Mic
        // ========================================================

        findViewById<Button>(
            R.id.btnMicStart
        ).setOnClickListener {

            sendCommand(
                "mic_start"
            )
        }

        findViewById<Button>(
            R.id.btnMicStop
        ).setOnClickListener {

            sendCommand(
                "mic_stop"
            )
        }

        // ========================================================
        // Info
        // ========================================================

        findViewById<Button>(
            R.id.btnDeviceInfo
        ).setOnClickListener {

            sendCommand(
                "device_info"
            )
        }

        findViewById<Button>(
            R.id.btnBattery
        ).setOnClickListener {

            sendCommand(
                "get_battery"
            )
        }

        findViewById<Button>(
            R.id.btnNetwork
        ).setOnClickListener {

            sendCommand(
                "get_network"
            )
        }

        // ========================================================
        // Apps
        // ========================================================

        findViewById<Button>(
            R.id.btnApps
        ).setOnClickListener {

            sendCommand(
                "get_apps"
            )
        }

        // ========================================================
        // Calls
        // ========================================================

        findViewById<Button>(
            R.id.btnCalls
        ).setOnClickListener {

            sendCommand(
                "get_calls"
            )
        }

        findViewById<Button>(
            R.id.btnDial
        ).setOnClickListener {

            showNumberDialog(
                title = "فتح تطبيق الاتصال",
                hint = "رقم الهاتف"
            ) { number ->

                sendCommand(
                    "open_dialer",
                    JSONObject()
                        .put(
                            "phone",
                            number
                        )
                )
            }
        }

        // ========================================================
        // SMS
        // ========================================================

        findViewById<Button>(
            R.id.btnSms
        ).setOnClickListener {

            sendCommand(
                "get_sms"
            )
        }

        findViewById<Button>(
            R.id.btnSendSms
        ).setOnClickListener {

            showSmsDialog()
        }

        // ========================================================
        // Files
        // ========================================================

        findViewById<Button>(
            R.id.btnFiles
        ).setOnClickListener {

            browseFiles(
                "/storage/emulated/0/Download"
            )
        }

        // ========================================================
        // Browser
        // ========================================================

        findViewById<Button>(
            R.id.btnOpenUrl
        ).setOnClickListener {

            val url =
                findViewById<EditText>(
                    R.id.browserUrl
                )
                    .text
                    .toString()
                    .trim()

            if (
                url.isBlank()
            ) {

                showToast(
                    "اكتب الرابط أولاً"
                )

            } else {

                sendCommand(
                    "open_url",
                    JSONObject()
                        .put(
                            "url",
                            url
                        )
                )
            }
        }

        // ========================================================
        // Notifications
        // ========================================================

        findViewById<Button>(
            R.id.btnNotifications
        ).setOnClickListener {

            showToast(
                "الإشعارات ستظهر هنا عند استلامها"
            )
        }

        // ========================================================
        // Security
        // ========================================================

        findViewById<Button>(
            R.id.btnLockDevice
        ).setOnClickListener {

            sendCommand(
                "lock_device"
            )
        }

        findViewById<Button>(
            R.id.btnAlarm
        ).setOnClickListener {

            sendCommand(
                "trigger_alarm"
            )
        }
    }

    // ============================================================
    // تسجيل الخروج
    // ============================================================

    private fun showLogoutDialog() {

        android.app.AlertDialog.Builder(
            this
        )
            .setTitle(
                "🚪 تسجيل الخروج"
            )
            .setMessage(
                "هل تريد تسجيل الخروج من الحساب الحالي؟"
            )
            .setNegativeButton(
                "إلغاء",
                null
            )
            .setPositiveButton(
                "خروج"
            ) { _, _ ->

                logout()
            }
            .show()
    }

    private fun logout() {

        RemoteConnection.stop()

        selectedDeviceId =
            null

        autoOpenDeviceId =
            null

        startActivity(
            Intent(
                this,
                LoginActivity::class.java
            )
        )

        finish()
    }

    // ============================================================
    // الاتصال
    // ============================================================

    private fun connectController() {

        val url =
            controllerServerUrl
                .text
                .toString()
                .trim()

        val code =
            controllerPairCode
                .text
                .toString()
                .trim()

        if (
            url.isBlank() ||
            code.isBlank()
        ) {

            showToast(
                "أدخل رابط السيرفر وكود الاقتران"
            )

            return
        }

        setDeviceSettingsVisible(
            false
        )

        lastDeviceList =
            JSONArray()

        renderDeviceList(
            lastDeviceList
        )

        ControllerPreferences.save(
            this,
            url,
            code
        )

        RemoteConnection.startController(
            this,
            url,
            code,

            onBinary = { data ->

                handleBinary(
                    data
                )
            },

            onText = { text ->

                handleText(
                    text
                )
            }
        )

        updateStatus(
            "جارٍ الاتصال...",
            0xFFFF9800.toInt()
        )
    }

    private fun requestDeviceList() {

        RemoteConnection.sendJson(
            JSONObject()
                .put(
                    "type",
                    "get_devices"
                )
        )
    }

    private fun loadSavedSettings() {

        controllerServerUrl.setText(
            ControllerPreferences.url(
                this
            )
        )

        val savedCode =
            ControllerPreferences.code(
                this
            )

        controllerPairCode.setText(
            savedCode.ifBlank {
                "KO2026"
            }
        )

        if (
            controllerServerUrl
                .text
                .toString()
                .isNotBlank() &&
            controllerPairCode
                .text
                .toString()
                .isNotBlank()
        ) {

            window.decorView.postDelayed(
                {

                    connectController()

                },
                250
            )
        }
    }

    // ============================================================
    // قائمة الأجهزة
    // ============================================================

    private fun ensureDeviceListSection() {

        renderDeviceList(
            lastDeviceList
        )
    }

    private fun renderDeviceList(
        devices: JSONArray?
    ) {

        val container =
            findViewById<LinearLayout>(
                R.id.deviceListContainer
            )
                ?: return

        container.removeAllViews()

        if (
            devices == null ||
            devices.length() == 0
        ) {

            val emptyText =
                TextView(
                    this
                ).apply {

                    text =
                        "لا توجد أجهزة"

                    setTextColor(
                        0xFFAAAAAA.toInt()
                    )

                    textSize =
                        14f

                    gravity =
                        Gravity.CENTER

                    setPadding(
                        12,
                        12,
                        12,
                        12
                    )

                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                }

            container.addView(
                emptyText
            )

            return
        }

        // ✅ بعد ما نعرض القائمة، لو في جهاز مطلوب فتحه تلقائيًا
        var autoOpenFound =
            false

        for (
            i in 0 until devices.length()
        ) {

            val device =
                devices.optJSONObject(
                    i
                )
                    ?: continue

            val deviceId =
                device.optString(
                    "deviceId"
                )

            if (
                deviceId.isBlank()
            ) {

                continue
            }

            val serverName =
                device.optString(
                    "deviceName",
                    "جهاز Android"
                )

            // ✅ الاسم المحلي أولاً
            val deviceName =
                DevicePreferences.getName(
                    this,
                    deviceId,
                    serverName
                )

            val online =
                device.optBoolean(
                    "online",
                    false
                )

            // ✅ تحقق: هل الجهاز ده هو المطلوب فتحه تلقائيًا؟
            if (
                autoOpenDeviceId != null &&
                autoOpenDeviceId == deviceId &&
                online
            ) {

                autoOpenFound =
                    true

                // سجّل الفتح مرة واحدة بس
                if (
                    selectedDeviceId != deviceId
                ) {

                    selectedDeviceId =
                        deviceId
                }
            }

            // ====================================================
            // صف الجهاز
            // ====================================================

            val isSelected =
                selectedDeviceId == deviceId

            val row =
                LinearLayout(
                    this
                ).apply {

                    orientation =
                        LinearLayout.HORIZONTAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    setPadding(
                        6,
                        6,
                        4,
                        6
                    )

                    setBackgroundResource(
                        android.R.drawable.list_selector_background
                    )

                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {

                            bottomMargin =
                                4
                        }
                }

            // ====================================================
            // الاسم + الحالة
            // ====================================================

            val infoLayout =
                LinearLayout(
                    this
                ).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                }

            val nameText =
                TextView(
                    this
                ).apply {

                    text =
                        if (isSelected)
                            "✅ $deviceName"
                        else
                            deviceName

                    setTextColor(
                        Color.WHITE
                    )

                    textSize =
                        16f

                    setTypeface(
                        typeface,
                        android.graphics.Typeface.BOLD
                    )
                }

            val statusText =
                TextView(
                    this
                ).apply {

                    text =
                        when {

                            !online ->
                                "● غير متصل"

                            isSelected ->
                                "● متصل - جاهز للتحكم"

                            else ->
                                "● اضغط للدخول"
                        }

                    textSize =
                        12f

                    setTextColor(
                        when {

                            !online ->
                                Color.GRAY

                            isSelected ->
                                0xFF4CAF50.toInt()

                            else ->
                                0xFF2196F3.toInt()
                        }
                    )
                }

            infoLayout.addView(
                nameText
            )

            infoLayout.addView(
                statusText
            )

            // ====================================================
            // زر الإعدادات ⚙ (تغيير الاسم فقط)
            // ====================================================

            val settingsButton =
                Button(
                    this
                ).apply {

                    text =
                        "⚙"

                    textSize =
                        18f

                    setTextColor(
                        Color.WHITE
                    )

                    isEnabled =
                        online

                    layoutParams =
                        LinearLayout.LayoutParams(
                            52,
                            48
                        )

                    setOnClickListener {

                        if (
                            !online
                        ) {

                            showToast(
                                "الجهاز غير متصل"
                            )

                            return@setOnClickListener
                        }

                        // ✅ تغيير الاسم فقط
                        showRenameDeviceDialog(
                            deviceId,
                            deviceName
                        )
                    }
                }

            // ====================================================
            // إضافة محتويات الصف
            // ====================================================

            row.addView(
                infoLayout
            )

            row.addView(
                settingsButton
            )

            // ====================================================
            // الضغط على الجهاز = فتح مباشر (بدون باسورد)
            // ====================================================

            row.setOnClickListener {

                if (
                    !online
                ) {

                    showToast(
                        "الجهاز غير متصل"
                    )

                    return@setOnClickListener
                }

                // ✅ فتح مباشر بدون باسورد
                selectedDeviceId =
                    deviceId

                autoOpenDeviceId =
                    null

                setDeviceSettingsVisible(
                    true
                )

                drawerLayout.closeDrawer(
                    GravityCompat.START
                )

                updateStatus(
                    "✅ تم فتح $deviceName",
                    0xFF4CAF50.toInt()
                )

                // أعد رسم القائمة عشان تظهر علامة ✅
                renderDeviceList(
                    lastDeviceList
                )
            }

            container.addView(
                row
            )
        }

        // ========================================================
        // لو الجهاز المطلوب فتحه تلقائيًا اتلغى أو مش موجود
        // ========================================================

        if (
            autoOpenDeviceId != null &&
            !autoOpenFound
        ) {

            // امسح الفتح التلقائي بعد المحاولة الأولى
            autoOpenDeviceId =
                null
        }
    }

    // ============================================================
    // SHA-256
    // ============================================================

    private fun sha256(
        value: String
    ): String {

        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        val hash =
            digest.digest(
                value.toByteArray(
                    Charsets.UTF_8
                )
            )

        return hash.joinToString("") {
            "%02x".format(it)
        }
    }

    // ============================================================
    // تغيير اسم الجهاز فقط
    // ============================================================

    private fun showRenameDeviceDialog(
        deviceId: String,
        currentName: String
    ) {

        val input =
            EditText(
                this
            ).apply {

                hint =
                    "اسم الجهاز"

                setSingleLine(
                    true
                )

                setText(
                    currentName
                )

                selectAll()
            }

        android.app.AlertDialog.Builder(
            this
        )
            .setTitle(
                "✏️ تغيير اسم الجهاز"
            )
            .setMessage(
                "الاسم ده بيتحفظ على هاتفك فقط."
            )
            .setView(
                input
            )
            .setNegativeButton(
                "إلغاء",
                null
            )
            .setPositiveButton(
                "حفظ"
            ) { _, _ ->

                val newName =
                    input.text
                        .toString()
                        .trim()

                if (
                    newName.isBlank()
                ) {

                    showToast(
                        "اكتب اسم للجهاز"
                    )

                    return@setPositiveButton
                }

                // ✅ احفظ الاسم محليًا
                DevicePreferences.saveName(
                    this,
                    deviceId,
                    newName
                )

                // ✅ حدّث الحساب المرتبط بالجهاز ده
                AccountStore.getAll(
                    this
                ).forEach { account ->

                    if (
                        account.deviceId ==
                        deviceId
                    ) {

                        AccountStore.removeAccount(
                            this,
                            account.name
                        )

                        AccountStore.addAccount(
                            this,
                            newName,
                            account.passwordHash,
                            deviceId
                        )
                    }
                }

                showToast(
                    "✅ تم تغيير الاسم إلى: $newName"
                )

                renderDeviceList(
                    lastDeviceList
                )
            }
            .show()
    }

    // ============================================================
    // إعدادات الجهاز (زر في القسم الرئيسي)
    // ============================================================

    private fun showDeviceSettingsDialog() {

        val deviceId =
            selectedDeviceId
                ?: run {

                    showToast(
                        "افتح جهازًا أولًا"
                    )

                    return
                }

        val currentName =
            getDeviceName(
                deviceId
            )

        showRenameDeviceDialog(
            deviceId,
            currentName
        )
    }

    // ============================================================
    // Navigation
    // ============================================================

    private fun setupNavigation() {

        val navTargets =
            mapOf(

                R.id.navConnection
                    to R.id.controllerServerUrl,

                R.id.navScreen
                    to R.id.sectionScreen,

                R.id.navCamera
                    to R.id.sectionCamera,

                R.id.navInfo
                    to R.id.sectionInfo,

                R.id.navApps
                    to R.id.sectionApps,

                R.id.navCalls
                    to R.id.sectionCalls,

                R.id.navSms
                    to R.id.sectionSms,

                R.id.navFiles
                    to R.id.sectionFiles,

                R.id.navBrowser
                    to R.id.sectionBrowser,

                R.id.navNotifications
                    to R.id.sectionNotifications,

                R.id.navSecurity
                    to R.id.sectionSecurity
            )

        navTargets.forEach {
                (navId, targetId) ->

            findViewById<TextView>(
                navId
            ).setOnClickListener {

                drawerLayout.closeDrawer(
                    GravityCompat.START
                )

                scrollContent.post {

                    val target =
                        findViewById<View>(
                            targetId
                        )

                    scrollContent.smoothScrollTo(
                        0,
                        target.top
                    )
                }
            }
        }
    }

    // ============================================================
    // Screen control
    // ============================================================

    private fun setupScreenControl() {

        remoteScreen.setOnTouchListener {
                _,
                event ->

            when (
                event.action
            ) {

                MotionEvent.ACTION_DOWN -> {

                    downX =
                        event.x

                    downY =
                        event.y

                    downTime =
                        System.currentTimeMillis()

                    true
                }

                MotionEvent.ACTION_UP -> {

                    if (
                        selectedDeviceId
                            .isNullOrBlank()
                    ) {

                        showToast(
                            "افتح جهازًا أولًا"
                        )

                        return@setOnTouchListener true
                    }

                    val upX =
                        event.x

                    val upY =
                        event.y

                    val dx =
                        upX - downX

                    val dy =
                        upY - downY

                    val distance =
                        sqrt(
                            dx * dx +
                                dy * dy
                        )

                    if (
                        remoteScreen.width <= 0 ||
                        remoteScreen.height <= 0
                    ) {

                        return@setOnTouchListener true
                    }

                    val nx1 =
                        (
                            downX /
                                remoteScreen.width
                                    .toFloat()
                            )
                                .coerceIn(
                                    0f,
                                    1f
                                )

                    val ny1 =
                        (
                            downY /
                                remoteScreen.height
                                    .toFloat()
                            )
                                .coerceIn(
                                    0f,
                                    1f
                                )

                    val nx2 =
                        (
                            upX /
                                remoteScreen.width
                                    .toFloat()
                            )
                                .coerceIn(
                                    0f,
                                    1f
                                )

                    val ny2 =
                        (
                            upY /
                                remoteScreen.height
                                    .toFloat()
                            )
                                .coerceIn(
                                    0f,
                                    1f
                                )

                    if (
                        distance < 25f
                    ) {

                        sendCommand(
                            "tap",
                            JSONObject()
                                .put(
                                    "x",
                                    nx1
                                )
                                .put(
                                    "y",
                                    ny1
                                )
                        )

                    } else {

                        val duration =
                            (
                                System.currentTimeMillis() -
                                    downTime
                                )
                                .coerceIn(
                                    100L,
                                    1500L
                                )

                        sendCommand(
                            "swipe",
                            JSONObject()
                                .put(
                                    "x1",
                                    nx1
                                )
                                .put(
                                    "y1",
                                    ny1
                                )
                                .put(
                                    "x2",
                                    nx2
                                )
                                .put(
                                    "y2",
                                    ny2
                                )
                                .put(
                                    "duration",
                                    duration
                                )
                        )
                    }

                    true
                }

                else -> true
            }
        }
    }

    // ============================================================
    // Commands
    // ============================================================

    private fun sendCommand(
        type: String
    ) {

        sendCommand(
            type,
            JSONObject()
        )
    }

    private fun sendCommand(
        type: String,
        payload: JSONObject
    ): Boolean {

        val deviceId =
            selectedDeviceId

        if (
            deviceId.isNullOrBlank()
        ) {

            showToast(
                "افتح جهازًا أولًا"
            )

            return false
        }

        val message =
            JSONObject(
                payload.toString()
            )
                .put(
                    "type",
                    type
                )
                .put(
                    "deviceId",
                    deviceId
                )

        if (
            !RemoteConnection.sendJson(
                message
            )
        ) {

            showToast(
                "غير متصل بالسيرفر"
            )

            return false
        }

        return true
    }

    private fun sendNav(
        action: String
    ) {

        sendCommand(
            "nav",
            JSONObject()
                .put(
                    "action",
                    action
                )
        )
    }

    // ============================================================
    // Binary
    // ============================================================

    private fun handleBinary(
        data: ByteArray
    ) {

        if (
            data.isEmpty()
        ) {

            return
        }

        val type =
            data[0].toInt() and 0xFF

        when (type) {

            0x48 -> {

                enqueueCameraH264(
                    data
                )
            }

            0x41 -> {

                val payload =
                    data.copyOfRange(
                        1,
                        data.size
                    )

                runOnUiThread {

                    playAudio(
                        payload
                    )
                }
            }

            0x53,
            0x43 -> {

                if (
                    data.size < 2
                ) {

                    return
                }

                val payload =
                    data.copyOfRange(
                        1,
                        data.size
                    )

                runOnUiThread {

                    showJpeg(
                        payload
                    )
                }
            }
        }
    }

    private fun showJpeg(
        data: ByteArray
    ) {

        val bitmap =
            BitmapFactory.decodeByteArray(
                data,
                0,
                data.size
            )

        if (
            bitmap != null
        ) {

            cameraVideoView.visibility =
                View.GONE

            remoteScreen.visibility =
                View.VISIBLE

            remoteScreen.setImageBitmap(
                bitmap
            )
        }
    }

    // ============================================================
    // Camera decoder
    // ============================================================

    private fun setupCameraVideoDecoder() {

        cameraDecoderThread =
            android.os.HandlerThread(
                "RemoteCameraDecoder"
            ).also {
                it.start()
            }

        cameraDecoderHandler =
            android.os.Handler(
                cameraDecoderThread!!
                    .looper
            )

        cameraVideoView
            .surfaceTextureListener =
            object :
                TextureView.SurfaceTextureListener {

                override fun
                    onSurfaceTextureAvailable(
                        surface:
                        android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {

                    pendingCameraVideoConfig
                        ?.let {

                            startCameraDecoder(
                                it
                            )
                        }
                }

                override fun
                    onSurfaceTextureSizeChanged(
                        surface:
                        android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int
                    ) = Unit

                override fun
                    onSurfaceTextureDestroyed(
                        surface:
                        android.graphics.SurfaceTexture
                    ): Boolean {

                    releaseCameraDecoder()

                    return true
                }

                override fun
                    onSurfaceTextureUpdated(
                        surface:
                        android.graphics.SurfaceTexture
                    ) = Unit
            }
    }

    private fun startCameraDecoder(
        config: JSONObject
    ) {

        val texture =
            cameraVideoView
                .surfaceTexture
                ?: return

        val csd0 =
            config.optString(
                "csd0"
            )
                .takeIf {
                    it.isNotBlank()
                }
                ?: return

        val csd1 =
            config.optString(
                "csd1"
            )
                .takeIf {
                    it.isNotBlank()
                }

        val width =
            config.optInt(
                "width",
                1280
            )

        val height =
            config.optInt(
                "height",
                720
            )

        pendingCameraVideoConfig =
            config

        val surface =
            Surface(
                texture
            )

        val bytes0 =
            Base64.decode(
                csd0,
                Base64.NO_WRAP
            )

        val bytes1 =
            csd1?.let {

                Base64.decode(
                    it,
                    Base64.NO_WRAP
                )
            }

        cameraDecoderHandler?.post {

            try {
                cameraDecoder?.stop()
            } catch (_: Exception) {
            }

            try {
                cameraDecoder?.release()
            } catch (_: Exception) {
            }

            cameraDecoder =
                null

            try {

                val format =
                    MediaFormat
                        .createVideoFormat(
                            "video/avc",
                            width,
                            height
                        )
                        .apply {

                            setByteBuffer(
                                "csd-0",
                                ByteBuffer.wrap(
                                    bytes0
                                )
                            )

                            if (
                                bytes1 != null
                            ) {

                                setByteBuffer(
                                    "csd-1",
                                    ByteBuffer.wrap(
                                        bytes1
                                    )
                                )
                            }

                            if (
                                android.os.Build.VERSION.SDK_INT >=
                                30
                            ) {

                                setInteger(
                                    MediaFormat.KEY_LOW_LATENCY,
                                    1
                                )
                            }
                        }

                val decoder =
                    MediaCodec
                        .createDecoderByType(
                            "video/avc"
                        )

                decoder.configure(
                    format,
                    surface,
                    null,
                    0
                )

                decoder.start()

                cameraDecoderSurface =
                    surface

                cameraDecoder =
                    decoder

                runOnUiThread {

                    applyCameraTransform(
                        config,
                        width,
                        height
                    )

                    remoteScreen.visibility =
                        View.GONE

                    cameraVideoView.visibility =
                        View.VISIBLE
                }

            } catch (e: Exception) {

                surface.release()

                cameraDecoderSurface =
                    null

                ActivityLogger.log(
                    "Camera decoder error: ${e.message}"
                )

                runOnUiThread {

                    showToast(
                        "تعذر تشغيل عرض فيديو الكاميرا: ${e.message}"
                    )
                }
            }
        }
    }

    private fun applyCameraTransform(
        config: JSONObject,
        sourceWidth: Int,
        sourceHeight: Int
    ) {

        val viewWidth =
            cameraVideoView.width

        val viewHeight =
            cameraVideoView.height

        if (
            viewWidth <= 0 ||
            viewHeight <= 0
        ) {

            return
        }

        val rotation =
            (
                (
                    config.optInt(
                        "rotation",
                        0
                    ) % 360
                ) + 360
                ) % 360

        val mirror =
            config.optBoolean(
                "mirror",
                false
            )

        val rotatedWidth =
            if (
                rotation % 180 == 0
            ) {

                sourceWidth

            } else {

                sourceHeight
            }

        val rotatedHeight =
            if (
                rotation % 180 == 0
            ) {

                sourceHeight

            } else {

                sourceWidth
            }

        val scale =
            minOf(
                viewWidth.toFloat() /
                    rotatedWidth.toFloat(),

                viewHeight.toFloat() /
                    rotatedHeight.toFloat()
            )

        val matrix =
            Matrix().apply {

                setTranslate(
                    -sourceWidth / 2f,
                    -sourceHeight / 2f
                )

                postRotate(
                    rotation.toFloat()
                )

                if (
                    mirror
                ) {

                    postScale(
                        -1f,
                        1f
                    )
                }

                postScale(
                    scale,
                    scale
                )

                postTranslate(
                    viewWidth / 2f,
                    viewHeight / 2f
                )
            }

        cameraVideoView.setTransform(
            matrix
        )
    }

    private fun resetCameraTransform() {

        cameraVideoView.setTransform(
            Matrix()
        )
    }

    private fun enqueueCameraH264(
        data: ByteArray
    ) {

        if (
            data.size < 14
        ) {

            return
        }

        val flags =
            ByteBuffer
                .wrap(
                    data,
                    1,
                    4
                )
                .int

        val pts =
            ByteBuffer
                .wrap(
                    data,
                    5,
                    8
                )
                .long

        val payload =
            data.copyOfRange(
                13,
                data.size
            )

        cameraDecoderHandler?.post {

            val decoder =
                cameraDecoder
                    ?: return@post

            try {

                val inputIndex =
                    decoder.dequeueInputBuffer(
                        5000
                    )

                if (
                    inputIndex >= 0
                ) {

                    val input =
                        decoder.getInputBuffer(
                            inputIndex
                        )
                            ?: return@post

                    input.clear()

                    input.put(
                        payload
                    )

                    decoder.queueInputBuffer(
                        inputIndex,
                        0,
                        payload.size,
                        pts,
                        flags and
                            MediaCodec
                                .BUFFER_FLAG_END_OF_STREAM
                    )
                }

                val info =
                    MediaCodec.BufferInfo()

                while (true) {

                    val outputIndex =
                        decoder.dequeueOutputBuffer(
                            info,
                            0
                        )

                    if (
                        outputIndex < 0
                    ) {

                        break
                    }

                    decoder.releaseOutputBuffer(
                        outputIndex,
                        true
                    )
                }

            } catch (e: Exception) {

                ActivityLogger.log(
                    "Camera decode error: ${e.message}"
                )
            }
        }
    }

    private fun releaseCameraDecoder() {

        cameraDecoderHandler?.post {

            try {
                cameraDecoder?.stop()
            } catch (_: Exception) {
            }

            try {
                cameraDecoder?.release()
            } catch (_: Exception) {
            }

            cameraDecoder =
                null

            try {
                cameraDecoderSurface?.release()
            } catch (_: Exception) {
            }

            cameraDecoderSurface =
                null
        }

        runOnUiThread {

            resetCameraTransform()

            cameraVideoView.visibility =
                View.GONE
        }
    }

    // ============================================================
    // Text
    // ============================================================

    private fun handleText(
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

            runOnUiThread {

                when (type) {

                    "hello" -> {

                        updateStatus(
                            "● متصل بالسيرفر",
                            0xFF4CAF50.toInt()
                        )

                        requestDeviceList()
                    }

                    "connection_status" -> {

                        val connected =
                            msg.optBoolean(
                                "connected",
                                false
                            )

                        updateStatus(
                            if (connected)
                                "● متصل"
                            else
                                "● غير متصل",

                            if (connected)
                                0xFF4CAF50.toInt()
                            else
                                0xFFFF5252.toInt()
                        )

                        if (connected) {

                            requestDeviceList()
                        }
                    }

                    "device_list" -> {

                        val devices =
                            msg.optJSONArray(
                                "devices"
                            ) ?: JSONArray()

                        lastDeviceList =
                            devices

                        renderDeviceList(
                            devices
                        )

                        // ✅ تحديث الحالة
                        if (
                            selectedDeviceId != null
                        ) {

                            val name =
                                getDeviceName(
                                    selectedDeviceId
                                )

                            updateStatus(
                                "✅ متصل بـ $name",
                                0xFF4CAF50.toInt()
                            )

                            setDeviceSettingsVisible(
                                true
                            )

                        } else if (
                            devices.length() > 0
                        ) {

                            val count =
                                devices.length()

                            updateStatus(
                                "● متصل\n$count جهاز متاح",
                                0xFF4CAF50.toInt()
                            )

                        } else {

                            updateStatus(
                                "● متصل\nلا توجد أجهزة متصلة",
                                0xFFFF9800.toInt()
                            )
                        }
                    }

                    "whatsapp_opened" -> {

                        showToast(
                            "✅ تم فتح WhatsApp على الجهاز"
                        )
                    }

                    "whatsapp_error" -> {

                        showToast(
                            "❌ ${
                                msg.optString(
                                    "message",
                                    "تعذر فتح WhatsApp"
                                )
                            }"
                        )
                    }

                    "camera_video_config" -> {

                        pendingCameraVideoConfig =
                            msg

                        cameraVideoView.visibility =
                            View.VISIBLE

                        remoteScreen.visibility =
                            View.GONE

                        if (
                            cameraVideoView.isAvailable
                        ) {

                            startCameraDecoder(
                                msg
                            )
                        }
                    }

                    "camera_video_stop" -> {

                        pendingCameraVideoConfig =
                            null

                        releaseCameraDecoder()

                        remoteScreen.visibility =
                            View.VISIBLE
                    }

                    "screen_frame",
                    "camera_frame" -> {

                        val bytes =
                            Base64.decode(
                                msg.optString(
                                    "data"
                                ),
                                Base64.NO_WRAP
                            )

                        showJpeg(
                            bytes
                        )
                    }

                    "audio_chunk" -> {

                        playAudio(
                            Base64.decode(
                                msg.optString(
                                    "data"
                                ),
                                Base64.NO_WRAP
                            )
                        )
                    }

                    "calls_list" -> {

                        showCalls(
                            msg
                        )
                    }

                    "sms_list" -> {

                        showSms(
                            msg
                        )
                    }

                    "calls_error",
                    "sms_error",
                    "sms_send_error",
                    "dial_error",
                    "url_error",
                    "file_error" -> {

                        showToast(
                            msg.optString(
                                "message",
                                "حدث خطأ"
                            )
                        )
                    }

                    "sms_sent" -> {

                        showToast(
                            "تم إرسال طلب الرسالة إلى ${
                                msg.optString(
                                    "to"
                                )
                            }"
                        )
                    }

                    "dial_ready" -> {

                        showToast(
                            "تم فتح شاشة الاتصال للرقم ${
                                msg.optString(
                                    "number"
                                )
                            }"
                        )
                    }

                    "url_opened" -> {

                        showToast(
                            "تم فتح الرابط على الهاتف"
                        )
                    }

                    "file_list" -> {

                        showFileList(
                            msg
                        )
                    }

                    "file_complete" -> {

                        finishIncomingFile(
                            msg.optString(
                                "name"
                            )
                        )
                    }

                    "file_chunk" -> {

                        appendIncomingFile(
                            msg.optString(
                                "name"
                            ),
                            msg.optString(
                                "data"
                            )
                        )
                    }

                    "device_info",
                    "battery_info",
                    "network_info",
                    "location_data",
                    "apps_list",
                    "restricted_apps",
                    "recording_started",
                    "recording_stopped",
                    "recording_error" -> {

                        android.app.AlertDialog
                            .Builder(
                                this
                            )
                            .setTitle(
                                type
                            )
                            .setMessage(
                                msg.toString(2)
                            )
                            .setPositiveButton(
                                "حسنًا",
                                null
                            )
                            .show()
                    }

                    "notification_intercepted" -> {

                        showToast(
                            "🔔 ${
                                msg.optString(
                                    "title"
                                )
                            }: ${
                                msg.optString(
                                    "text"
                                )
                            }"
                        )
                    }
                }
            }

        } catch (e: Exception) {

            ActivityLogger.log(
                "Controller message error: ${e.message}"
            )
        }
    }

    // ============================================================
    // Number dialog
    // ============================================================

    private fun showNumberDialog(
        title: String,
        hint: String,
        onSubmit: (String) -> Unit
    ) {

        val input =
            EditText(
                this
            )

        input.hint =
            hint

        input.inputType =
            android.text.InputType.TYPE_CLASS_PHONE

        android.app.AlertDialog
            .Builder(this)
            .setTitle(
                title
            )
            .setView(
                input
            )
            .setNegativeButton(
                "إلغاء",
                null
            )
            .setPositiveButton(
                "متابعة"
            ) { _, _ ->

                val value =
                    input.text
                        .toString()
                        .trim()

                if (
                    value.isNotBlank()
                ) {

                    onSubmit(
                        value
                    )
                }
            }
            .show()
    }

    // ============================================================
    // SMS dialog
    // ============================================================

    private fun showSmsDialog() {

        val container =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    40,
                    0,
                    40,
                    0
                )
            }

        val phone =
            EditText(
                this
            ).apply {

                hint =
                    "رقم الهاتف"

                inputType =
                    android.text.InputType
                        .TYPE_CLASS_PHONE
            }

        val body =
            EditText(
                this
            ).apply {

                hint =
                    "نص الرسالة"

                inputType =
                    android.text.InputType
                        .TYPE_CLASS_TEXT or
                        android.text.InputType
                            .TYPE_TEXT_FLAG_MULTI_LINE

                minLines =
                    4
            }

        container.addView(
            phone,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        android.app.AlertDialog
            .Builder(this)
            .setTitle(
                "إرسال رسالة SMS"
            )
            .setView(
                container
            )
            .setNegativeButton(
                "إلغاء",
                null
            )
            .setPositiveButton(
                "إرسال"
            ) { _, _ ->

                val number =
                    phone.text
                        .toString()
                        .trim()

                val message =
                    body.text
                        .toString()
                        .trim()

                if (
                    number.isBlank() ||
                    message.isBlank()
                ) {

                    showToast(
                        "أدخل الرقم ونص الرسالة"
                    )

                } else {

                    sendCommand(
                        "send_sms",
                        JSONObject()
                            .put(
                                "phone",
                                number
                            )
                            .put(
                                "message",
                                message
                            )
                    )
                }
            }
            .show()
    }

    // ============================================================
    // Calls
    // ============================================================

    private fun showCalls(
        msg: JSONObject
    ) {

        val calls =
            msg.optJSONArray(
                "calls"
            )
                ?: return

        if (
            calls.length() == 0
        ) {

            showToast(
                "لا يوجد سجل مكالمات"
            )

            return
        }

        val lines =
            Array(
                calls.length()
            ) { index ->

                val item =
                    calls.optJSONObject(
                        index
                    )
                        ?: JSONObject()

                val name =
                    item.optString(
                        "name"
                    ).ifBlank {

                        item.optString(
                            "number"
                        )
                    }

                val type =
                    item.optString(
                        "type"
                    )

                val duration =
                    item.optLong(
                        "duration"
                    )

                "$name\n$type • ${duration}ث"
            }

        android.app.AlertDialog
            .Builder(this)
            .setTitle(
                "سجل المكالمات"
            )
            .setItems(
                lines,
                null
            )
            .setPositiveButton(
                "إغلاق",
                null
            )
            .show()
    }

    // ============================================================
    // SMS
    // ============================================================

    private fun showSms(
        msg: JSONObject
    ) {

        val messages =
            msg.optJSONArray(
                "messages"
            )
                ?: return

        if (
            messages.length() == 0
        ) {

            showToast(
                "لا توجد رسائل"
            )

            return
        }

        val lines =
            Array(
                messages.length()
            ) { index ->

                val item =
                    messages.optJSONObject(
                        index
                    )
                        ?: JSONObject()

                "${item.optString("address")}\n${
                    item.optString("body")
                }"
            }

        android.app.AlertDialog
            .Builder(this)
            .setTitle(
                "الرسائل"
            )
            .setItems(
                lines,
                null
            )
            .setPositiveButton(
                "إغلاق",
                null
            )
            .show()
    }

    // ============================================================
    // Files
    // ============================================================

    private fun browseFiles(
        path: String
    ) {

        sendCommand(
            "list_files",
            JSONObject()
                .put(
                    "path",
                    path
                )
        )
    }

    private fun showFileList(
        msg: JSONObject
    ) {

        val path =
            msg.optString(
                "path"
            )

        val files =
            msg.optJSONArray(
                "files"
            )
                ?: return

        val names =
            mutableListOf<String>()

        val actions =
            mutableListOf<JSONObject>()

        if (
            path != "/storage/emulated/0" &&
            path != "/"
        ) {

            names +=
                "⬆ ../"

            actions +=
                JSONObject()
                    .put(
                        "directory",
                        true
                    )
                    .put(
                        "path",
                        File(path)
                            .parent
                            ?: "/storage/emulated/0"
                    )
        }

        for (
            i in 0 until files.length()
        ) {

            val item =
                files.optJSONObject(
                    i
                )
                    ?: continue

            val isDir =
                item.optBoolean(
                    "is_directory"
                )

            val name =
                item.optString(
                    "name"
                )

            names +=
                if (
                    isDir
                )
                    "📁 $name"
                else
                    "📄 $name"

            actions +=
                JSONObject(
                    item.toString()
                )
                    .put(
                        "path",
                        File(
                            path,
                            name
                        ).absolutePath
                    )
        }

        if (
            names.isEmpty()
        ) {

            showToast(
                "المجلد فارغ أو لا يمكن الوصول إليه"
            )

            return
        }

        android.app.AlertDialog
            .Builder(this)
            .setTitle(
                "تصفح الملفات\n$path"
            )
            .setItems(
                names.toTypedArray()
            ) { _, which ->

                val item =
                    actions[
                        which
                    ]

                if (
                    item.optBoolean(
                        "directory"
                    ) ||
                    item.optBoolean(
                        "is_directory"
                    )
                ) {

                    browseFiles(
                        item.optString(
                            "path"
                        )
                    )

                } else {

                    requestFileDownload(
                        item
                    )
                }
            }
            .setNegativeButton(
                "إغلاق",
                null
            )
            .show()
    }

    private fun requestFileDownload(
        item: JSONObject
    ) {

        val path =
            item.optString(
                "path"
            )

        if (
            path.isBlank()
        ) {

            return
        }

        android.app.AlertDialog
            .Builder(this)
            .setTitle(
                "تحميل الملف؟"
            )
            .setMessage(
                path
            )
            .setNegativeButton(
                "إلغاء",
                null
            )
            .setPositiveButton(
                "تحميل"
            ) { _, _ ->

                sendCommand(
                    "download_file",
                    JSONObject()
                        .put(
                            "path",
                            path
                        )
                )
            }
            .show()
    }

    private fun appendIncomingFile(
        name: String,
        base64: String
    ) {

        if (
            name.isBlank() ||
            base64.isBlank()
        ) {

            return
        }

        val stream =
            incomingFiles.getOrPut(
                name
            ) {
                ByteArrayOutputStream()
            }

        try {

            stream.write(
                Base64.decode(
                    base64,
                    Base64.NO_WRAP
                )
            )

        } catch (e: Exception) {

            incomingFiles.remove(
                name
            )

            showToast(
                "خطأ أثناء تحميل $name"
            )
        }
    }

    private fun finishIncomingFile(
        name: String
    ) {

        val stream =
            incomingFiles.remove(
                name
            )
                ?: return

        try {

            val dir =
                getExternalFilesDir(
                    "downloads"
                )
                    ?: filesDir

            if (
                !dir.exists()
            ) {

                dir.mkdirs()
            }

            val outFile =
                File(
                    dir,
                    name.ifBlank {
                        "downloaded_file"
                    }
                )

            FileOutputStream(
                outFile
            ).use {

                it.write(
                    stream.toByteArray()
                )
            }

            showToast(
                "تم حفظ الملف: ${outFile.absolutePath}"
            )

        } catch (e: Exception) {

            showToast(
                "تعذر حفظ الملف: ${e.message}"
            )
        }
    }

    // ============================================================
    // Status
    // ============================================================

    private fun updateStatus(
        text: String,
        color: Int
    ) {

        tvStatus.text =
            text

        tvStatus.setTextColor(
            color
        )
    }

    private fun showToast(
        message: String
    ) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ============================================================
    // Audio
    // ============================================================

    private fun playAudio(
        data: ByteArray
    ) {

        if (
            data.isEmpty()
        ) {

            return
        }

        if (
            audioTrack == null
        ) {

            val bufferSize =
                AudioTrack.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            audioTrack =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(
                                16000
                            )
                            .setEncoding(
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            .setChannelMask(
                                AudioFormat.CHANNEL_OUT_MONO
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(
                        maxOf(
                            bufferSize,
                            4096
                        )
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )
                    .build()

            audioTrack?.play()
        }

        try {

            audioTrack?.write(
                data,
                0,
                data.size
            )

        } catch (_: Exception) {
        }
    }

    // ============================================================
    // Destroy
    // ============================================================

    override fun onDestroy() {

        RemoteConnection
            .clearControllerStatusListener()

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        releaseCameraDecoder()

        cameraDecoderThread
            ?.quitSafely()

        cameraDecoderThread =
            null

        cameraDecoderHandler =
            null

        // لا نوقف الاتصال — لو المستخدم رجع للتطبيق بسرعة
        // RemoteConnection.stop()

        super.onDestroy()
    }
}