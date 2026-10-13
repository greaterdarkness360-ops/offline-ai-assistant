package com.offline.aiassistant.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        val lastSearchResults = mutableListOf<FileItemInfo>()
        var lastSearchQuery: String = ""
    }

    fun searchFilesAdvanced(
        query: String = "",
        minSizeMb: Double? = null,
        maxSizeMb: Double? = null,
        sortBy: String? = null
    ): List<FileItemInfo> {
        val cleanQuery = query.trim()
        lastSearchQuery = cleanQuery
        val foundMap = mutableMapOf<String, FileItemInfo>()

        val tokens = cleanQuery.lowercase()
            .split(Regex("[\\s_\\-]+"))
            .filter { it.isNotBlank() }

        try {
            queryMediaStore(tokens, foundMap)
        } catch (_: Exception) {}

        try {
            scanAllUserFolders(tokens, foundMap)
        } catch (_: Exception) {}

        var filteredList = foundMap.values.toList()

        if (minSizeMb != null) {
            val minBytes = (minSizeMb * 1024 * 1024).toLong()
            filteredList = filteredList.filter { it.sizeBytes >= minBytes }
        }
        if (maxSizeMb != null) {
            val maxBytes = (maxSizeMb * 1024 * 1024).toLong()
            filteredList = filteredList.filter { it.sizeBytes <= maxBytes }
        }

        filteredList = when (sortBy?.lowercase()) {
            "largest" -> filteredList.sortedByDescending { it.sizeBytes }
            "oldest" -> filteredList.sortedBy { it.lastModifiedTimestamp }
            else -> filteredList.sortedByDescending { it.lastModifiedTimestamp }
        }

        lastSearchResults.clear()
        lastSearchResults.addAll(filteredList)

        return filteredList
    }

    fun searchFiles(query: String): List<FileItemInfo> = searchFilesAdvanced(query)
    fun searchLocal(query: String): List<FileItemInfo> = searchFilesAdvanced(query)

    /**
     * Resolusi Fleksibel: Menangani Hapus Berkas Tunggal Maupun Massal ("keduanya", "semua", dll)
     */
    fun resolveTargetFiles(reference: String): List<FileItemInfo> {
        val clean = reference.trim().lowercase()

        // 1. Cek apakah meminta hapus massal
        if (clean.contains("keduanya") || clean.contains("semua") || clean.contains("semuanya") || clean.contains("seluruh")) {
            if (lastSearchResults.isNotEmpty()) {
                return lastSearchResults.toList()
            }
        }

        // 2. Cek apakah merujuk nomor urut tunggal
        val targetIndex: Int? = when {
            clean.contains("pertama") || clean.contains("kesatu") || clean == "1" || clean.contains("ke-1") || clean.contains("ke 1") || clean.contains("nomor 1") || clean.contains("no 1") -> 0
            clean.contains("kedua") || clean == "2" || clean.contains("ke-2") || clean.contains("ke 2") || clean.contains("nomor 2") || clean.contains("no 2") -> 1
            clean.contains("ketiga") || clean == "3" || clean.contains("ke-3") || clean.contains("ke 3") || clean.contains("nomor 3") || clean.contains("no 3") -> 2
            clean.contains("keempat") || clean == "4" || clean.contains("ke-4") || clean.contains("ke 4") || clean.contains("nomor 4") || clean.contains("no 4") -> 3
            clean.contains("kelima") || clean == "5" || clean.contains("ke-5") || clean.contains("ke 5") || clean.contains("nomor 5") || clean.contains("no 5") -> 4
            clean.contains("terakhir") -> if (lastSearchResults.isNotEmpty()) lastSearchResults.lastIndex else null
            else -> {
                val numMatch = Regex("(?:file|nomor|no|ke)?\\s*(\\d+)").find(clean)
                val num = numMatch?.groupValues?.get(1)?.toIntOrNull()
                if (num != null && num > 0) num - 1 else null
            }
        }

        if (targetIndex != null && targetIndex in lastSearchResults.indices) {
            return listOf(lastSearchResults[targetIndex])
        }

        // 3. Cek kecocokan nama dalam hasil pencarian terakhir
        val matchedInRecent = lastSearchResults.filter {
            it.name.contains(clean, ignoreCase = true) || clean.contains(it.name.substringBeforeLast(".").lowercase())
        }
        if (matchedInRecent.isNotEmpty()) return matchedInRecent

        // 4. Cari langsung di penyimpanan jika belum ada di memori
        val directSearch = searchFilesAdvanced(clean)
        return directSearch
    }

    fun deletePhysicalFiles(filePaths: List<String>): String {
        var successCount = 0
        var failCount = 0

        for (path in filePaths) {
            try {
                val f = File(path)
                if (f.exists() && f.delete()) {
                    successCount++
                    lastSearchResults.removeAll { it.path == path }
                } else {
                    failCount++
                }
            } catch (_: Exception) {
                failCount++
            }
        }

        return when {
            failCount == 0 -> "Berhasil menghapus $successCount berkas secara permanen."
            successCount > 0 -> "Berhasil menghapus $successCount berkas, $failCount berkas gagal dihapus."
            else -> "Gagal menghapus berkas yang dipilih."
        }
    }

    private fun queryMediaStore(tokens: List<String>, outputMap: MutableMap<String, FileItemInfo>) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val selection = if (tokens.isNotEmpty()) {
            tokens.joinToString(" AND ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? COLLATE NOCASE" }
        } else null
        val selectionArgs = if (tokens.isNotEmpty()) {
            tokens.map { "%$it%" }.toTypedArray()
        } else null

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val path = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                val name = if (nameCol != -1) cursor.getString(nameCol) ?: File(path).name else File(path).name
                val sizeBytes = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                val dateSec = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                val dateMs = dateSec * 1000L

                if (path.isNotBlank() && !path.contains("/Android/data") && !path.contains("/Android/obb")) {
                    val file = File(path)
                    val ext = file.extension.uppercase()
                    outputMap[path] = FileItemInfo(
                        name = name,
                        path = path,
                        size = formatFileSize(sizeBytes),
                        lastModified = formatDate(dateMs),
                        extension = ext,
                        sizeBytes = sizeBytes,
                        lastModifiedTimestamp = dateMs
                    )
                }
            }
        }
    }

    private fun scanAllUserFolders(tokens: List<String>, outputMap: MutableMap<String, FileItemInfo>) {
        val storageRoot = Environment.getExternalStorageDirectory() ?: return
        val targetFolders = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            File(storageRoot, "WhatsApp/Media/WhatsApp Documents"),
            File(storageRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"),
            File(storageRoot, "Telegram/Telegram Documents")
        )

        for (folder in targetFolders) {
            if (folder.exists() && folder.canRead()) {
                scanFolderRecursively(folder, tokens, outputMap, depth = 0)
            }
        }

        storageRoot.listFiles()?.forEach { rootChild ->
            if (rootChild.isDirectory && !rootChild.name.startsWith(".") && rootChild.name != "Android") {
                scanFolderRecursively(rootChild, tokens, outputMap, depth = 0)
            }
        }
    }

    private fun scanFolderRecursively(dir: File, tokens: List<String>, outputMap: MutableMap<String, FileItemInfo>, depth: Int) {
        if (depth > 8) return
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                if (!file.name.startsWith(".") && file.name != "Android") {
                    scanFolderRecursively(file, tokens, outputMap, depth + 1)
                }
            } else {
                val fileNameLower = file.name.lowercase()
                val isMatch = if (tokens.isEmpty()) true else tokens.all { token -> fileNameLower.contains(token) }

                if (isMatch) {
                    val path = file.absolutePath
                    if (!outputMap.containsKey(path)) {
                        outputMap[path] = FileItemInfo(
                            name = file.name,
                            path = path,
                            size = formatFileSize(file.length()),
                            lastModified = formatDate(file.lastModified()),
                            extension = file.extension.uppercase(),
                            sizeBytes = file.length(),
                            lastModifiedTimestamp = file.lastModified()
                        )
                    }
                }
            }
        }
    }

    fun cleanDuplicateFiles(folderName: String = "Download", deleteDuplicates: Boolean = false): String {
        val targetDir = File(Environment.getExternalStorageDirectory(), folderName)
        if (!targetDir.exists() || !targetDir.isDirectory) return "Folder $folderName tidak ditemukan."

        val fileList = targetDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val nameSizeMap = mutableMapOf<String, File>()
        val duplicates = mutableListOf<File>()

        for (file in fileList) {
            val key = "${file.name.lowercase()}_${file.length()}"
            if (nameSizeMap.containsKey(key)) {
                duplicates.add(file)
            } else {
                nameSizeMap[key] = file
            }
        }

        if (duplicates.isEmpty()) return "Tidak ditemukan berkas duplikat di folder $folderName."

        if (deleteDuplicates) {
            var count = 0
            duplicates.forEach { if (it.delete()) count++ }
            return "Berhasil menghapus $count berkas duplikat dari folder $folderName."
        }

        return "Ditemukan ${duplicates.size} berkas duplikat di folder $folderName."
    }

    fun getDuplicateFilesList(folderName: String = "Download"): List<FileItemInfo> {
        val targetDir = File(Environment.getExternalStorageDirectory(), folderName)
        if (!targetDir.exists() || !targetDir.isDirectory) return emptyList()

        val fileList = targetDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val nameSizeMap = mutableMapOf<String, File>()
        val duplicates = mutableListOf<FileItemInfo>()

        for (file in fileList) {
            val key = "${file.name.lowercase()}_${file.length()}"
            if (nameSizeMap.containsKey(key)) {
                duplicates.add(
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
            } else {
                nameSizeMap[key] = file
            }
        }
        return duplicates
    }

    fun convertImagesToPdf(imageNames: List<String>, outputPdfName: String): String {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val validImageFiles = mutableListOf<File>()

            for (name in imageNames) {
                val f = File(downloadDir, name)
                if (f.exists() && f.isFile) validImageFiles.add(f)
            }

            if (validImageFiles.isEmpty()) return "Tidak ada gambar di folder Download."

            val outputFile = File(downloadDir, outputPdfName)
            val pdfDocument = PdfDocument()

            for ((index, imgFile) in validImageFiles.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath) ?: continue
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)
                bitmap.recycle()
            }

            FileOutputStream(outputFile).use { out -> pdfDocument.writeTo(out) }
            pdfDocument.close()
            "Berhasil menggabungkan ${validImageFiles.size} gambar menjadi PDF: ${outputFile.name}"
        } catch (e: Exception) {
            "Gagal membuat PDF: ${e.localizedMessage}"
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
