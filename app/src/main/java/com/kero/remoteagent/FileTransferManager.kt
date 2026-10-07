package com.kero.remoteagent

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FileTransferManager {

    private const val CHUNK_SIZE = 50 * 1024 // 50 KB per chunk
    private val uploadBuffers = mutableMapOf<String, FileOutputStream>()

    // تحميل ملف من الهاتف المدار وإرساله للمتحكم (Download)
    fun downloadFile(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) {
                RemoteConnection.sendJson(JSONObject().apply {
                    put("type", "file_error")
                    put("message", "File not found: $filePath")
                })
                return
            }

            val fileName = file.name
            val totalChunks = (file.length() / CHUNK_SIZE + (if (file.length() % CHUNK_SIZE > 0) 1 else 0)).toInt()
            val fis = FileInputStream(file)
            val buffer = ByteArray(CHUNK_SIZE)
            var chunkIndex = 0

            Thread {
                try {
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        val chunkData = Base64.encodeToString(buffer.copyOf(bytesRead), Base64.NO_WRAP)
                        val msg = JSONObject().apply {
                            put("type", "file_chunk")
                            put("name", fileName)
                            put("chunk", chunkIndex)
                            put("total", totalChunks)
                            put("data", chunkData)
                        }
                        RemoteConnection.sendJson(msg)
                        chunkIndex++
                        Thread.sleep(50) // تأخير بسيط لعدم إغراق الـ WebSocket
                    }
                    RemoteConnection.sendJson(JSONObject().apply {
                        put("type", "file_complete")
                        put("name", fileName)
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    fis.close()
                }
            }.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // استقبال ملف من المتحكم وحفظه في الهاتف المدار (Upload)
    fun handleUploadChunk(json: JSONObject) {
        try {
            val fileName = json.getString("name")
            val chunkIndex = json.getInt("chunk")
            val totalChunks = json.getInt("total")
            val dataBase64 = json.getString("data")
            val dataBytes = Base64.decode(dataBase64, Base64.NO_WRAP)

            val saveDir = File(AppContextHolder.context.getExternalFilesDir(null), "KeroUploads")
            if (!saveDir.exists()) saveDir.mkdirs()
            val saveFile = File(saveDir, fileName)

            if (chunkIndex == 0) {
                // أول جزء: إنشاء الملف
                uploadBuffers[fileName]?.close()
                uploadBuffers[fileName] = FileOutputStream(saveFile, false)
            }

            uploadBuffers[fileName]?.write(dataBytes)

            if (chunkIndex == totalChunks - 1) {
                // آخر جزء: إغلاق الملف
                uploadBuffers[fileName]?.close()
                uploadBuffers.remove(fileName)
                RemoteConnection.sendJson(JSONObject().apply {
                    put("type", "upload_complete")
                    put("name", fileName)
                    put("path", saveFile.absolutePath)
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}