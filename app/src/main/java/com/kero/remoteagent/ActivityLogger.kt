package com.kero.remoteagent

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ActivityLogger {

    private val logs = mutableListOf<String>()
    private const val MAX_LOGS = 100

    fun log(event: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $event"
        logs.add(logEntry)
        
        if (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        
        Log.d("KERO_LOG", logEntry)
        
        RemoteConnection.sendJson(JSONObject().apply {
            put("type", "activity_log")
            put("event", event)
            put("timestamp", timestamp)
        })
    }

    fun getLogs() {
        RemoteConnection.sendJson(JSONObject().apply {
            put("type", "activity_logs")
            put("logs", logs.joinToString("\n"))
        })
    }

    fun clearLogs() {
        logs.clear()
        log("Logs cleared")
    }
}