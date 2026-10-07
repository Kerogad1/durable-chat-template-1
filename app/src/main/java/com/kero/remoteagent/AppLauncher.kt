package com.kero.remoteagent

import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

object AppLauncher {
    
    // فتح تطبيق معين بناءً على اسم الحزمة (Package Name)
    fun open(context: Context, packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                RemoteConnection.sendJson(JSONObject().apply {
                    put("type", "app_error")
                    put("message", "App not found or cannot be launched: $packageName")
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // جلب قائمة التطبيقات المثبتة وإرسالها للمتحكم
    fun listInstalledApps(context: Context) {
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            val jsonArray = JSONArray()
            
            for (app in apps) {
                // نتأكد من أن التطبيق له واجهة إطلاق (Launch Intent)
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    val appObj = JSONObject().apply {
                        put("package", app.packageName)
                        put("name", pm.getApplicationLabel(app).toString())
                    }
                    jsonArray.put(appObj)
                }
            }
            
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "app_list")
                put("apps", jsonArray)
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    fun openDialer(context: Context, phoneNumber: String) {
        try {
            if (phoneNumber.isBlank()) {
                RemoteConnection.sendJson(
                    JSONObject().put("type", "dial_error").put("message", "رقم الهاتف فارغ")
                )
                return
            }

            val intent = Intent(
                Intent.ACTION_DIAL,
                android.net.Uri.parse("tel:${android.net.Uri.encode(phoneNumber)}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            RemoteConnection.sendJson(
                JSONObject().put("type", "dial_ready").put("number", phoneNumber)
            )
        } catch (e: Exception) {
            RemoteConnection.sendJson(
                JSONObject()
                    .put("type", "dial_error")
                    .put("message", e.message ?: "تعذر فتح تطبيق الاتصال")
            )
        }
    }

    fun openUrl(context: Context, url: String) {
        try {
            val normalized = when {
                url.startsWith("http://") || url.startsWith("https://") -> url
                else -> "https://$url"
            }

            val intent = Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse(normalized)
            ).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            RemoteConnection.sendJson(
                JSONObject().put("type", "url_opened").put("url", normalized)
            )
        } catch (e: Exception) {
            RemoteConnection.sendJson(
                JSONObject()
                    .put("type", "url_error")
                    .put("message", e.message ?: "تعذر فتح الرابط")
            )
        }
    }

}