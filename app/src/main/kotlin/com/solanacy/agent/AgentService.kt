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
        val encoded = java.net.URLEncoder.encode(memory.take(800), "UTF-8")
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
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Solanacy::WakeLock")
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Solanacy::WifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {}
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) {}
    }

    fun toggle() { if (isConnected || shouldReconnect) disconnect() else connect() }

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
                    reconnectJob = scope.launch { delay(2000); if (shouldReconnect) doConnect() }
                } else {
                    callback?.onLog("Disconnected.")
                    callback?.onStatusChanged("Disconnected")
                    releaseLocks()
                }
            }
            override fun onError(ex: Exception?) {
                callback?.onLog("⚠️ ${ex?.message}")
            }
        }
        try { wsClient?.connect() } catch (e: Exception) { callback?.onLog("⚠️ ${e.message}") }
    }

    fun disconnect() {
        shouldReconnect = false
        geminiReady = false
        pingJob?.cancel()
        reconnectJob?.cancel()
        stopAudioStreaming()
        releaseLocks()
        try { wsClient?.close() } catch (e: Exception) {}
        isConnected = false
        callback?.onLog("Disconnected.")
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
                delay(20000)
                try { if (wsClient?.isOpen == true) wsClient?.sendPing() } catch (e: Exception) {}
            }
        }
    }

    private fun getStorageDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Solanacy")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun startAudioStreaming() {
        try {
            val inputSampleRate = 16000
            val chunkSamples = 4096
            val minBuffer = AudioRecord.getMinBufferSize(inputSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) { callback?.onLog("⚠️ AudioRecord not supported!"); return }

            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, inputSampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, chunkSamples * 4)
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) { callback?.onLog("⚠️ Mic init failed!"); ar.release(); return }

            val outputSampleRate = 24000
            val outMinBuffer = AudioTrack.getMinBufferSize(outputSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val at = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder().setSampleRate(outputSampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(outMinBuffer, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM).build()

            audioRecord = ar; audioTrack = at
            at.play(); ar.startRecording()
            isRecording.set(true)
            callback?.onLog("🎙️ Speak now!")
            callback?.onStatusChanged("Listening...")

            playbackJob = scope.launch(Dispatchers.IO) {
                while (isRecording.get()) {
                    try {
                        val data = audioOutputQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (data != null) {
                            isAiSpeaking.set(true)
                            audioTrack?.write(data, 0, data.size)
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
                            if (Math.sqrt(rms / read) / 32768.0 > 0.01 && isAiSpeaking.get()) stopAiAudio()

                            val bytes = ByteArray(read * 2)
                            for (i in 0 until read) {
                                bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                                bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                            }
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            wsClient?.send(JSONObject().apply {
                                put("realtime_input", JSONObject().apply {
                                    put("media_chunks", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("mime_type", "audio/pcm")
                                            put("data", base64)
                                        })
                                    })
                                })
                            }.toString())
                        }
                    } catch (e: Exception) {
                        if (isRecording.get()) callback?.onLog("⚠️ Audio error: ${e.message}")
                        break
                    }
                }
            }
        } catch (e: Exception) { callback?.onLog("⚠️ Audio setup: ${e.message}") }
    }

    private suspend fun handleMessage(message: String) {
        try {
            val data = JSONObject(message)
            if (data.has("setupComplete")) {
                geminiReady = true
                callback?.onLog("✅ ARIA v1.1 ready!")
                startAudioStreaming()
                return
            }
            val serverContent = data.optJSONObject("serverContent")
            val modelTurn = serverContent?.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    part.optJSONObject("inlineData")?.let {
                        try { audioOutputQueue.offer(Base64.decode(it.getString("data"), Base64.NO_WRAP)) } catch (e: Exception) {}
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
                    MemoryManager.saveEvent(this, "tool", "$name: ${args.toString().take(80)}")
                    val result = dispatchTool(name, args)
                    responses.put(JSONObject().apply {
                        put("name", name)
                        put("id", call.optString("id"))
                        put("response", JSONObject().apply { put("result", result) })
                    })
                }
                wsClient?.send(JSONObject().apply {
                    put("tool_response", JSONObject().apply { put("function_responses", responses) })
                }.toString())
            }
        } catch (e: Exception) { callback?.onLog("Parse error: ${e.message}") }
    }

    private suspend fun dispatchTool(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "createFile" -> {
                    val path = args.getString("path")
                    val content = args.getString("content")
                    val file = File(getStorageDir(), path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    val lines = content.lines().size
                    callback?.onLog("✅ Created: $path [$lines lines]")
                    "Created: ${file.absolutePath} [$lines lines]"
                }
                "appendFile" -> {
                    val path = args.getString("path")
                    val content = args.getString("content")
                    val file = File(getStorageDir(), path)
                    file.parentFile?.mkdirs()
                    file.appendText(content)
                    val lines = file.readLines().size
                    callback?.onLog("📝 Appended: $path [total $lines lines]")
                    "Appended to ${file.absolutePath} [total $lines lines]"
                }
                "readFile" -> {
                    val path = args.getString("path")
                    val file = File(getStorageDir(), path)
                    if (file.exists()) {
                        val content = file.readText()
                        callback?.onLog("📖 Read: $path [${content.lines().size} lines]")
                        content.take(3000)
                    } else "File not found: $path"
                }
                "editFile" -> {
                    val path = args.getString("path")
                    val content = args.getString("content")
                    val file = File(getStorageDir(), path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    val lines = content.lines().size
                    callback?.onLog("✏️ Edited: $path [$lines lines]")
                    "Edited: ${file.absolutePath} [$lines lines]"
                }
                "searchInFile" -> {
                    val path = args.getString("path")
                    val query = args.getString("query")
                    val file = File(getStorageDir(), path)
                    if (!file.exists()) return "File not found: $path"
                    val results = file.readLines().mapIndexedNotNull { idx, line ->
                        if (line.contains(query, ignoreCase = true)) "L${idx+1}: $line" else null
                    }.take(20)
                    callback?.onLog("🔍 Found ${results.size} matches in $path")
                    if (results.isEmpty()) "No matches found" else results.joinToString("\n")
                }
                "moveFile" -> {
                    val from = args.getString("from")
                    val to = args.getString("to")
                    val src = File(getStorageDir(), from)
                    val dst = File(getStorageDir(), to)
                    dst.parentFile?.mkdirs()
                    src.renameTo(dst)
                    callback?.onLog("📦 Moved: $from → $to")
                    "Moved: $from → $to"
                }
                "deleteFile" -> {
                    val path = args.getString("path")
                    File(getStorageDir(), path).delete()
                    callback?.onLog("🗑️ Deleted: $path")
                    "Deleted: $path"
                }
                "listFiles" -> {
                    val path = args.getString("path")
                    val dir = if (path == "/" || path == "") getStorageDir() else File(getStorageDir(), path)
                    if (dir.exists()) {
                        val files = dir.listFiles() ?: emptyArray()
                        callback?.onLog("📁 Listed: $path [${files.size} items]")
                        files.joinToString("\n") { "${if (it.isDirectory) "📁" else "📄"} ${it.name}" }
                    } else "Directory not found: $path"
                }
                "createFolder" -> {
                    val path = args.getString("path")
                    File(getStorageDir(), path).mkdirs()
                    callback?.onLog("📁 Created folder: $path")
                    "Folder created: $path"
                }
                "showStatus" -> {
                    val msg = args.getString("message")
                    callback?.onLog("📊 $msg")
                    "Status shown"
                }
                "updateCurrentTask" -> {
                    val task = args.getString("task")
                    MemoryManager.saveEvent(this, "task", task)
                    callback?.onLog("💾 Memory: ${task.take(60)}")
                    "Task saved to memory"
                }
                "openUrl" -> {
                    val url = args.getString("url")
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    callback?.onLog("🌐 $url")
                    "Opening $url"
                }
                "webSearch" -> {
                    val query = args.getString("query")
                    val url = "https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    callback?.onLog("🔍 $query")
                    "Searching: $query"
                }
                "readWebPage" -> {
                    val url = args.getString("url")
                    try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        val text = conn.inputStream.bufferedReader().readText()
                        val clean = text.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(3000)
                        callback?.onLog("🌐 Read: $url")
                        clean
                    } catch (e: Exception) { "Error: ${e.message}" }
                }
                "runCommand" -> {
                    val cmd = args.getString("command")
                    val workDir = args.optString("workDir", "")
                    callback?.onLog("💻 $ $cmd")
                    val result = TermuxBridge.runCommand(cmd, workDir)
                    callback?.onLog("📤 ${result.take(100)}")
                    result
                }
                "githubCreateRepo" -> {
                    val result = gitHub?.createRepo(args.getString("name"), args.optString("description", ""), args.optBoolean("isPrivate", false)) ?: "GitHub not configured"
                    callback?.onLog("📦 $result")
                    result
                }
                "githubPush" -> {
                    val repo = args.getString("repo")
                    val commitMsg = args.getString("message")
                    val files = args.optJSONArray("files")
                    if (files != null && gitHub != null) {
                        val results = mutableListOf<String>()
                        for (i in 0 until files.length()) {
                            val f = files.getJSONObject(i)
                            val r = gitHub!!.pushFile(repo, f.getString("path"), f.getString("content"), commitMsg)
                            callback?.onLog("🚀 $r")
                            results.add(r)
                        }
                        results.joinToString(", ")
                    } else "GitHub not configured"
                }
                "githubRead" -> {
                    val result = gitHub?.readFile(args.getString("repo"), args.optString("path", "README.md")) ?: "GitHub not configured"
                    callback?.onLog("📖 GitHub read done")
                    result
                }
                "n8nWebhook" -> {
                    val url = args.getString("url")
                    val payload = args.optJSONObject("payload") ?: JSONObject()
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.write(payload.toString().toByteArray())
                    val code = conn.responseCode
                    callback?.onLog("⚡ n8n: $code")
                    "Webhook triggered: $code"
                }
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) { "Error in $name: ${e.message}" }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("agent_channel", "Solanacy Agent", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "agent_channel")
            .setContentTitle("Solanacy Founder AI v1.1")
            .setContentText("ARIA is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        disconnect()
    }
}
// v1.1 build Sat Feb 28 00:22:41 IST 2026
