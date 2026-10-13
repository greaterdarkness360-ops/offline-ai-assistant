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
        userPrompt: String,
        customModelName: String? = null
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext "ERROR: API Key Gemini belum diatur di menu Pengaturan [⚙️]."
        }

        // Daftar model resmi Google dari yang paling stabil & kapasitas server terbesar
        val modelsToTry = mutableListOf<String>()
        if (!customModelName.isNullOrBlank()) {
            modelsToTry.add(customModelName.trim().removePrefix("models/"))
        }
        // Tambahkan model-model stabil dengan server besar
        listOf(
            "gemini-2.0-flash",
            "gemini-1.5-flash-002",
            "gemini-1.5-flash-8b",
            "gemini-3.8-flash",
            "gemini-2.5-flash"
        ).forEach { if (!modelsToTry.contains(it)) modelsToTry.add(it) }

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

        var lastDiagnostic = ""

        for (modelName in modelsToTry) {
            val cleanModel = modelName.trim()
            val generateUrl = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$cleanKey"

            try {
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
                    return@withContext "Jawaban kosong dari server Gemini ($cleanModel)."
                } else {
                    val errorStream = conn.errorStream
                    val errorMsg = if (errorStream != null) {
                        BufferedReader(InputStreamReader(errorStream, "UTF-8")).readText()
                    } else "HTTP $responseCode"

                    lastDiagnostic = "Model $cleanModel ($responseCode): $errorMsg"

                    // Jika error 503 (Server Penuh / High Demand), 404 (Tidak Ada), atau 429 (Limit Sementara)
                    // JANGAN BERHENTI, langsung lompat mencoba model alternatif berikutnya!
                    if (responseCode in listOf(503, 404, 429)) {
                        continue
                    }

                    return@withContext "ERROR: Google AI Studio ($responseCode): $errorMsg"
                }
            } catch (e: Exception) {
                lastDiagnostic = "Model $cleanModel: ${e.localizedMessage}"
            }
        }

        "ERROR: Seluruh server model Gemini Cloud sedang padat/tidak merespon. Terakhir: $lastDiagnostic"
    }
}
