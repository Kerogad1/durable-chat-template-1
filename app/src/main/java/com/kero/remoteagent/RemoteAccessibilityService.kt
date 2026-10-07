package com.kero.remoteagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
    }

    private var isStreaming = false
    private val screenshotExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()
    private val handler =
        Handler(Looper.getMainLooper())
    private var screenshotRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        stopScreenStreaming()
        screenshotExecutor.shutdownNow()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        val currentPackage =
            event?.packageName?.toString()
                ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                AppRestrictor.checkAndRestrict(
                    currentPackage
                )
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun screen(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    fun tap(nx: Double, ny: Double) {
        val (w, h) = screen()

        val x =
            (nx.coerceIn(0.0, 1.0) * w).toFloat()
        val y =
            (ny.coerceIn(0.0, 1.0) * h).toFloat()

        val path =
            Path().apply {
                moveTo(x, y)
            }

        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        80
                    )
                )
                .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
    }

    fun swipe(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        duration: Long
    ) {
        val (w, h) = screen()

        val path =
            Path().apply {
                moveTo(
                    (x1.coerceIn(0.0, 1.0) * w)
                        .toFloat(),
                    (y1.coerceIn(0.0, 1.0) * h)
                        .toFloat()
                )

                lineTo(
                    (x2.coerceIn(0.0, 1.0) * w)
                        .toFloat(),
                    (y2.coerceIn(0.0, 1.0) * h)
                        .toFloat()
                )
            }

        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        duration.coerceIn(
                            100,
                            5000
                        )
                    )
                )
                .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
    }

    fun nav(action: String) {
        when (action) {
            "back" ->
                performGlobalAction(
                    GLOBAL_ACTION_BACK
                )

            "home" ->
                performGlobalAction(
                    GLOBAL_ACTION_HOME
                )

            "recents" ->
                performGlobalAction(
                    GLOBAL_ACTION_RECENTS
                )
        }
    }

    @SuppressLint("NewApi")
    fun startScreenStreaming() {
        if (
            Build.VERSION.SDK_INT <
                Build.VERSION_CODES.R
        ) {
            return
        }

        if (isStreaming) return

        isStreaming = true

        lateinit var runnable: Runnable
        runnable = object : Runnable {
                override fun run() {
                    if (!isStreaming) return

                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        screenshotExecutor,
                        object :
                            TakeScreenshotCallback {

                            override fun onSuccess(
                                screenshot: ScreenshotResult
                            ) {
                                try {
                                    val hardwareBitmap =
                                        Bitmap.wrapHardwareBuffer(
                                            screenshot.hardwareBuffer,
                                            screenshot.colorSpace
                                        )

                                    val bitmap =
                                        hardwareBitmap
                                            ?.copy(
                                                Bitmap.Config.ARGB_8888,
                                                false
                                            )

                                    if (bitmap != null) {
                                        val output =
                                            ByteArrayOutputStream()

                                        bitmap.compress(
                                            Bitmap.CompressFormat.JPEG,
                                            40,
                                            output
                                        )

                                        val jpeg =
                                            output.toByteArray()

                                        RemoteConnection
                                            .sendBinary(
                                                byteArrayOf(0x53) +
                                                    jpeg
                                            )

                                        bitmap.recycle()
                                    }

                                    hardwareBitmap?.recycle()
                                    screenshot.hardwareBuffer.close()

                                } catch (e: Exception) {
                                    ActivityLogger.log(
                                        "Screenshot error: ${e.message}"
                                    )
                                }

                                if (isStreaming) {
                                    handler.postDelayed(
                                        runnable,
                                        500
                                    )
                                }
                            }

                            override fun onFailure(
                                errorCode: Int
                            ) {
                                if (isStreaming) {
                                    handler.postDelayed(
                                        runnable,
                                        1000
                                    )
                                }
                            }
                        }
                    )
                }
            }

        screenshotRunnable = runnable
        handler.post(runnable)
    }

    fun stopScreenStreaming() {
        isStreaming = false

        screenshotRunnable?.let {
            handler.removeCallbacks(it)
        }

        screenshotRunnable = null
    }
}
