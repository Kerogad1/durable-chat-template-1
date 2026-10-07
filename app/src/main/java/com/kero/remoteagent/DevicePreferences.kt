package com.kero.remoteagent

import android.content.Context

object DevicePreferences {

    private const val PREFS = "controller_devices"

    fun saveName(context: Context, deviceId: String, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("name_$deviceId", name)
            .apply()
    }

    fun getName(context: Context, deviceId: String, default: String): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("name_$deviceId", default)
            ?: default
    }

    fun isConfigured(context: Context, deviceId: String): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains("name_$deviceId")
    }

    fun clear(context: Context, deviceId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("name_$deviceId")
            .apply()
    }
}