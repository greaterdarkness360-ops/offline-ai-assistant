package com.offline.aiassistant.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiCloudEngine(private val context: Context) {

    private val prefs = context.getSharedPreferences("towr_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_api_key", value).apply()

    private val systemPrompt = """
        Kamu adalah TOWR, asisten AI pribadi pintar di Android buatan Natanael.
        Kamu saat ini beroperasi dalam Mode Hybrid (Cloud-Accelerated Gemini Flash).
        
        ATURAN RESPON:
        1. Jika pengguna meminta aksi perangkat, jawab HANYA dalam 1 baris JSON persis:
           - Kirim WhatsApp: {"action": "share_text", "target_app": "whatsapp", "text": "isi pesan", "contact_name": "nama"}
           - Kirim file ke WhatsApp: {"action": "share_file", "file_path": "nama_file_atau_nomor", "target_app": "whatsapp"}
           - Cari file baru/waktu: {"action": "search_files", "query": "nama_file", "hours": 24}
           - Cari file umum: {"action": "search_files", "query": "nama_file"}
           - Buka aplikasi: {"action": "open_app", "app_name": "nama_aplikasi"}
           - Cek file duplikat: {"action": "clean_duplicates", "delete_duplicates": false}
           - Hapus file duplikat: {"action": "clean_duplicates", "delete_duplicates": true}
           - Cek pemakaian HP: {"action": "inspect_usage"}
        2. Jika pengguna mengobrol biasa, bertanya sains, pengetahuan, analisis teks, coding, atau logika, jawablah secara ramah, komprehensif, dan cerdas dalam Bahasa Indonesia tanpa format JSON.
    """.trimIndent()

    private var generativeModel: GenerativeModel? = null

    fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun getOrCreateModel(): GenerativeModel {
        val key = apiKey.trim()
        return GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = key
        )
    }

    suspend fun askGemini(prompt: String): String = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            return@withContext "OFFLINE_FALLBACK"
        }
        if (apiKey.isBlank()) {
            return@withContext "API_KEY_EMPTY"
        }

        try {
            val model = generativeModel ?: getOrCreateModel().also { generativeModel = it }
            val fullPrompt = "$systemPrompt\n\nPengguna: $prompt\nTOWR:"
            val response = model.generateContent(fullPrompt)
            response.text?.trim() ?: "Tidak ada balasan dari Gemini Cloud."
        } catch (e: Exception) {
            "Gagal komputasi Cloud: ${e.localizedMessage}"
        }
    }

    fun resetModel() {
        generativeModel = null
    }
}
