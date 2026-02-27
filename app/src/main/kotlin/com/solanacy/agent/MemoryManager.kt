package com.solanacy.agent

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object MemoryManager {

    private fun getMemoryFile(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Solanacy/.memory")
        dir.mkdirs()
        return File(dir, "context.json")
    }

    private fun getCurrentTaskFile(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Solanacy/.memory")
        dir.mkdirs()
        return File(dir, "current_task.txt")
    }

    fun saveEvent(context: Context, role: String, content: String) {
        try {
            val file = getMemoryFile()
            val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            val newEntry = JSONObject().apply {
                put("role", role)
                put("content", content.take(500))
                put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            }
            arr.put(newEntry)
            val trimmed = JSONArray()
            val start = maxOf(0, arr.length() - 50)
            for (i in start until arr.length()) trimmed.put(arr.get(i))
            file.writeText(trimmed.toString())
        } catch (e: Exception) {}
    }

    fun saveCurrentTask(task: String) {
        try {
            getCurrentTaskFile().writeText(task)
            saveEvent(null as? Context ?: return, "task", task)
        } catch (e: Exception) {}
    }

    fun getCurrentTask(): String {
        return try {
            val file = getCurrentTaskFile()
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) { "" }
    }

    fun getContextSummary(): String {
        return try {
            val file = getMemoryFile()
            if (!file.exists()) return ""
            val arr = JSONArray(file.readText())
            if (arr.length() == 0) return ""
            val sb = StringBuilder()
            sb.append("PREVIOUS SESSION:\n")
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                sb.append("[${entry.getString("time")}] ${entry.getString("role")}: ${entry.getString("content")}\n")
            }
            val currentTask = getCurrentTask()
            if (currentTask.isNotBlank()) sb.append("\nCURRENT TASK: $currentTask\n")
            sb.toString()
        } catch (e: Exception) { "" }
    }

    fun clearMemory() {
        try { getMemoryFile().delete() } catch (e: Exception) {}
        try { getCurrentTaskFile().delete() } catch (e: Exception) {}
    }
}
