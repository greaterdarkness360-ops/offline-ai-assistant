package com.offline.aiassistant

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import com.offline.aiassistant.ai.GemmaEngine
import com.offline.aiassistant.router.ActionRouter
import kotlinx.coroutines.launch

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

@Composable
fun TowrMainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val actionRouter = remember { ActionRouter(context) }
    val gemmaEngine = remember { GemmaEngine(context) }

    // Pengatur gulir layar konsol
    val consoleScrollState = rememberScrollState()

    var queryText by remember { mutableStateOf("") }
    var terminalOutput by remember {
        mutableStateOf(
            "STATUS: SISTEM ONLINE\n" +
            "OTAK: ACTION ROUTER & LOCAL GEMMA READY\n" +
            "JARINGAN: 100% OFFLINE TERISOLASI\n\n" +
            "TOWR siap mengeksekusi instruksi Anda."
        )
    }

    // Efek otomatis: Setiap kali teks bertambah, layar otomatis bergulir ke posisi paling bawah
    LaunchedEffect(terminalOutput) {
        consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
    }

    val skyBlue = Color(0xFF38BDF8)
    val electricBlue = Color(0xFF00E5FF)
    val containerColor = Color(0xFF0F172A)
    val borderDim = Color(0xFF1E293B)

    fun detectAndLoadModel() {
        coroutineScope.launch {
            terminalOutput += "\n\n> SISTEM: [Memeriksa memori internal...]"
            terminalOutput += "\n> SISTEM: Mencari file model (.task / .litertlm) di folder Download..."
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
                        "Tips: Simpan file model Gemma di folder Download HP Anda, lalu sentuh tombol '🧠 Muat Model AI' lagi."
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
                Text(
                    text = "TOWR",
                    color = electricBlue,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "by: Natanael",
                    color = skyBlue.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF022C22),
                border = BorderStroke(1.dp, Color(0xFF10B981))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF10B981),
                        modifier = Modifier.size(6.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "100% Aman & Offline",
                        color = Color(0xFF34D399),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        HorizontalDivider(color = electricBlue.copy(alpha = 0.25f), thickness = 1.dp)

        Spacer(modifier = Modifier.height(12.dp))

        // Monitor Layar Konsol (Dapat di-scroll bebas & auto-scroll)
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = containerColor,
            border = BorderStroke(1.dp, electricBlue.copy(alpha = 0.35f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(consoleScrollState) // BISA DI-SCROLL KE ATAS & BAWAH
                    .padding(16.dp)
            ) {
                Text(
                    text = terminalOutput,
                    color = skyBlue,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tombol Pintas Cepat (Quick Action Chips)
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
                    terminalOutput += "\n> TOWR:\n$res"
                }
            }
            ActionChip(title = "🧹 Cek File Ganda", accent = skyBlue, bg = containerColor) {
                coroutineScope.launch {
                    terminalOutput += "\n\n> USER: Periksa file ganda di Download"
                    terminalOutput += "\n> TOWR: [Sedang memindai dan membandingkan ukuran file di folder Download...]"
                    val res = actionRouter.processInstruction("cek file ganda")
                    terminalOutput += "\n> TOWR:\n$res"
                }
            }
            ActionChip(title = "🗑️ Bersihkan Layar", accent = Color(0xFFF87171), bg = containerColor) {
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
                placeholder = {
                    Text("Tanya atau beri perintah ke TOWR...", color = Color.Gray, fontSize = 13.sp)
                },
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

                            if (gemmaEngine.isModelLoaded) {
                                terminalOutput += "\n> TOWR: [Sedang menganalisis instruksi...]"
                                val aiReply = gemmaEngine.askGemma(userPrompt)
                                val trimmedReply = aiReply.trim()

                                if (trimmedReply.startsWith("{") && trimmedReply.endsWith("}")) {
                                    terminalOutput += "\n> TOWR: [Mengeksekusi alat native...]"
                                    val actionResult = actionRouter.processInstruction(trimmedReply)
                                    terminalOutput += "\n> TOWR (Hasil Eksekusi):\n$actionResult"
                                } else {
                                    terminalOutput += "\n> TOWR: $trimmedReply"
                                }
                            } else {
                                terminalOutput += "\n> TOWR: [Mengeksekusi...]"
                                val directResult = actionRouter.processInstruction(userPrompt)
                                terminalOutput += "\n> TOWR:\n$directResult"
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = electricBlue),
                modifier = Modifier.height(56.dp)
            ) {
                Text(
                    text = "KIRIM",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ActionChip(
    title: String,
    accent: Color,
    bg: Color,
    onTap: () -> Unit
) {
    Surface(
        onClick = onTap,
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Text(
            text = title,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
