package com.mickey.phonemirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.media.AudioPlaybackCaptureConfiguration
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_DATA = "DATA"
        private const val PORT = 8080
        private const val NOTIFICATION_ID = 99
        private const val CHANNEL_ID = "mirror_channel"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: DataOutputStream? = null

    @Volatile
    private var isRunning = false

    @Volatile private var needsRotationRestart = false
    @Volatile private var currentBitrate = 12000000 // default 12 Mbps
    @Volatile private var currentFps = 60 // default 60 FPS
    @Volatile private var currentMaxResolution = 0 // default 0 (Native)

    private var audioServerSocket: ServerSocket? = null
    private var audioClientSocket: Socket? = null
    @Volatile private var isAudioRunning = false

    private var configReceiver: android.content.BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerConfigReceiver()
    }

    private fun registerConfigReceiver() {
        if (configReceiver != null) return
        configReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.mickey.phonemirror.UPDATE_CONFIG") {
                    val bitrate = intent.getIntExtra("bitrate", -1)
                    val fps = intent.getIntExtra("fps", -1)
                    val maxRes = intent.getIntExtra("max_res", -1)
                    
                    Log.e(TAG, "Received config update broadcast: bitrate=$bitrate, fps=$fps, max_res=$maxRes")
                    
                    var changed = false
                    if (bitrate > 0 && bitrate != currentBitrate) {
                        currentBitrate = bitrate
                        changed = true
                    }
                    if (fps > 0 && fps != currentFps) {
                        currentFps = fps
                        changed = true
                    }
                    if (maxRes >= 0 && maxRes != currentMaxResolution) {
                        currentMaxResolution = maxRes
                        changed = true
                    }
                    
                    if (changed) {
                        Log.e(TAG, "Config updated dynamically. Restarting video encoder...")
                        needsRotationRestart = true
                    }
                }
            }
        }
        val filter = android.content.IntentFilter("com.mickey.phonemirror.UPDATE_CONFIG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(configReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(configReceiver, filter)
        }
    }

    private fun unregisterConfigReceiver() {
        if (configReceiver != null) {
            try {
                unregisterReceiver(configReceiver)
            } catch (e: Exception) {}
            configReceiver = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        // Activity.RESULT_OK is -1.
        if (resultCode == android.app.Activity.RESULT_OK && data != null && !isRunning) {
            isRunning = true
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            // Android 14 requires startForeground(MEDIA_PROJECTION) to be called BEFORE getMediaProjection()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }

            // NOW get media projection
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            Log.e(TAG, "Foreground service started successfully with MediaProjection")

            // Read starting quality options
            currentBitrate = intent.getIntExtra("bitrate", 12000000)
            currentFps = intent.getIntExtra("fps", 60)
            currentMaxResolution = intent.getIntExtra("max_res", 0)
            Log.e(TAG, "Initial config: bitrate=$currentBitrate, fps=$currentFps, maxRes=$currentMaxResolution")

            // Start the TCP server and encoder
            thread(name = "MirrorServerThread") {
                startServer()
            }
            // Start the audio TCP server
            thread(name = "AudioServerThread") {
                startAudioServer()
            }
        } else {
            if (isRunning) {
                Log.e(TAG, "Service already running. Ignoring start command.")
            } else {
                Log.e(TAG, "Invalid intent. Starting foreground with basic notification then stopping to prevent crash.")
                startForeground(NOTIFICATION_ID, createNotification())
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startServer() {
        try {
            serverSocket = ServerSocket(PORT)
            Log.e(TAG, "TCP Server started on port $PORT")

            while (isRunning) {
                try {
                    Log.e(TAG, "Waiting for client connection...")
                    val socket = serverSocket!!.accept()
                    Log.e(TAG, "Client connected: ${socket.remoteSocketAddress}")

                    clientSocket = socket
                    outputStream = DataOutputStream(socket.getOutputStream())

                    // Start screen capture encoding and stream it to the client
                    runEncodingLoop()

                    Log.e(TAG, "Stream loop finished. Stopping server.")
                    break
                } catch (e: IOException) {
                    Log.e(TAG, "Socket error: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal server error: ${e.message}")
        } finally {
            Log.e(TAG, "Stopping CaptureService.")
            isRunning = false
            cleanUpSession()
            try {
                serverSocket?.close()
            } catch (e: Exception) {}
            stopSelf()
        }
    }

    private var displayListener: DisplayManager.DisplayListener? = null

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                    Log.e(TAG, "Display rotated! Triggering encoder restart...")
                    needsRotationRestart = true
                }
            }
        }
        displayManager.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    private fun unregisterDisplayListener() {
        if (displayListener != null) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.unregisterDisplayListener(displayListener)
            displayListener = null
        }
    }

    private fun runEncodingLoop() {
        registerDisplayListener()
        var firstRun = true

        while (isRunning && clientSocket?.isConnected == true) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val realMetrics = android.util.DisplayMetrics()
            var obtained = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    this.display?.getRealMetrics(realMetrics)
                    obtained = realMetrics.widthPixels > 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get display from context: ${e.message}")
            }
            if (!obtained) {
                try {
                    windowManager.defaultDisplay?.getRealMetrics(realMetrics)
                    obtained = realMetrics.widthPixels > 0
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get default display: ${e.message}")
                }
            }

            val rawWidth = if (obtained) realMetrics.widthPixels else resources.displayMetrics.widthPixels
            val rawHeight = if (obtained) realMetrics.heightPixels else resources.displayMetrics.heightPixels
            val dpi = if (obtained) realMetrics.densityDpi else resources.displayMetrics.densityDpi

            // Apply resolution capping based on user selection
            var targetWidth = rawWidth
            var targetHeight = rawHeight
            val maxRes = currentMaxResolution
            if (maxRes > 0) {
                if (targetWidth > targetHeight) {
                    // Landscape
                    if (targetWidth > maxRes) {
                        targetHeight = (targetHeight * maxRes.toFloat() / targetWidth).toInt()
                        targetWidth = maxRes
                    }
                } else {
                    // Portrait
                    if (targetHeight > maxRes) {
                        targetWidth = (targetWidth * maxRes.toFloat() / targetHeight).toInt()
                        targetHeight = maxRes
                    }
                }
            }

            // MUST be even numbers for MediaCodec H.264 configuration
            targetWidth = targetWidth and 1.inv()
            targetHeight = targetHeight and 1.inv()

            Log.e(TAG, "Native capture dimensions: ${targetWidth}x${targetHeight} (original: ${rawWidth}x${rawHeight}) (maxRes: $maxRes)")

            if (firstRun) {
                // 1. Send resolution header: [4-byte width][4-byte height]
                outputStream?.writeInt(targetWidth)
                outputStream?.writeInt(targetHeight)
                outputStream?.flush()
                firstRun = false
            } else {
                // Send rotation signal header: [4-byte -1] [4-byte width][4-byte height]
                outputStream?.writeInt(-1) // 0xFFFFFFFF indicates resolution change
                outputStream?.writeInt(targetWidth)
                outputStream?.writeInt(targetHeight)
                outputStream?.flush()
            }

            // 2. Set up MediaCodec using current quality configurations
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, currentFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second
                setInteger(MediaFormat.KEY_LATENCY, 1) // Request low-latency mode if available
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val codecSurface = mediaCodec!!.createInputSurface()

            if (virtualDisplay == null) {
                // Android 14 requirement: MUST register a callback before creating virtual display
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.e(TAG, "MediaProjection stopped by system")
                        isRunning = false
                    }
                }
                mediaProjection!!.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))

                // 3. Create virtual display from MediaProjection pointing to the codec surface
                virtualDisplay = mediaProjection!!.createVirtualDisplay(
                    "ScreenMirror",
                    targetWidth, targetHeight, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    codecSurface, null, null
                )
            } else {
                virtualDisplay?.resize(targetWidth, targetHeight, dpi)
                virtualDisplay?.surface = codecSurface
            }

            mediaCodec!!.start()
            Log.e(TAG, "MediaCodec started and VirtualDisplay attached.")

            val bufferInfo = MediaCodec.BufferInfo()
            val stream = outputStream!!

            needsRotationRestart = false

            while (isRunning && clientSocket?.isConnected == true && !needsRotationRestart) {
                val outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 10000) // 10ms timeout
                if (outputBufferIndex >= 0) {
                    val outputBuffer = mediaCodec!!.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        var flags = 0
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            flags = flags or 0x01
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            flags = flags or 0x02
                        }

                        stream.writeInt(bufferInfo.size)
                        stream.writeByte(flags)
                        stream.writeLong(bufferInfo.presentationTimeUs)

                        val payload = ByteArray(bufferInfo.size)
                        outputBuffer.get(payload)
                        stream.write(payload)
                        stream.flush()
                    }
                    mediaCodec!!.releaseOutputBuffer(outputBufferIndex, false)
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.e(TAG, "MediaCodec output format changed: ${mediaCodec!!.outputFormat}")
                }
            }

            // Cleanup before restart
            try {
                // DO NOT release VirtualDisplay here. Reuse it for rotation.
                virtualDisplay?.surface = null // clear the surface so we can release codec
                mediaCodec?.stop()
                mediaCodec?.release()
                mediaCodec = null
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up codec on rotation: ${e.message}")
            }
        }
        unregisterDisplayListener()
    }

    private fun startAudioServer() {
        try {
            audioServerSocket = ServerSocket(8081)
            Log.e(TAG, "Audio TCP Server started on port 8081")

            while (isRunning) {
                try {
                    Log.e(TAG, "Waiting for audio client connection...")
                    val socket = audioServerSocket!!.accept()
                    Log.e(TAG, "Audio client connected: ${socket.remoteSocketAddress}")

                    audioClientSocket = socket
                    runAudioLoop()
                    Log.e(TAG, "Audio loop finished. Cleaning up socket...")
                    try {
                        audioClientSocket?.close()
                    } catch (e: Exception) {}
                    audioClientSocket = null
                } catch (e: IOException) {
                    Log.e(TAG, "Audio Socket error: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal audio server error: ${e.message}")
        } finally {
            isAudioRunning = false
            try {
                audioClientSocket?.close()
            } catch (e: Exception) {}
            audioClientSocket = null
            try {
                audioServerSocket?.close()
            } catch (e: Exception) {}
        }
    }

    private fun runAudioLoop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "Audio playback capture requires Android 10 (Q) or higher.")
            return
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted for audio capture.")
            return
        }

        val projection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection is null. Cannot capture audio.")
            return
        }

        isAudioRunning = true
        var audioRecord: android.media.AudioRecord? = null
        try {
            val config = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build()

            val sampleRate = 44100
            val channelConfig = android.media.AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioRecord = android.media.AudioRecord.Builder()
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            audioRecord.startRecording()
            Log.e(TAG, "AudioRecord started recording")

            val stream = audioClientSocket!!.getOutputStream()
            val buffer = ByteArray(minBufferSize)

            while (isRunning && isAudioRunning && audioClientSocket?.isConnected == true) {
                val readBytes = audioRecord.read(buffer, 0, buffer.size)
                if (readBytes > 0) {
                    stream.write(buffer, 0, readBytes)
                    stream.flush()
                } else if (readBytes < 0) {
                    Log.e(TAG, "AudioRecord read error: $readBytes")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording loop error: ${e.message}")
        } finally {
            Log.e(TAG, "Stopping audio recording loop...")
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {}
            isAudioRunning = false
        }
    }

    private fun cleanUpSession() {
        Log.e(TAG, "Cleaning up streaming session...")
        isAudioRunning = false
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) { Log.e(TAG, "Error releasing virtual display: ${e.message}") }

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
        } catch (e: Exception) { Log.e(TAG, "Error releasing MediaCodec: ${e.message}") }

        try {
            clientSocket?.close()
            clientSocket = null
        } catch (e: Exception) {}
        outputStream = null

        try {
            audioClientSocket?.close()
            audioClientSocket = null
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        isRunning = false
        isAudioRunning = false
        unregisterConfigReceiver()
        cleanUpSession()
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        try {
            audioServerSocket?.close()
        } catch (e: Exception) {}
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
        Log.e(TAG, "CaptureService destroyed.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Mirroring Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirroring Active")
            .setContentText("Streaming device screen in real-time...")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
