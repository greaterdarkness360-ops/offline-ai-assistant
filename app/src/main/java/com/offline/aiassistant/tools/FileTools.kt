package com.offline.aiassistant.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
    val fileName: String get() = name
    val filePath: String get() = path
    val fileSizeFormatted: String get() = size
    val lastModifiedFormatted: String get() = lastModified
}

typealias FileSearchResult = FileItemInfo

// =========================================================================
// KELAS FILE TOOLS
// =========================================================================
class FileTools(private val context: Context) {

    companion object {
        // Menyimpan daftar hasil pencarian terakhir di memori
        // Kunci untuk fitur: "Hapus semua kecuali nomor X"
        private val activeSearchResults = mutableListOf<File>()
    }

    private val storageRoot: File?
        get() = Environment.getExternalStorageDirectory()

    private fun getTargetDirectories(): List<File> {
        val root = storageRoot ?: return emptyList()
        return listOf(
            File(root, "Download"),
            File(root, "Documents"),
            File(root, "DCIM"),
            File(root, "Pictures")
        ).filter { it.exists() && it.canRead() }
    }

    /**
     * Pencarian Lanjutan untuk ActionRouter: filter ukuran (MB) & sorting
     */
    fun searchFilesAdvanced(
        query: String = "",
        minSizeMb: Double? = null,
        maxSizeMb: Double? = null,
        sortBy: String? = null
    ): List<FileItemInfo> {
        activeSearchResults.clear()
        val results = mutableListOf<FileItemInfo>()
        val minBytes = minSizeMb?.let { (it * 1024 * 1024).toLong() }
        val maxBytes = maxSizeMb?.let { (it * 1024 * 1024).toLong() }

        val root = storageRoot
        if (root == null || !root.exists()) return results

        for (dir in getTargetDirectories()) {
            scanDirectoryAdvanced(dir, query.trim(), minBytes, maxBytes, results)
        }

        // Sorting
        return when (sortBy?.lowercase()) {
            "oldest" -> results.sortedBy { it.lastModifiedTimestamp }
            "newest" -> results.sortedByDescending { it.lastModifiedTimestamp }
            "largest" -> results.sortedByDescending { it.sizeBytes }
            else -> results.sortedByDescending { it.lastModifiedTimestamp }
        }
    }

    private fun scanDirectoryAdvanced(
        dir: File,
        query: String,
        minBytes: Long?,
        maxBytes: Long?,
        output: MutableList<FileItemInfo>,
        depth: Int = 0
    ) {
        if (depth > 4) return
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                if (!file.name.startsWith(".")) {
                    scanDirectoryAdvanced(file, query, minBytes, maxBytes, output, depth + 1)
                }
            } else {
                val matchesQuery = query.isBlank() || file.name.contains(query, ignoreCase = true)
                val matchesMin = minBytes == null || file.length() >= minBytes
                val matchesMax = maxBytes == null || file.length() <= maxBytes

                if (matchesQuery && matchesMin && matchesMax) {
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
     * Hapus berkas berdasarkan nama
     */
    fun deleteFileByName(targetName: String): String {
        if (targetName.isBlank()) return "Nama berkas tidak boleh kosong."

        var deletedCount = 0
        val root = storageRoot ?: return "Penyimpanan tidak dapat diakses."

        for (dir in getTargetDirectories()) {
            val matchedFiles = dir.walkTopDown()
                .maxDepth(4)
                .filter { it.isFile && it.name.contains(targetName, ignoreCase = true) }
                .toList()

            for (file in matchedFiles) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }

        return if (deletedCount > 0) {
            "Berhasil menghapus $deletedCount berkas dengan nama '$targetName'."
        } else {
            "Tidak ditemukan berkas dengan nama '$targetName'."
        }
    }

    /**
     * Mendapatkan daftar berkas duplikat di folder tertentu (default: Download)
     */
    fun getDuplicateFilesList(folderName: String = "Download"): List<FileItemInfo> {
        val root = storageRoot ?: return emptyList()
        val targetDir = File(root, folderName)
        if (!targetDir.exists() || !targetDir.canRead()) return emptyList()

        val files = targetDir.walkTopDown().maxDepth(3).filter { it.isFile }.toList()
        val groupedByNameAndSize = files.groupBy { "${it.name.lowercase()}_${it.length()}" }

        val duplicates = mutableListOf<FileItemInfo>()
        for ((_, fileList) in groupedByNameAndSize) {
            if (fileList.size > 1) {
                // Ambil file ke-2 dan seterusnya sebagai duplikat
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
        return duplicates
    }

    /**
     * Membersihkan berkas duplikat
     */
    fun cleanDuplicateFiles(folderName: String = "Download", deleteDuplicates: Boolean = false): String {
        val root = storageRoot ?: return "Penyimpanan tidak dapat diakses."
        val targetDir = File(root, folderName)
        if (!targetDir.exists()) return "Folder '$folderName' tidak ditemukan."

        val files = targetDir.walkTopDown().maxDepth(3).filter { it.isFile }.toList()
        val grouped = files.groupBy { "${it.name.lowercase()}_${it.length()}" }

        var duplicateCount = 0
        var deletedCount = 0

        for ((_, fileList) in grouped) {
            if (fileList.size > 1) {
                val toDelete = fileList.drop(1)
                duplicateCount += toDelete.size
                if (deleteDuplicates) {
                    for (file in toDelete) {
                        if (file.delete()) deletedCount++
                    }
                }
            }
        }

        return if (deleteDuplicates) {
            "Berhasil menghapus $deletedCount berkas duplikat dari folder $folderName."
        } else {
            "Ditemukan $duplicateCount berkas duplikat di folder $folderName."
        }
    }

    /**
     * Menggabungkan daftar gambar menjadi satu dokumen PDF
     */
    fun convertImagesToPdf(imageNames: List<String>, outputPdfName: String = "Dokumen_TOWR.pdf"): String {
        if (imageNames.isEmpty()) return "Tidak ada gambar yang dipilih untuk dijadikan PDF."

        val root = storageRoot ?: return "Penyimpanan tidak dapat diakses."
        val allImages = mutableListOf<File>()

        for (dir in getTargetDirectories()) {
            val found = dir.walkTopDown().maxDepth(3)
                .filter { it.isFile && imageNames.any { img -> it.name.equals(img, ignoreCase = true) } }
                .toList()
            allImages.addAll(found)
        }

        if (allImages.isEmpty()) {
            return "Berkas gambar yang dimaksud tidak ditemukan di penyimpanan."
        }

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
            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }

            return "Berhasil membuat PDF di: ${outputFile.absolutePath}"
        } catch (e: Exception) {
            return "Gagal membuat PDF: ${e.localizedMessage}"
        } finally {
            pdfDocument.close()
        }
    }

    /**
     * Pencarian standar
     */
    fun searchFiles(
        query: String = "",
        extensions: List<String> = emptyList(),
        maxAgeHours: Long? = null
    ): List<FileItemInfo> {
        activeSearchResults.clear()
        val results = mutableListOf<FileItemInfo>()
        val root = storageRoot ?: return results

        val currentTime = System.currentTimeMillis()
        val maxAgeMs = maxAgeHours?.let { it * 3600 * 1000 }
        val normalizedExtensions = extensions.map { it.lowercase().trim().removePrefix(".") }

        for (dir in getTargetDirectories()) {
            scanDirectory(dir, query, normalizedExtensions, currentTime, maxAgeMs, results)
        }

        return results
    }

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
    fun shareFileToWhatsApp(filePath: String): Boolean {
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
