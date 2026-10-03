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

data class RouterResponse(
    val message: String,
    val files: List<FileItemInfo> = emptyList()
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
                executeJsonAction(parsedAction)
            } catch (e: Exception) {
                RouterResponse("Gagal membaca aksi JSON: ${e.localizedMessage}")
            }
        }

        val lower = trimmed.lowercase()

        when {
            // HAPUS SEMUA KECUALI NOMOR X
            lower.contains("kecuali nomor") || lower.contains("kecuali no") -> {
                val numMatch = Regex("\\d+").find(lower)
                if (numMatch != null) {
                    val keepIndex = numMatch.value.toInt()
                    RouterResponse(fileTools.deleteFilesExcept(keepIndex))
                } else {
                    RouterResponse("Sebutkan nomor berkas yang ingin disimpan.")
                }
            }

            // HAPUS BERKAS / BERSIHKAN DUPLIKAT
            lower.startsWith("hapus file") || lower.startsWith("hapus") || lower.startsWith("bersihkan") -> {
                if (lower.contains("ganda") || lower.contains("duplikat") || lower.contains("double")) {
                    val msg = fileTools.cleanDuplicateFiles(deleteDuplicates = true)
                    RouterResponse(msg)
                } else {
                    val targetName = trimmed
                        .replace(Regex("(?i)^(hapus file|hapus|bersihkan)\\s*"), "")
                        .replace(Regex("(?i)(semua|yang bernama|file|berkas)\\s*"), "")
                        .trim()

                    if (targetName.isBlank()) {
                        RouterResponse("Sebutkan nama file yang ingin dihapus.")
                    } else {
                        RouterResponse(fileTools.deleteFileByName(targetName))
                    }
                }
            }

            // CEK FILE DUPLIKAT
            lower.contains("file ganda") || lower.contains("duplikat") || lower.contains("file double") -> {
                val list = fileTools.getDuplicateFilesList()
                if (list.isEmpty()) {
                    RouterResponse("Tidak ditemukan file duplikat di penyimpanan Anda.")
                } else {
                    RouterResponse("Ditemukan ${list.size} file duplikat di perangkat Anda:", list)
                }
            }

            // BUKA / LUNCURKAN APLIKASI
            lower.startsWith("buka") || lower.startsWith("luncurkan") || lower.contains("buka aplikasi") || lower.contains("luncurkan aplikasi") -> {
                val appName = trimmed
                    .replace(Regex("(?i)^(buka aplikasi|buka|luncurkan aplikasi|luncurkan)\\s*"), "")
                    .trim()
                val msg = if (appName.isBlank()) "Sebutkan nama aplikasi yang ingin dibuka." else appLauncherTool.openAppByName(appName)
                RouterResponse(msg)
            }

            // CEK AKTIVITAS & PENGGUNAAN
            lower.contains("aktivitas") || lower.contains("latar belakang") || lower.contains("pemakaian") -> {
                RouterResponse(usageStatsTool.getRecentUsageSummary())
            }

            // SHARE VIA WHATSAPP / TELEGRAM / NOTION
            lower.contains("telegram") || lower.contains("tele") -> {
                val messageText = trimmed.replace(Regex("(?i)^(kirim ke telegram|kirim pesan ke telegram|bagikan ke telegram|telegram)\\s*[:,-]?\\s*"), "")
                RouterResponse(shareBridgeTool.shareTextToApp(messageText, "telegram"))
            }

            lower.contains("notion") -> {
                val noteContent = trimmed.replace(Regex("(?i)^(simpan ke notion|catatan notion|notion)\\s*[:,-]?\\s*"), "")
                RouterResponse(shareBridgeTool.saveNoteForNotion("Catatan_TOWR", noteContent))
            }

            // GABUNG GAMBAR KE PDF
            lower.contains("pdf") && (lower.contains("satukan") || lower.contains("ubah") || lower.contains("gabung")) -> {
                val imageList = extractImageNames(trimmed)
                val msg = if (imageList.isEmpty()) "Sebutkan nama gambar (contoh: foto1.jpg, foto2.jpg)." 
                          else fileTools.convertImagesToPdf(imageList, "Dokumen_TOWR.pdf")
                RouterResponse(msg)
            }

            // PENCARIAN BERKAS (Mendukung Waktu, Ekstensi, Ukuran, Folder)
            lower.startsWith("cari") || lower.startsWith("temukan") || lower.startsWith("lihat file") -> {
                var minMb: Double? = null
                var maxMb: Double? = null
                var sortBy: String? = null
                var maxAgeHours: Long? = null
                val extensions = mutableListOf<String>()

                // 1. Ekstraksi Waktu Alami
                when {
                    lower.contains("24 jam") || lower.contains("sehari") || lower.contains("1 hari") -> maxAgeHours = 24L
                    lower.contains("48 jam") || lower.contains("2 hari") -> maxAgeHours = 48L
                    lower.contains("minggu ini") || lower.contains("7 hari") -> maxAgeHours = 168L
                    lower.contains("hari ini") -> maxAgeHours = 12L
                }

                // 2. Ekstraksi Target Folder
                val targetFolder = when {
                    lower.contains("download") || lower.contains("didownload") || lower.contains("unduh") || lower.contains("diunduh") -> "Download"
                    lower.contains("dokumen") || lower.contains("document") -> "Documents"
                    lower.contains("foto") || lower.contains("gambar") || lower.contains("kamera") -> "DCIM"
                    else -> null
                }

                // 3. Ekstraksi Ekstensi
                if (lower.contains("pdf")) extensions.add("pdf")
                if (lower.contains("word") || lower.contains("docx") || lower.contains("doc")) { extensions.add("docx"); extensions.add("doc") }
                if (lower.contains("excel") || lower.contains("xlsx") || lower.contains("xls")) { extensions.add("xlsx"); extensions.add("xls") }
                if (lower.contains("foto") || lower.contains("gambar") || lower.contains("image")) { extensions.addAll(listOf("jpg", "jpeg", "png", "webp")) }

                // 4. Ekstraksi Ukuran & Sorting
                val sizeMatch = Regex("(lebih dari|>|di atas)\\s*(\\d+)\\s*(mb|megabyte)", RegexOption.IGNORE_CASE).find(lower)
                if (sizeMatch != null) minMb = sizeMatch.groupValues[2].toDoubleOrNull()

                val smallerMatch = Regex("(kurang dari|<|di bawah)\\s*(\\d+)\\s*(mb|megabyte)", RegexOption.IGNORE_CASE).find(lower)
                if (smallerMatch != null) maxMb = smallerMatch.groupValues[2].toDoubleOrNull()

                if (lower.contains("paling lama") || lower.contains("terlama")) sortBy = "oldest"
                if (lower.contains("paling baru") || lower.contains("terbaru")) sortBy = "newest"
                if (lower.contains("paling besar") || lower.contains("terbesar")) sortBy = "largest"

                // 5. Bersihkan Kata Perintah dan Stopwords (Termasuk "didownload")
                val cleanQuery = trimmed
                    .replace(Regex("(?i)\\b(cari|file|berkas|dokumen|temukan|lihat|yang|di|ke|dari|pada|dalam|selama|sejak|didownload|download|diunduh|unduh|tersimpan|dibuat|ada|semua|terakhir|terakhie|terbaru|paling\\s+baru|paling\\s+lama|terlama|terbesar|paling\\s+besar|hari\\s+ini|minggu\\s+ini|sehari|\\d+\\s*(mb|megabyte|gb|kb)|\\d+\\s*jam|pdf|word|excel|docx|xlsx|foto|gambar|lokal)\\b"), "")
                    .replace("\\s+".toRegex(), " ")
                    .trim()

                val foundFiles = fileTools.searchFilesAdvanced(
                    query = cleanQuery,
                    minSizeMb = minMb,
                    maxSizeMb = maxMb,
                    sortBy = sortBy,
                    maxAgeHours = maxAgeHours,
                    extensions = extensions,
                    specificFolder = targetFolder
                )

                if (foundFiles.isEmpty()) {
                    if (maxAgeHours != null) {
                        // Fallback: Jika tidak ada file baru di jendela waktu tersebut, tampilkan berkas terbaru yang ada
                        val recentFiles = fileTools.searchFilesAdvanced(
                            query = cleanQuery,
                            sortBy = "newest",
                            extensions = extensions,
                            specificFolder = targetFolder
                        )
                        if (recentFiles.isNotEmpty()) {
                            RouterResponse("Tidak ada berkas dalam $maxAgeHours jam terakhir. Berikut berkas terbaru di perangkat Anda:", recentFiles.take(8))
                        } else {
                            RouterResponse("Tidak ditemukan berkas yang memenuhi kriteria pencarian.")
                        }
                    } else {
                        RouterResponse("Tidak ditemukan berkas yang memenuhi kriteria pencarian.")
                    }
                } else {
                    val kriteriaText = when {
                        maxAgeHours != null -> " (dalam $maxAgeHours jam terakhir)"
                        extensions.isNotEmpty() -> " (tipe ${extensions.joinToString()})"
                        else -> ""
                    }
                    RouterResponse("Ditemukan ${foundFiles.size} berkas$kriteriaText yang cocok:", foundFiles)
                }
            }

            else -> {
                RouterResponse("Instruksi diterima: '$trimmed'.\nGunakan tombol cepat atau beri perintah spesifik seperti 'cari file pdf', 'cek file duplikat', atau 'buka kalkulator'.")
            }
        }
    }

    private suspend fun executeJsonAction(action: AgentAction): RouterResponse {
        return when (action.action.lowercase()) {
            "delete_file" -> RouterResponse(fileTools.deleteFileByName(action.file_name ?: action.query ?: ""))
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
                    RouterResponse(fileTools.cleanDuplicateFiles(action.folder, deleteDuplicates = true))
                } else {
                    val list = fileTools.getDuplicateFilesList(action.folder)
                    RouterResponse("Ditemukan ${list.size} file ganda:", list)
                }
            }
            "images_to_pdf" -> RouterResponse(fileTools.convertImagesToPdf(action.images ?: emptyList(), action.output_pdf ?: "TOWR_Result.pdf"))
            "open_app" -> RouterResponse(appLauncherTool.openAppByName(action.app_name ?: ""))
            "inspect_usage" -> RouterResponse(usageStatsTool.getRecentUsageSummary())
            "share_wa" -> RouterResponse(shareBridgeTool.shareTextToApp(action.text ?: "", "whatsapp"))
            else -> RouterResponse("Aksi '${action.action}' tidak dikenali oleh sistem TOWR.")
        }
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
            if (candidate.contains("\"action\"")) return candidate
        }
        return null
    }
}
