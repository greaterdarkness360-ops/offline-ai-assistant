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

    @Volatile
    private var cachedModelName: String? = null

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

        // 1. Dapatkan Nama Model Aktif Secara Otomatis dari Akun Pengguna (Anti 404)
        var modelToUse = cachedModelName
        if (modelToUse == null) {
            val detected = autoDetectAvailableModel(cleanKey)
            if (detected.startsWith("ERROR:")) {
                return@withContext detected
            }
            modelToUse = detected
            cachedModelName = detected
        }

        // 2. Normalisasi Path Model untuk Pemanggilan generateContent
        val normalizedModelPath = if (modelToUse.startsWith("models/")) modelToUse else "models/$modelToUse"
        val generateUrl = "https://generativelanguage.googleapis.com/v1beta/$normalizedModelPath:generateContent?key=$cleanKey"

        try {
            val url = URL(generateUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.doOutput = true

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

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(rootJson.toString())
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
                return@withContext "Jawaban kosong dari server Gemini ($modelToUse)."
            } else {
                if (responseCode == 404) {
                    cachedModelName = null // Reset cache jika Google mengubah model
                }
                val errorStream = conn.errorStream
                val errorMsg = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream, "UTF-8")).readText()
                } else "HTTP $responseCode"
                return@withContext "ERROR: Google AI Studio ($responseCode): $errorMsg"
            }
        } catch (e: Exception) {
            return@withContext "ERROR: Kendala koneksi ke Gemini Cloud: ${e.localizedMessage}"
        }
    }

    /**
     * Meminta daftar resmi seluruh model yang aktif untuk API Key ini (ModelService.ListModels)
     */
    private fun autoDetectAvailableModel(apiKey: String): String {
        return try {
            val listUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val conn = URL(listUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val responseStr = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).readText()
                val json = JSONObject(responseStr)
                val modelsArray = json.optJSONArray("models") ?: JSONArray()

                val supportedModels = mutableListOf<String>()
                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    val name = m.optString("name")
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    var canGenerate = false
                    for (j in 0 until methods.length()) {
                        if (methods.getString(j) == "generateContent") {
                            canGenerate = true
                            break
                        }
                    }
                    if (canGenerate && name.isNotBlank()) {
                        supportedModels.add(name)
                    }
                }

                if (supportedModels.isEmpty()) {
                    return "ERROR: Tidak ada model yang mendukung generateContent di akun ini."
                }

                // Prioritaskan model Flash resmi yang aktif di akun pengguna
                val flashModel = supportedModels.firstOrNull { it.contains("flash", ignoreCase = true) }
                flashModel ?: supportedModels.first()
            } else {
                val errorStream = conn.errorStream
                val errorMsg = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream, "UTF-8")).readText()
                } else "HTTP $code"
                "ERROR: Gagal memeriksa model di Google AI Studio (HTTP $code): $errorMsg"
            }
        } catch (e: Exception) {
            "ERROR: Tidak dapat menghubungi server Google AI Studio: ${e.localizedMessage}"
        }
    }

    fun resetCache() {
        cachedModelName = null
    }
}
