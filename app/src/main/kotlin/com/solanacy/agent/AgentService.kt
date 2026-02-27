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
    private val audioOutputQueue = LinkedBlockingQueue<ByteArray>(1000)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private var gitHub: GitHubApi? = null
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
            wakeLock?.acquire(12 * 60 * 60 * 1000L) 
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Solanacy::AgentWifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {}
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) {}
    }

    fun toggle() {
        if (isConnected || shouldReconnect) disconnect() else connect()
    }

    fun connect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        shouldReconnect = true
        acquireLocks()
        doConnect()
    }

    private fun doConnect() {
        geminiReady = false
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
                    reconnectJob = scope.launch { delay(2000); if (shouldReconnect) doConnect() }
                } else {
                    callback?.onStatusChanged("Disconnected")
                }
            }
            override fun onError(ex: Exception?) {}
        }
        wsClient?.connectionLostTimeout = 120 
        try { wsClient?.connect() } catch (e: Exception) {}
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
                delay(15000)
                try { if (wsClient?.isOpen == true) wsClient?.sendPing() } catch (e: Exception) {}
            }
        }
    }

    private fun getStorageDir() = File(Environment.getExternalStorageDirectory(), "Solanacy").apply { mkdirs() }

    private fun startAudioStreaming() {
        try {
            val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 16384))
            val at = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(32768)
                .setTransferMode(AudioTrack.MODE_STREAM).build()

            audioRecord = ar
            audioTrack = at
            at.play()
            ar.startRecording()
            isRecording.set(true)
            callback?.onStatusChanged("Listening...")

            playbackJob = scope.launch(Dispatchers.IO) {
                while (isRecording.get()) {
                    try {
                        val data = audioOutputQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (data != null) { isAiSpeaking.set(true); audioTrack?.write(data, 0, data.size) } 
                        else if (audioOutputQueue.isEmpty()) isAiSpeaking.set(false)
                    } catch (e: Exception) { break }
                }
            }

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(4096)
                while (isRecording.get() && isConnected) {
                    try {
                        val read = ar.read(buffer, 0, 4096)
                        if (read > 0 && wsClient?.isOpen == true && geminiReady) {
                            var rms = 0.0
                            for (i in 0 until read) rms += buffer[i].toDouble() * buffer[i].toDouble()
                            val level = Math.sqrt(rms / read) / 32768.0
                            
                            // Echo cancellation
                            if (level > 0.015 && isAiSpeaking.get()) stopAiAudio()

                            // 🚀 FIX: NOISE GATE FOR INSTANT RESPONSE
                            // Jodi sound level 0.01 er niche hoy (mane shudhu background noise), 
                            // tahole pure silence (0) pathabo jate Gemini instantly kotha shuru kore!
                            if (level < 0.01) {
                                for (i in 0 until read) {
                                    buffer[i] = 0
                                }
                            }

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
            if (data.has("setupComplete")) { geminiReady = true; startAudioStreaming(); return }
            data.optJSONObject("serverContent")?.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    parts.getJSONObject(i).optJSONObject("inlineData")?.let { try { audioOutputQueue.offer(Base64.decode(it.getString("data"), Base64.NO_WRAP)) } catch (e: Exception) {} }
                    val text = parts.getJSONObject(i).optString("text", "")
                    if (text.isNotBlank()) MemoryManager.saveEvent(this, "assistant", text)
                }
            }
            data.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                val responses = JSONArray()
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val name = call.getString("name")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    callback?.onLog("🔧 $name")
                    responses.put(JSONObject().apply { put("name", name); put("id", call.optString("id")); put("response", JSONObject().apply { put("result", dispatchTool(name, args)) }) })
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
                "readWebPage" -> {
                    val urlStr = args.getString("url")
                    callback?.onLog("📖 Reading Docs: $urlStr")
                    try {
                        val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        val rawHtml = conn.inputStream.bufferedReader().use { it.readText() }
                        val cleanText = rawHtml.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ")
                        cleanText.take(8000) 
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
                        for (i in 0 until files.length()) { gitHub!!.pushFile(repo, files.getJSONObject(i).getString("path"), files.getJSONObject(i).getString("content"), commitMsg) }
                        callback?.onLog("🚀 Pushed to $repo"); "Pushed to $repo"
                    } else "No GitHub/files"
                }
                "githubRead" -> gitHub?.readFile(args.getString("repo"), args.optString("path", "README.md")) ?: "No GitHub"
                "runCommand" -> {
                    val cmd = args.getString("command")
                    callback?.onLog("💻 Run: $cmd")
                    TermuxBridge.runCommand(cmd, args.optString("workDir", ""))
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

    private fun createNotificationChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("agent_channel", "Solanacy Agent", NotificationManager.IMPORTANCE_LOW)) }
    private fun buildNotification() = NotificationCompat.Builder(this, "agent_channel").setContentTitle("Solanacy Founder AI").setContentText("Agent Pro Max Running").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
    override fun onDestroy() { super.onDestroy(); scope.cancel(); disconnect() }
}
