package com.offline.aiassistant.tools

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

class ShareBridgeTool(private val context: Context) {

    // 1. Membagikan teks/pesan langsung ke Telegram, Notion, atau WhatsApp
    fun shareTextToApp(text: String, targetApp: String = "general"): String {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            when (targetApp.lowercase()) {
                "telegram", "tele", "tg" -> intent.setPackage("org.telegram.messenger")
                "notion" -> intent.setPackage("notion.id")
                "whatsapp", "wa" -> intent.setPackage("com.whatsapp")
            }

            try {
                context.startActivity(intent)
                "Berhasil membuka jembatan berbagi ke $targetApp."
            } catch (e: Exception) {
                // Jika aplikasi target belum terpasang, buka menu berbagi bawaan HP
                val chooser = Intent.createChooser(intent.apply { `package` = null }, "Bagikan pesan via...")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                "Membuka menu berbagi HP."
            }
        } catch (e: Exception) {
            "Gagal membagikan teks: ${e.localizedMessage}"
        }
    }

    // 2. Membagikan berkas (PDF, Foto, Video) ke Telegram, Notion, atau WhatsApp
    fun shareFileToApp(filePath: String, targetApp: String = "general"): String {
        return try {
            val file = File(filePath)
            if (!file.exists()) return "File tidak ditemukan di: $filePath"

            val uri = FileProvider.getUriForFile(context, "com.offline.aiassistant.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            when (targetApp.lowercase()) {
                "telegram", "tele", "tg" -> intent.setPackage("org.telegram.messenger")
                "notion" -> intent.setPackage("notion.id")
                "whatsapp", "wa" -> intent.setPackage("com.whatsapp")
            }

            try {
                context.startActivity(intent)
                "Berhasil membagikan file '${file.name}' ke$targetApp."
            } catch (e: Exception) {
                val chooser = Intent.createChooser(intent.apply { `package` = null }, "Kirim file ke...")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                "Membuka menu berbagi HP untuk file '${file.name}'."
            }
        } catch (e: Exception) {
            "Gagal membagikan file: ${e.localizedMessage}"
        }
    }

    // 3. Menyimpan catatan khusus format Notion (.md Markdown) di folder Dokumen
    fun saveNoteForNotion(title: String, content: String): String {
        return try {
            val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val notionFolder = File(docDir, "Notion_Notes")
            if (!notionFolder.exists()) notionFolder.mkdirs()

            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val noteFile = File(notionFolder, "$cleanTitle.md")
            noteFile.writeText("# $title\n\n$content\n\n---\n*Dibuat oleh TOWR Assistant (Offline)*")

            "Catatan berhasil disimpan untuk Notion di:\n${noteFile.absolutePath}"
        } catch (e: Exception) {
            "Gagal menyimpan catatan: ${e.localizedMessage}"
        }
    }
}
