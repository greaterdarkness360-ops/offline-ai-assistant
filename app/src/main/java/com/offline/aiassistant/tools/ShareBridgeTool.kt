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
     * Mencari nomor telepon kontak HP menggunakan CONTENT_FILTER_URI resmi Android.
     * Sangat akurat untuk nama panggilan, nama depan, atau nama belakang tanpa case-sensitive.
     */
    fun findContactPhoneNumber(contactName: String): Pair<String, String>? {
        val cleanQuery = contactName.trim()
            .replace(Regex("^(ke|untuk|pada|chat|wa)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()

        if (cleanQuery.isBlank()) return null

        return try {
            val contentResolver = context.contentResolver
            val filterUri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                Uri.encode(cleanQuery)
            )

            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            contentResolver.query(filterUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numIdx != -1) {
                        val realName = if (nameIdx != -1) cursor.getString(nameIdx) else cleanQuery
                        val rawNumber = cursor.getString(numIdx)
                        Pair(realName, rawNumber)
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatPhoneForWhatsApp(raw: String): String {
        var digits = raw.replace(Regex("[^0-9]"), "")
        if (digits.startsWith("0")) {
            digits = "62" + digits.substring(1)
        } else if (digits.startsWith("8")) {
            digits = "62$digits"
        }
        return digits
    }

    /**
     * Langsung membuka ruang obrolan (room chat) kontak target di WhatsApp dengan teks terisi otomatis.
     */
    fun shareTextToApp(text: String, targetApp: String = "general", contactName: String? = null): String {
        return try {
            val isWhatsApp = targetApp.lowercase() in listOf("whatsapp", "wa")

            if (isWhatsApp && !contactName.isNullOrBlank()) {
                val cleanContact = contactName.trim()
                    .replace(Regex("^(ke|untuk|pada)\\s+", RegexOption.IGNORE_CASE), "")
                    .trim()

                val isRawNumber = cleanContact.matches(Regex("^[0-9+\\-\\s()]+$")) && cleanContact.replace(Regex("[^0-9]"), "").length >= 7
                val targetPhone = if (isRawNumber) {
                    formatPhoneForWhatsApp(cleanContact)
                } else {
                    findContactPhoneNumber(cleanContact)?.let { formatPhoneForWhatsApp(it.second) } ?: ""
                }

                val displayName = if (isRawNumber) targetPhone else (findContactPhoneNumber(cleanContact)?.first ?: cleanContact)

                if (targetPhone.isNotBlank()) {
                    val encodedText = if (text.isNotBlank()) Uri.encode(text) else ""
                    val directUri = Uri.parse("https://api.whatsapp.com/send?phone=$targetPhone&text=$encodedText")

                    val directIntent = Intent(Intent.ACTION_VIEW, directUri).apply {
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    return try {
                        context.startActivity(directIntent)
                        "Membuka chat WhatsApp langsung ke $displayName ($targetPhone)."
                    } catch (_: Exception) {
                        val fallbackIntent = Intent(Intent.ACTION_VIEW, directUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(fallbackIntent)
                        "Membuka chat WhatsApp ke $displayName ($targetPhone)."
                    }
                } else {
                    shareGeneralText(text, "com.whatsapp")
                    return "Kontak '$cleanContact' tidak ditemukan di buku telepon. Membuka WhatsApp untuk memilih kontak secara manual."
                }
            }

            val pkg = when (targetApp.lowercase()) {
                "telegram", "tele", "tg" -> "org.telegram.messenger"
                "notion" -> "notion.id"
                "whatsapp", "wa" -> "com.whatsapp"
                else -> null
            }

            shareGeneralText(text, pkg)
            "Membuka aplikasi $targetApp."
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
        } catch (_: Exception) {
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
            } catch (_: Exception) {
                val chooser = Intent.createChooser(intent.apply { `package` = null }, "Kirim file ke...")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                "Membuka menu berbagi untuk file '${file.name}'."
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
