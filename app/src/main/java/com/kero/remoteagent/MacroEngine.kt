package com.kero.remoteagent

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object MacroEngine {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var currentMacroIndex = 0

    fun executeMacro(macroJson: String) {
        if (isRunning) {
            Log.w("KERO_MACRO", "Macro already running")
            return
        }
        
        try {
            val macro = JSONArray(macroJson)
            isRunning = true
            currentMacroIndex = 0
            executeStep(macro, 0)
        } catch (e: Exception) {
            Log.e("KERO_MACRO", "Error parsing macro: ${e.message}")
            isRunning = false
        }
    }

    private fun executeStep(macro: JSONArray, index: Int) {
        if (index >= macro.length()) {
            Log.d("KERO_MACRO", "Macro completed")
            isRunning = false
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "macro_complete")
                put("status", "success")
            })
            return
        }
        
        try {
            val step = macro.getJSONObject(index)
            val action = step.getString("action")
            val delay = step.optLong("delay", 1000)
            
            Log.d("KERO_MACRO", "Executing step $index: $action")
            
            when (action) {
                "tap" -> {
                    val x = step.getDouble("x")
                    val y = step.getDouble("y")
                    RemoteAccessibilityService.instance?.tap(x, y)
                }
                "swipe" -> {
                    val x1 = step.getDouble("x1")
                    val y1 = step.getDouble("y1")
                    val x2 = step.getDouble("x2")
                    val y2 = step.getDouble("y2")
                    val duration = step.getLong("duration")
                    RemoteAccessibilityService.instance?.swipe(x1, y1, x2, y2, duration)
                }
                "nav" -> {
                    val navAction = step.getString("action_type")
                    RemoteAccessibilityService.instance?.nav(navAction)
                }
                "wait" -> {
                    val waitTime = step.getLong("time")
                    handler.postDelayed({
                        executeStep(macro, index + 1)
                    }, waitTime)
                    return
                }
                "open_app" -> {
                    val packageName = step.getString("package")
                    AppLauncher.open(AppContextHolder.context, packageName)
                }
                "screenshot" -> {
                    RemoteAccessibilityService.instance?.startScreenStreaming()
                    handler.postDelayed({
                        RemoteAccessibilityService.instance?.stopScreenStreaming()
                    }, 500)
                }
                "home" -> {
                    RemoteAccessibilityService.instance?.nav("home")
                }
                "back" -> {
                    RemoteAccessibilityService.instance?.nav("back")
                }
            }
            
            handler.postDelayed({
                executeStep(macro, index + 1)
            }, delay)
            
        } catch (e: Exception) {
            Log.e("KERO_MACRO", "Error executing step $index: ${e.message}")
            isRunning = false
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "macro_complete")
                put("status", "error")
                put("message", e.message ?: "Unknown error")
            })
        }
    }

    fun stopMacro() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        Log.d("KERO_MACRO", "Macro stopped")
    }
}