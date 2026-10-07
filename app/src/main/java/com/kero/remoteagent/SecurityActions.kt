package com.kero.remoteagent

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import org.json.JSONObject

object SecurityActions {

    // ==================== REMOTE LOCK ====================
    fun lockDevice() {
        try {
            val context = AppContextHolder.context
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (keyguardManager.isDeviceLocked) {
                    Log.d("KERO_SECURITY", "Device already locked")
                    RemoteConnection.sendJson(JSONObject().apply {
                        put("type", "lock_status")
                        put("status", "already_locked")
                    })
                } else {
                    // محاولة قفل الجهاز (يتطلب Device Owner أو صلاحيات خاصة)
                    val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminComponent = ComponentName(context, RemoteDeviceAdminReceiver::class.java)

                    if (devicePolicyManager.isAdminActive(adminComponent)) {
                        devicePolicyManager.lockNow()
                        RemoteConnection.sendJson(JSONObject().apply {
                            put("type", "lock_status")
                            put("status", "locked")
                        })
                    } else {
                        Log.w("KERO_SECURITY", "Device admin not active")
                        RemoteConnection.sendJson(JSONObject().apply {
                            put("type", "lock_status")
                            put("status", "error")
                            put("message", "Device admin not active")
                        })
                    }
                }
            } else {
                Log.w("KERO_SECURITY", "Lock requires Android P+")
                RemoteConnection.sendJson(JSONObject().apply {
                    put("type", "lock_status")
                    put("status", "error")
                    put("message", "Requires Android P+")
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== REMOTE ALARM ====================
    fun triggerAlarm(durationMs: Long = 30000) {
        try {
            val context = AppContextHolder.context

            // تشغيل صوت الإنذار بأقصى صوت
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.audioAttributes = audioAttributes
            }

            ringtone.play()

            // اهتزاز قوي
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }

            // إيقاف الإنذار بعد المدة المحددة
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                ringtone.stop()
                Log.d("KERO_SECURITY", "Alarm stopped")
            }, durationMs)

            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "alarm_status")
                put("status", "triggered")
                put("duration", durationMs)
            })

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}