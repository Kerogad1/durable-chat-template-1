package com.kero.remoteagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecorderService : Service() {

    companion object {
        const val CHANNEL_ID = "KERO_RECORDER_CHANNEL"
        const val NOTIFICATION_ID = 1003
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_START = "start_recording"
        const val ACTION_STOP = "stop_recording"

        fun startRecording(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenRecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRecording(context: Context) {
            context.stopService(
                Intent(context, ScreenRecorderService::class.java)
            )
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var outputFilePath: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                buildNotification()
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (resultCode != -1 && data != null) {
                    startRecordingInternal(resultCode, data)
                }
            }
            ACTION_STOP -> stopRecordingInternal()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingInternal()
    }

    private fun startRecordingInternal(resultCode: Int, data: Intent) {
        if (isRecording) {
            Log.w("KERO_RECORDER", "Already recording")
            return
        }

        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e("KERO_RECORDER", "MediaProjection is null")
                RemoteConnection.sendJson(JSONObject().apply {
                    put("type", "recording_error")
                    put("message", "Failed to get MediaProjection")
                })
                return
            }

            // إعداد مسار الملف
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "KeroRecordings")
            if (!outputDir.exists()) outputDir.mkdirs()
            val outputFile = File(outputDir, "recording_$timestamp.mp4")
            outputFilePath = outputFile.absolutePath

            // إعداد MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFilePath)
                setVideoSize(width, height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(5 * 1024 * 1024) // 5 Mbps
                setVideoFrameRate(30)
                prepare()
            }

            // إنشاء VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "KeroScreenRecorder",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface,
                null,
                null
            )

            mediaRecorder?.start()
            isRecording = true

            ActivityLogger.log("Screen recording started")
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "recording_started")
                put("path", outputFilePath)
                put("width", width)
                put("height", height)
            })

        } catch (e: Exception) {
            Log.e("KERO_RECORDER", "Error starting recording: ${e.message}")
            e.printStackTrace()
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "recording_error")
                put("message", e.message ?: "Unknown error")
            })
        }
    }

    private fun stopRecordingInternal() {
        if (!isRecording) return

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null

            virtualDisplay?.release()
            virtualDisplay = null

            mediaProjection?.stop()
            mediaProjection = null

            isRecording = false

            ActivityLogger.log("Screen recording stopped: $outputFilePath")
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "recording_stopped")
                put("path", outputFilePath)
                put("status", "success")
            })

        } catch (e: Exception) {
            Log.e("KERO_RECORDER", "Error stopping recording: ${e.message}")
            e.printStackTrace()
            RemoteConnection.sendJson(JSONObject().apply {
                put("type", "recording_error")
                put("message", e.message ?: "Unknown error")
            })
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KERO Screen Recorder",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "يستخدم لتسجيل الشاشة"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("KERO Screen Recorder")
            .setContentText("جاري تسجيل الشاشة...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}