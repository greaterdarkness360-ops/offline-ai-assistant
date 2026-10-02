package com.offline.aiassistant.router

import android.content.Context
import com.offline.aiassistant.tools.AppLauncherTool
import com.offline.aiassistant.tools.FileTools
import com.offline.aiassistant.tools.UsageStatsTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AgentAction(
    val action: String,
    val query: String? = null,
    val app_name: String? = null,
    val folder: String? = null,
    val delete_duplicates: Boolean? = false,
    val images: List<String>? = null,
    val output_pdf: String? = null
)

class ActionRouter(context: Context) {

    private val fileTools = FileTools(context)
    private val appLauncherTool = AppLauncherTool(context)
    private val usageStatsTool = UsageStatsTool(context)

    private val jsonParser = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
    }

    // Menjalankan perintah di jalur Dispatchers.IO agar HP tidak membeku
    suspend fun processInstruction(input: String): String = withContext(Dispatchers.IO) {
        val trimmed = input.trim()

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return@withContext try {
                val parsedAction = jsonParser.decodeFromString<AgentAction>(trimmed)
                executeAction(parsedAction)
            } catch (e: Exception) {
                "Kesalahan parsing instruksi JSON: ${e.localizedMessage}"
            }
        }

        val lower = trimmed.lowercase()

        when {
            lower.startsWith("buka aplikasi") || lower.startsWith("buka") -> {
                val appName = trimmed.replace(Regex("(?i)^(buka aplikasi|buka)\\s*"), "")
                if (appName.isBlank()) "Sebutkan nama aplikasi yang ingin dibuka." else appLauncherTool.openAppByName(appName)
            }

            lower.contains("hapus file ganda") || lower.contains("hapus duplikat") || lower.contains("bersihkan ganda") -> {
                fileTools.cleanDuplicateFiles(folderName = "Download", deleteDuplicates = true)
            }

            lower.contains("file ganda") || lower.contains("duplikat") || lower.contains("file double") -> {
                fileTools.cleanDuplicateFiles(folderName = "Download", deleteDuplicates = false)
            }

            lower.contains("pdf") && (lower.contains("satukan") || lower.contains("ubah") || lower.contains("gabung")) -> {
                val imageList = extractImageNames(trimmed)
                if (imageList.isEmpty()) {
                    "Sebutkan nama file gambar yang ingin disatukan ke PDF (contoh: foto1.jpg, foto2.jpg)."
                } else {
                    fileTools.convertImagesToPdf(imageList, "Dokumen_TOWR.pdf")
                }
            }

            lower.startsWith("cari file") || lower.startsWith("cari") -> {
                val searchQuery = trimmed.replace(Regex("(?i)^(cari file|cari)\\s*"), "")
                if (searchQuery.isBlank()) "Sebutkan nama file yang ingin dicari." 
                else fileTools.searchFiles(searchQuery).joinToString("\n")
            }

            lower.contains("aktivitas") || lower.contains("latar belakang") || lower.contains("pemakaian") -> {
                usageStatsTool.getRecentUsageSummary()
            }

            else -> {
                "Instruksi diterima: '$trimmed'.\n" +
                "Gunakan tombol cepat di bawah untuk mencari file, meluncurkan aplikasi, atau memeriksa aktivitas HP."
            }
        }
    }

    private suspend fun executeAction(action: AgentAction): String {
        return when (action.action.lowercase()) {
            "search_files" -> fileTools.searchFiles(action.query ?: "").joinToString("\n")
            "clean_duplicates" -> fileTools.cleanDuplicateFiles(action.folder ?: "Download", action.delete_duplicates ?: false)
            "images_to_pdf" -> fileTools.convertImagesToPdf(action.images ?: emptyList(), action.output_pdf ?: "TOWR_Result.pdf")
            "open_app" -> appLauncherTool.openAppByName(action.app_name ?: "")
            "inspect_usage" -> usageStatsTool.getRecentUsageSummary()
            else -> "Aksi '${action.action}' tidak dikenali oleh sistem TOWR."
        }
    }

    private fun extractImageNames(text: String): List<String> {
        val regex = Regex("([a-zA-Z0-9_-]+\\.(jpg|jpeg|png))", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value }.toList()
    }
}
