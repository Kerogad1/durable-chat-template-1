package com.kero.remoteagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AgentService : Service() {

    companion object {
        private const val CHANNEL_ID = "kero_agent_service"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
        createNotificationChannel()

        RemoteConnection.setAgentStatusListener { status ->
            updateNotification(status)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        startAsForeground("جارٍ تجهيز الاتصال...")

        val serverUrl = AgentPreferences.getServerUrl(this)
        val pairCode = AgentPreferences.getPairCode(this)

        if (serverUrl.isNotBlank() && pairCode.isNotBlank()) {
            RemoteConnection.startPersistentAgent(
                this,
                serverUrl,
                pairCode
            )
        } else {
            updateNotification("في انتظار إعداد الاتصال")
        }

        return START_STICKY
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Phone Assistant")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Phone Assistant",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    override fun onDestroy() {
        RemoteConnection.clearAgentStatusListener()
        RemoteConnection.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
