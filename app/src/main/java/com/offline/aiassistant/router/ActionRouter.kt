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
    val max_age_hours: Long? = null,
    val app_name: String? = null,
    val folder: String? = null,
    val delete_duplicates: Boolean? = false,
    val images: List<String>? = null,
    val output_pdf: String? = null,
    val target_app: String? = null,
    val text: String? = null,
    val file_path: String? = null,
    val title: String? = null,
    val content: String? = null,
    val contact_name: String? = null,
    val phone_number: String? = null
)

data class PendingDeleteInfo(
    val files: List<FileItemInfo>,
    val summaryTitle: String
) {
    val fileCount: Int get() = files.size
    val fileName: String get() = if (files.size == 1) files[0].name else "${files.size} berkas (${files.take(2).joinToString { it.name }}${if (files.size > 2) "..." else ""})"
    val fileSizeFormatted: String get() = if (files.size == 1) files[0].size else "Total ${files.size} berkas"
}

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

        // 1. Ekstraksi Multi-JSON: Mampu membaca jika AI mengeluarkan lebih dari satu JSON sekaligus
        val jsonPayloads = extractAllJsonPayloads(trimmed)
        if (jsonPayloads.isNotEmpty()) {
            return@withContext try {
                val parsedActions = jsonPayloads.mapNotNull { raw ->
                    try { jsonParser.decodeFromString<AgentAction>(raw) } catch (_: Exception) { null }
                }

                if (parsedActions.isEmpty()) {
                    RouterResponse("Gagal membaca struktur aksi JSON.")
                } else if (parsedActions.size == 1) {
                    executeJsonAction(parsedActions[0], originalInput = trimmed)
                } else {
                    executeMultipleActions(parsedActions, originalInput = trimmed)
                }
            } catch (e: Exception) {
                RouterResponse("Gagal mengeksekusi aksi: ${e.localizedMessage}")
            }
        }

        val lower = trimmed.lowercase()

        when {
            // DETEKSI PESAN & KONTAK WHATSAPP
            lower.contains("wa") || lower.contains("whatsapp") -> {
                val match = Regex("(?i)(?:kirim|chat|buka|pesan)?\\s*(?:wa|whatsapp)\\s*(?:ke|untuk)?\\s*([a-zA-Z0-9_\\s]+?)(?:\\s*[:,-]\\s*|\\s+pesan\\s+|\\s+isi\\s+)(.*)").find(trimmed)

                if (match != null) {
                    val contactTarget = match.groupValues[1].trim()
                    val messageBody = match.groupValues[2].trim()
                    RouterResponse(shareBridgeTool.shareTextToApp(text = messageBody, targetApp = "whatsapp", contactName = contactTarget))
                } else {
                    val contactOnly = Regex("(?i)(?:chat|buka|kirim)?\\s*(?:wa|whatsapp)\\s*(?:ke|untuk)?\\s*([a-zA-Z0-9_]+)").find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
                    if (contactOnly.isNotBlank() && contactOnly.lowercase() !in listOf("pesan", "aplikasi", "chat")) {
                        RouterResponse(shareBridgeTool.shareTextToApp(text = "", targetApp = "whatsapp", contactName = contactOnly))
                    } else {
                        val generalText = trimmed.replace(Regex("(?i)^(kirim ke wa|kirim wa|whatsapp|wa)\\s*[:,-]?\\s*"), "")
                        RouterResponse(shareBridgeTool.shareTextToApp(text = generalText, targetApp = "whatsapp"))
                    }
                }
            }

            // PERINTAH HAPUS BERKAS (TUNGGAL ATAU MASSAL)
            lower.startsWith("hapus") || lower.contains("hapus file") || lower.contains("delete") -> {
                if (lower.contains("ganda") || lower.contains("duplikat") || lower.contains("kembar")) {
                    val msg = fileTools.cleanDuplicateFiles("Download", deleteDuplicates = true)
                    RouterResponse(msg)
                } else {
                    val targetName = trimmed
                        .replace(Regex("(?i)^(hapus semua file|hapus semua|hapus file|hapus|delete)\\s*"), "")
                        .replace(Regex("(?i)(semua file|semua|yang bernama|file|berkas)\\s*"), "")
                        .trim()

                    if (targetName.isBlank() || targetName.lowercase() in listOf("semua", "keduanya", "seluruhnya", "hasil")) {
                        if (FileTools.lastSearchResults.isNotEmpty()) {
                            val targets = FileTools.lastSearchResults.toList()
                            RouterResponse(
                                message = "Peringatan Keamanan: Apakah Anda yakin ingin menghapus ${targets.size} berkas dari hasil pencarian?",
                                pendingDelete = PendingDeleteInfo(
                                    files = targets,
                                    summaryTitle = "Konfirmasi Hapus ${targets.size} Berkas Sekaligus"
                                )
                            )
                        } else {
                            RouterResponse("Tidak ada daftar berkas aktif di layar untuk dihapus.")
                        }
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

            // PENCARIAN BERKAS DENGAN FILTER WAKTU 24 JAM & FOLDER UNDUHAN
            lower.startsWith("cari") || lower.startsWith("temukan") || lower.startsWith("list") || lower.contains("24 jam") || lower.contains("hari ini") -> {
                var minMb: Double? = null
                var maxMb: Double? = null
                var sortBy: String? = null
                var maxAgeHours: Long? = null
                var folderFilter: String? = null

                if (Regex("(24\\s*jam|sehari|hari ini|1\\s*hari)", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
                    maxAgeHours = 24L
                } else if (Regex("(1\\s*jam|sejam)", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
                    maxAgeHours = 1L
                } else if (Regex("(7\\s*hari|seminggu)", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
                    maxAgeHours = 168L
                }

                if (Regex("(ku\\s*download|di\\s*download|unduhan|download)", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
                    folderFilter = "Download"
                }

                val sizeMatch = Regex("(lebih dari|>|di atas)\\s*(\\d+)\\s*(mb|megabyte)", RegexOption.IGNORE_CASE).find(lower)
                if (sizeMatch != null) minMb = sizeMatch.groupValues[2].toDoubleOrNull()

                val smallerMatch = Regex("(kurang dari|<|di bawah)\\s*(\\d+)\\s*(mb|megabyte)", RegexOption.IGNORE_CASE).find(lower)
                if (smallerMatch != null) maxMb = smallerMatch.groupValues[2].toDoubleOrNull()

                if (lower.contains("paling lama") || lower.contains("terlama")) sortBy = "oldest"
                if (lower.contains("paling baru") || lower.contains("terbaru") || maxAgeHours != null) sortBy = "newest"
                if (lower.contains("paling besar") || lower.contains("terbesar")) sortBy = "largest"

                val cleanQuery = trimmed
                    .replace(Regex("(?i)^(cari file|cari|temukan|list semua file|list semua|list)\\s*"), "")
                    .replace(Regex("(?i)(yang\\s+ku\\s+download|yang\\s+di\\s*download|yang\\s+baru\\s+di\\s*download|di\\s*download|ku\\s*download|unduhan|download)"), "")
                    .replace(Regex("(?i)(24\\s*jam\\s*terakhir|24\\s*jam|hari\\s*ini|kemarin|terbaru|paling\\s*baru|terakhir)"), "")
                    .replace(Regex("(?i)(yang|berukuran|lebih\\s*dari|kurang\\s*dari|di\\s*atas|di\\s*bawah|\\d+\\s*mb|file|berkas)"), "")
                    .trim()

                val foundFiles = fileTools.searchFilesAdvanced(
                    query = cleanQuery,
                    minSizeMb = minMb,
                    maxSizeMb = maxMb,
                    sortBy = sortBy,
                    maxAgeHours = maxAgeHours,
                    folderFilter = folderFilter
                )

                val label = when {
                    cleanQuery.isNotBlank() && maxAgeHours != null -> "berkas '$cleanQuery' (24 jam terakhir)"
                    cleanQuery.isNotBlank() -> "berkas '$cleanQuery'"
                    maxAgeHours != null -> "berkas unduhan dalam $maxAgeHours jam terakhir"
                    else -> "berkas terbaru"
                }

                if (foundFiles.isEmpty()) {
                    RouterResponse("Ditemukan 0 $label yang memenuhi kriteria.")
                } else {
                    RouterResponse("Ditemukan ${foundFiles.size} $label:", foundFiles)
                }
            }

            else -> {
                RouterResponse("Instruksi diterima: '$trimmed'.\nGunakan tombol cepat atau beri perintah spesifik.")
            }
        }
    }

    private suspend fun executeJsonAction(action: AgentAction, originalInput: String): RouterResponse {
        val actionName = action.action.lowercase()

        return when (actionName) {
            "share_wa", "send_whatsapp", "open_whatsapp" -> {
                val targetContact = (action.contact_name ?: action.phone_number ?: "").trim()
                val msg = action.text ?: ""
                RouterResponse(shareBridgeTool.shareTextToApp(text = msg, targetApp = "whatsapp", contactName = targetContact.ifBlank { null }))
            }
            "delete_file" -> {
                val target = action.file_name ?: action.query ?: ""
                prepareDeleteGuardrail(target)
            }
            "search_files" -> {
                var q = action.query ?: action.file_name ?: ""
                var ageHours = action.max_age_hours
                var folder = action.folder

                if (q.contains("24 jam", ignoreCase = true) || q.contains("hari ini", ignoreCase = true)) {
                    ageHours = 24L
                    q = q.replace(Regex("(?i)(24\\s*jam\\s*terakhir|24\\s*jam|hari\\s*ini|download)"), "").trim()
                }

                val list = fileTools.searchFilesAdvanced(
                    query = q,
                    minSizeMb = action.min_size_mb,
                    maxSizeMb = action.max_size_mb,
                    sortBy = action.sort_by,
                    maxAgeHours = ageHours,
                    folderFilter = folder
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
            "share_text" -> RouterResponse(shareBridgeTool.shareTextToApp(action.text ?: "", action.target_app ?: "general"))
            "share_file" -> RouterResponse(shareBridgeTool.shareFileToApp(action.file_path ?: "", action.target_app ?: "general"))
            "save_note_for_notion" -> RouterResponse(shareBridgeTool.saveNoteForNotion(action.title ?: "Catatan", action.content ?: ""))
            else -> RouterResponse("Aksi '${action.action}' tidak dikenali oleh sistem TOWR.")
        }
    }

    private fun executeMultipleActions(actions: List<AgentAction>, originalInput: String): RouterResponse {
        val deleteTargets = mutableListOf<FileItemInfo>()

        for (act in actions) {
            if (act.action.lowercase() == "delete_file") {
                val targetRef = act.file_name ?: act.query ?: ""
                val resolved = fileTools.resolveTargetFiles(targetRef)
                deleteTargets.addAll(resolved)
            }
        }

        val uniqueTargets = deleteTargets.distinctBy { it.path }
        return if (uniqueTargets.isNotEmpty()) {
            RouterResponse(
                message = "Peringatan Keamanan: Apakah Anda yakin ingin menghapus ${uniqueTargets.size} berkas ini?",
                pendingDelete = PendingDeleteInfo(
                    files = uniqueTargets,
                    summaryTitle = "Konfirmasi Hapus ${uniqueTargets.size} Berkas Sekaligus"
                )
            )
        } else {
            RouterResponse("Tidak ditemukan berkas target yang sesuai untuk dihapus.")
        }
    }

    private fun prepareDeleteGuardrail(targetReference: String): RouterResponse {
        val targets = fileTools.resolveTargetFiles(targetReference)
        return if (targets.isNotEmpty()) {
            val title = if (targets.size == 1) "Konfirmasi Hapus: '${targets[0].name}'" else "Konfirmasi Hapus ${targets.size} Berkas Sekaligus"
            RouterResponse(
                message = "Peringatan Keamanan: Apakah Anda yakin ingin menghapus ${targets.size} berkas ini?",
                pendingDelete = PendingDeleteInfo(
                    files = targets,
                    summaryTitle = title
                )
            )
        } else {
            RouterResponse("Berkas '$targetReference' tidak ditemukan di penyimpanan.")
        }
    }

    fun executeConfirmedDelete(pendingInfo: PendingDeleteInfo): String {
        return fileTools.deletePhysicalFiles(pendingInfo.files.map { it.path })
    }

    private fun extractImageNames(text: String): List<String> {
        val regex = Regex("([a-zA-Z0-9_-]+\\.(jpg|jpeg|png))", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value }.toList()
    }

    private fun extractAllJsonPayloads(text: String): List<String> {
        val regex = Regex("\\{[^{}]*\"action\"[^{}]*\\}")
        return regex.findAll(text).map { it.value.trim() }.toList()
    }
}
