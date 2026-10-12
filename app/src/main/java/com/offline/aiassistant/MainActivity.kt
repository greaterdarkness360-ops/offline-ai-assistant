package com.offline.aiassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.offline.aiassistant.ai.GemmaEngine
import com.offline.aiassistant.router.ActionRouter
import com.offline.aiassistant.router.PendingDeleteInfo
import com.offline.aiassistant.tools.FileSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =========================================================================
// 1. PALET WARNA TEMA CYBER-DARK TOWR
// =========================================================================
val TowrBgDark = Color(0xFF070B14)
val TowrSurfaceDark = Color(0xFF0F172A)
val TowrSurfaceElevated = Color(0xFF1E293B)
val TowrBorderDim = Color(0xFF334155)
val TowrElectricCyan = Color(0xFF00E5FF)
val TowrSkyBlue = Color(0xFF38BDF8)
val TowrEmeraldGreen = Color(0xFF10B981)
val TowrGreenBg = Color(0xFF022C22)
val TowrTextPrimary = Color(0xFFF8FAFC)
val TowrTextSecondary = Color(0xFF94A3B8)
val TowrRedDanger = Color(0xFFEF4444)
val TowrRedBg = Color(0xFF2D1214)

enum class MessageSender { USER, TOWR, SYSTEM }

data class ToolExecutionLog(
    val toolName: String,
    val actionDetail: String,
    val latencyMs: Long,
    val isSuccess: Boolean
)

data class TowrChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val messageText: String,
    val timestamp: String,
    val thinkingProcess: List<ToolExecutionLog>? = null,
    val foundFiles: List<FileSearchResult>? = null,
    val pendingDelete: PendingDeleteInfo? = null,
    val isActionResolved: Boolean = false
)

class MainActivity : ComponentActivity() {

    private lateinit var gemmaEngine: GemmaEngine
    private lateinit var actionRouter: ActionRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gemmaEngine = GemmaEngine(this)
        actionRouter = ActionRouter(this)

        setContent {
            TowrTheme {
                TowrMainScreen(gemmaEngine, actionRouter)
            }
        }
    }
}

@Composable
fun TowrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = TowrBgDark,
            surface = TowrSurfaceDark,
            primary = TowrElectricCyan,
            secondary = TowrSkyBlue
        ),
        content = content
    )
}

// =========================================================================
// 2. FUNGSI PENCARIAN MODEL CERDAS OTOMATIS
// =========================================================================
fun findModelPath(): String? {
    val searchDirectories = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        File(Environment.getExternalStorageDirectory(), "Download"),
        File(Environment.getExternalStorageDirectory(), "Documents")
    )

    for (dir in searchDirectories) {
        if (dir != null && dir.exists() && dir.canRead()) {
            val modelFiles = dir.listFiles { file -> file.isFile && file.extension.lowercase() == "litertlm" }
            if (!modelFiles.isNullOrEmpty()) {
                val gpuPreferred = modelFiles.firstOrNull { it.name.contains("gpu", ignoreCase = true) }
                return (gpuPreferred ?: modelFiles.first()).absolutePath
            }
        }
    }
    return null
}

