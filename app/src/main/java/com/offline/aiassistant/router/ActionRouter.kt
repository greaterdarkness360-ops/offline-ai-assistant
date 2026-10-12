package com.offline.aiassistant.router

import android.content.Context
import com.offline.aiassistant.tools.AppLauncherTool
import com.offline.aiassistant.tools.FileItemInfo
import com.offline.aiassistant.tools.FileTools
import com.offline.aiassistant.tools.ShareBridgeTool
import com.offline.aiassistant.tools.UsageStatsTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AgentAction(
    val action: String,
    val query: String? = null,
    val file_name: String? = null,
    val min_size_mb: Double? = null,
    val max_size_mb: Double? = null,
    val sort_by: String? = null,
    val app_name: String? = null,
    val folder: String? = null,
    val delete_duplicates: Boolean? = false,
    val images: List<String>? = null,
    val output_pdf: String? = null,
    val target_app: String? = null,
    val text: String? = null,
    val file_path: String? = null,
    val title: String? = null,
    val content: String? = null
)

data class PendingDeleteInfo(
    val fileName: String,
    val filePath: String,
    val fileSizeFormatted: String
)

data class RouterResponse(
    val message: String,
    val files: List<FileItemInfo> = emptyList(),
    val pendingDelete: PendingDeleteInfo? = null
)

class ActionRouter(context: Context) {

    val fileTools = FileTools(context)
    private val appLauncherTool = AppLauncherTool(context)
    private val usageStatsTool = UsageStatsTool(context)
    private val shareBridgeTool = ShareBridgeTool(context)

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun processInstruction(input: String): RouterResponse = withContext(Dispatchers.IO) {
        val trimmed = input.trim()

        val cleanJson = extractJsonPayload(trimmed)
        if (cleanJson != null) {
            return@withContext try {
                val parsedAction = jsonParser.decodeFromString<AgentAction>(cleanJson)
                executeJsonAction(parsedAction, originalInput = trimmed)
            } catch (e: Exception) {
                RouterResponse("Gagal membaca aksi JSON: ${e.localizedMessage}")
            }
        }

        val lower = trimmed.lowercase()

        when {
            // PERINTAH HAPUS
            lower.startsWith("hapus") || lower.contains("hapus file") || lower.contains("delete") -> {
                // Hanya jalankan clean duplicate jika eksplisit meminta duplikat/ganda/kembar
                if (lower.contains("ganda") || lower.contains("duplikat") || lower.contains("kembar")) {
                    val msg = fileTools.cleanDuplicateFiles("Download", deleteDuplicates = true)
                    RouterResponse(msg)
                } else {
                    val targetName = trimmed
                        .replace(Regex("(?i)^(hapus file|hapus|delete)\\s*"), "")
                        .replace(Regex("(?i)(semua|yang bernama|file|berkas)\\s*"), "")
                        .trim()

                    if (targetName.isBlank()) {
                        RouterResponse("Sebutkan nama atau nomor urut file yang ingin dihapus.")
                    } else {
                        prepareDeleteGuardrail(targetName)
                    }
                }
            }

            lower.contains("telegram") || lower.contains("tele") -> {
                val messageText = trimmed.replace(Regex("(?i)^(kirim ke telegram|kirim pesan ke telegram|bagikan ke telegram|telegram)\\s*[:,-]?\\s*"), "")
                RouterResponse(shareBridgeTool.shareTextToApp(messageText, "telegram"))
            }

            lower.contains("notion") -> {
                val noteContent = trimmed.replace(Regex("(?i)^(simpan ke notion|catatan notion|notion)\\s*[:,-]?\\s*"), "")
                RouterResponse(shareBridgeTool.saveNoteForNotion("Catatan_TOWR", noteContent))
            }

            lower.startsWith("buka aplikasi") || lower.startsWith("buka") -> {
                val appName = trimmed.replace(Regex("(?i)^(buka aplikasi|buka)\\s*"), "")
                val msg = if (appName.isBlank()) "Sebutkan nama aplikasi yang ingin dibuka." else appLauncherTool.openAppByName(appName)
                RouterResponse(msg)
            }

            lower.contains("file ganda") || lower.contains("duplikat") || lower.contains("file double") -> {
                val list = fileTools.getDuplicateFilesList("Download")
                RouterResponse("Ditemukan ${list.size} file duplikat di folder Download:", list)
            }

            lower.contains("pdf") && (lower.contains("satukan") || lower.contains("ubah") || lower.contains("gabung")) -> {
                val imageList = extractImageNames(trimmed)
                val msg = if (imageList.isEmpty()) "Sebutkan nama file gambar (contoh: foto1.jpg, foto2.jpg)."
                else fileTools.convertImagesToPdf(imageList, "Dokumen_TOWR.pdf")
                RouterResponse(msg)
            }

            lower.contains("aktivitas") || lower.contains("latar belakang") || lower.contains("pemakaian") -> {
                RouterResponse(usageStatsTool.getRecentUsageSummary())
            }

            lower.startsWith("cari file") || lower.startsWith("cari") || lower.startsWith("temukan") -> {
                var minMb: Double? = null
                var maxMb: Double? = null
                var sortBy: String? = null

                val sizeMatch = Regex("(lebih dari|>|di atas)\\s*(\\d+)\\s*(mb|megabyte)", RegexOption.IGNORE_CASE).find(lower)
                if (sizeMatch != null) minMb = sizeMatch.groupValues[2].toDoubleOrNull()

                val smallerMatch = Regex("(kurang dari|<|di bawah)\\s*(\\d+)\\s*(mb|megabyte)", RegexOption.IGNORE_CASE).find(lower)
                if (smallerMatch != null) maxMb = smallerMatch.groupValues[2].toDoubleOrNull()

                if (lower.contains("paling lama") || lower.contains("terlama")) sortBy = "oldest"
                if (lower.contains("paling baru") || lower.contains("terbaru")) sortBy = "newest"
                if (lower.contains("paling besar") || lower.contains("terbesar")) sortBy = "largest"

                val cleanQuery = trimmed
                    .replace(Regex("(?i)^(cari file|cari|temukan)\\s*"), "")
                    .replace(Regex("(?i)(yang|berukuran|lebih dari|kurang dari|di atas|di bawah|\\d+\\s*mb|paling lama|paling baru|paling besar|terlama|terbaru|terbesar)"), "")
                    .trim()

                val foundFiles = fileTools.searchFilesAdvanced(cleanQuery, minMb, maxMb, sortBy)

                if (foundFiles.isEmpty()) {
                    RouterResponse("Ditemukan 0 file yang cocok.")
                } else {
                    RouterResponse("Ditemukan ${foundFiles.size} file:", foundFiles)
                }
            }

            else -> {
                RouterResponse("Instruksi diterima: '$trimmed'.\nGunakan tombol cepat atau beri perintah spesifik.")
            }
        }
    }

