package com.offline.aiassistant.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class FileTools(private val context: Context) {

    // 1. Mencari file di background thread (aman tanpa membuat HP macet)
    suspend fun searchFiles(query: String, extensionFilter: String? = null): List<String> = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        val searchDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        )

        for (dir in searchDirs) {
            if (dir != null && dir.exists()) {
                dir.walkTopDown().maxDepth(4).forEach { file ->
                    if (file.isFile) {
                        val matchesName = file.name.contains(query, ignoreCase = true)
                        val matchesExt = extensionFilter == null || file.extension.equals(extensionFilter, ignoreCase = true)
                        if (matchesName && matchesExt) {
                            results.add("${file.name} (${file.length() / 1024} KB) -> ${file.absolutePath}")
                        }
                    }
                }
            }
        }
        if (results.isEmpty()) listOf("Tidak ada file yang cocok dengan kata kunci '$query'.") else results
    }

    // 2. Pembersihan File Ganda Kilat (Cek ukuran dulu, baru hash)
    suspend fun cleanDuplicateFiles(folderName: String = "Download", deleteDuplicates: Boolean = false): String = withContext(Dispatchers.IO) {
        val targetDir = when (folderName.lowercase()) {
            "documents", "dokumen" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            "pictures", "foto", "gambar" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }

        if (targetDir == null || !targetDir.exists()) {
            return@withContext "Folder $folderName tidak ditemukan di memori HP."
        }

        // Kumpulkan file dan kelompokkan berdasarkan UKURAN file
        val allFiles = mutableListOf<File>()
        targetDir.walkTopDown().maxDepth(3).forEach { file ->
            if (file.isFile && file.length() > 0) {
                allFiles.add(file)
            }
        }

        val potentialDuplicates = allFiles.groupBy { it.length() }.filter { it.value.size > 1 }

        if (potentialDuplicates.isEmpty()) {
            return@withContext "Pemeriksaan selesai: Tidak ditemukan file ganda di folder $folderName."
        }

        // Hanya hitung hash digital untuk file yang ukurannya sama persis
        val fileHashMap = mutableMapOf<String, File>()
        val duplicatesFound = mutableListOf<File>()

        for ((_, filesWithSameSize) in potentialDuplicates) {
            for (file in filesWithSameSize) {
                val hash = calculateFileHash(file)
                if (fileHashMap.containsKey(hash)) {
                    duplicatesFound.add(file)
                } else {
                    fileHashMap[hash] = file
                }
            }
        }

        if (duplicatesFound.isEmpty()) {
            return@withContext "Pemeriksaan selesai: Tidak ditemukan file ganda di folder $folderName."
        }

        if (deleteDuplicates) {
            var deletedCount = 0
            duplicatesFound.forEach { dupFile ->
                if (dupFile.delete()) deletedCount++
            }
            "Berhasil menghapus $deletedCount file duplikat dari folder $folderName. File asli tetap aman."
        } else {
            val listText = duplicatesFound.take(15).joinToString("\n") { "- ${it.name} (${it.length() / 1024} KB)" }
            val moreText = if (duplicatesFound.size > 15) "\n...dan ${duplicatesFound.size - 15} file lainnya." else ""
            "Ditemukan ${duplicatesFound.size} file duplikat di folder $folderName:\n$listText$moreText\n\nKetik 'hapus file ganda' untuk membersihkannya."
        }
    }

    // 3. Menyatukan Gambar ke Format PDF di Background Thread
    suspend fun convertImagesToPdf(imageFileNames: List<String>, outputPdfName: String): String = withContext(Dispatchers.IO) {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

        val imageFiles = mutableListOf<File>()
        for (name in imageFileNames) {
            val foundFile = listOf(picturesDir, downloadDir, documentsDir).mapNotNull { dir ->
                dir?.walkTopDown()?.maxDepth(3)?.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            }.firstOrNull()

            if (foundFile != null) {
                imageFiles.add(foundFile)
            }
        }

        if (imageFiles.isEmpty()) {
            return@withContext "Gagal: Tidak ada gambar yang ditemukan dari daftar yang Anda berikan."
        }

        val pdfDocument = PdfDocument()

        try {
            imageFiles.forEachIndexed { index, file ->
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    pdfDocument.finishPage(page)
                    bitmap.recycle()
                }
            }

            val validPdfName = if (outputPdfName.endsWith(".pdf", ignoreCase = true)) outputPdfName else "$outputPdfName.pdf"
            val outputFile = File(documentsDir, validPdfName)

            FileOutputStream(outputFile).use { outStream ->
                pdfDocument.writeTo(outStream)
            }

            "Sukses! ${imageFiles.size} gambar berhasil disatukan menjadi PDF di:\n${outputFile.absolutePath}"
        } catch (e: Exception) {
            "Terjadi kendala saat menyusun PDF: ${e.localizedMessage}"
        } finally {
            pdfDocument.close()
        }
    }

    private fun calculateFileHash(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
