package com.solanacy.agent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.solanacy.agent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var agentService: AgentService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as AgentService.AgentBinder
            agentService = b.getService()
            isBound = true
            agentService?.setCallback(object : AgentService.AgentCallback {
                override fun onLog(message: String) {
                    runOnUiThread { appendLog(message) }
                }
                override fun onStatusChanged(status: String) {
                    runOnUiThread { updateStatus(status) }
                }
            })
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissions()

        binding.btnMic.setOnClickListener {
            if (isBound) {
                agentService?.toggle()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            if (isBound) {
                agentService?.disconnect()
                binding.btnDisconnect.isEnabled = false
            }
        }

        val intent = Intent(this, AgentService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun appendLog(message: String) {
        binding.tvTerminal.append("\n$ $message")
        binding.terminalScroll.post {
            binding.terminalScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun updateStatus(status: String) {
        binding.tvStatus.text = status
        when (status) {
            "Connected", "Listening..." -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_connected)
                binding.btnDisconnect.isEnabled = true
            }
            "Disconnected" -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_disconnected)
                binding.btnDisconnect.isEnabled = false
            }
            else -> {
                binding.statusDot.setBackgroundResource(R.drawable.dot_listening)
            }
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_CONTACTS
        )
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(serviceConnection)
    }
}
