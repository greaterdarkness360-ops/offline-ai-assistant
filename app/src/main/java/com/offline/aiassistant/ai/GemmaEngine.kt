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

    var errorMessage: String? = null
        private set

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

    // Mencari file model langsung di Documents, Dokumen, atau Download
    fun findModelPath(): String? {
        val root = Environment.getExternalStorageDirectory() ?: return null

        // 1. Prioritas utama: Folder Documents sesuai lokasi foto Anda
        val directCandidates = listOf(
            File(root, "Documents/gemma-4-E2B-it-gpu.litertlm"),
            File(root, "Documents/gemma-4-e2b-it-gpu.litertlm"),
            File(root, "Dokumen/gemma-4-E2B-it-gpu.litertlm"),
            File(root, "Download/gemma-4-E2B-it-gpu.litertlm"),
            File(root, "gemma-4-E2B-it-gpu.litertlm")
        )
        for (file in directCandidates) {
            if (file.exists() && file.canRead()) return file.absolutePath
        }

        // 2. Telusuri folder Documents / Dokumen jika nama atau huruf besar/kecil berbeda
        val docDirs = listOf(
            File(root, "Documents"),
            File(root, "Dokumen"),
            File(root, "Download")
        ).filter { it.exists() && it.canRead() }

        for (dir in docDirs) {
            val found = dir.walkTopDown()
                .maxDepth(3)
                .filter { !it.name.startsWith(".") }
                .firstOrNull { f -> 
                    f.isFile && (f.name.endsWith(".litertlm", ignoreCase = true) || f.name.endsWith(".bin", ignoreCase = true)) 
                }
            if (found != null) return found.absolutePath
        }

        return null
    }

    suspend fun loadModel(modelPath: String): String = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        if (!file.exists()) {
            isModelLoaded = false
            errorMessage = "File tidak ditemukan di: $modelPath"
            return@withContext errorMessage!!
        }

        // Bebaskan instansiasi lama dari memori GPU agar tidak Out-of-Memory
        release()

        try {
            val gpuConfig = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.GPU()
            )
            val newEngine = Engine(gpuConfig)
            newEngine.initialize()
            engine = newEngine
            isModelLoaded = true
            loadedModelPath = file.absolutePath
            errorMessage = null
            "Model Gemma On-Device (.litertlm) aktif via GPU!"
        } catch (eGpu: Exception) {
            try {
                val cpuConfig = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU()
                )
                val fallbackEngine = Engine(cpuConfig)
                fallbackEngine.initialize()
                engine = fallbackEngine
                isModelLoaded = true
                loadedModelPath = file.absolutePath
                errorMessage = null
                "Model Gemma On-Device aktif via CPU!"
            } catch (eCpu: Exception) {
                isModelLoaded = false
                errorMessage = eGpu.localizedMessage ?: eCpu.localizedMessage ?: "Gagal inisialisasi engine"
                errorMessage!!
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

    fun release() {
        try {
            engine?.close()
        } catch (ignored: Exception) {}
        engine = null
        isModelLoaded = false
    }
}
