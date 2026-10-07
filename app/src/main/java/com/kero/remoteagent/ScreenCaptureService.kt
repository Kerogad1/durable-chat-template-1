package com.kero.remoteagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "kero_screen_stream"
        private const val NOTIFICATION_ID = 2001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }

            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, ScreenCaptureService::class.java)
            )
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var lastFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val resultCode =
            intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1

        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()

        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection =
            manager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        workerThread = HandlerThread("KeroScreenCapture")
        workerThread?.start()
        workerHandler = Handler(workerThread!!.looper)

        setupProjection()

        RemoteConnection.sendJson(
            org.json.JSONObject()
                .put("type", "screen_status")
                .put("streaming", true)
        )

        return START_NOT_STICKY
    }

    private fun setupProjection() {

        val metrics = resources.displayMetrics

        val sourceWidth = metrics.widthPixels
        val sourceHeight = metrics.heightPixels

        val maxWidth = 720

        val outputWidth =
            minOf(sourceWidth, maxWidth)

        val outputHeight =
            (sourceHeight.toFloat() *
                    outputWidth.toFloat() /
                    sourceWidth.toFloat())
                .roundToInt()
                .coerceAtLeast(1)

        imageReader = ImageReader.newInstance(
            outputWidth,
            outputHeight,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )

        mediaProjection?.registerCallback(
            object : MediaProjection.Callback() {

                override fun onStop() {
                    stopSelf()
                }
            },
            workerHandler
        )

        imageReader?.setOnImageAvailableListener(
            { reader ->
                captureFrame(reader)
            },
            workerHandler
        )

        virtualDisplay =
            mediaProjection?.createVirtualDisplay(
                "KERO Remote Screen",
                outputWidth,
                outputHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                workerHandler
            )
    }

    private fun captureFrame(reader: ImageReader) {

        val now = android.os.SystemClock.elapsedRealtime()

        // تقريبًا 5 FPS في النسخة التجريبية
        if (now - lastFrameTime < 200) {
            reader.acquireLatestImage()?.close()
            return
        }

        lastFrameTime = now

        val image = reader.acquireLatestImage() ?: return

        try {

            val plane = image.planes[0]
            val buffer = plane.buffer

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val rowPadding =
                rowStride -
                        pixelStride * image.width

            val bitmapWidth =
                image.width +
                        rowPadding / pixelStride

            val bitmap =
                Bitmap.createBitmap(
                    bitmapWidth,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )

            buffer.rewind()

            bitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap =
                if (bitmapWidth != image.width) {
                    Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        image.width,
                        image.height
                    )
                } else {
                    bitmap
                }

            val output =
                ByteArrayOutputStream()

            croppedBitmap.compress(
                Bitmap.CompressFormat.JPEG,
                55,
                output
            )

            val jpegData =
                output.toByteArray()

            RemoteConnection.sendBinary(byteArrayOf(0x53) + jpegData)

            if (croppedBitmap !== bitmap) {
                croppedBitmap.recycle()
            }

            bitmap.recycle()

        } catch (_: Exception) {

        } finally {
            image.close()
        }
    }

    private fun startForegroundServiceNotification() {

        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("KERO Remote")
                .setContentText("مشاركة الشاشة قيد التشغيل")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "KERO Screen Streaming",
                    NotificationManager.IMPORTANCE_LOW
                )

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {

        RemoteConnection.sendJson(
            org.json.JSONObject()
                .put("type", "screen_status")
                .put("streaming", false)
        )

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}