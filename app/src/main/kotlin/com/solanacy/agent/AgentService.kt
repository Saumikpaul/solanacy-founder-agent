package com.solanacy.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var recordingJob: Job? = null
    private var gitHub: GitHubApi? = null

    private val backendUrl get() = "wss://solanacy-agent-backend.onrender.com?name=${TokenManager.getUser(this)}"

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

    fun toggle() {
        if (isConnected || shouldReconnect) disconnect()
        else connect()
    }

    fun connect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            callback?.onLog("⚠️ Microphone permission not granted!")
            callback?.onStatusChanged("Disconnected")
            return
        }
        shouldReconnect = true
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
                callback?.onLog("🚀 Connected! Waiting for Gemini setup...")
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
                    callback?.onLog("⚠️ Lost connection. Reconnecting in 5s...")
                    callback?.onStatusChanged("Reconnecting...")
                    reconnectJob = scope.launch {
                        delay(5000)
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
        try { wsClient?.connect() }
        catch (e: Exception) { callback?.onLog("⚠️ Connect failed: ${e.message}") }
    }

    fun disconnect() {
        shouldReconnect = false
        geminiReady = false
        pingJob?.cancel()
        reconnectJob?.cancel()
        stopAudioStreaming()
        try { wsClient?.close() } catch (e: Exception) {}
        isConnected = false
        callback?.onLog("Disconnected by user.")
        callback?.onStatusChanged("Disconnected")
    }

    private fun stopAudioStreaming() {
        isRecording.set(false)
        recordingJob?.cancel()
        try { audioRecord?.stop(); audioRecord?.release(); audioRecord = null } catch (e: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release(); audioTrack = null } catch (e: Exception) {}
    }

    private fun startPing() {
        pingJob = scope.launch {
            while (isConnected) {
                delay(20000)
                try { if (wsClient?.isOpen == true) wsClient?.sendPing() }
                catch (e: Exception) {}
            }
        }
    }

    private fun startAudioStreaming() {
        try {
            val sampleRate = 16000
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
                callback?.onLog("⚠️ AudioRecord not supported!")
                return
            }

            val bufferSize = maxOf(minBuffer * 2, 8192)

            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                callback?.onLog("⚠️ Microphone init failed!")
                ar.release()
                return
            }

            val outBufferSize = maxOf(
                AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                8192
            )

            val at = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(24000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(outBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioRecord = ar
            audioTrack = at

            at.play()
            ar.startRecording()
            isRecording.set(true)

            callback?.onLog("🎙️ Microphone active. Speak now!")
            callback?.onStatusChanged("Listening...")

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(bufferSize / 2)
                while (isRecording.get() && isConnected) {
                    try {
                        val read = ar.read(buffer, 0, buffer.size)
                        if (read > 0 && wsClient?.isOpen == true && geminiReady) {
                            val bytes = ByteArray(read * 2)
                            for (i in 0 until read) {
                                bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                                bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                            }
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            val json = JSONObject().apply {
                                put("realtimeInput", JSONObject().apply {
                                    put("mediaChunks", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("mimeType", "audio/pcm;rate=16000")
                                            put("data", base64)
                                        })
                                    })
                                })
                            }
                            wsClient?.send(json.toString())
                            delay(20)
                        }
                    } catch (e: Exception) {
                        if (isRecording.get()) callback?.onLog("⚠️ Audio read error: ${e.message}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            callback?.onLog("⚠️ Audio setup error: ${e.message}")
        }
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
                        try {
                            val audioData = Base64.decode(inlineData.getString("data"), Base64.NO_WRAP)
                            audioTrack?.write(audioData, 0, audioData.size)
                        } catch (e: Exception) {
                            callback?.onLog("⚠️ Audio output error: ${e.message}")
                        }
                    }
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
                    val result = dispatchTool(name, args)
                    responses.put(JSONObject().apply {
                        put("name", name)
                        put("id", call.optString("id"))
                        put("response", JSONObject().apply { put("result", result) })
                    })
                }
                wsClient?.send(JSONObject().apply {
                    put("toolResponse", JSONObject().apply {
                        put("functionResponses", responses)
                    })
                }.toString())
            }

        } catch (e: Exception) {
            callback?.onLog("Parse error: ${e.message}")
        }
    }

    private suspend fun dispatchTool(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "createFile" -> {
                    val path = args.getString("path")
                    val content = args.getString("content")
                    val file = File(getExternalFilesDir(null), path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    callback?.onLog("✅ Created: $path")
                    "File created at $path"
                }
                "readFile" -> {
                    val path = args.getString("path")
                    val file = File(getExternalFilesDir(null), path)
                    if (file.exists()) file.readText().take(2000)
                    else "File not found: $path"
                }
                "editFile" -> {
                    val path = args.getString("path")
                    val content = args.getString("content")
                    val file = File(getExternalFilesDir(null), path)
                    file.writeText(content)
                    callback?.onLog("✅ Edited: $path")
                    "File edited: $path"
                }
                "deleteFile" -> {
                    val path = args.getString("path")
                    File(getExternalFilesDir(null), path).delete()
                    callback?.onLog("🗑️ Deleted: $path")
                    "File deleted: $path"
                }
                "listFiles" -> {
                    val path = args.getString("path")
                    val dir = File(getExternalFilesDir(null), path)
                    if (dir.exists()) dir.listFiles()?.joinToString("\n") { it.name } ?: "Empty"
                    else "Directory not found"
                }
                "createFolder" -> {
                    val path = args.getString("path")
                    File(getExternalFilesDir(null), path).mkdirs()
                    callback?.onLog("📁 Created: $path")
                    "Folder created: $path"
                }
                "showStatus" -> {
                    callback?.onLog("📡 ${args.getString("message")}")
                    "Status shown"
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
                "githubCreateRepo" -> {
                    val repoName = args.getString("name")
                    val desc = args.optString("description", "")
                    val isPrivate = args.optBoolean("isPrivate", false)
                    val result = gitHub?.createRepo(repoName, desc, isPrivate) ?: "GitHub not configured"
                    callback?.onLog("📦 $result")
                    result
                }
                "githubPush" -> {
                    val repo = args.getString("repo")
                    val message = args.getString("message")
                    val files = args.optJSONArray("files")
                    if (files != null && gitHub != null) {
                        val results = mutableListOf<String>()
                        for (i in 0 until files.length()) {
                            val f = files.getJSONObject(i)
                            val path = f.getString("path")
                            val content = f.getString("content")
                            val r = gitHub!!.pushFile(repo, path, content, message)
                            callback?.onLog("🚀 $r")
                            results.add(r)
                        }
                        results.joinToString(", ")
                    } else "GitHub not configured or no files"
                }
                "githubRead" -> {
                    val repo = args.getString("repo")
                    val path = args.optString("path", "README.md")
                    val result = gitHub?.readFile(repo, path) ?: "GitHub not configured"
                    callback?.onLog("📖 Read: $repo/$path")
                    result
                }
                else -> "Unknown: $name"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("agent_channel", "Solanacy Agent", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "agent_channel")
            .setContentTitle("Solanacy Founder AI")
            .setContentText("Agent running in background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        disconnect()
    }
}
