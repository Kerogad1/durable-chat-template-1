package com.kero.remoteagent

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var url: EditText
    private lateinit var code: EditText

    private val requestCodeScreenRecording = 2001

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_main
        )

        AppContextHolder.init(
            this
        )

        status =
            findViewById(
                R.id.statusText
            )

        url =
            findViewById(
                R.id.serverUrl
            )

        code =
            findViewById(
                R.id.pairCode
            )

        // =====================================================
        // تحميل إعدادات الاتصال
        // =====================================================

        url.setText(
            AgentPreferences.getServerUrl(
                this
            )
        )

        val savedPairCode =
            AgentPreferences.getPairCode(
                this
            )

        code.setText(
            savedPairCode.ifBlank {
                "KO2026"
            }
        )

        // =====================================================
        // حالة الاتصال
        // =====================================================

        RemoteConnection
            .setAgentUiStatusListener { newStatus ->

                runOnUiThread {

                    when {

                        newStatus.contains("متصل") ||
                        newStatus.contains("Connected") -> {

                            status.text =
                                "● متصل"

                            status.setTextColor(
                                0xFF4CAF50.toInt()
                            )
                        }

                        newStatus.contains("جارٍ") ||
                        newStatus.contains("إعادة") -> {

                            status.text =
                                "● جارٍ الاتصال"

                            status.setTextColor(
                                0xFFFF9800.toInt()
                            )
                        }

                        else -> {

                            status.text =
                                "● غير متصل"

                            status.setTextColor(
                                0xFFFF5252.toInt()
                            )
                        }
                    }
                }
            }

        // =====================================================
        // التشغيل التلقائي
        // =====================================================

        if (
            BuildConfig.FLAVOR == "agent"
        ) {

            if (
                AgentPreferences.isConfigured(
                    this
                )
            ) {

                AgentService.start(
                    applicationContext
                )
            }

        } else if (
            BuildConfig.FLAVOR == "controller"
        ) {

            if (
                AgentPreferences.isConfigured(
                    this
                )
            ) {

                connectController()
            }
        }

        // =====================================================
        // Accessibility
        // =====================================================

        findViewById<Button>(
            R.id.accessibility
        ).setOnClickListener {

            startActivity(
                Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                )
            )
        }

        // =====================================================
        // Device Admin
        // =====================================================

        findViewById<Button>(
            R.id.enableDeviceAdmin
        ).setOnClickListener {

            val admin =
                ComponentName(
                    this,
                    RemoteDeviceAdminReceiver::class.java
                )

            startActivity(
                Intent(
                    DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                ).apply {

                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        admin
                    )

                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "يسمح هذا الإعداد بتفعيل قفل الجهاز من لوحة الإدارة."
                    )
                }
            )
        }

        // =====================================================
        // Mic
        // =====================================================

        findViewById<Button>(
            R.id.requestMic
        ).setOnClickListener {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.RECORD_AUDIO
                ),
                11
            )
        }

        // =====================================================
        // Camera
        // =====================================================

        findViewById<Button>(
            R.id.requestCamera
        ).setOnClickListener {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA
                ),
                12
            )
        }

        // =====================================================
        // Location
        // =====================================================

        findViewById<Button>(
            R.id.requestLocation
        ).setOnClickListener {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                13
            )
        }

        // =====================================================
        // Calls
        // =====================================================

        findViewById<Button>(
            R.id.requestCalls
        ).setOnClickListener {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_CALL_LOG
                ),
                14
            )
        }

        // =====================================================
        // SMS
        // =====================================================

        findViewById<Button>(
            R.id.requestSms
        ).setOnClickListener {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS
                ),
                15
            )
        }

        // =====================================================
        // Files
        // =====================================================

        findViewById<Button>(
            R.id.requestFilesAccess
        ).setOnClickListener {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.R
            ) {

                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse(
                            "package:$packageName"
                        )
                    )
                )
            }
        }

        // =====================================================
        // Screen Recording
        // =====================================================

        findViewById<Button>(
            R.id.requestScreenRecord
        ).setOnClickListener {

            val projectionManager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            startActivityForResult(
                projectionManager
                    .createScreenCaptureIntent(),
                requestCodeScreenRecording
            )
        }

        // =====================================================
        // Save / Connect
        // =====================================================

        findViewById<Button>(
            R.id.saveConnect
        ).setOnClickListener {

            val serverUrl =
                url.text
                    .toString()
                    .trim()

            val pairCode =
                code.text
                    .toString()
                    .trim()

            if (
                serverUrl.isBlank() ||
                pairCode.isBlank()
            ) {

                Toast.makeText(
                    this,
                    "يرجى إدخال رابط السيرفر وكود الاقتران",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            // =================================================
            // لا يوجد اسم أو باسورد للجهاز هنا.
            // إدارة الاسم والباسورد أصبحت من Remote.
            // =================================================

            AgentPreferences.saveConnection(
                this,
                serverUrl,
                pairCode
            )

            if (
                BuildConfig.FLAVOR == "controller"
            ) {

                connectController()

                Toast.makeText(
                    this,
                    "✅ تم حفظ الإعداد وبدء الاتصال",
                    Toast.LENGTH_SHORT
                ).show()

            } else {

                AgentService.start(
                    applicationContext
                )

                Toast.makeText(
                    this,
                    "✅ تم حفظ الاتصال وبدء خدمة الجهاز",
                    Toast.LENGTH_SHORT
                ).show()
            }

            ActivityLogger.log(
                "Connection settings saved"
            )
        }

        // =====================================================
        // Start Screen
        // =====================================================

        findViewById<Button>(
            R.id.startScreen
        ).setOnClickListener {

            if (
                Build.VERSION.SDK_INT <
                Build.VERSION_CODES.R
            ) {

                Toast.makeText(
                    this,
                    "يتطلب Android 11 أو أحدث",
                    Toast.LENGTH_LONG
                ).show()

                return@setOnClickListener
            }

            val accessibility =
                RemoteAccessibilityService.instance

            if (
                accessibility == null
            ) {

                Toast.makeText(
                    this,
                    "⚠️ فعّل Accessibility أولاً",
                    Toast.LENGTH_LONG
                ).show()

                startActivity(
                    Intent(
                        Settings.ACTION_ACCESSIBILITY_SETTINGS
                    )
                )

                return@setOnClickListener
            }

            accessibility.startScreenStreaming()

            status.text =
                "جاري بث الشاشة..."

            status.setTextColor(
                0xFFFF9800.toInt()
            )

            ActivityLogger.log(
                "Screen streaming started"
            )
        }

        // =====================================================
        // Stop Screen
        // =====================================================

        findViewById<Button>(
            R.id.stopScreen
        ).setOnClickListener {

            RemoteAccessibilityService.instance
                ?.stopScreenStreaming()

            Toast.makeText(
                this,
                "⏹ تم إيقاف بث الشاشة",
                Toast.LENGTH_SHORT
            ).show()

            ActivityLogger.log(
                "Screen streaming stopped"
            )
        }

        updatePermissionStatus()
    }

    // ============================================================
    // Controller
    // ============================================================

    private fun connectController() {

        val serverUrl =
            url.text
                .toString()
                .trim()

        val pairCode =
            code.text
                .toString()
                .trim()

        if (
            serverUrl.isBlank() ||
            pairCode.isBlank()
        ) {

            status.text =
                "● أدخل رابط السيرفر وكود الاقتران"

            status.setTextColor(
                0xFFFF5252.toInt()
            )

            return
        }

        RemoteConnection.startController(
            applicationContext,
            serverUrl,
            pairCode,

            onBinary = {
                // ControllerActivity هي التي تستقبل البيانات الثنائية
            },

            onText = { message ->

                runOnUiThread {

                    handleControllerMessage(
                        message
                    )
                }
            }
        )
    }

    private fun handleControllerMessage(
        message: String
    ) {

        try {

            val json =
                org.json.JSONObject(
                    message
                )

            when (
                json.optString(
                    "type"
                )
            ) {

                "connection_status" -> {

                    status.text =
                        "● متصل"

                    status.setTextColor(
                        0xFF4CAF50.toInt()
                    )
                }

                "device_list" -> {

                    val devices =
                        json.optJSONArray(
                            "devices"
                        )

                    if (
                        devices == null ||
                        devices.length() == 0
                    ) {

                        status.text =
                            "● متصل\nلا توجد أجهزة متصلة"

                        return
                    }

                    val text =
                        StringBuilder(
                            "● الأجهزة المتصلة:\n\n"
                        )

                    for (
                        i in 0 until devices.length()
                    ) {

                        val device =
                            devices.getJSONObject(
                                i
                            )

                        val name =
                            device.optString(
                                "deviceName",
                                "Android"
                            )

                        val online =
                            device.optBoolean(
                                "online",
                                false
                            )

                        text.append(
                            if (online)
                                "🟢 "
                            else
                                "⚪ "
                        )

                        text.append(
                            name
                        )

                        text.append("\n")
                    }

                    status.text =
                        text.toString()
                }

                "password_required" -> {

                    status.text =
                        "🔒 الجهاز يحتاج باسورد"

                    status.setTextColor(
                        0xFFFF9800.toInt()
                    )
                }

                "device_setup_required" -> {

                    status.text =
                        "⚙️ الجهاز يحتاج إعداد الاسم والباسورد"

                    status.setTextColor(
                        0xFFFF9800.toInt()
                    )
                }

                "device_authenticated" -> {

                    status.text =
                        "✅ تم فتح الجهاز"

                    status.setTextColor(
                        0xFF4CAF50.toInt()
                    )
                }

                "device_settings_updated" -> {

                    status.text =
                        "✅ تم تحديث بيانات الجهاز"

                    status.setTextColor(
                        0xFF4CAF50.toInt()
                    )
                }

                "auth_error" -> {

                    status.text =
                        "❌ ${json.optString("message")}"

                    status.setTextColor(
                        0xFFFF5252.toInt()
                    )
                }

                "auth_required" -> {

                    status.text =
                        "🔒 أدخل باسورد الجهاز أولاً"

                    status.setTextColor(
                        0xFFFF9800.toInt()
                    )
                }

                "error" -> {

                    status.text =
                        "❌ ${json.optString("message")}"

                    status.setTextColor(
                        0xFFFF5252.toInt()
                    )
                }
            }

        } catch (e: Exception) {

            ActivityLogger.log(
                "Controller message error: ${e.message}"
            )
        }
    }

    // ============================================================
    // MediaProjection
    // ============================================================

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode ==
            requestCodeScreenRecording
        ) {

            if (
                resultCode == Activity.RESULT_OK &&
                data != null
            ) {

                RemoteConnection.mediaProjectionData =
                    RemoteConnection.MediaProjectionData(
                        resultCode,
                        data
                    )

                Toast.makeText(
                    this,
                    "✅ تم الحصول على إذن تسجيل الشاشة",
                    Toast.LENGTH_SHORT
                ).show()

                ActivityLogger.log(
                    "MediaProjection permission granted"
                )

            } else {

                Toast.makeText(
                    this,
                    "⚠️ تم رفض إذن تسجيل الشاشة",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ============================================================
    // Destroy
    // ============================================================

    override fun onDestroy() {

        RemoteConnection
            .clearAgentUiStatusListener()

        super.onDestroy()
    }

    // ============================================================
    // Permissions
    // ============================================================

    private fun updatePermissionStatus() {

        val permissions =
            mapOf(

                R.id.requestMic
                    to Manifest.permission.RECORD_AUDIO,

                R.id.requestCamera
                    to Manifest.permission.CAMERA,

                R.id.requestLocation
                    to Manifest.permission.ACCESS_FINE_LOCATION,

                R.id.requestCalls
                    to Manifest.permission.READ_CALL_LOG,

                R.id.requestSms
                    to Manifest.permission.READ_SMS
            )

        permissions.forEach {
                (buttonId, permission) ->

            val granted =
                ActivityCompat.checkSelfPermission(
                    this,
                    permission
                ) ==
                    PackageManager.PERMISSION_GRANTED

            if (
                granted
            ) {

                val button =
                    findViewById<Button>(
                        buttonId
                    )

                if (
                    !button.text
                        .toString()
                        .startsWith("✅")
                ) {

                    button.text =
                        "✅ ${button.text}"
                }

                button.backgroundTintList =
                    android.content.res.ColorStateList
                        .valueOf(
                            0xFF2E7D32.toInt()
                        )

                button.isEnabled =
                    false
            }
        }
    }
}