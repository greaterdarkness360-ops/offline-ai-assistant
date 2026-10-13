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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

class ActionRouter(private val context: Context) {

    val fileTools = FileTools(context)
    private val appLauncherTool = AppLauncherTool(context)
    private val usageStatsTool = UsageStatsTool(context)
    private val shareBridgeTool = ShareBridgeTool(context)

    private val prefs = context.getSharedPreferences("towr_prefs", Context.MODE_PRIVATE)

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
            // PINTASAN INSTAN CEK EMAIL
            lower.contains("cek email") || lower.contains("email masuk") || lower.contains("baca email") || lower.contains("ada email") -> {
                RouterResponse(fetchEmailsFromGas(null))
            }

            // EKSEKUSI PENGHAPUSAN FILE BERDASARKAN NAMA
            lower.startsWith("hapus file") || lower.startsWith("hapus") -> {
                if (lower.contains("ganda") || lower.contains("duplikat")) {
                    val msg = fileTools.cleanDuplicateFiles("Download", deleteDuplicates = true)
                    RouterResponse(msg)
                } else {
                    val targetName = trimmed
                        .replace(Regex("(?i)^(hapus file|hapus)\\s*"), "")
                        .replace(Regex("(?i)(semua|yang bernama|file|berkas)\\s*"), "")
                        .trim()

                    if (targetName.isBlank()) {
                        RouterResponse("Sebutkan nama file yang ingin dihapus.")
                    } else {
                        val msg = fileTools.deleteFileByName(targetName)
                        RouterResponse(msg)
                    }
                }
            }

            // KIRIM PESAN WHATSAPP
            lower.contains("whatsapp") || lower.contains("wa") -> {
                val waText = trimmed.replace(Regex("(?i)^(kirim ke wa|kirim wa|wa|whatsapp)\\s*[:,-]?\\s*"), "")
                RouterResponse(shareBridgeTool.shareTextToApp(waText, "whatsapp"))
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
                    RouterResponse("Tidak ditemukan file yang memenuhi kriteria pencarian.")
                } else {
                    RouterResponse("Ditemukan ${foundFiles.size} file yang cocok:", foundFiles)
                }
            }

            else -> {
                RouterResponse("Instruksi diterima: '$trimmed'.\nGunakan tombol cepat atau beri perintah spesifik.")
            }
        }
    }

    private suspend fun executeJsonAction(action: AgentAction): RouterResponse {
        return when (action.action.lowercase()) {
            // INTEGRASI EMAIL (GOOGLE APPS SCRIPT)
            "check_email", "fetch_emails", "get_emails", "read_emails", "list_emails", "email" -> {
                RouterResponse(fetchEmailsFromGas(action.query))
            }
            "delete_file" -> {
                val target = action.file_name ?: action.query ?: ""
                RouterResponse(fileTools.deleteFileByName(target))
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

    private suspend fun fetchEmailsFromGas(query: String?): String = withContext(Dispatchers.IO) {
        val rawUrl = prefs.getString("gas_webhook_url", "")?.trim() ?: ""
        if (rawUrl.isBlank()) {
            return@withContext "⚠️ URL Webhook GAS belum diisi.\n\nBuka Pengaturan [⚙️] di kanan atas dan tempelkan Webhook URL dari Google Apps Script Anda."
        }
        if (!rawUrl.endsWith("/exec")) {
            return@withContext "⚠️ URL Webhook GAS harus berakhiran '/exec'.\n\nURL tersimpan saat ini: $rawUrl\nSilakan periksa kembali tautan Web App Anda di menu Pengaturan [⚙️]."
        }

        try {
            val qParam = if (!query.isNullOrBlank()) "&q=" + URLEncoder.encode(query, "UTF-8") else ""
            val targetUrl = "$rawUrl?action=get_inbox$qParam"

            val responseBody = executeHttpGetWithRedirect(targetUrl)
            parseGasEmailResponse(responseBody)
        } catch (e: Exception) {
            "❌ Gagal mengambil email dari Google Apps Script:\n${e.localizedMessage ?: "Koneksi terputus atau timeout."}"
        }
    }

    private fun executeHttpGetWithRedirect(initialUrl: String): String {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TOWR-Android-Assistant")
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == 307 || code == 308) {
                val newLocation = conn.getHeaderField("Location")
                conn.disconnect()
                if (!newLocation.isNullOrBlank()) {
                    currentUrl = newLocation
                    redirects++
                    continue
                }
            }

            if (code in 200..299) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return response
            } else {
                val errStream = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                conn.disconnect()
                throw Exception("Server GAS merespons HTTP $code: $errStream")
            }
        }
        throw Exception("Terlalu banyak pengalihan (redirect) dari Google Apps Script.")
    }

    private fun parseGasEmailResponse(jsonString: String): String {
        return try {
            val root = JSONObject(jsonString)
            val status = root.optString("status", "")
            if (status != "success") {
                val errMsg = root.optString("message", "Terjadi kendala pada skrip GAS.")
                return "⚠️ Respon Google Apps Script: $errMsg"
            }

            val emailsArray = root.optJSONArray("emails")
            val count = emailsArray?.length() ?: 0
            if (count == 0) {
                return "📬 Kotak Masuk Bersih!\nTidak ada email belum dibaca di akun Google Anda."
            }

            val sb = StringBuilder()
            sb.append("📬 Ditemukan $count Email Masuk Terbaru:\n\n")

            for (i in 0 until count) {
                val emailObj = emailsArray!!.getJSONObject(i)
                val from = emailObj.optString("from", "Pengirim Tidak Diketahui")
                val subject = emailObj.optString("subject", "(Tanpa Subjek)")
                val snippet = emailObj.optString("snippet", "")

                sb.append("📩 [${i + 1}] $subject\n")
                sb.append("   • Dari: $from\n")
                if (snippet.isNotBlank()) {
                    val cleanSnippet = snippet.take(120).replace("\n", " ").trim()
                    sb.append("   • Cuplikan: \"$cleanSnippet...\"\n")
                }
                if (i < count - 1) sb.append("\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "Respon GAS diterima namun format tidak standar:\n${jsonString.take(300)}"
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
            if (candidate.contains("\"action\"")) {
                return candidate
            }
        }
        return null
    }
}
