package com.kero.remoteagent

import android.content.Context

object ControllerPreferences {
    private const val PREFS_NAME = "kero_remote_controller_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_PAIR_CODE = "pair_code"

    fun save(context: Context, url: String, code: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url)
            .putString(KEY_PAIR_CODE, code)
            .apply()
    }

    fun url(context: Context): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SERVER_URL, "") ?: ""
    fun code(context: Context): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PAIR_CODE, "") ?: ""
}
