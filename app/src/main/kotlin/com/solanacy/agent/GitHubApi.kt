package com.solanacy.agent

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GitHubApi(private val token: String, private val username: String) {

    private fun request(method: String, endpoint: String, body: JSONObject? = null): JSONObject? {
        return try {
            val url = URL("https://api.github.com$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            if (body != null) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = BufferedReader(InputStreamReader(stream)).readText()
            if (response.isBlank()) JSONObject().put("status", code)
            else JSONObject(response)
        } catch (e: Exception) {
            JSONObject().put("error", e.message)
        }
    }

    suspend fun createRepo(name: String, description: String, isPrivate: Boolean): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("name", name)
                put("description", description)
                put("private", isPrivate)
                put("auto_init", false)
            }
            val result = request("POST", "/user/repos", body)
            val htmlUrl = result?.optString("html_url", "")
            if (!htmlUrl.isNullOrBlank()) "Repo created: $htmlUrl"
            else "Error: ${result?.optString("message", "Unknown error")}"
        }

    suspend fun pushFile(repo: String, path: String, content: String, message: String): String =
        withContext(Dispatchers.IO) {
            // Check if file exists to get sha
            val existing = request("GET", "/repos/$username/$repo/contents/$path")
            val sha = existing?.optString("sha", "")

            val encoded = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("message", message)
                put("content", encoded)
                if (!sha.isNullOrBlank()) put("sha", sha)
            }
            val result = request("PUT", "/repos/$username/$repo/contents/$path", body)
            if (result?.has("content") == true) "Pushed: $path to $repo"
            else "Error: ${result?.optString("message", "Unknown error")}"
        }

    suspend fun readFile(repo: String, path: String): String =
        withContext(Dispatchers.IO) {
            val result = request("GET", "/repos/$username/$repo/contents/$path")
            val encoded = result?.optString("content", "")
            if (!encoded.isNullOrBlank()) {
                val clean = encoded.replace("\n", "")
                String(Base64.decode(clean, Base64.DEFAULT))
            } else "Error: ${result?.optString("message", "Not found")}"
        }

    suspend fun listRepos(): String =
        withContext(Dispatchers.IO) {
            val url = URL("https://api.github.com/user/repos?per_page=20&sort=updated")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            val arr = JSONArray(response)
            (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).getString("full_name") }
        }

    suspend fun deleteRepo(repo: String): String =
        withContext(Dispatchers.IO) {
            val result = request("DELETE", "/repos/$username/$repo")
            "Repo deleted: $repo"
        }
}
