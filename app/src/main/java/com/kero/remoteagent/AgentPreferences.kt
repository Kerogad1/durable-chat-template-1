package com.kero.remoteagent

import android.content.Context
import android.content.SharedPreferences

object AgentPreferences {
    private const val PREFS_NAME = "kero_remote_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_PAIR_CODE = "pair_code"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveConnection(context: Context, url: String, code: String) {
        getPrefs(context).edit().apply {
            putString(KEY_SERVER_URL, url)
            putString(KEY_PAIR_CODE, code)
            apply()
        }
    }

    fun getServerUrl(context: Context): String = getPrefs(context).getString(KEY_SERVER_URL, "") ?: ""
    fun getPairCode(context: Context): String = getPrefs(context).getString(KEY_PAIR_CODE, "") ?: ""
    
    fun isConfigured(context: Context): Boolean {
        return getServerUrl(context).isNotEmpty() && getPairCode(context).isNotEmpty()
    }
}