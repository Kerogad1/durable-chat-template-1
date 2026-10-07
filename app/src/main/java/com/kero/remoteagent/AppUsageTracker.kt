package com.kero.remoteagent

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object AppUsageTracker {

    fun getAppUsage(days: Int = 1) {
        try {
            val context = AppContextHolder.context
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            
            val calendar = Calendar.getInstance()
            val endTime = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, -days)
            val startTime = calendar.timeInMillis
            
            val usageStatsList: List<UsageStats> = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )
            
            val appsArray = JSONArray()
            for (usageStats in usageStatsList) {
                if (usageStats.totalTimeInForeground > 0) {
                    val packageName = usageStats.packageName
                    val appName = try {
                        val pm = context.packageManager
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        packageName
                    }
                    
                    appsArray.put(JSONObject().apply {
                        put("package", packageName)
                        put("name", appName)
                        put("time_in_foreground", usageStats.totalTimeInForeground)
                        put("last_time_used", usageStats.lastTimeUsed)
                    })
                }
            }
            
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "app_usage")
                put("days", days)
                put("apps", appsArray)
            })
            
        } catch (e: Exception) {
            Log.e("KERO_USAGE", "Error getting app usage: ${e.message}")
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "app_usage_error")
                put("message", e.message ?: "Unknown error")
            })
        }
    }
}