// =========================================================================
// 3. LAYAR UTAMA (TOWR MAIN SCREEN)
// =========================================================================
@Composable
fun TowrMainScreen(gemmaEngine: GemmaEngine, actionRouter: ActionRouter) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val prefs = remember { context.getSharedPreferences("towr_prefs", Context.MODE_PRIVATE) }

    var inputPrompt by remember { mutableStateOf("") }
    var engineStatusText by remember { mutableStateOf("Memeriksa model on-device...") }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val messages = remember {
        mutableStateListOf(
            TowrChatMessage(
                sender = MessageSender.TOWR,
                messageText = "TOWR Executive Assistant aktif. Siap mengeksekusi instruksi berkas, aplikasi, dan produktivitas Anda.",
                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            )
        )
    }

    // Inisialisasi Model Otomatis saat aplikasi terbuka
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val detectedPath = findModelPath()
            if (detectedPath != null) {
                val modelFile = File(detectedPath)
                engineStatusText = "Memuat ${modelFile.name.take(15)}..."
                val res = gemmaEngine.loadModel(detectedPath)
                engineStatusText = if (gemmaEngine.isModelLoaded) "Gemma: ${modelFile.name.take(15)} (GPU)" else res
            } else {
                engineStatusText = "Model .litertlm belum ada di Download/Documents"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TowrBgDark)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. Header Terkunci Kokoh di Pucuk
        TowrTopBar(
            statusText = engineStatusText,
            isLoaded = gemmaEngine.isModelLoaded,
            onSettingsClick = { showSettingsDialog = true },
            onClearHistoryClick = {
                messages.clear()
                messages.add(
                    TowrChatMessage(
                        sender = MessageSender.TOWR,
                        messageText = "Riwayat obrolan dibersihkan. Memori aktif dikosongkan.",
                        timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    )
                )
                Toast.makeText(context, "Riwayat dibersihkan", Toast.LENGTH_SHORT).show()
            }
        )

        // 2. Baris Aksi Cepat (Scrollable Horizontal)
        QuickActionChipsRow(onChipClicked = { inputPrompt = it })

        // 3. Area Obrolan Berbobot Fleksibel
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    if (msg.sender == MessageSender.USER) {
                        UserChatBubble(message = msg)
                    } else {
                        TowrAgentBubble(
                            message = msg,
                            onFileClick = { file ->
                                openFileWithSystemApp(context, file.filePath)
                            },
                            onConfirmDelete = { pendingInfo ->
                                coroutineScope.launch {
                                    val deleteResult = withContext(Dispatchers.IO) {
                                        actionRouter.executeConfirmedDelete(pendingInfo.filePath)
                                    }
                                    val index = messages.indexOfFirst { it.id == msg.id }
                                    if (index != -1) {
                                        messages[index] = messages[index].copy(
                                            isActionResolved = true,
                                            messageText = deleteResult
                                        )
                                    }
                                    Toast.makeText(context, deleteResult, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onCancelDelete = {
                                val index = messages.indexOfFirst { it.id == msg.id }
                                if (index != -1) {
                                    messages[index] = messages[index].copy(
                                        isActionResolved = true,
                                        messageText = "Penghapusan berkas '${msg.pendingDelete?.fileName}' dibatalkan."
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        // 4. Input Bar di Bagian Bawah
        TowrInputBar(
            inputPrompt = inputPrompt,
            onPromptChanged = { inputPrompt = it },
            onSendClicked = {
                if (inputPrompt.isNotBlank()) {
                    val promptText = inputPrompt.trim()
                    inputPrompt = ""
                    val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

                    messages.add(
                        TowrChatMessage(
                            sender = MessageSender.USER,
                            messageText = promptText,
                            timestamp = now
                        )
                    )

                    coroutineScope.launch {
                        listState.animateScrollToItem(messages.size - 1)

                        val startTime = System.currentTimeMillis()
                        var aiOutput = ""
                        var executionLatency = 0L

                        val response = withContext(Dispatchers.IO) {
                            if (gemmaEngine.isModelLoaded) {
                                aiOutput = gemmaEngine.askGemma(promptText)
                                executionLatency = System.currentTimeMillis() - startTime
                                actionRouter.processInstruction(aiOutput)
                            } else {
                                executionLatency = System.currentTimeMillis() - startTime
                                actionRouter.processInstruction(promptText)
                            }
                        }

                        val executionLog = listOf(
                            ToolExecutionLog(
                                toolName = "TOWR Engine",
                                actionDetail = if (aiOutput.isNotBlank()) "AI JSON: $aiOutput" else "Direct Router Rule",
                                latencyMs = executionLatency,
                                isSuccess = true
                            )
                        )

                        messages.add(
                            TowrChatMessage(
                                sender = MessageSender.TOWR,
                                messageText = response.message,
                                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                                thinkingProcess = executionLog,
                                foundFiles = response.files.ifEmpty { null },
                                pendingDelete = response.pendingDelete
                            )
                        )

                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
            }
        )
    }

    // Dialog Pengaturan TOWR (Settings [⚙️])
    if (showSettingsDialog) {
        TowrSettingsDialog(
            prefs = prefs,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

// =========================================================================
// 4. TOPBAR KOKOH DENGAN IKON PENGATURAN & HAPUS RIWAYAT
// =========================================================================
@Composable
fun TowrTopBar(
    statusText: String,
    isLoaded: Boolean,
    onSettingsClick: () -> Unit,
    onClearHistoryClick: () -> Unit
) {
    Surface(
        color = TowrBgDark,
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sisi Kiri: Logo & Status Teks
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(TowrSurfaceElevated)
                        .border(1.5.dp, TowrElectricCyan, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("T", color = TowrElectricCyan, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = "TOWR AGENT AI",
                        color = TowrTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = statusText,
                        color = if (isLoaded) TowrSkyBlue else TowrTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Sisi Kanan: Tombol Pengaturan, Bersihkan, dan Badge Offline
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Pengaturan", tint = TowrSkyBlue, modifier = Modifier.size(19.dp))
                }

                IconButton(
                    onClick = onClearHistoryClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Hapus Riwayat", tint = TowrTextSecondary, modifier = Modifier.size(19.dp))
                }

                // Badge Offline (Pasti Lebar Terjaga, Tidak Pernah Terhimpit)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TowrGreenBg)
                        .border(1.dp, TowrEmeraldGreen, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TowrEmeraldGreen))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("100% OFFLINE", color = TowrEmeraldGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// =========================================================================
// 5. CHIP AKSI CEPAT (SCROLLABLE HORIZONTAL)
// =========================================================================
@Composable
fun QuickActionChipsRow(onChipClicked: (String) -> Unit) {
    val quickActions = listOf(
        Pair("🔍 Cari File...", "cari file "),
        Pair("⏰ 24 Jam Terakhir", "cari file paling baru"),
        Pair("📑 File Duplikat", "file ganda"),
        Pair("📱 Pemakaian HP", "pemakaian aplikasi"),
        Pair("💬 Chat WhatsApp", "kirim whatsapp ke ")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        quickActions.forEach { (label, prompt) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(TowrSurfaceElevated)
                    .border(1.dp, TowrBorderDim, RoundedCornerShape(20.dp))
                    .clickable { onChipClicked(prompt) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(label, color = TowrTextSecondary, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

// =========================================================================
// 6. BUBBLE PERCAKAPAN & GUARDRAIL CONFIRMATION CARD
// =========================================================================
@Composable
fun UserChatBubble(message: TowrChatMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(TowrSurfaceElevated)
                .border(1.dp, TowrSkyBlue.copy(alpha = 0.5f), RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .padding(14.dp)
        ) {
            Column {
                Text(message.messageText, color = TowrTextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(message.timestamp, color = TowrTextSecondary, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
fun TowrAgentBubble(
    message: TowrChatMessage,
    onFileClick: (FileSearchResult) -> Unit,
    onConfirmDelete: (PendingDeleteInfo) -> Unit,
    onCancelDelete: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(TowrSurfaceDark)
                .border(1.2.dp, TowrElectricCyan.copy(alpha = 0.7f), RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .padding(14.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TOWR Intelligence", color = TowrElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(message.timestamp, color = TowrTextSecondary, fontSize = 10.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))

                // 1. Log Proses Berpikir
                if (!message.thinkingProcess.isNullOrEmpty()) {
                    CollapsibleThinkingCard(thinkingLogs = message.thinkingProcess)
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // 2. Teks Utama Balasan
                Text(message.messageText, color = TowrTextPrimary, fontSize = 14.sp, lineHeight = 21.sp)

                // 3. Kartu Guardrail Konfirmasi Hapus (P2)
                if (message.pendingDelete != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ConfirmationDeleteCard(
                        pending = message.pendingDelete,
                        isResolved = message.isActionResolved,
                        onConfirm = { onConfirmDelete(message.pendingDelete) },
                        onCancel = onCancelDelete
                    )
                }

                // 4. Kartu Berkas Hasil Pencarian Sekali Ketuk (P1)
                if (!message.foundFiles.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    message.foundFiles.forEach { fileItem ->
                        SingleTapFileCard(file = fileItem, onFileClick = { onFileClick(fileItem) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmationDeleteCard(
    pending: PendingDeleteInfo,
    isResolved: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TowrRedBg)
            .border(1.2.dp, TowrRedDanger, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = "Peringatan", tint = TowrRedDanger, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Konfirmasi Hapus Berkas", color = TowrRedDanger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Berkas: ${pending.fileName}", color = TowrTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Ukuran: ${pending.fileSizeFormatted}", color = TowrTextSecondary, fontSize = 11.sp)

            Spacer(modifier = Modifier.height(12.dp))

            if (!isResolved) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TowrTextSecondary),
                        border = BorderStroke(1.dp, TowrBorderDim),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Batal", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = TowrRedDanger),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Hapus Sekarang", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(
                    text = "Aksi telah diselesaikan.",
                    color = TowrTextSecondary,
                    fontSize = 12.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
    }
}

@Composable
fun CollapsibleThinkingCard(thinkingLogs: List<ToolExecutionLog>) {
    var isExpanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "arrowAnim")
    val totalLatency = thinkingLogs.sumOf { it.latencyMs }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TowrSurfaceElevated)
            .border(1.dp, TowrBorderDim, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Sukses", tint = TowrEmeraldGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Log Eksekusi (${thinkingLogs.size} Tool • ${totalLatency}ms)", color = TowrTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Buka", tint = TowrSkyBlue, modifier = Modifier.size(18.dp).rotate(arrowRotation))
        }

        AnimatedVisibility(visible = isExpanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                thinkingLogs.forEach { log ->
                    Text("• ${log.toolName} [${log.latencyMs}ms]: ${log.actionDetail}", color = TowrSkyBlue, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun SingleTapFileCard(file: FileSearchResult, onFileClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TowrSurfaceElevated)
            .border(1.dp, TowrSkyBlue.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TowrBgDark)
                    .border(1.dp, TowrRedDanger.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(file.extension.take(4), color = TowrTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(file.fileName, color = TowrTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${file.fileSizeFormatted} • ${file.lastModifiedFormatted}", color = TowrTextSecondary, fontSize = 11.sp)
            }
        }

        Button(
            onClick = onFileClick,
            colors = ButtonDefaults.buttonColors(containerColor = TowrElectricCyan),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text("Buka", color = TowrBgDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// =========================================================================
// 7. INPUT BAR DI BAWAH LAYAR
// =========================================================================
@Composable
fun TowrInputBar(
    inputPrompt: String,
    onPromptChanged: (String) -> Unit,
    onSendClicked: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TowrBgDark)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = inputPrompt,
            onValueChange = onPromptChanged,
            placeholder = { Text("Beri instruksi ke TOWR...", color = TowrTextSecondary, fontSize = 13.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TowrElectricCyan,
                unfocusedBorderColor = TowrBorderDim,
                focusedTextColor = TowrTextPrimary,
                unfocusedTextColor = TowrTextPrimary,
                focusedContainerColor = TowrSurfaceDark,
                unfocusedContainerColor = TowrSurfaceDark
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f),
            maxLines = 3
        )

        Spacer(modifier = Modifier.width(10.dp))

        Button(
            onClick = onSendClicked,
            colors = ButtonDefaults.buttonColors(containerColor = TowrElectricCyan),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(52.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Send, contentDescription = "Kirim", tint = TowrBgDark, modifier = Modifier.size(22.dp))
        }
    }
}

// =========================================================================
// 8. DIALOG PENGATURAN TOWR ([⚙️] SETTINGS)
// =========================================================================
@Composable
fun TowrSettingsDialog(
    prefs: android.content.SharedPreferences,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var hybridMode by remember { mutableStateOf(prefs.getBoolean("hybrid_mode_enabled", false)) }
    var temp by remember { mutableStateOf(prefs.getFloat("ai_temperature", 0.8f)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TowrSurfaceDark,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = TowrElectricCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pengaturan TOWR", color = TowrTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Mode Hybrid Cloud (Gemini Free Tier):", color = TowrTextSecondary, fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (hybridMode) "Aktif (Cloud + Offline)" else "Nonaktif (100% Offline)", color = TowrTextPrimary, fontSize = 13.sp)
                    Switch(
                        checked = hybridMode,
                        onCheckedChange = { hybridMode = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TowrElectricCyan, checkedTrackColor = TowrGreenBg)
                    )
                }

                if (hybridMode) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Gemini API Key (Gratis)", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TowrTextPrimary,
                            unfocusedTextColor = TowrTextPrimary,
                            focusedBorderColor = TowrElectricCyan,
                            unfocusedBorderColor = TowrBorderDim
                        )
                    )
                }

                Text("Temperatur AI: ${String.format(Locale.US, "%.1f", temp)}", color = TowrTextSecondary, fontSize = 12.sp)
                Slider(
                    value = temp,
                    onValueChange = { temp = it },
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = TowrElectricCyan, activeTrackColor = TowrSkyBlue)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    prefs.edit()
                        .putString("gemini_api_key", apiKey.trim())
                        .putBoolean("hybrid_mode_enabled", hybridMode)
                        .putFloat("ai_temperature", temp)
                        .apply()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TowrElectricCyan)
            ) {
                Text("Simpan", color = TowrBgDark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = TowrTextSecondary)
            }
        }
    )
}

// =========================================================================
// 9. UTILITAS INTENT BUKA BERKAS
// =========================================================================
fun openFileWithSystemApp(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Berkas tidak ditemukan: $filePath", Toast.LENGTH_SHORT).show()
            return
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val extension = file.extension.lowercase()
        val mimeType = when (extension) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "jpg", "jpeg", "png" -> "image/*"
            "txt" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooserIntent = Intent.createChooser(intent, "Buka berkas dengan...")
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooserIntent)
    } catch (e: Exception) {
        Toast.makeText(context, "Gagal membuka berkas: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}
