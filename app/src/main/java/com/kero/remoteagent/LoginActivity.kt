package com.kero.remoteagent

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class LoginActivity : AppCompatActivity() {

    private lateinit var loginSection: LinearLayout
    private lateinit var addSection: LinearLayout

    private lateinit var loginDeviceName: EditText
    private lateinit var loginPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnAddAccount: Button
    private lateinit var tvLoginError: TextView

    // قسم الإضافة
    private lateinit var addServerUrl: EditText
    private lateinit var addPairCode: EditText
    private lateinit var btnRefreshDevices: Button
    private lateinit var tvAddStatus: TextView
    private lateinit var spinnerDevices: Spinner
    private lateinit var addDeviceName: EditText
    private lateinit var addDevicePassword: EditText
    private lateinit var btnSaveAccount: Button
    private lateinit var btnCancelAdd: Button

    private val availableDevices = mutableListOf<JSONObject>()

    // ✅ متغير لتتبع إن الاتصال اتوقف
    private var connectionStopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        bindViews()
        loadServerSettings()

        btnLogin.setOnClickListener { attemptLogin() }
        btnAddAccount.setOnClickListener { showAddSection() }
        btnCancelAdd.setOnClickListener { showLoginSection() }
        btnSaveAccount.setOnClickListener { saveNewAccount() }
        btnRefreshDevices.setOnClickListener {
            saveServerSettings()
            loadDeviceList()
        }
    }

    private fun bindViews() {
        loginSection = findViewById(R.id.loginSection)
        addSection = findViewById(R.id.addSection)

        loginDeviceName = findViewById(R.id.loginDeviceName)
        loginPassword = findViewById(R.id.loginPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnAddAccount = findViewById(R.id.btnAddAccount)
        tvLoginError = findViewById(R.id.tvLoginError)

        addServerUrl = findViewById(R.id.addServerUrl)
        addPairCode = findViewById(R.id.addPairCode)
        btnRefreshDevices = findViewById(R.id.btnRefreshDevices)
        tvAddStatus = findViewById(R.id.tvAddStatus)
        spinnerDevices = findViewById(R.id.spinnerDevices)
        addDeviceName = findViewById(R.id.addDeviceName)
        addDevicePassword = findViewById(R.id.addDevicePassword)
        btnSaveAccount = findViewById(R.id.btnSaveAccount)
        btnCancelAdd = findViewById(R.id.btnCancelAdd)
    }

    private fun loadServerSettings() {
        val savedUrl = ControllerPreferences.url(this)
        val savedCode = ControllerPreferences.code(this)

        addServerUrl.setText(savedUrl)
        addPairCode.setText(savedCode.ifBlank { "KO2026" })

        if (!AccountStore.hasAccounts(this)) {
            showAddSection()
        }
    }

    private fun saveServerSettings() {
        val url = addServerUrl.text.toString().trim()
        val code = addPairCode.text.toString().trim()
        if (url.isNotBlank() && code.isNotBlank()) {
            ControllerPreferences.save(this, url, code)
        }
    }

    // ============================================================
    // Login
    // ============================================================

    private fun attemptLogin() {
        val name = loginDeviceName.text.toString().trim()
        val password = loginPassword.text.toString().trim()

        if (name.isBlank() || password.isBlank()) {
            showLoginError("اكتب اسم الجهاز والباسورد")
            return
        }

        when (val result = AccountStore.authenticate(this, name, password, ::sha256)) {
            is AccountStore.AuthResult.Success -> {
                hideLoginError()
                openController(result.account.deviceId, result.account.name)
            }
            AccountStore.AuthResult.NameNotFound -> {
                showLoginError("لا يوجد جهاز بهذا الاسم")
            }
            AccountStore.AuthResult.WrongPassword -> {
                showLoginError("الباسورد غلط")
            }
        }
    }

    private fun showLoginError(message: String) {
        tvLoginError.text = message
        tvLoginError.visibility = View.VISIBLE
    }

    private fun hideLoginError() {
        tvLoginError.visibility = View.GONE
    }

    // ============================================================
    // ✅ فتح ControllerActivity مع إغلاق اتصال Login أولاً
    // ============================================================

    private fun openController(deviceId: String, deviceName: String) {

        // ✅ 1. علّم إن الاتصال اتوقف عشان onDestroy ما يعملش stop تاني
        connectionStopped = true

        // ✅ 2. وقّف اتصال الـ Login
        try {
            RemoteConnection.clearControllerStatusListener()
        } catch (_: Exception) {}

        try {
            RemoteConnection.stop()
        } catch (_: Exception) {}

        // ✅ 3. انتظر 500ms عشان الـ WebSocket server يقفل الاتصال تمامًا
        //     بعدها افتح ControllerActivity عشان تبدأ اتصال نظيف
        Handler(Looper.getMainLooper()).postDelayed({

            val intent = Intent(this, ControllerActivity::class.java).apply {
                putExtra("autoDeviceId", deviceId)
                putExtra("autoDeviceName", deviceName)
            }

            startActivity(intent)
            finish()

        }, 500)
    }

    // ============================================================
    // Add Account Section
    // ============================================================

    private fun showAddSection() {
        loginSection.visibility = View.GONE
        addSection.visibility = View.VISIBLE

        val url = addServerUrl.text.toString().trim()
        val code = addPairCode.text.toString().trim()
        if (url.isNotBlank() && code.isNotBlank()) {
            loadDeviceList()
        } else {
            tvAddStatus.text = "❌ اكتب رابط السيرفر وكود الاقتران أولًا"
        }
    }

    private fun showLoginSection() {
        addSection.visibility = View.GONE
        loginSection.visibility = View.VISIBLE
        stopDeviceListener()
    }

    private fun loadDeviceList() {
        val url = addServerUrl.text.toString().trim()
        val code = addPairCode.text.toString().trim()

        if (url.isBlank() || code.isBlank()) {
            tvAddStatus.text = "❌ أدخل رابط السيرفر وكود الاقتران أولًا"
            return
        }

        saveServerSettings()

        tvAddStatus.text = "⏳ جاري الاتصال بالسيرفر..."
        availableDevices.clear()
        spinnerDevices.adapter = null

        stopDeviceListener()

        // ✅ صفّر العلم لأننا بنعمل اتصال جديد
        connectionStopped = false

        val listener: (String) -> Unit = { text ->
            try {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "hello", "connection_status" -> {
                        RemoteConnection.sendJson(
                            JSONObject().put("type", "get_devices")
                        )
                    }
                    "device_list" -> {
                        val devices = msg.optJSONArray("devices") ?: JSONArray()
                        runOnUiThread {
                            updateDeviceSpinner(devices)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        RemoteConnection.setControllerStatusListener { status ->
            runOnUiThread {
                when {
                    status.contains("متصل") -> {
                        tvAddStatus.text = "⏳ جاري تحميل الأجهزة..."
                    }
                    status.contains("فشل") || status.contains("قطع") -> {
                        tvAddStatus.text = "❌ فشل الاتصال بالسيرفر"
                    }
                }
            }
        }

        RemoteConnection.startController(
            this,
            url,
            code,
            onBinary = {},
            onText = listener
        )
    }

    private fun stopDeviceListener() {
        try {
            RemoteConnection.clearControllerStatusListener()
        } catch (_: Exception) {}

        try {
            RemoteConnection.stop()
        } catch (_: Exception) {}
    }

    private fun updateDeviceSpinner(devices: JSONArray) {
        availableDevices.clear()

        val labels = mutableListOf<String>()

        for (i in 0 until devices.length()) {
            val device = devices.optJSONObject(i) ?: continue
            val deviceId = device.optString("deviceId")
            val online = device.optBoolean("online", false)

            if (deviceId.isBlank() || !online) continue

            val serverName = device.optString("deviceName", "جهاز Android")
            availableDevices.add(device)
            labels.add(serverName)
        }

        if (availableDevices.isEmpty()) {
            tvAddStatus.text = "❌ لا توجد أجهزة متصلة حاليًا"
            spinnerDevices.adapter = null
            return
        }

        tvAddStatus.text = "✅ اختر جهازًا (${availableDevices.size} متاح)"

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices.adapter = adapter
    }

    private fun saveNewAccount() {
        val name = addDeviceName.text.toString().trim()
        val password = addDevicePassword.text.toString().trim()

        if (name.isBlank()) {
            addDeviceName.error = "اكتب اسم للجهاز"
            return
        }

        if (password.length < 4) {
            addDevicePassword.error = "الباسورد 4 أحرف على الأقل"
            return
        }

        if (availableDevices.isEmpty()) {
            Toast.makeText(
                this,
                "❌ اضغط '🔄 اتصال وتحديث الأجهزة' الأول",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val selectedIndex = spinnerDevices.selectedItemPosition
        if (selectedIndex < 0 || selectedIndex >= availableDevices.size) {
            Toast.makeText(this, "اختر جهازًا أولًا", Toast.LENGTH_SHORT).show()
            return
        }

        val device = availableDevices[selectedIndex]
        val deviceId = device.optString("deviceId")

        if (deviceId.isBlank()) {
            Toast.makeText(this, "جهاز غير صالح", Toast.LENGTH_SHORT).show()
            return
        }

        AccountStore.addAccount(
            this,
            name,
            sha256(password),
            deviceId
        )

        Toast.makeText(this, "✅ تم حفظ الحساب: $name", Toast.LENGTH_SHORT).show()

        loginDeviceName.setText(name)
        loginPassword.setText("")
        addDeviceName.setText("")
        addDevicePassword.setText("")

        showLoginSection()

        Toast.makeText(this, "🔑 ادخل بالاسم والباسورد", Toast.LENGTH_LONG).show()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    override fun onDestroy() {
        // ✅ لو الاتصال اتوقف بالفعل (احنا مسافرين لـ ControllerActivity)،
        //    ما نعملش stop تاني عشان ما نقفلش اتصال ControllerActivity
        if (!connectionStopped) {
            stopDeviceListener()
        }
        super.onDestroy()
    }
}