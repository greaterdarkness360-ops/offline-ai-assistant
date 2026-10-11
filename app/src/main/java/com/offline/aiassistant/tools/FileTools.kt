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
        // Menyimpan daftar berkas dari pencarian terakhir
        private val activeSearchResults = mutableListOf<File>()
    }

    private val storageRoot: File?
        get() = Environment.getExternalStorageDirectory()

    private fun getSearchableDirectories(specificFolder: String? = null): List<File> {
        val root = storageRoot ?: return emptyList()

        if (!specificFolder.isNullOrBlank()) {
            val target = File(root, specificFolder)
            if (target.exists() && target.canRead()) return listOf(target)
        }

        val targetDirs = mutableListOf<File>()
        val standardFolders = listOf("Documents", "Dokumen", "Download", "DCIM", "Pictures", "Movies", "Music")

        for (name in standardFolders) {
            val f = File(root, name)
            if (f.exists() && f.canRead()) targetDirs.add(f)
        }

        val waMedia = File(root, "Android/media/com.whatsapp/WhatsApp/Media")
        if (waMedia.exists() && waMedia.canRead()) targetDirs.add(waMedia)
        val waOld = File(root, "WhatsApp/Media")
        if (waOld.exists() && waOld.canRead()) targetDirs.add(waOld)
        val tele = File(root, "Telegram")
        if (tele.exists() && tele.canRead()) targetDirs.add(tele)

        val customDirs = root.listFiles { file ->
            file.isDirectory && !file.name.startsWith(".") && !file.name.equals("Android", ignoreCase = true) &&
            !standardFolders.any { it.equals(file.name, ignoreCase = true) }
        } ?: emptyArray()

        targetDirs.addAll(customDirs)
        return targetDirs
    }

    /**
     * Menerjemahkan nomor urut atau nama berkas dari memori pencarian sebelumnya
     */
    fun resolveFilePath(identifier: String): String {
        val clean = identifier.trim().lowercase()
        val numMatch = Regex("\\d+").find(clean)
        val num = numMatch?.value?.toIntOrNull()

        // 1. Jika pengguna merujuk nomor (misal "nomor 2", "kedua", "2")
        if (num != null && num in 1..activeSearchResults.size) {
            return activeSearchResults[num - 1].absolutePath
        }

        // 2. Jika merujuk sebagian nama file dari hasil pencarian terakhir
        val matchedInActive = activeSearchResults.firstOrNull { 
            it.name.contains(clean, ignoreCase = true) 
        }
        if (matchedInActive != null) return matchedInActive.absolutePath

        // 3. Jika berkas fisik sudah valid
        val directFile = File(identifier)
        if (directFile.exists()) return directFile.absolutePath

        // 4. Cari cepat di penyimpanan
        val found = searchFilesAdvanced(query = clean)
        if (found.isNotEmpty()) return found.first().path

        return identifier
    }

    fun searchFilesAdvanced(
        query: String = "",
        minSizeMb: Double? = null,
        maxSizeMb: Double? = null,
        sortBy: String? = null,
        maxAgeHours: Long? = null,
        extensions: List<String> = emptyList(),
        specificFolder: String? = null
    ): List<FileItemInfo> {
        activeSearchResults.clear()
        val results = mutableListOf<FileItemInfo>()
        val root = storageRoot ?: return results

        val currentTime = System.currentTimeMillis()
        val maxAgeMs = maxAgeHours?.let { it * 3600 * 1000 }
        val minBytes = minSizeMb?.let { (it * 1024 * 1024).toLong() }
        val maxBytes = maxSizeMb?.let { (it * 1024 * 1024).toLong() }
        val normalizedExts = extensions.map { it.lowercase().trim().removePrefix(".") }

        for (dir in getSearchableDirectories(specificFolder)) {
            scanDirFast(
                dir = dir,
                query = query.trim(),
                minBytes = minBytes,
                maxBytes = maxBytes,
                currentTime = currentTime,
                maxAgeMs = maxAgeMs,
                extensions = normalizedExts,
                output = results,
                depth = 0
            )
        }

        val sorted = when (sortBy?.lowercase()) {
            "oldest" -> results.sortedBy { it.lastModifiedTimestamp }
            "newest" -> results.sortedByDescending { it.lastModifiedTimestamp }
            "largest" -> results.sortedByDescending { it.sizeBytes }
            else -> results.sortedByDescending { it.lastModifiedTimestamp }
        }

        return sorted.take(50)
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
        if (depth > 3) return
        val files = dir.listFiles() ?: return

        for (file in files) {
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

    fun getDuplicateFilesList(folderName: String? = null): List<FileItemInfo> {
        val dirs = getSearchableDirectories(folderName)
        val allFiles = mutableListOf<File>()

        for (dir in dirs) {
            dir.walkTopDown().maxDepth(3).filter { 
                it.isFile && !it.name.startsWith(".") && it.length() > 1024 
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
            val matched = dir.walkTopDown().maxDepth(3)
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
