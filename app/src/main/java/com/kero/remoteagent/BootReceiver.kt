package com.kero.remoteagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (!AgentPreferences.isConfigured(context)) {
                Log.d("KERO_BOOT", "Boot received but Agent is not configured")
                return
            }

            try {
                Log.d("KERO_BOOT", "Device booted, restoring AgentService")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(
                        Intent(context, AgentService::class.java)
                    )
                } else {
                    context.startService(
                        Intent(context, AgentService::class.java)
                    )
                }
            } catch (e: Exception) {
                Log.e("KERO_BOOT", "Failed to restore AgentService", e)
            }
        }
    }
}
