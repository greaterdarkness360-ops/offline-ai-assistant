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

    /**
     * PING DIAGNOSTIK REAL-TIME:
     * Menguji koneksi langsung ke Google AI Studio tanpa perlu mengirim obrolan manual.
     * Mengembalikan Pair(isSuccess, diagnosticMessage)
     */
    suspend fun testConnection(
        apiKey: String,
        modelName: String = "gemini-3.8-flash"
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Pair(false, "API Key belum diisi di kolom Pengaturan.")
        }

        val cleanModel = modelName.trim().removePrefix("models/").ifBlank { "gemini-3.8-flash" }
        val testUrl = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$cleanKey"
        val startTime = System.currentTimeMillis()

        try {
            val url = URL(testUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.doOutput = true

            // Kirim payload pengujian ringan
            val testPayload = "{\"contents\":[{\"parts\":[{\"text\":\"ping\"}]}]}"
            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(testPayload)
                writer.flush()
            }

            val code = conn.responseCode
            val latency = System.currentTimeMillis() - startTime

            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    Pair(true, "🟢 Terhubung Sukses! Gemini ($cleanModel) Siap • Latensi: ${latency}ms")
                }
                503 -> {
                    Pair(false, "🟡 Server Padat (HTTP 503): Antrean Free Tier Google sedang penuh sesaat. Coba beberapa saat lagi.")
                }
                429 -> {
                    Pair(false, "🟡 Limit Kecepatan (HTTP 429): Terlalu banyak permintaan dalam 1 menit.")
                }
                404 -> {
                    Pair(false, "🔴 Model Tidak Ditemukan (HTTP 404): Nama model '$cleanModel' belum aktif.")
                }
                else -> {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Pair(false, "🔴 Gagal (HTTP $code): ${err.take(90)}")
                }
            }
        } catch (e: Exception) {
            Pair(false, "🔴 Kendala Koneksi: ${e.localizedMessage ?: "Tidak ada jaringan"}")
        }
    }

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

        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                delay(1500)
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
