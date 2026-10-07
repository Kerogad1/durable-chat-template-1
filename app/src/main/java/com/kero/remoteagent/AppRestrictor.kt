package com.kero.remoteagent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object AppRestrictor {

    private const val PREFS = "kero_restricted_apps"
    private const val KEY = "packages"

    private fun getPrefs() =
        AppContextHolder.context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    private fun load(): MutableSet<String> =
        getPrefs()
            .getStringSet(KEY, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()

    private fun save(apps: Set<String>) {
        getPrefs()
            .edit()
            .putStringSet(KEY, apps)
            .apply()
    }

    @Synchronized
    fun restrictApp(packageName: String) {
        if (packageName.isBlank()) return

        val apps = load()
        apps.add(packageName)
        save(apps)

        Log.d(
            "KERO_RESTRICT",
            "App restricted: $packageName"
        )

        RemoteConnection.sendJson(
            JSONObject()
                .put("type", "app_restricted")
                .put("package", packageName)
        )
    }

    @Synchronized
    fun unrestrictApp(packageName: String) {
        val apps = load()
        apps.remove(packageName)
        save(apps)

        RemoteConnection.sendJson(
            JSONObject()
                .put("type", "app_unrestricted")
                .put("package", packageName)
        )
    }

    fun checkAndRestrict(currentPackage: String) {
        if (currentPackage.isBlank()) return

        if (currentPackage in load()) {
            Log.w(
                "KERO_RESTRICT",
                "Blocked app: $currentPackage"
            )

            RemoteAccessibilityService
                .instance
                ?.nav("home")

            RemoteConnection.sendJson(
                JSONObject()
                    .put("type", "app_blocked")
                    .put("package", currentPackage)
            )
        }
    }

    fun getRestrictedApps() {
        RemoteConnection.sendJson(
            JSONObject()
                .put(
                    "type",
                    "restricted_apps"
                )
                .put(
                    "apps",
                    JSONArray(load().toList())
                )
        )
    }
}
