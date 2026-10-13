package com.offline.aiassistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import androidx.core.content.FileProvider
import java.io.File

class ShareBridgeTool(private val context: Context) {

    /**
     * Mencari nomor telepon dari nama kontak di buku telepon HP
     */
    fun findContactPhoneNumber(contactName: String): Pair<String, String>? {
        if (contactName.isBlank()) return null
        return try {
            val contentResolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$contactName%")

            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numIdx != -1) {
                        val realName = if (nameIdx != -1) cursor.getString(nameIdx) else contactName
                        val rawNumber = cursor.getString(numIdx)
                        return Pair(realName, rawNumber)
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Memformat nomor lokal Indonesia menjadi format internasional WhatsApp (628xxx)
     */
    private fun formatPhoneForWhatsApp(raw: String): String {
        val digits = raw.replace(Regex("[^0-9]"), "")
        return when {
            digits.startsWith("0") -> "62" + digits.substring(1)
            digits.startsWith("62") -> digits
            digits.startsWith("8") -> "62$digits"
            else -> digits
        }
    }

    /**
     * Membagikan teks/pesan langsung ke WhatsApp (ke kontak langsung), Telegram, atau Notion
     */
    fun shareTextToApp(text: String, targetApp: String = "general", contactName: String? = null): String {
        return try {
            val isWhatsApp = targetApp.lowercase() in listOf("whatsapp", "wa")

            // Jika tujuan adalah WhatsApp dan nama kontak disebutkan
            if (isWhatsApp && !contactName.isNullOrBlank()) {
                val contactInfo = findContactPhoneNumber(contactName.trim())
                if (contactInfo != null) {
                    val (realName, rawPhone) = contactInfo
                    val formattedPhone = formatPhoneForWhatsApp(rawPhone)
                    val encodedText = Uri.encode(text)

                    // URL API WhatsApp resmi untuk membuka chat kontak seketika
                    val directUri = Uri.parse("https://api.whatsapp.com/send?phone=$formattedPhone&text=$encodedText")
                    val directIntent = Intent(Intent.ACTION_VIEW, directUri).apply {
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    return try {
                        context.startActivity(directIntent)
                        "Membuka chat WhatsApp langsung ke $realName ($formattedPhone) dengan pesan: '$text'"
                    } catch (e: Exception) {
                        val fallbackIntent = Intent(Intent.ACTION_VIEW, directUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(fallbackIntent)
                        "Membuka chat WhatsApp ke $realName ($formattedPhone) dengan pesan: '$text'"
                    }
                } else {
                    // Kontak tidak ditemukan, buka WhatsApp biasa dengan teks terisi
                    shareGeneralText(text, "com.whatsapp")
                    return "Kontak '$contactName' tidak ditemukan di buku telepon. Membuka WhatsApp untuk memilih kontak secara manual."
                }
            }

            // Pengiriman teks umum (Telegram / WhatsApp tanpa kontak tertentu)
            val pkg = when (targetApp.lowercase()) {
                "telegram", "tele", "tg" -> "org.telegram.messenger"
                "notion" -> "notion.id"
                "whatsapp", "wa" -> "com.whatsapp"
                else -> null
            }

            shareGeneralText(text, pkg)
            "Berhasil membuka jembatan berbagi ke $targetApp."
        } catch (e: Exception) {
            "Gagal membagikan teks: ${e.localizedMessage}"
        }
    }

    private fun shareGeneralText(text: String, targetPackage: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (targetPackage != null) setPackage(targetPackage)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val chooser = Intent.createChooser(intent.apply { `package` = null }, "Bagikan pesan via...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

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