    private suspend fun executeJsonAction(action: AgentAction, originalInput: String): RouterResponse {
        val actionName = action.action.lowercase()

        // PENGAMAN KHUSUS: Jika AI salah memanggil clean_duplicates padahal user merujuk ke urutan/file tunggal
        if (actionName == "clean_duplicates") {
            val lowerInput = originalInput.lowercase()
            val isExplicitDuplicate = lowerInput.contains("duplikat") || lowerInput.contains("ganda") || lowerInput.contains("kembar")
            if (!isExplicitDuplicate && (lowerInput.contains("kedua") || lowerInput.contains("pertama") || lowerInput.contains("nomor") || lowerInput.contains("file"))) {
                // Alihkan ke delete_file yang aman
                val targetRef = originalInput
                    .replace(Regex("(?i)^(hapus file|hapus)\\s*"), "")
                    .trim()
                return prepareDeleteGuardrail(targetRef)
            }
        }

        return when (actionName) {
            "delete_file" -> {
                val target = action.file_name ?: action.query ?: ""
                prepareDeleteGuardrail(target)
            }
            "search_files" -> {
                val list = fileTools.searchFilesAdvanced(
                    query = action.query ?: action.file_name ?: "",
                    minSizeMb = action.min_size_mb,
                    maxSizeMb = action.max_size_mb,
                    sortBy = action.sort_by
                )
                RouterResponse("Ditemukan ${list.size} file:", list)
            }
            "clean_duplicates" -> {
                if (action.delete_duplicates == true) {
                    RouterResponse(fileTools.cleanDuplicateFiles(action.folder ?: "Download", deleteDuplicates = true))
                } else {
                    val list = fileTools.getDuplicateFilesList(action.folder ?: "Download")
                    RouterResponse("Ditemukan ${list.size} file ganda:", list)
                }
            }
            "images_to_pdf" -> RouterResponse(fileTools.convertImagesToPdf(action.images ?: emptyList(), action.output_pdf ?: "TOWR_Result.pdf"))
            "open_app" -> RouterResponse(appLauncherTool.openAppByName(action.app_name ?: ""))
            "inspect_usage" -> RouterResponse(usageStatsTool.getRecentUsageSummary())
            "share_wa" -> RouterResponse(shareBridgeTool.shareTextToApp(action.text ?: "", "whatsapp"))
            "share_text" -> RouterResponse(shareBridgeTool.shareTextToApp(action.text ?: "", action.target_app ?: "general"))
            "share_file" -> RouterResponse(shareBridgeTool.shareFileToApp(action.file_path ?: "", action.target_app ?: "general"))
            "save_note_for_notion" -> RouterResponse(shareBridgeTool.saveNoteForNotion(action.title ?: "Catatan", action.content ?: ""))
            else -> RouterResponse("Aksi '${action.action}' tidak dikenali oleh sistem TOWR.")
        }
    }

    /**
     * Mempersiapkan Guardrail Penghapusan: Tidak langsung menghapus, melainkan memicu kartu konfirmasi
     */
    private fun prepareDeleteGuardrail(targetReference: String): RouterResponse {
        val target = fileTools.resolveTargetFile(targetReference)
        return if (target != null) {
            RouterResponse(
                message = "Konfirmasi keamanan: Apakah Anda yakin ingin menghapus berkas '${target.name}'?",
                pendingDelete = PendingDeleteInfo(
                    fileName = target.name,
                    filePath = target.path,
                    fileSizeFormatted = target.size
                )
            )
        } else {
            RouterResponse("Berkas '$targetReference' tidak ditemukan di penyimpanan.")
        }
    }

    /**
     * Dipanggil hanya ketika user menekan tombol [Hapus Sekarang] pada kartu konfirmasi UI
     */
    fun executeConfirmedDelete(filePath: String): String {
        return fileTools.deletePhysicalFile(filePath)
    }

    private fun extractImageNames(text: String): List<String> {
        val regex = Regex("([a-zA-Z0-9_-]+\\.(jpg|jpeg|png))", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value }.toList()
    }

    private fun extractJsonPayload(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            val candidate = text.substring(start, end + 1).trim()
            if (candidate.contains("\"action\"")) {
                return candidate
            }
        }
        return null
    }
}
