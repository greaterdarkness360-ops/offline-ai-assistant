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

    // Prompt sistem terstruktur untuk memaksa Gemma memanggil alat (Tool Calling)
    private val systemPrompt = """
        Kamu adalah TOWR, asisten AI pribadi on-device pintar di Android buatan Natanael.
        Kamu MEMILIKI AKSES PENUH ke alat perangkat melalui format JSON.
        JANGAN PERNAH menolak atau berkata tidak memiliki akses.

        ATURAN RESPON:
        1. Jika pengguna meminta aksi perangkat, jawab HANYA dalam 1 baris JSON persis:
           - Kirim WhatsApp: {"action": "share_text", "target_app": "whatsapp", "text": "isi pesan", "contact_name": "nama"}
           - Cari file baru/waktu: {"action": "search_files", "query": "nama_file", "hours": 24}
           - Cari file umum: {"action": "search_files", "query": "nama_file"}
           - Buka aplikasi: {"action": "open_app", "app_name": "nama_aplikasi"}
           - Cek file duplikat: {"action": "clean_duplicates", "delete_duplicates": false}
           - Hapus file duplikat: {"action": "clean_duplicates", "delete_duplicates": true}
           - Cek pemakaian HP: {"action": "inspect_usage"}
        2. Jika pengguna mengobrol biasa (sains, definisi, salam, santai), jawab langsung secara ramah dan ringkas dalam Bahasa Indonesia tanpa JSON.
    """.trimIndent()

    // Mendeteksi otomatis file model di memori internal ponsel
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
