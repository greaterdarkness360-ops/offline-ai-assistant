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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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

@Composable
fun TowrMainScreen(gemmaEngine: GemmaEngine, actionRouter: ActionRouter) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputPrompt by remember { mutableStateOf("") }
    var engineStatusText by remember { mutableStateOf("Memuat model...") }

    val messages = remember {
        mutableStateListOf(
            TowrChatMessage(
                sender = MessageSender.TOWR,
                messageText = "TOWR Executive Assistant aktif. Siap mengeksekusi instruksi berkas, aplikasi, dan produktivitas.",
                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            )
        )
    }

    // Inisialisasi model di latar belakang
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val modelFile = File(downloadDir, "gemma-4-E2B-it-gpu.litertlm")
            if (modelFile.exists()) {
                val res = gemmaEngine.loadModel(modelFile.absolutePath)
                engineStatusText = if (gemmaEngine.isModelLoaded) "Gemma: gemma-4... (GPU)" else res
            } else {
                engineStatusText = "Model .litertlm tidak ditemukan di folder Download"
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
        TowrTopBar(engineStatusText)

        QuickActionChipsRow(onChipClicked = { inputPrompt = it })

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                                    // Update status pesan
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
                                        messageText = "Penghapusan berkas dibatalkan oleh pengguna."
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

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
                                toolName = "TOWR Execution Engine",
                                actionDetail = if (aiOutput.isNotBlank()) "AI JSON / Rule: $aiOutput" else "Direct Rule Execution",
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
}

@Composable
fun TowrTopBar(statusText: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TowrBgDark)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Column {
                    Text("TOWR AGENT AI", color = TowrTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(statusText, color = TowrSkyBlue, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(TowrGreenBg)
                    .border(1.dp, TowrEmeraldGreen, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(TowrEmeraldGreen))
                Spacer(modifier = Modifier.width(6.dp))
                Text("100% OFFLINE", color = TowrEmeraldGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun QuickActionChipsRow(onChipClicked: (String) -> Unit) {
    val quickActions = listOf(
        Pair("🔍 Cari File...", "cari file "),
        Pair("⏰ 24 Jam Terakhir", "cari file paling baru"),
        Pair("📑 File Duplikat", "file ganda"),
        Pair("📱 Pemakaian HP", "pemakaian aplikasi")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                Text(label, color = TowrTextSecondary, fontSize = 12.sp)
            }
        }
    }
}

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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("TOWR On-Device Intelligence", color = TowrElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(message.timestamp, color = TowrTextSecondary, fontSize = 10.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))

                // Log Eksekusi
                if (!message.thinkingProcess.isNullOrEmpty()) {
                    CollapsibleThinkingCard(thinkingLogs = message.thinkingProcess)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Teks Utama TOWR
                Text(message.messageText, color = TowrTextPrimary, fontSize = 14.sp, lineHeight = 21.sp)

                // Kartu Guardrail Konfirmasi Hapus
                if (message.pendingDelete != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ConfirmationDeleteCard(
                        pending = message.pendingDelete,
                        isResolved = message.isActionResolved,
                        onConfirm = { onConfirmDelete(message.pendingDelete) },
                        onCancel = onCancelDelete
                    )
                }

                // Kartu Daftar Berkas
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
                Text("Konfirmasi Hapus Berkas", color = TowrRedDanger, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Berkas: ${pending.fileName}", color = TowrTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Ukuran: ${pending.fileSizeFormatted}", color = TowrTextSecondary, fontSize = 12.sp)

            Spacer(modifier = Modifier.height(12.dp))

            if (!isResolved) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TowrTextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TowrBorderDim),
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
                Text("Tindakan telah diselesaikan.", color = TowrTextSecondary, fontSize = 12.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            modifier = Modifier.size(54.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Send, contentDescription = "Kirim", tint = TowrBgDark, modifier = Modifier.size(22.dp))
        }
    }
}

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
