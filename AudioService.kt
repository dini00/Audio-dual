package com.dualaudio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process

class AudioService : Service() {

    companion object {
        const val ACTION_STOP = "com.dualaudio.app.STOP"
        private const val CHANNEL = "dual_audio"
        private const val SAMPLE_RATE = 44100
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Dual Audio", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, AudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("Dual Audio chal raha hai")
            .setContentText("Audio Bluetooth pe bhi ja raha hai")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .setOngoing(true)
            .build()
        startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (android.os.Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        val deviceId = intent.getIntExtra("deviceId", -1)

        if (data == null) { stopAll(); return START_NOT_STICKY }

        try {
            startBridge(code, data, deviceId)
        } catch (e: Exception) {
            e.printStackTrace()
            stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startBridge(code: Int, data: Intent, deviceId: Int) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopAll() }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()

        val inFmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minIn = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(inFmt)
            .setBufferSizeInBytes(minIn * 2)
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        record = rec

        val outFmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
            .setAudioFormat(outFmt)
            .setBufferSizeInBytes(minOut * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
        if (dev != null) trk.preferredDevice = dev
        track = trk

        rec.startRecording()
        trk.play()
        running = true

        worker = Thread {
            val buf = ByteArray(minIn)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) trk.write(buf, 0, n)
                else if (n < 0) break
            }
        }.also { it.start() }
    }

    private fun stopAll() {
        running = false
        try { worker?.join(300) } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        record = null; track = null; projection = null; worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
