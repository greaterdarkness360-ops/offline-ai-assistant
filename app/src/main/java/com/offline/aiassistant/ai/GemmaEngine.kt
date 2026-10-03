package com.offline.aiassistant.ai

import android.content.Context
import android.os.Environment
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GemmaEngine(private val context: Context) {

    private var engine: Engine? = null
    var isModelLoaded: Boolean = false
        private set

    var loadedModelPath: String? = null
        private set

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
           - Kirim teks ke Telegram: {"action": "share_text", "target_app": "telegram", "text": "isi pesan"}
           - Simpan catatan ke Notion: {"action": "save_note_for_notion", "title": "Judul", "content": "isi catatan"}
        
        2. Jika pengguna bertanya hal umum (sains, geografi, sejarah, definisi, logika, santai), jawablah langsung secara ramah dan jelas dalam Bahasa Indonesia tanpa format JSON.
    """.trimIndent()

    // Fungsi pencari file model otomatis di memori internal ponsel
    fun findModelPath(): String? {
        val root = Environment.getExternalStorageDirectory() ?: return null
        val candidates = listOf(
            File(root, "Download/gemma-4-E2B-it-gpu.litertlm"),
            File(root, "Download/gemma-4-e2b-it-gpu.litertlm"),
            File(root, "Documents/gemma-4-E2B-it-gpu.litertlm"),
            File(root, "gemma-4-E2B-it-gpu.litertlm")
        )
        for (file in candidates) {
            if (file.exists() && file.canRead()) return file.absolutePath
        }

        // Cari berkas .litertlm lain yang ada di folder Download
        val downloadDir = File(root, "Download")
        if (downloadDir.exists()) {
            val found = downloadDir.listFiles { f -> 
                f.isFile && f.name.endsWith(".litertlm", ignoreCase = true) 
            }?.firstOrNull()
            if (found != null) return found.absolutePath
        }

        return null
    }

    suspend fun loadModel(modelPath: String): String = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        if (!file.exists()) {
            isModelLoaded = false
            return@withContext "File model tidak ditemukan: $modelPath"
        }

        try {
            val gpuConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU()
            )
            val newEngine = Engine(gpuConfig)
            newEngine.initialize()
            engine = newEngine
            isModelLoaded = true
            loadedModelPath = modelPath
            "Model Gemma On-Device (.litertlm) berhasil aktif via GPU HP!"
        } catch (eGpu: Exception) {
            try {
                val cpuConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU()
                )
                val fallbackEngine = Engine(cpuConfig)
                fallbackEngine.initialize()
                engine = fallbackEngine
                isModelLoaded = true
                loadedModelPath = modelPath
                "Model Gemma On-Device aktif via CPU mode!"
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
            val conversationConfig = ConversationConfig(
                samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.8)
            )
            val conversation = activeEngine.createConversation(conversationConfig)
            val response = conversation.sendMessage(fullInput)
            response.toString().trim()
        } catch (e: Exception) {
            "Kendala komputasi AI: ${e.localizedMessage}"
        }
    }
}
