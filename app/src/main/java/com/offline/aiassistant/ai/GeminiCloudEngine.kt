package com.offline.aiassistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiCloudEngine {

    suspend fun askGeminiFlash(
        apiKey: String,
        systemPrompt: String,
        conversationHistory: String,
        userPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext "ERROR: API Key Gemini belum diatur di menu Pengaturan [⚙️]."
        }

        // Daftar model Flash resmi Google yang aktif saat ini (mencegah error HTTP 404)
        val candidateModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash-latest"
        )

        val fullInstruction = buildString {
            append(systemPrompt)
            if (conversationHistory.isNotBlank()) {
                append("\n\n[RIWAYAT OBROLAN]:\n")
                append(conversationHistory)
            }
            append("\n\nPengguna: ")
            append(userPrompt)
            append("\nTOWR:")
        }

        val partObject = JSONObject().put("text", fullInstruction)
        val partsArray = JSONArray().put(partObject)
        val contentObject = JSONObject().put("parts", partsArray)
        val contentsArray = JSONArray().put(contentObject)
        val rootJson = JSONObject().put("contents", contentsArray)
        val jsonPayloadString = rootJson.toString()

        var lastError = ""

        for (modelName in candidateModels) {
            try {
                val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$cleanKey"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(jsonPayloadString)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                    val responseStr = reader.readText()
                    reader.close()

                    val responseJson = JSONObject(responseStr)
                    val candidates = responseJson.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val candidate = candidates.getJSONObject(0)
                        val content = candidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            return@withContext parts.getJSONObject(0).optString("text").trim()
                        }
                    }
                    return@withContext "Jawaban kosong dari server Gemini."
                } else if (responseCode == 404) {
                    lastError = "Model $modelName HTTP 404"
                    continue // Coba model versi berikutnya
                } else {
                    val errorStream = conn.errorStream
                    val errorMsg = if (errorStream != null) {
                        BufferedReader(InputStreamReader(errorStream, "UTF-8")).readText()
                    } else "HTTP $responseCode"
                    return@withContext "ERROR: Kendala Gemini Cloud ($responseCode): $errorMsg"
                }
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: "Jaringan terputus"
            }
        }

        "ERROR: Gagal menghubungi Gemini Cloud: $lastError"
    }
}
