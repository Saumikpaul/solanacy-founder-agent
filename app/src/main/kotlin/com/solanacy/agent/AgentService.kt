package com.solanacy.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import android.util.Base64
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class AgentService : Service() {

    private val binder = AgentBinder()
    private var callback: AgentCallback? = null
    private var wsClient: WebSocketClient? = null
    private var isConnected = false
    private var shouldReconnect = false
    private var geminiReady = false
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val isRecording = AtomicBoolean(false)
    private val isAiSpeaking = AtomicBoolean(false)
    
    // 🚀 High-performance Audio Jitter Buffer
    private val audioOutputQueue = LinkedBlockingQueue<ByteArray>(1000)
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private var gitHub: GitHubApi? = null
    
    // 🚀 Power & Wifi Locks
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val backendUrl get(): String {
        val user = TokenManager.getUser(this)
        val memory = MemoryManager.getContextSummary()
        val currentTask = MemoryManager.getCurrentTask()
        val combinedContext = "CURRENT ACTIVE TASK:\n$currentTask\n\n$memory"
        val encoded = java.net.URLEncoder.encode(combinedContext.take(1500), "UTF-8")
        return "wss://solanacy-agent-backend.onrender.com?name=$user&memory=$encoded"
    }

    interface AgentCallback {
        fun onLog(message: String)
        fun onStatusChanged(status: String)
    }

    inner class AgentBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    override fun onBind(intent: Intent): IBinder = binder
    fun setCallback(cb: AgentCallback) { callback = cb }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        val token = TokenManager.getToken(this)
        val user = TokenManager.getUser(this)
        if (token.isNotBlank()) gitHub = GitHubApi(token, user)
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Solanacy::AgentWakeLock")
            wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours lock
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Solanacy::AgentWifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {
            callback?.onLog("⚠️ Lock info: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) {}
    }

    fun toggle() {
        if (isConnected || shouldReconnect) disconnect()
        else connect()
    }

    fun connect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callback?.onLog("⚠️ Microphone permission not granted!")
            callback?.onStatusChanged("Disconnected")
            return
        }
        shouldReconnect = true
        acquireLocks()
        doConnect()
    }

    private fun doConnect() {
        geminiReady = false
        callback?.onLog("Connecting to Solanacy Agent...")
        callback?.onStatusChanged("Connecting...")

        val uri = URI(backendUrl)
        wsClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                isConnected = true
                callback?.onLog("🚀 Connected!")
                callback?.onStatusChanged("Connecting...")
                startPing()
            }

            override fun onMessage(message: String?) {
                message ?: return
                scope.launch { handleMessage(message) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                isConnected = false
                geminiReady = false
                stopAudioStreaming()
                pingJob?.cancel()
                if (shouldReconnect) {
                    callback?.onStatusChanged("Reconnecting...")
                    reconnectJob = scope.launch {
                        delay(2000)
                        if (shouldReconnect) doConnect()
                    }
                } else {
                    callback?.onLog("Disconnected.")
                    callback?.onStatusChanged("Disconnected")
                }
            }

            override fun onError(ex: Exception?) {
                callback?.onLog("⚠️ Error: ${ex?.message}")
            }
        }
        
        wsClient?.connectionLostTimeout = 120 
        try { wsClient?.connect() } catch (e: Exception) { callback?.onLog("⚠️ Connect failed: ${e.message}") }
    }

    fun disconnect() {
        shouldReconnect = false
        geminiReady = false
        pingJob?.cancel()
        reconnectJob?.cancel()
        stopAudioStreaming()
        try { wsClient?.close() } catch (e: Exception) {}
        isConnected = false
        releaseLocks()
        callback?.onLog("Disconnected by user.")
        callback?.onStatusChanged("Disconnected")
    }

    private fun stopAudioStreaming() {
        isRecording.set(false)
        isAiSpeaking.set(false)
        recordingJob?.cancel()
        playbackJob?.cancel()
        audioOutputQueue.clear()
        try { audioRecord?.stop(); audioRecord?.release(); audioRecord = null } catch (e: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release(); audioTrack = null } catch (e: Exception) {}
    }

    private fun stopAiAudio() {
        isAiSpeaking.set(false)
        audioOutputQueue.clear()
        try { audioTrack?.stop(); audioTrack?.flush(); audioTrack?.play() } catch (e: Exception) {}
    }

    private fun startPing() {
        pingJob = scope.launch {
            while (isConnected) {
                delay(15000) // Fast ping
                try { if (wsClient?.isOpen == true) wsClient?.sendPing() } catch (e: Exception) {}
            }
        }
    }

    private fun getStorageDir() = File(Environment.getExternalStorageDirectory(), "Solanacy").apply { mkdirs() }

    private fun startAudioStreaming() {
        try {
            val inputSampleRate = 16000
            val chunkSamples = 4096
            val minBuffer = AudioRecord.getMinBufferSize(inputSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return

            val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, inputSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, chunkSamples * 4))
            if (ar.state != AudioRecord.STATE_INITIALIZED) return

            val outputSampleRate = 24000
            val outMinBuffer = AudioTrack.getMinBufferSize(outputSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            
            val at = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder().setSampleRate(outputSampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(outMinBuffer, 32768))
                .setTransferMode(AudioTrack.MODE_STREAM).build()

            audioRecord = ar
            audioTrack = at
            at.play()
            ar.startRecording()
            isRecording.set(true)
            callback?.onLog("🎙️ Microphone active. Speak now!")
            callback?.onStatusChanged("Listening...")

            playbackJob = scope.launch(Dispatchers.IO) {
                while (isRecording.get()) {
                    try {
                        val data = audioOutputQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (data != null) {
                            isAiSpeaking.set(true)
                            audioTrack?.write(data, 0, data.size)
                        } else {
                            if (audioOutputQueue.isEmpty()) isAiSpeaking.set(false)
                        }
                    } catch (e: Exception) { break }
                }
            }

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(chunkSamples)
                while (isRecording.get() && isConnected) {
                    try {
                        val read = ar.read(buffer, 0, chunkSamples)
                        if (read > 0 && wsClient?.isOpen == true && geminiReady) {
                            var rms = 0.0
                            for (i in 0 until read) rms += buffer[i].toDouble() * buffer[i].toDouble()
                            val level = Math.sqrt(rms / read) / 32768.0
                            if (level > 0.01 && isAiSpeaking.get()) stopAiAudio()

                            val bytes = ByteArray(read * 2)
                            for (i in 0 until read) {
                                bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                                bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                            }
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            wsClient?.send(JSONObject().apply { put("realtime_input", JSONObject().apply { put("media_chunks", JSONArray().apply { put(JSONObject().apply { put("mime_type", "audio/pcm"); put("data", base64) }) }) }) }.toString())
                        }
                    } catch (e: Exception) { break }
                }
            }
        } catch (e: Exception) {}
    }

    private suspend fun handleMessage(message: String) {
        try {
            val data = JSONObject(message)

            if (data.has("setupComplete")) {
                geminiReady = true
                callback?.onLog("✅ Gemini ready!")
                startAudioStreaming()
                return
            }

            val serverContent = data.optJSONObject("serverContent")
            val modelTurn = serverContent?.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        try { audioOutputQueue.offer(Base64.decode(inlineData.getString("data"), Base64.NO_WRAP)) } catch (e: Exception) {}
                    }
                    val text = part.optString("text", "")
                    if (text.isNotBlank()) MemoryManager.saveEvent(this, "assistant", text)
                }
            }

            val toolCall = data.optJSONObject("toolCall")
            val functionCalls = toolCall?.optJSONArray("functionCalls")
            if (functionCalls != null) {
                val responses = JSONArray()
                for (i in 0 until functionCalls.length()) {
                    val call = functionCalls.getJSONObject(i)
                    val name = call.getString("name")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    callback?.onLog("🔧 $name")
                    MemoryManager.saveEvent(this, "tool", "$name: ${args.toString().take(100)}")
                    val result = dispatchTool(name, args)
                    responses.put(JSONObject().apply {
                        put("name", name)
                        put("id", call.optString("id"))
                        put("response", JSONObject().apply { put("result", result) })
                    })
                }
                wsClient?.send(JSONObject().apply { put("tool_response", JSONObject().apply { put("function_responses", responses) }) }.toString())
            }
        } catch (e: Exception) {}
    }

    private suspend fun dispatchTool(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "createFile" -> { val f = File(getStorageDir(), args.getString("path")).apply { parentFile?.mkdirs(); writeText(args.getString("content")) }; callback?.onLog("✅ Created: ${f.name}"); "File created" }
                "readFile" -> { val f = File(getStorageDir(), args.getString("path")); if (f.exists()) f.readText().take(5000) else "Not found" }
                "editFile" -> { File(getStorageDir(), args.getString("path")).apply { parentFile?.mkdirs(); writeText(args.getString("content")) }; callback?.onLog("✅ Edited: ${args.getString("path")}"); "File edited" }
                "deleteFile" -> { File(getStorageDir(), args.getString("path")).delete(); "Deleted" }
                "listFiles" -> { val d = File(getStorageDir(), args.optString("path", "")); if (d.exists()) d.listFiles()?.joinToString("\n") { it.name } ?: "Empty" else "Not found" }
                "createFolder" -> { File(getStorageDir(), args.getString("path")).mkdirs(); "Folder created" }
                "showStatus" -> { callback?.onLog("📡 ${args.getString("message")}"); "Status shown" }
                "updateCurrentTask" -> { val task = args.getString("task"); MemoryManager.saveCurrentTask(task); callback?.onLog("📌 Memory: ${task.take(30)}..."); "Task saved" }
                
                // 🚀 NEW: Web Scraper Implementation
                "readWebPage" -> {
                    val urlStr = args.getString("url")
                    callback?.onLog("📖 Reading Docs: $urlStr")
                    try {
                        val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        val rawHtml = conn.inputStream.bufferedReader().use { it.readText() }
                        // Basic regex to strip HTML tags and extra spaces so Gemini can easily read it
                        val cleanText = rawHtml.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ")
                        cleanText.take(8000) // 8000 characters limit to save tokens
                    } catch (e: Exception) { "Error fetching URL: ${e.message}" }
                }
                
                "openUrl" -> { val url = args.getString("url"); val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); callback?.onLog("🌐 Open $url"); "Opened" }
                "webSearch" -> { val q = args.getString("query"); val url = "https://www.google.com/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}"; startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); callback?.onLog("🔍 $q"); "Searched" }
                "githubCreateRepo" -> gitHub?.createRepo(args.getString("name"), args.optString("description", ""), args.optBoolean("isPrivate", false)) ?: "No GitHub"
                "githubPush" -> {
                    val repo = args.getString("repo")
                    val commitMsg = args.getString("message")
                    val files = args.optJSONArray("files")
                    if (files != null && gitHub != null) {
                        for (i in 0 until files.length()) {
                            val f = files.getJSONObject(i)
                            gitHub!!.pushFile(repo, f.getString("path"), f.getString("content"), commitMsg)
                        }
                        callback?.onLog("🚀 Pushed to $repo"); "Pushed to $repo"
                    } else "No GitHub/files"
                }
                "githubRead" -> gitHub?.readFile(args.getString("repo"), args.optString("path", "README.md")) ?: "No GitHub"
                "runCommand" -> {
                    val cmd = args.getString("command")
                    callback?.onLog("💻 Run: $cmd")
                    val res = TermuxBridge.runCommand(cmd, args.optString("workDir", ""))
                    callback?.onLog("📤 Result ready")
                    res
                }
                "n8nWebhook" -> {
                    val url = java.net.URL(args.getString("url"))
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.write((args.optJSONObject("payload") ?: JSONObject()).toString().toByteArray())
                    callback?.onLog("⚡ n8n: ${conn.responseCode}"); "Triggered: ${conn.responseCode}"
                }
                else -> "Unknown: $name"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("agent_channel", "Solanacy Agent", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "agent_channel")
            .setContentTitle("Solanacy Founder AI")
            .setContentText("Agent Pro Max Running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        disconnect()
    }
}
