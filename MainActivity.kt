package com.dualaudio.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_PERM = 1
    private val REQ_CAPTURE = 2

    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private var devices: List<AudioDeviceInfo> = emptyList()

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
            text = "1) Wired headphones lagao\n2) Bluetooth speaker/earbuds phone se connect karo\n" +
                "3) Neeche device chuno aur Start dabao.\n\n" +
                "Phone ka audio wired me chalta rahega aur Bluetooth pe bhi jayega " +
                "(Bluetooth me thoda delay hoga)."
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        spinner = Spinner(this)
        val refresh = Button(this).apply {
            text = "Devices refresh karo"
            setOnClickListener { loadDevices() }
        }
        val start = Button(this).apply {
            text = "Start"
            setOnClickListener { onStartClicked() }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startService(Intent(this@MainActivity, AudioService::class.java)
                    .setAction(AudioService.ACTION_STOP))
                status.text = "Band kar diya"
            }
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }

        listOf(title, info, spinner, refresh, start, stop, status).forEach { root.addView(it) }
        setContentView(root)
        loadDevices()
    }

    private fun loadDevices() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        }
        val names = if (devices.isEmpty()) listOf("Koi Bluetooth device nahi mila")
        else devices.map { "${it.productName} (id ${it.id})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    private fun onStartClicked() {
        loadDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Pehle Bluetooth device connect karo", Toast.LENGTH_LONG).show()
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
            if (resultCode == RESULT_OK && data != null && devices.isNotEmpty()) {
                val dev = devices[spinner.selectedItemPosition.coerceIn(0, devices.size - 1)]
                val i = Intent(this, AudioService::class.java).apply {
                    putExtra("code", resultCode)
                    putExtra("data", data)
                    putExtra("deviceId", dev.id)
                }
                startForegroundService(i)
                status.text = "Chal raha hai: ${dev.productName}"
            } else {
                status.text = "Permission nahi mili"
            }
        }
    }
}
