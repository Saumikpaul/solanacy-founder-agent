package com.solanacy.agent

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object MemoryManager {

    private fun getMemoryDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Solanacy/.memory")
        dir.mkdirs()
        return dir
    }

    private fun getMemoryFile(): File {
        return File(getMemoryDir(), "context.json")
    }

    private fun getTaskFile(): File {
        return File(getMemoryDir(), "current_task.txt")
    }

    fun saveEvent(context: Context, role: String, content: String) {
        try {
            val file = getMemoryFile()
            val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()

            // Keep last 50 events only
            val newEntry = JSONObject().apply {
                put("role", role)
                put("content", content.take(500))
                put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            }
            arr.put(newEntry)

            // Trim to last 50
            val trimmed = JSONArray()
            val start = maxOf(0, arr.length() - 50)
            for (i in start until arr.length()) trimmed.put(arr.get(i))

            file.writeText(trimmed.toString())
        } catch (e: Exception) {}
    }

    fun getContextSummary(): String {
        return try {
            val file = getMemoryFile()
            if (!file.exists()) return ""
            val arr = JSONArray(file.readText())
            if (arr.length() == 0) return ""

            val sb = StringBuilder()
            sb.append("PREVIOUS CONVERSATION CONTEXT:\n")
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val role = entry.getString("role")
                val content = entry.getString("content")
                val time = entry.getString("time")
                sb.append("[$time] $role: $content\n")
            }
            sb.toString()
        } catch (e: Exception) { "" }
    }

    fun saveCurrentTask(task: String) {
        try {
            getTaskFile().writeText(task)
        } catch (e: Exception) {}
    }

    fun getCurrentTask(): String {
        return try {
            val file = getTaskFile()
            if (file.exists()) file.readText() else "No active task."
        } catch (e: Exception) { "No active task." }
    }

    fun clearMemory() {
        try { 
            getMemoryFile().delete()
            getTaskFile().delete()
        } catch (e: Exception) {}
    }
}
