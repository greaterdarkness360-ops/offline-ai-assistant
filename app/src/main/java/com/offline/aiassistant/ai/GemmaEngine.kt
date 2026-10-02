package com.offline.aiassistant.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GemmaEngine(private val context: Context) {

    private var engine: Engine? = null
    var isModelLoaded: Boolean = false
        private set

    // Panduan Perilaku AI
    private val systemPrompt = """
        Kamu adalah TOWR, asisten AI pribadi on-device 100% offline dan aman yang dibuat oleh Natanael.
        
        PANDUAN MENJAWAB:
        1. Jika pengguna meminta tindakan fisik di HP, jawab HANYA dalam format JSON satu baris:
           - Buka aplikasi: {"action": "open_app", "app_name": "NamaAplikasi"}
           - Cari file: {"action": "search_files", "query": "kata_kunci"}
           - Cek file ganda: {"action": "clean_duplicates", "folder": "Download", "delete_duplicates": false}
           - Hapus file ganda: {"action": "clean_duplicates", "folder": "Download", "delete_duplicates": true}
           - Satukan gambar ke PDF: {"action": "images_to_pdf", "images": ["foto1.jpg", "foto2.jpg"], "output_pdf": "Nama.pdf"}
           - Cek aktivitas aplikasi: {"action": "inspect_usage"}
        
        2. Jika pengguna bertanya hal umum (sains, geografi, sejarah, definisi, logika, santai), jawablah langsung secara ramah dan jelas dalam Bahasa Indonesia tanpa tanda kurung kurawal JSON.
    """.trimIndent()

    suspend fun loadModel(modelPath: String): String = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        if (!file.exists()) {
            return@withContext "File model tidak ditemukan di lokasi: $modelPath"
        }

        // Coba inisialisasi mesin LiteRT-LM dengan GPU, fallback ke CPU jika GPU tidak didukung
        try {
            val gpuConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU()
            )
            val newEngine = Engine(gpuConfig)
            newEngine.initialize()
            engine = newEngine
            isModelLoaded = true
            "Model Gemma On-Device (.litertlm) berhasil aktif via GPU HP!"
        } catch (eGpu: Exception) {
            // Jika GPU gagal, coba inisialisasi ulang dengan mode CPU
            try {
                val cpuConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU()
                )
                val fallbackEngine = Engine(cpuConfig)
                fallbackEngine.initialize()
                engine = fallbackEngine
                isModelLoaded = true
                "Model Gemma On-Device berhasil aktif via CPU mode!"
            } catch (eCpu: Exception) {
                isModelLoaded = false
                "Gagal memuat model: ${eCpu.localizedMessage ?: eGpu.localizedMessage}"
            }
        }
    }

    suspend fun askGemma(prompt: String): String = withContext(Dispatchers.IO) {
        val activeEngine = engine
        if (activeEngine == null || !isModelLoaded) {
            return@withContext "MODEL_NOT_READY"
        }

        val fullInput = "$systemPrompt\n\nPengguna: $prompt\nTOWR:"

        try {
            val conversation = activeEngine.createConversation()
            val response = conversation.sendMessage(fullInput)
            val outputText = response.text ?: response.toString()
            outputText.trim()
        } catch (e: Exception) {
            "Kendala komputasi AI: ${e.localizedMessage}"
        }
    }
}
