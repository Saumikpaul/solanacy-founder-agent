package com.solanacy.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import android.util.Base64
import java.io.File

class AgentService : Service() {

    private val binder = AgentBinder()
    private var callback: AgentCallback? = null
    private var wsClient: WebSocketClient? = null
    private var isConnected = false
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val BACKEND_URL = "wss://solanacy-agent-backend.onrender.com?name=Saumik"

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
    }

    fun toggle() {
        if (isConnected) disconnect()
        else connect()
    }

    fun connect() {
        callback?.onLog("Connecting to Solanacy Agent Backend...")
        callback?.onStatusChanged("Connecting...")

        val uri = URI(BACKEND_URL)
        wsClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                isConnected = true
                callback?.onLog("Connected! 🚀 Solanacy Founder AI is ready.")
                callback?.onStatusChanged("Listening...")
                startAudioStreaming()
            }

            override fun onMessage(message: String?) {
                message ?: return
                scope.launch { handleMessage(message) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                isConnected = false
                isRecording = false
                callback?.onLog("Disconnected.")
                callback?.onStatusChanged("Disconnected")
            }

            override fun onError(ex: Exception?) {
                callback?.onLog("Error: ${ex?.message}")
                callback?.onStatusChanged("Error")
            }
        }
        wsClient?.connect()
    }

    fun disconnect() {
        isRecording = false
        wsClient?.close()
        audioRecord?.stop()
        audioTrack?.stop()
        isConnected = false
        callback?.onLog("Disconnected by user.")
        callback?.onStatusChanged("Disconnected")
    }

    private fun startAudioStreaming() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioTrack = AudioTrack.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(24000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ))
            .build()

        audioTrack?.play()
        audioRecord?.startRecording()
        isRecording = true

        scope.launch {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (read > 0 && wsClient?.isOpen == true) {
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val json = JSONObject().apply {
                        put("realtime_input", JSONObject().apply {
                            put("media_chunks", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("mime_type", "audio/pcm")
                                    put("data", base64)
                                })
                            })
                        })
                    }
                    wsClient?.send(json.toString())
                }
            }
        }
    }

    private suspend fun handleMessage(message: String) {
        try {
            val data = JSONObject(message)

            // Audio output
            val serverContent = data.optJSONObject("serverContent")
            val modelTurn = serverContent?.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val audioData = Base64.decode(inlineData.getString("data"), Base64.NO_WRAP)
                        audioTrack?.write(audioData, 0, audioData.size)
                    }
                }
            }

            // Tool calls
            val toolCall = data.optJSONObject("toolCall")
            val functionCalls = toolCall?.optJSONArray("functionCalls")
            if (functionCalls != null) {
                val responses = JSONArray()
                for (i in 0 until functionCalls.length()) {
                    val call = functionCalls.getJSONObject(i)
                    val name = call.getString("name")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    callback?.onLog("🔧 Tool: $name")
                    val result = dispatchTool(name, args)
                    responses.put(JSONObject().apply {
                        put("name", name)
                        put("id", call.optString("id"))
                        put("response", JSONObject().apply {
                            put("result", result)
                        })
                    })
                }
                val response = JSONObject().apply {
                    put("tool_response", JSONObject().apply {
                        put("function_responses", responses)
                    })
                }
                wsClient?.send(response.toString())
            }

        } catch (e: Exception) {
            callback?.onLog("Parse error: ${e.message}")
        }
    }

    private fun dispatchTool(name: String, args: JSONObject): String {
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
                    val file = File(getExternalFilesDir(null), path)
                    file.delete()
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
                    val dir = File(getExternalFilesDir(null), path)
                    dir.mkdirs()
                    callback?.onLog("📁 Created folder: $path")
                    "Folder created: $path"
                }
                "showStatus" -> {
                    val msg = args.getString("message")
                    callback?.onLog("📡 $msg")
                    "Status shown"
                }
                "openUrl" -> {
                    val url = args.getString("url")
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    callback?.onLog("🌐 Opening: $url")
                    "Opening $url"
                }
                "webSearch" -> {
                    val query = args.getString("query")
                    val url = "https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    callback?.onLog("🔍 Searching: $query")
                    "Searching for $query"
                }
                "githubCreateRepo" -> {
                    val repoName = args.getString("name")
                    callback?.onLog("📦 GitHub repo creation requested: $repoName")
                    "GitHub repo creation: use gh CLI with repo name $repoName"
                }
                "githubPush" -> {
                    val repo = args.getString("repo")
                    val msg = args.getString("message")
                    callback?.onLog("🚀 GitHub push requested: $repo — $msg")
                    "GitHub push requested for $repo"
                }
                "githubRead" -> {
                    val repo = args.getString("repo")
                    val path = args.optString("path", "")
                    callback?.onLog("📖 GitHub read: $repo/$path")
                    "GitHub read requested: $repo/$path"
                }
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Tool error: ${e.message}"
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "agent_channel",
            "Solanacy Agent",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "agent_channel")
            .setContentTitle("Solanacy Founder AI")
            .setContentText("Agent is running in background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        disconnect()
    }
}
