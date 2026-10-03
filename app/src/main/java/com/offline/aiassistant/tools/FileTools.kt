package com.offline.aiassistant.tools

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileItemInfo(
    val name: String,
    val path: String,
    val size: String = "",
    val lastModified: String = "",
    val extension: String = "",
    val sizeBytes: Long = 0L,
    val lastModifiedTimestamp: Long = 0L
) {
    val fileName: String get() = name
    val filePath: String get() = path
    val fileSizeFormatted: String get() = size
    val lastModifiedFormatted: String get() = lastModified
}

typealias FileSearchResult = FileItemInfo

class FileTools(private val context: Context) {

    companion object {
        private val activeSearchResults = mutableListOf<File>()
    }

    private val storageRoot: File?
        get() = Environment.getExternalStorageDirectory()

    // Memindai folder utama dan subfolder penting tanpa menyentuh cache/sistem
    private fun getSearchableDirectories(specificFolder: String? = null): List<File> {
        val root = storageRoot ?: return emptyList()

        if (!specificFolder.isNullOrBlank()) {
            val target = File(root, specificFolder)
            if (target.exists() && target.canRead()) return listOf(target)
        }

        // Folder prioritas utama
        val defaultDirs = listOf(
            File(root, "Download"),
            File(root, "Documents"),
            File(root, "DCIM"),
            File(root, "Pictures"),
            File(root, "Movies"),
            File(root, "Music"),
            File(root, "Android/media/com.whatsapp/WhatsApp/Media"),
            File(root, "WhatsApp/Media"),
            File(root, "Telegram")
        ).filter { it.exists() && it.canRead() }

        // Tambahkan root folder level-1 (folder buatan pengguna)
        val otherUserDirs = root.listFiles { file ->
            file.isDirectory && !file.name.startsWith(".") && file.name != "Android"
        }?.toList() ?: emptyList()

        return (defaultDirs + otherUserDirs + listOf(root)).distinctBy { it.absolutePath }
    }

    /**
     * Pencarian Lanjutan: Mendukung query nama fleksibel, ukuran, rentang jam, dan ekstensi
     */
    fun searchFilesAdvanced(
        query: String = "",
        minSizeMb: Double? = null,
        maxSizeMb: Double? = null,
        sortBy: String? = null,
        maxAgeHours: Long? = null,
        extensions: List<String> = emptyList()
    ): List<FileItemInfo> {
        activeSearchResults.clear()
        val results = mutableListOf<FileItemInfo>()
        val root = storageRoot ?: return results

        val currentTime = System.currentTimeMillis()
        val maxAgeMs = maxAgeHours?.let { it * 3600 * 1000 }
        val minBytes = minSizeMb?.let { (it * 1024 * 1024).toLong() }
        val maxBytes = maxSizeMb?.let { (it * 1024 * 1024).toLong() }
        val normalizedExts = extensions.map { it.lowercase().trim().removePrefix(".") }

        val visitedDirs = mutableSetOf<String>()

        for (dir in getSearchableDirectories()) {
            if (visitedDirs.add(dir.absolutePath)) {
                scanDirFast(
                    dir = dir,
                    query = query.trim(),
                    minBytes = minBytes,
                    maxBytes = maxBytes,
                    currentTime = currentTime,
                    maxAgeMs = maxAgeMs,
                    extensions = normalizedExts,
                    output = results
                )
            }
        }

        // Urutkan hasil
        val sorted = when (sortBy?.lowercase()) {
            "oldest" -> results.sortedBy { it.lastModifiedTimestamp }
            "newest" -> results.sortedByDescending { it.lastModifiedTimestamp }
            "largest" -> results.sortedByDescending { it.sizeBytes }
            else -> results.sortedByDescending { it.lastModifiedTimestamp }
        }

        return sorted.take(60) // Batasi 60 berkas teratas agar UI tetap sangat ringan
    }

