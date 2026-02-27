package com.solanacy.agent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var agentService: AgentService? = null
    private var isBound = false
    private lateinit var tvTerminal: TextView
    private lateinit var btnMic: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var tvStatus: TextView

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as AgentService.AgentBinder
            agentService = b.getService()
            isBound = true
            agentService?.setCallback(object : AgentService.AgentCallback {
                override fun onLog(message: String) {
                    runOnUiThread {
                        tvTerminal.append("\n› $message")
                        val scrollView = tvTerminal.parent as? android.widget.ScrollView
                        scrollView?.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
                    }
                }
                override fun onStatusChanged(status: String) {
                    runOnUiThread {
                        tvStatus.text = "● $status".uppercase()
                        when {
                            status.contains("Listen") -> {
                                tvStatus.setTextColor(0xFF00E5A0.toInt())
                                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF003322.toInt())
                            }
                            status.contains("Connect") -> {
                                tvStatus.setTextColor(0xFF00BFFF.toInt())
                                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF001A2E.toInt())
                            }
                            status.contains("Reconnect") -> {
                                tvStatus.setTextColor(0xFFFFAA00.toInt())
                                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A1A00.toInt())
                            }
                            else -> {
                                tvStatus.setTextColor(0xFFFF4444.toInt())
                                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A0000.toInt())
                            }
                        }
                    }
                }
            })
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTerminal = findViewById(R.id.tvTerminal)
        btnMic = findViewById(R.id.btnMic)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvStatus = findViewById(R.id.tvStatus)

        // Start and bind service
        val serviceIntent = Intent(this, AgentService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        btnMic.setOnClickListener {
            requestAllPermissions()
        }

        btnDisconnect.setOnClickListener {
            agentService?.disconnect()
        }

        // Request permissions on start
        requestAllPermissions()
    }

    private fun requestAllPermissions() {
        // Step 1: Basic permissions
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            return
        }

        // Step 2: All files access (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, 101)
                return
            }
        }

        // All permissions granted — connect!
        agentService?.connect()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            // Continue to check all files access
            requestAllPermissions()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101) {
            // After all files access — connect
            agentService?.connect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
