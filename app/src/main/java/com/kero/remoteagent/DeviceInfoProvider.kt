package com.kero.remoteagent

import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject

object DeviceInfoProvider {
    fun send() {
        val app = AppContextHolder.context
        val dm = app.resources.displayMetrics
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val free = stat.availableBytes

        val batteryIntent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battery = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val chargingState = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = chargingState == BatteryManager.BATTERY_STATUS_CHARGING ||
                chargingState == BatteryManager.BATTERY_STATUS_FULL

        val activityManager = app.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        RemoteConnection.sendJson(
            JSONObject()
                .put("type", "device_info")
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("battery", battery)
                .put("charging", charging)
                .put("ram_total", memoryInfo.totalMem)
                .put("ram_available", memoryInfo.availMem)
                .put("storage_total", total)
                .put("storage_free", free)
                .put("screen_width", dm.widthPixels)
                .put("screen_height", dm.heightPixels)
                .put("density", dm.density)
        )
    }
}
