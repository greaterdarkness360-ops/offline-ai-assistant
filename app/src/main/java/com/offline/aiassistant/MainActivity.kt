package com.offline.aiassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.offline.aiassistant.ai.GemmaEngine
import com.offline.aiassistant.router.ActionRouter
import com.offline.aiassistant.tools.FileItemInfo
import com.offline.aiassistant.tools.FileTools
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF070B14)
                ) {
                    TowrMainScreen()
                }
            }
        }
    }
}

// Fungsi Resmi: Membuka File Apapun ke Aplikasi Bawaan HP (Galeri, Pemutar Video, PDF, dll.)
fun openFileWithSystemApp(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File tidak ditemukan di memori.", Toast.LENGTH_SHORT).show()
            return
        }

        val uri: Uri = FileProvider.getUriForFile(context, "com.offline.aiassistant.provider", file)
        val ext = file.extension.lowercase()
        val mimeType = when (ext) {
            "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
            "mp4", "mkv", "avi", "mov", "3gp" -> "video/*"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "txt" -> "text/plain"
            "mp3", "wav", "m4a" -> "audio/*"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Buka dengan..."))
    } catch (e: Exception) {
        Toast.makeText(context, "Tidak ada aplikasi untuk membuka format ini.", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun TowrMainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val actionRouter = remember { ActionRouter(context) }
    val fileTools = remember { FileTools(context) }
    val gemmaEngine = remember { GemmaEngine(context) }

    val consoleScrollState = rememberScrollState()

    var queryText by remember { mutableStateOf("") }
    var detectedDuplicates by remember { mutableStateOf<List<FileItemInfo>>(emptyList()) }
    var terminalOutput by remember {
        mutableStateOf(
            "STATUS: SISTEM ONLINE\n" +
            "OTAK: ACTION ROUTER & LOCAL GEMMA READY\n" +
            "JARINGAN: 100% OFFLINE TERISOLASI\n\n" +
            "TOWR siap mengeksekusi instruksi Anda."
        )
    }

    LaunchedEffect(terminalOutput, detectedDuplicates.size) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

    val skyBlue = Color(0xFF38BDF8)
    val electricBlue = Color(0xFF00E5FF)
    val containerColor = Color(0xFF0F172A)
    val borderDim = Color(0xFF1E293B)

    fun detectAndLoadModel() {
        coroutineScope.launch {
            terminalOutput += "\n\n> SISTEM: Mencari file model (.task / .litertlm) di folder Download..."
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val modelFile = downloadDir?.walkTopDown()?.maxDepth(2)?.firstOrNull {
                it.isFile && (it.extension.equals("task", true) || it.extension.equals("litertlm", true) || it.extension.equals("bin", true))
            }

            if (modelFile != null) {
                terminalOutput += "\n> SISTEM: Menemukan model '${modelFile.name}'. Memuat bobot ke GPU HP..."
                val result = gemmaEngine.loadModel(modelFile.absolutePath)
                terminalOutput += "\n> TOWR: $result"
            } else {
                terminalOutput += "\n> TOWR: File model belum ada di folder Download.\n" +
                        "Tips: Simpan file model Gemma di folder Download HP Anda, lalu sentuh '🧠 Muat Model AI' lagi."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "TOWR", color = electricBlue, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                Text(text = "by: Natanael", color = skyBlue.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF022C22), border = BorderStroke(1.dp, Color(0xFF10B981))) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(50), color = Color(0xFF10B981), modifier = Modifier.size(6.dp)) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "100% Aman & Offline", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        HorizontalDivider(color = electricBlue.copy(alpha = 0.25f), thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        // Monitor Layar Konsol & Daftar Kartu File Interaktif
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = containerColor,
            border = BorderStroke(1.dp, electricBlue.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(consoleScrollState)
                    .padding(16.dp)
            ) {
                Text(
                    text = terminalOutput,
                    color = skyBlue,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp
                )

                // Jika ada file ganda yang terdeteksi, munculkan kartu tombol interaktif
                if (detectedDuplicates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "📁 PILIH FILE UNTUK DILIHAT ATAU DIHAPUS:",
                        color = Color.Yellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    detectedDuplicates.forEach { fileItem ->
                        val icon = when (fileItem.extension) {
                            "jpg", "jpeg", "png", "webp" -> "🖼️"
                            "mp4", "mkv", "mov" -> "🎬"
                            "pdf", "doc", "docx" -> "📄"
                            else -> "📦"
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(text = "$icon ${fileItem.name}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(text = "${fileItem.sizeKb} KB", color = Color.Gray, fontSize = 10.sp)
                                }

                                Row {
                                    // Tombol BUKA (Langsung buka Galeri/Video/File)
                                    Button(
                                        onClick = { openFileWithSystemApp(context, fileItem.path) },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = electricBlue),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("👁️ Buka", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Tombol HAPUS (Hapus file spesifik ini)
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                val success = fileTools.deleteSingleFile(fileItem.path)
                                                if (success) {
                                                    detectedDuplicates = detectedDuplicates.filter { it.path != fileItem.path }
                                                    terminalOutput += "\n> TOWR: File '${fileItem.name}' berhasil dihapus secara permanen."
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("🗑️", color = Color.White, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tombol Pintas Cepat
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionChip(title = "🧠 Muat Model AI", accent = Color(0xFF34D399), bg = containerColor) {
                detectAndLoadModel()
            }
            ActionChip(title = "🔍 Cari File", accent = skyBlue, bg = containerColor) {
                queryText = "Cari file "
            }
            ActionChip(title = "🚀 Buka Aplikasi", accent = skyBlue, bg = containerColor) {
                queryText = "Buka "
            }
            ActionChip(title = "📊 Cek Aktivitas HP", accent = skyBlue, bg = containerColor) {
                coroutineScope.launch {
                    terminalOutput += "\n\n> USER: Cek aktivitas HP"
                    terminalOutput += "\n> TOWR: [Sedang membaca statistik sistem 24 jam terakhir...]"
                    val res = actionRouter.processInstruction("cek aktivitas")
                    terminalOutput += "\n> TOWR:\n${res.message}"
                }
            }
            ActionChip(title = "🧹 Cek File Ganda", accent = skyBlue, bg = containerColor) {
                coroutineScope.launch {
                    terminalOutput += "\n\n> USER: Periksa file ganda di Download"
                    terminalOutput += "\n> TOWR: [Sedang memindai file ganda di folder Download...]"
                    val list = fileTools.getDuplicateFilesList("Download")
                    detectedDuplicates = list
                    terminalOutput += "\n> TOWR: Ditemukan ${list.size} file ganda. Silakan tinjau dan buka filenya di bawah."
                }
            }
            ActionChip(title = "🗑️ Bersihkan Layar", accent = Color(0xFFF87171), bg = containerColor) {
                detectedDuplicates = emptyList()
                terminalOutput = "STATUS: SISTEM ONLINE\nLayar konsol telah dibersihkan.\n\nTOWR siap menerima instruksi."
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Kolom Input Perintah & Tombol Kirim
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                placeholder = { Text("Tanya atau beri perintah ke TOWR...", color = Color.Gray, fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = electricBlue,
                    unfocusedBorderColor = borderDim,
                    focusedContainerColor = containerColor,
                    unfocusedContainerColor = containerColor
                ),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (queryText.isNotBlank()) {
                        val userPrompt = queryText
                        queryText = ""

                        coroutineScope.launch {
                            terminalOutput += "\n\n> USER: $userPrompt"

                            val lowerPrompt = userPrompt.lowercase().trim()

                            // 1. JIKA PERINTAH LANGSUNG -> Langsung eksekusi via ActionRouter (Tanpa tunggu AI)
                            val isDirectCommand = lowerPrompt.startsWith("hapus") || 
                                                  lowerPrompt.startsWith("buka") || 
                                                  lowerPrompt.startsWith("cari") || 
                                                  lowerPrompt.startsWith("temukan") ||
                                                  lowerPrompt.contains("duplikat") || 
                                                  lowerPrompt.contains("file ganda")

                            if (isDirectCommand) {
                                terminalOutput += "\n> TOWR: [Mengeksekusi alat native...]"
                                val actionResult = actionRouter.processInstruction(userPrompt)
                                var display = actionResult.message
                                if (actionResult.files.isNotEmpty()) {
                                    display += "\n" + actionResult.files.joinToString("\n") { "• ${it.name} (${it.sizeKb} KB)" }
                                }
                                terminalOutput += "\n> TOWR (Hasil Eksekusi):\n$display"
                            } 
                            // 2. JIKA PERTANYAAN UMUM / PERCAKAPAN -> Lempar ke Gemma AI
                            else if (gemmaEngine.isModelLoaded) {
                                terminalOutput += "\n> TOWR: [Sedang menganalisis instruksi...]"
                                val aiReply = gemmaEngine.askGemma(userPrompt)
                                
                                // Bersihkan jika dibungkus ```json ... ```
                                val cleanJson = aiReply
                                    .replace("```json", "")
                                    .replace("```", "")
                                    .trim()

                                if (cleanJson.contains("\"action\"") && cleanJson.contains("{") && cleanJson.contains("}")) {
                                    terminalOutput += "\n> TOWR: [Mengeksekusi alat native...]"
                                    val actionResult = actionRouter.processInstruction(cleanJson)
                                    var display = actionResult.message
                                    if (actionResult.files.isNotEmpty()) {
                                        display += "\n" + actionResult.files.joinToString("\n") { "• ${it.name} (${it.sizeKb} KB)" }
                                    }
                                    terminalOutput += "\n> TOWR (Hasil Eksekusi):\n$display"
                                } else {
                                    terminalOutput += "\n> TOWR: $aiReply"
                                }
                            } else {
                                terminalOutput += "\n> TOWR: [Mengeksekusi...]"
                                val directResult = actionRouter.processInstruction(userPrompt)
                                var display = directResult.message
                                if (directResult.files.isNotEmpty()) {
                                    display += "\n" + directResult.files.joinToString("\n") { "• ${it.name} (${it.sizeKb} KB)" }
                                }
                                terminalOutput += "\n> TOWR:\n$display"
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = electricBlue),
                modifier = Modifier.height(56.dp)
            ) {
                Text(text = "KIRIM", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ActionChip(title: String, accent: Color, bg: Color, onTap: () -> Unit) {
    Surface(
        onClick = onTap,
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Text(text = title, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
