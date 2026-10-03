package com.offline.aiassistant

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

object FileTools {

    // Menyimpan daftar berkas aktif hasil pencarian terakhir di memori
    // Ini kuncinya agar pengguna bisa bilang: "Hapus semua kecuali no. 9"
    private val activeSearchResults = mutableListOf<File>()

    /**
     * 1. PENCARIAN BERKAS DENGAN FILTER MULTI-FORMAT & JEJAK WAKTU
     * Contoh penggunaan:
     * - Query: "laporan"
     * - Extensions: listOf("pdf", "docx")
     * - MaxAgeHours: 24 (hanya berkas dalam 24 jam terakhir, opsional)
     */
    fun searchFiles(
        query: String = "",
        extensions: List<String> = emptyList(),
        maxAgeHours: Long? = null
    ): List<FileSearchResult> {
        activeSearchResults.clear()
        val results = mutableListOf<FileSearchResult>()

        val storageRoot = Environment.getExternalStorageDirectory()
        if (storageRoot == null || !storageRoot.exists()) {
            return results
        }

        // Folder umum yang biasa dipindai
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

    private fun scanDirectory(
        dir: File,
        query: String,
        extensions: List<String>,
        currentTime: Long,
        maxAgeMs: Long?,
        output: MutableList<FileSearchResult>,
        depth: Int = 0
    ) {
        if (depth > 4) return // Batasi kedalaman folder agar proses tetap cepat di HP

        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                // Abaikan folder sistem yang tersembunyi
                if (!file.name.startsWith(".")) {
                    scanDirectory(file, query, extensions, currentTime, maxAgeMs, output, depth + 1)
                }
            } else {
                val matchesQuery = query.isBlank() || file.name.contains(query, ignoreCase = true)
                val matchesExt = extensions.isEmpty() || extensions.contains(file.extension.lowercase())
                
                // Filter jejak waktu (misal: 24 jam terakhir)
                val matchesTime = if (maxAgeMs != null) {
                    (currentTime - file.lastModified()) <= maxAgeMs
                } else {
                    true
                }

                if (matchesQuery && matchesExt && matchesTime) {
                    activeSearchResults.add(file)
                    output.add(
                        FileSearchResult(
                            fileName = file.name,
                            filePath = file.absolutePath,
                            fileSizeFormatted = formatFileSize(file.length()),
                            lastModifiedFormatted = formatDate(file.lastModified()),
                            extension = file.extension.uppercase()
                        )
                    )
                }
            }
        }
    }

    /**
     * 2. AKSI SELEKTIF: "HAPUS SEMUA KECUALI NO. X"
     * Menghapus seluruh berkas pada hasil pencarian terakhir, KECUALI nomor urut yang dikecualikan.
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

        // Perbarui daftar aktif hanya menyisakan berkas yang dipertahankan
        activeSearchResults.clear()
        activeSearchResults.add(keptFile)

        return "Berhasil menghapus $deletedCount berkas. Berkas nomor $keepIndex ('${keptFile.name}') tetap aman disimpan."
    }

    /**
     * 3. BERBAGI BERKAS LANGSUNG KE WHATSAPP (ATAU APLIKASI LAIN)
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
                // Arahkan langsung ke WhatsApp jika terpasang
                setPackage("com.whatsapp")
            }

            try {
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(shareIntent)
                true
            } catch (e: Exception) {
                // Fallback jika WhatsApp tidak terpasang di HP: Buka jendela pemilih aplikasi biasa
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
