package com.offline.aiassistant.tools

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        // Menyimpan daftar file dari hasil pencarian terakhir di RAM (untuk rujukan nomor urut)
        private val activeSearchResults = mutableListOf<File>()
    }

    /**
     * PENCARIAN BERKAS SUPER CEPAT MENGGUNAKAN ANDROID MEDIASTORE API
     * Kecepatan: 10 - 30 milidetik (Bypass disk scan)
     */
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

        val selectionClauses = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        // 1. Filter Nama Berkas (Multi-kata kunci fleksibel)
        if (query.isNotBlank()) {
            val keywords = query.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
            for (kw in keywords) {
                selectionClauses.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
                selectionArgs.add("%$kw%")
            }
        }

        // 2. Filter Ukuran Berkas
        minSizeMb?.let {
            val bytes = (it * 1024 * 1024).toLong()
            selectionClauses.add("${MediaStore.Files.FileColumns.SIZE} >= ?")
            selectionArgs.add(bytes.toString())
        }
        maxSizeMb?.let {
            val bytes = (it * 1024 * 1024).toLong()
            selectionClauses.add("${MediaStore.Files.FileColumns.SIZE} <= ?")
            selectionArgs.add(bytes.toString())
        }

        // 3. Filter Rentang Waktu (DATE_MODIFIED di MediaStore dihitung dalam satuan DETIK)
        maxAgeHours?.let {
            val minTimeSec = (System.currentTimeMillis() - (it * 3600 * 1000L)) / 1000L
            selectionClauses.add("${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ?")
            selectionArgs.add(minTimeSec.toString())
        }

        // 4. Filter Ekstensi
        if (extensions.isNotEmpty()) {
            val extClauses = extensions.map {
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            }
            selectionClauses.add("(${extClauses.joinToString(" OR ")})")
            for (ext in extensions) {
                val cleanExt = ext.lowercase().trim().removePrefix(".")
                selectionArgs.add("%.$cleanExt")
            }
        }

        // 5. Filter Folder Tertentu
        if (!specificFolder.isNullOrBlank()) {
            selectionClauses.add("${MediaStore.Files.FileColumns.DATA} LIKE ?")
            selectionArgs.add("%/$specificFolder/%")
        }

        // Lewati file kosong 0 B dan file cache tersembunyi
        selectionClauses.add("${MediaStore.Files.FileColumns.SIZE} > 0")
        selectionClauses.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} NOT LIKE '.%'")

        // 6. Pengurutan Kueri (Sort Order)
        val sortOrder = when (sortBy?.lowercase()) {
            "oldest" -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} ASC"
            "newest" -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            "largest" -> "${MediaStore.Files.FileColumns.SIZE} DESC"
            else -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        }

        try {
            val cursor = context.contentResolver.query(
                collection,
                projection,
                if (selectionClauses.isNotEmpty()) selectionClauses.joinToString(" AND ") else null,
                if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null,
                "$sortOrder LIMIT 100"
            )

            cursor?.use {
                val nameCol = it.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val sizeCol = it.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (it.moveToNext()) {
                    val name = if (nameCol != -1) it.getString(nameCol) ?: "" else ""
                    val path = if (pathCol != -1) it.getString(pathCol) ?: "" else ""
                    val sizeBytes = if (sizeCol != -1) it.getLong(sizeCol) else 0L
                    val dateSec = if (dateCol != -1) it.getLong(dateCol) else 0L
                    val dateMs = dateSec * 1000L
                    val ext = name.substringAfterLast('.', "").uppercase()

                    if (name.isBlank() || path.isBlank()) continue

                    val f = File(path)
                    if (f.exists()) {
                        activeSearchResults.add(f)
                    }

                    results.add(
                        FileItemInfo(
                            name = name,
                            path = path,
                            size = formatFileSize(sizeBytes),
                            lastModified = formatDate(dateMs),
                            extension = ext,
                            sizeBytes = sizeBytes,
                            lastModifiedTimestamp = dateMs
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Fallback cadangan jika MediaStore mengalami kendala sistem
            return searchFilesFallbackDisk(query, specificFolder)
        }

        return results.take(60)
    }

    // Cadangan darurat pemindaian disk jika kueri MediaStore kosong atau error
    private fun searchFilesFallbackDisk(query: String, folder: String?): List<FileItemInfo> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val target = if (!folder.isNullOrBlank()) File(root, folder) else File(root, "Documents")
        if (!target.exists()) return emptyList()

        return target.walkTopDown().maxDepth(2).filter { 
            it.isFile && !it.name.startsWith(".") && it.name.contains(query, ignoreCase = true) 
        }.map {
            FileItemInfo(
                name = it.name,
                path = it.absolutePath,
                size = formatFileSize(it.length()),
                lastModified = formatDate(it.lastModified()),
                extension = it.extension.uppercase(),
                sizeBytes = it.length(),
                lastModifiedTimestamp = it.lastModified()
            )
        }.take(30).toList()
    }

    /**
     * Menerjemahkan rujukan berkas (misal: "nomor 2", "blue soap") dari hasil pencarian terakhir
     */
    fun resolveFilePath(identifier: String): String {
        val clean = identifier.trim().lowercase()
        val numMatch = Regex("\\d+").find(clean)
        val num = numMatch?.value?.toIntOrNull()

        if (num != null && num in 1..activeSearchResults.size) {
            return activeSearchResults[num - 1].absolutePath
        }

        val matchedInActive = activeSearchResults.firstOrNull { 
            it.name.contains(clean, ignoreCase = true) 
        }
        if (matchedInActive != null) return matchedInActive.absolutePath

        val directFile = File(identifier)
        if (directFile.exists()) return directFile.absolutePath

        val found = searchFilesAdvanced(query = clean)
        if (found.isNotEmpty()) return found.first().path

        return identifier
    }

    /**
     * Deteksi berkas duplikat super cepat via MediaStore
     */
    fun getDuplicateFilesList(folderName: String? = null): List<FileItemInfo> {
        val allFiles = searchFilesAdvanced(specificFolder = folderName)
        val grouped = allFiles.groupBy { "${it.name.lowercase()}_${it.sizeBytes}" }
        val duplicates = mutableListOf<FileItemInfo>()

        for ((_, fileList) in grouped) {
            if (fileList.size > 1) {
                duplicates.addAll(fileList.drop(1))
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

        val found = searchFilesAdvanced(query = targetName)
        if (found.isEmpty()) return "Tidak ditemukan berkas dengan kata kunci '$targetName'."

        var deletedCount = 0
        for (item in found) {
            val f = File(item.path)
            if (f.exists() && f.delete()) {
                deletedCount++
            }
        }

        return if (deletedCount > 0) {
            "Berhasil menghapus $deletedCount berkas terkait '$targetName'."
        } else {
            "Gagal menghapus berkas '$targetName'."
        }
    }

    fun convertImagesToPdf(imageNames: List<String>, outputPdfName: String = "Dokumen_TOWR.pdf"): String {
        if (imageNames.isEmpty()) return "Pilih nama gambar yang ingin diubah ke PDF."
        val root = Environment.getExternalStorageDirectory() ?: return "Penyimpanan tidak tersedia."

        val allImages = mutableListOf<File>()
        for (name in imageNames) {
            val found = searchFilesAdvanced(query = name, extensions = listOf("jpg", "jpeg", "png", "webp"))
            for (item in found) {
                val f = File(item.path)
                if (f.exists()) allImages.add(f)
            }
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
