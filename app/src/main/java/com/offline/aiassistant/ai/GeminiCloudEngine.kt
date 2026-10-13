package com.offline.aiassistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        userPrompt: String,
        customModelName: String? = null
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext "ERROR: API Key Gemini belum diatur di menu Pengaturan [⚙️]."
        }

        // Gunakan model yang diketik pengguna, atau default resmi gemini-3.8-flash
        val modelTarget = if (!customModelName.isNullOrBlank() && !customModelName.contains("2.5") && !customModelName.contains("1.5")) {
            customModelName.trim().removePrefix("models/")
        } else {
            "gemini-3.8-flash"
        }

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
        val maxRetries = 2

        // Percobaan pengiriman dengan jeda jika terkena antrean server 503
        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                delay(1500) // Tunggu 1.5 detik sebelum mencoba lagi saat server Google padat
            }

            try {
                val generateUrl = "https://generativelanguage.googleapis.com/v1beta/models/$modelTarget:generateContent?key=$cleanKey"
                val url = URL(generateUrl)
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
                    return@withContext "Jawaban kosong dari server Gemini ($modelTarget)."
                } else {
                    val errorStream = conn.errorStream
                    val errorMsg = if (errorStream != null) {
                        BufferedReader(InputStreamReader(errorStream, "UTF-8")).readText()
                    } else "HTTP $responseCode"

                    lastError = "Google AI Studio ($responseCode): $errorMsg"

                    // Jika terkena 503 (Server Padat) atau 429 (Batas Waktu), lakukan retry
                    if (responseCode == 503 || responseCode == 429) {
                        continue
                    }

                    return@withContext "ERROR: $lastError"
                }
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: "Jaringan terputus"
            }
        }

        "ERROR: Server Gemini Cloud ($modelTarget) sedang padat antrean: $lastError"
    }
}