    private fun scanDirFast(
        dir: File,
        query: String,
        minBytes: Long?,
        maxBytes: Long?,
        currentTime: Long,
        maxAgeMs: Long?,
        extensions: List<String>,
        output: MutableList<FileItemInfo>,
        depth: Int = 0
    ) {
        if (depth > 4) return
        val files = dir.listFiles() ?: return

        for (file in files) {
            // Lewati folder sampah, thumbnail, cache
            val nameLower = file.name.lowercase()
            if (file.isDirectory) {
                if (!file.name.startsWith(".") && 
                    !nameLower.contains("cache") && 
                    !nameLower.contains("thumbnail")
                ) {
                    scanDirFast(file, query, minBytes, maxBytes, currentTime, maxAgeMs, extensions, output, depth + 1)
                }
            } else {
                val matchesQuery = isFlexibleMatch(file.name, query)
                val matchesMin = minBytes == null || file.length() >= minBytes
                val matchesMax = maxBytes == null || file.length() <= maxBytes
                val matchesAge = maxAgeMs == null || ((currentTime - file.lastModified()) <= maxAgeMs)
                val matchesExt = extensions.isEmpty() || extensions.contains(file.extension.lowercase())

                if (matchesQuery && matchesMin && matchesMax && matchesAge && matchesExt) {
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

    private fun isFlexibleMatch(fileName: String, query: String): Boolean {
        if (query.isBlank()) return true
        val cleanName = fileName.lowercase().replace("[-_.]".toRegex(), " ")
        val keywords = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        return keywords.all { cleanName.contains(it) }
    }

    /**
     * Cek berkas duplikat di seluruh penyimpanan atau folder tertentu
     */
    fun getDuplicateFilesList(folderName: String? = null): List<FileItemInfo> {
        val dirs = getSearchableDirectories(folderName)
        val allFiles = mutableListOf<File>()

        for (dir in dirs) {
            dir.walkTopDown().maxDepth(3).filter { 
                it.isFile && !it.name.startsWith(".") && it.length() > 1024 // lewati file kosong 0kb
            }.forEach { allFiles.add(it) }
        }

        val grouped = allFiles.groupBy { "${it.name.lowercase()}_${it.length()}" }
        val duplicates = mutableListOf<FileItemInfo>()

        for ((_, fileList) in grouped) {
            if (fileList.size > 1) {
                for (duplicateFile in fileList.drop(1)) {
                    duplicates.add(
                        FileItemInfo(
                            name = duplicateFile.name,
                            path = duplicateFile.absolutePath,
                            size = formatFileSize(duplicateFile.length()),
                            lastModified = formatDate(duplicateFile.lastModified()),
                            extension = duplicateFile.extension.uppercase(),
                            sizeBytes = duplicateFile.length(),
                            lastModifiedTimestamp = duplicateFile.lastModified()
                        )
                    )
                }
            }
        }
        return duplicates.take(50)
    }

    fun cleanDuplicateFiles(folderName: String? = null, deleteDuplicates: Boolean = false): String {
        val duplicates = getDuplicateFilesList(folderName)
        if (duplicates.isEmpty()) {
            return "Tidak ditemukan berkas duplikat di penyimpanan Anda."
        }

        return if (deleteDuplicates) {
            var deletedCount = 0
            for (item in duplicates) {
                val f = File(item.path)
                if (f.exists() && f.delete()) deletedCount++
            }
            "Berhasil membersihkan $deletedCount berkas duplikat."
        } else {
            "Ditemukan ${duplicates.size} berkas duplikat yang siap dibersihkan."
        }
    }

    fun deleteFileByName(targetName: String): String {
        if (targetName.isBlank()) return "Nama berkas tidak boleh kosong."

        var deletedCount = 0
        for (dir in getSearchableDirectories()) {
            val matched = dir.walkTopDown().maxDepth(4)
                .filter { it.isFile && isFlexibleMatch(it.name, targetName) }
                .toList()

            for (file in matched) {
                if (file.delete()) deletedCount++
            }
        }

        return if (deletedCount > 0) {
            "Berhasil menghapus $deletedCount berkas terkait '$targetName'."
        } else {
            "Tidak ditemukan berkas dengan kata kunci '$targetName'."
        }
    }

    fun convertImagesToPdf(imageNames: List<String>, outputPdfName: String = "Dokumen_TOWR.pdf"): String {
        if (imageNames.isEmpty()) return "Pilih nama gambar yang ingin diubah ke PDF."
        val root = storageRoot ?: return "Penyimpanan tidak tersedia."

        val allImages = mutableListOf<File>()
        for (dir in getSearchableDirectories()) {
            val found = dir.walkTopDown().maxDepth(3)
                .filter { it.isFile && imageNames.any { img -> it.name.contains(img, ignoreCase = true) } }
                .toList()
            allImages.addAll(found)
        }

        if (allImages.isEmpty()) return "Berkas gambar tidak ditemukan di memori."

        val pdfDocument = PdfDocument()
        try {
            var pageIndex = 1
            for (imageFile in allImages.distinctBy { it.absolutePath }) {
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: continue
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pageIndex++).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)
                bitmap.recycle()
            }

            val outputFile = File(File(root, "Documents").apply { mkdirs() }, outputPdfName)
            FileOutputStream(outputFile).use { out -> pdfDocument.writeTo(out) }
            return "Berhasil membuat PDF: ${outputFile.name} di folder Documents."
        } catch (e: Exception) {
            return "Gagal membuat PDF: ${e.localizedMessage}"
        } finally {
            pdfDocument.close()
        }
    }

    fun deleteFilesExcept(keepIndex: Int): String {
        if (activeSearchResults.isEmpty()) return "Tidak ada daftar berkas aktif dari pencarian sebelumnya."
        val zeroIndex = keepIndex - 1
        if (zeroIndex !in activeSearchResults.indices) return "Nomor urut $keepIndex tidak ditemukan."

        var deletedCount = 0
        val keptFile = activeSearchResults[zeroIndex]
        val filesToDelete = activeSearchResults.filterIndexed { index, _ -> index != zeroIndex }

        for (file in filesToDelete) {
            if (file.exists() && file.delete()) deletedCount++
        }

        activeSearchResults.clear()
        activeSearchResults.add(keptFile)
        return "Berhasil menghapus $deletedCount berkas. Berkas nomor $keepIndex ('${keptFile.name}') tetap aman."
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
