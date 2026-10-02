package com.offline.aiassistant.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GemmaEngine(private val context: Context) {

    private var llmInference: LlmInference? = null
    var isModelLoaded: Boolean = false
        private set

    // Instruksi Dasar (System Prompt): Mengatur kapan harus menjalankan alat & kapan menjawab santai
    private val systemPrompt = """
        Kamu adalah TOWR, asisten AI pribadi on-device yang 100% offline dan aman, dibuat oleh Natanael.
        
        PANDUAN MENJAWAB:
        1. Jika pengguna meminta tindakan fisik di HP, jawab HANYA dalam format JSON satu baris:
           - Buka aplikasi: {"action": "open_app", "app_name": "NamaAplikasi"}
           - Cari file: {"action": "search_files", "query": "kata_kunci"}
           - Cek file ganda: {"action": "clean_duplicates", "folder": "Download", "delete_duplicates": false}
           - Hapus file ganda: {"action": "clean_duplicates", "folder": "Download", "delete_duplicates": true}
           - Satukan gambar ke PDF: {"action": "images_to_pdf", "images": ["foto1.jpg", "foto2.jpg"], "output_pdf": "Nama.pdf"}
           - Cek aktivitas aplikasi: {"action": "inspect_usage"}
        
        2. Jika pengguna bertanya hal umum (sains, geografi, sejarah, definisi, logika), jawablah secara langsung, ramah, dan jelas dalam Bahasa Indonesia tanpa format JSON.
    """.trimIndent()

    // Memuat bobot model Gemma dari memori internal HP
    suspend fun loadModel(modelPath: String): String = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        if (!file.exists()) {
            return@withContext "File model tidak ditemukan di lokasi: $modelPath"
        }

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(512)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isModelLoaded = true
            "Model Gemma On-Device berhasil aktif dan siap berpikir!"
        } catch (e: Exception) {
            isModelLoaded = false
            "Gagal memuat model: ${e.localizedMessage}"
        }
    }

    // Menghasilkan tanggapan cerdas dari model Gemma
    suspend fun askGemma(prompt: String): String = withContext(Dispatchers.IO) {
        val inference = llmInference
        if (inference == null || !isModelLoaded) {
            return@withContext "MODEL_NOT_READY"
        }

        val fullInput = "$systemPrompt\n\nPengguna: $prompt\nTOWR:"

        try {
            val output = inference.generateResponse(fullInput)
            output.trim()
        } catch (e: Exception) {
            "Kendala komputasi model: ${e.localizedMessage}"
        }
    }
}
