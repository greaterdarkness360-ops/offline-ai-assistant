package com.offline.aiassistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =========================================================================
// MODEL DATA BERKAS (Mendukung ActionRouter & MainActivity)
// =========================================================================
data class FileItemInfo(
    val name: String,
    val path: String,
    val size: String = "",
    val lastModified: String = "",
    val extension: String = "",
    val sizeBytes: Long = 0L,
    val lastModifiedTimestamp: Long = 0L
) {
    // Properti pendukung agar langsung cocok dengan MainActivity
    val fileName: String get() = name
    val filePath: String get() = path
    val fileSizeFormatted: String get() = size
    val lastModifiedFormatted: String get() = lastModified
}

typealias FileSearchResult = FileItemInfo

// =========================================================================
// OBJEK FILE TOOLS
// =========================================================================
object FileTools {

    // Menyimpan daftar hasil pencarian terakhir di memori
    // Kunci untuk fitur: "Hapus semua kecuali nomor 9"
    private val activeSearchResults = mutableListOf<File>()

    /**
     * Pencarian utama: Mendukung filter nama, ekstensi ganda, dan rentang jam
     */
    fun searchFiles(
        query: String = "",
        extensions: List<String> = emptyList(),
        maxAgeHours: Long? = null
    ): List<FileItemInfo> {
        activeSearchResults.clear()
        val results = mutableListOf<FileItemInfo>()

        val storageRoot = Environment.getExternalStorageDirectory()
        if (storageRoot == null || !storageRoot.exists()) {
            return results
        }

        val targetDirs = listOf(
            File(storageRoot, "Documents"),
            File(storageRoot, "Download"),
            File(storageRoot, "DCIM"),
            storageRoot
        )

        val currentTime = System.currentTimeMillis()
        val maxAgeMs = maxAgeHours?.let { it * 3600 * 1000 }
        val normalizedExtensions = extensions.map { it.lowercase().trim().removePrefix(".") }

        for (dir in targetDirs) {
            if (dir.exists() && dir.canRead()) {
                scanDirectory(dir, query, normalizedExtensions, currentTime, maxAgeMs, results)
            }
        }

        return results
    }

    /**
     * Kompatibilitas untuk pemanggilan sederhana dari ActionRouter lama
     */
    fun searchFiles(query: String): List<FileItemInfo> {
        return searchFiles(query = query, extensions = emptyList(), maxAgeHours = null)
    }

    fun searchLocal(query: String): List<FileItemInfo> {
        return searchFiles(query = query, extensions = emptyList(), maxAgeHours = null)
    }

    private fun scanDirectory(
        dir: File,
        query: String,
        extensions: List<String>,
        currentTime: Long,
        maxAgeMs: Long?,
        output: MutableList<FileItemInfo>,
        depth: Int = 0
    ) {
        if (depth > 4) return

        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                if (!file.name.startsWith(".")) {
                    scanDirectory(file, query, extensions, currentTime, maxAgeMs, output, depth + 1)
                }
            } else {
                val matchesQuery = query.isBlank() || file.name.contains(query, ignoreCase = true)
                val matchesExt = extensions.isEmpty() || extensions.contains(file.extension.lowercase())
                val matchesTime = if (maxAgeMs != null) {
                    (currentTime - file.lastModified()) <= maxAgeMs
                } else {
                    true
                }

                if (matchesQuery && matchesExt && matchesTime) {
                    activeSearchResults.add(file)
                    output.add(
                        FileItemInfo(
                            name = file.name,
                            path = file.absolutePath,
                            size = formatFileSize(file.length()),
                            lastModified = formatDate(file.lastModified()),
                            extension = file.extension.uppercase(),
                            sizeBytes = file.length(),
                            lastModifiedTimestamp = file.lastModified()
                        )
                    )
                }
            }
        }
    }

    /**
     * Aksi Selektif: "Hapus semua kecuali nomor X"
     */
    fun deleteFilesExcept(keepIndex: Int): String {
        if (activeSearchResults.isEmpty()) {
            return "Tidak ada daftar berkas aktif dari pencarian sebelumnya."
        }

        val zeroBasedKeepIndex = keepIndex - 1
        if (zeroBasedKeepIndex !in activeSearchResults.indices) {
            return "Nomor urut $keepIndex tidak ditemukan dalam daftar hasil pencarian."
        }

        var deletedCount = 0
        var failedCount = 0
        val keptFile = activeSearchResults[zeroBasedKeepIndex]
        val filesToDelete = activeSearchResults.filterIndexed { index, _ -> index != zeroBasedKeepIndex }

        for (file in filesToDelete) {
            try {
                if (file.exists() && file.delete()) {
                    deletedCount++
                } else {
                    failedCount++
                }
            } catch (e: Exception) {
                failedCount++
            }
        }

        activeSearchResults.clear()
        activeSearchResults.add(keptFile)

        return "Berhasil menghapus $deletedCount berkas. Berkas nomor $keepIndex ('${keptFile.name}') tetap aman disimpan."
    }

    /**
     * Berbagi berkas ke WhatsApp
     */
    fun shareFileToWhatsApp(context: Context, filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "Berkas tidak ditemukan: $filePath", Toast.LENGTH_SHORT).show()
                return false
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "com.offline.aiassistant.provider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = when (file.extension.lowercase()) {
                    "pdf" -> "application/pdf"
                    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    "jpg", "jpeg", "png" -> "image/*"
                    else -> "*/*"
                }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("com.whatsapp")
            }

            try {
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(shareIntent)
                true
            } catch (e: Exception) {
                val chooser = Intent.createChooser(shareIntent.apply { setPackage(null) }, "Bagikan berkas via...")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                true
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membagikan berkas: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
