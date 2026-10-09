package com.dualaudio.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_PERM = 1
    private val REQ_CAPTURE = 2

    private lateinit var cbWired: CheckBox
    private lateinit var cbBt: CheckBox
    private lateinit var status: TextView

    private var wiredDev: AudioDeviceInfo? = null
    private var btDev: AudioDeviceInfo? = null

    private val wiredTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )
    private val btTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Dual Audio"
            textSize = 26f
        }
        val info = TextView(this).apply {
            text = "Jis device pe audio ki copy bhejni hai usko tick karo. " +
                "Dono tick karoge to dono pe copy jayegi."
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        cbWired = CheckBox(this).apply { text = "Headphone (wired / USB)"; textSize = 18f }
        cbBt = CheckBox(this).apply { text = "Bluetooth"; textSize = 18f }

        val refresh = Button(this).apply {
            text = "Devices refresh karo"
            setOnClickListener { loadDevices() }
        }
        val start = Button(this).apply {
            text = "Start"
            setOnClickListener { onStartClicked() }
        }
        val test = Button(this).apply {
            text = "Test tone (dono device)"
            setOnClickListener { playTestTone() }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, AudioService::class.java)
                        .setAction(AudioService.ACTION_STOP)
                )
                status.text = "Band kar diya"
                loadDevices()
            }
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }

        listOf(title, info, cbWired, cbBt, refresh, start, stop, test, status).forEach { root.addView(it) }
        setContentView(root)
        loadDevices()
    }

    private fun loadDevices() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        wiredDev = outs.firstOrNull { wiredTypes.contains(it.type) }
        btDev = outs.firstOrNull { btTypes.contains(it.type) }

        cbWired.text = "Headphone: " + (wiredDev?.productName ?: "nahi mila")
        cbBt.text = "Bluetooth: " + (btDev?.productName ?: "nahi mila")
        if (wiredDev == null) cbWired.isChecked = false
        if (btDev == null) cbBt.isChecked = false
    }

    private fun makeToneTrack(dev: AudioDeviceInfo, freq: Double): AudioTrack {
        val rate = 44100
        val n = rate * 6
        val data = ShortArray(n) { i ->
            (Math.sin(2.0 * Math.PI * freq * i / rate) * 9000).toInt().toShort()
        }
        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        trk.write(data, 0, n)
        trk.preferredDevice = dev
        return trk
    }

    private fun playTestTone() {
        loadDevices()
        val w = wiredDev
        val b = btDev
        if (w == null || b == null) {
            Toast.makeText(this, "Pehle headphone aur Bluetooth dono connect karo", Toast.LENGTH_LONG).show()
            return
        }
        val low = makeToneTrack(w, 440.0)
        val high = makeToneTrack(b, 880.0)
        low.play()
        high.play()
        status.text = "Test chal raha hai (6 sec): headphone ko LOW aawaz, Bluetooth ko HIGH aawaz"
        Handler(Looper.getMainLooper()).postDelayed({
            val rw = low.routedDevice?.productName ?: "pata nahi"
            val rb = high.routedDevice?.productName ?: "pata nahi"
            Toast.makeText(this, "Low -> $rw | High -> $rb", Toast.LENGTH_LONG).show()
        }, 800)
        Handler(Looper.getMainLooper()).postDelayed({
            try { low.stop(); low.release() } catch (_: Exception) {}
            try { high.stop(); high.release() } catch (_: Exception) {}
            status.text = "Test khatam"
        }, 6500)
    }

    private fun onStartClicked() {
        loadDevices()
        if (!cbWired.isChecked && !cbBt.isChecked) {
            Toast.makeText(this, "Kam se kam ek device tick karo", Toast.LENGTH_LONG).show()
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) needed += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERM)
        } else {
            askCapture()
        }
    }

    private fun askCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERM) {
            val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (micOk) askCapture()
            else Toast.makeText(this, "Audio permission zaruri hai", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val wId = if (cbWired.isChecked) wiredDev?.id ?: -1 else -1
                val bId = if (cbBt.isChecked) btDev?.id ?: -1 else -1
                if (wId == -1 && bId == -1) {
                    status.text = "Tick kiya hua device connect nahi hai"
                    return
                }
                val i = Intent(this, AudioService::class.java).apply {
                    putExtra("code", resultCode)
                    putExtra("data", data)
                    putExtra("wiredId", wId)
                    putExtra("btId", bId)
                }
                startForegroundService(i)
                val parts = mutableListOf<String>()
                if (wId != -1) parts += "Headphone"
                if (bId != -1) parts += "Bluetooth"
                status.text = "Chal raha hai: " + parts.joinToString(" + ")
            } else {
                status.text = "Permission nahi mili"
            }
        }
    }
}
