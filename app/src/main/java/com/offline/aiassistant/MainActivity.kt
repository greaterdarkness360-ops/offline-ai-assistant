package com.offline.aiassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.offline.aiassistant.ai.GemmaEngine
import com.offline.aiassistant.router.ActionRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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

enum class MessageSender { USER, TOWR, SYSTEM }

data class FileSearchResult(
    val fileName: String,
    val filePath: String,
    val fileSizeFormatted: String,
    val lastModifiedFormatted: String,
    val extension: String
)

data class ToolExecutionLog(
    val toolName: String,
    val actionDetail: String,
    val latencyMs: Long,
    val isSuccess: Boolean
)

data class TowrChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val messageText: String,
    val timestamp: String,
    val thinkingProcess: List<ToolExecutionLog>? = null,
    val foundFiles: List<FileSearchResult>? = null,
    val isLoading: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TowrTheme {
                TowrMainScreen()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TowrMainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputPrompt by remember { mutableStateOf("") }

    val actionRouter = remember { ActionRouter(context) }
    val gemmaEngine = remember { GemmaEngine(context) }

    var modelStatusLabel by remember { mutableStateOf("Memeriksa Model...") }
    var isGemmaReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val detectedPath = gemmaEngine.findModelPath()
            if (detectedPath != null) {
                modelStatusLabel = "Memuat ke GPU..."
                gemmaEngine.loadModel(detectedPath)
                if (gemmaEngine.isModelLoaded) {
                    isGemmaReady = true
                    val fileName = File(detectedPath).name
                    modelStatusLabel = "Gemma Aktif: $fileName"
                } else {
                    modelStatusLabel = "Mode Native (Gemma: Fallback)"
                }
            } else {
                modelStatusLabel = "Kaitkan Model Gemma"
            }
        }
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Model Terpilih"
            modelStatusLabel = "Memuat $fileName..."
            coroutineScope.launch(Dispatchers.IO) {
                val detected = gemmaEngine.findModelPath() ?: "/storage/emulated/0/Download/$fileName"
                gemmaEngine.loadModel(detected)
                isGemmaReady = gemmaEngine.isModelLoaded
                modelStatusLabel = if (isGemmaReady) "Gemma Aktif: $fileName" else "Gagal memuat $fileName"
            }
        }
    }

    val messages = remember { mutableStateListOf<TowrChatMessage>() }

    val sendInstruction: (String) -> Unit = { rawText ->
        val trimmed = rawText.trim()
        if (trimmed.isNotBlank()) {
            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            messages.add(
                TowrChatMessage(
                    sender = MessageSender.USER,
                    messageText = trimmed,
                    timestamp = currentTime
                )
            )

            val loadingMessageId = UUID.randomUUID().toString()
            messages.add(
                TowrChatMessage(
                    id = loadingMessageId,
                    sender = MessageSender.TOWR,
                    messageText = "Perintah diterima. Sedang memproses, mohon tunggu sebentar...",
                    timestamp = currentTime,
                    isLoading = true
                )
            )

            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)

                val startTime = System.currentTimeMillis()
                val (finalMessage, files, toolName) = withContext(Dispatchers.IO) {
                    if (gemmaEngine.isModelLoaded) {
                        val aiResponse = gemmaEngine.askGemma(trimmed)
                        val lowerTrimmed = trimmed.lowercase()

                        if (aiResponse.contains("\"action\"")) {
                            // 1. Gemma menghasilkan JSON aksi
                            val routerRes = actionRouter.processInstruction(aiResponse)
                            Triple(routerRes.message, routerRes.files, "Gemma AI + ActionRouter")
                        } else if (lowerTrimmed.startsWith("cari") || 
                                   lowerTrimmed.startsWith("buka") || 
                                   lowerTrimmed.startsWith("luncurkan") || 
                                   lowerTrimmed.startsWith("hapus") || 
                                   lowerTrimmed.startsWith("bersihkan") || 
                                   lowerTrimmed.startsWith("kirim") ||
                                   lowerTrimmed.contains("wa") ||
                                   lowerTrimmed.contains("whatsapp")) {
                            // 2. HYBRID GUARD: Jika Gemma membalas teks basa-basi padahal ini instruksi perangkat, langsung eksekusi secara native!
                            val routerRes = actionRouter.processInstruction(trimmed)
                            Triple(routerRes.message, routerRes.files, "ActionRouter Native Guard")
                        } else {
                            // 3. Obrolan umum bebas dari Gemma
                            Triple(aiResponse, emptyList(), "Gemma 2B (On-Device LLM)")
                        }
                    } else {
                        val routerRes = actionRouter.processInstruction(trimmed)
                        Triple(routerRes.message, routerRes.files, "ActionRouter Native Engine")
                    }
                }
                val latency = System.currentTimeMillis() - startTime

                val resultFiles = files.map {
                    FileSearchResult(
                        fileName = it.name,
                        filePath = it.path,
                        fileSizeFormatted = it.size,
                        lastModifiedFormatted = it.lastModified,
                        extension = it.extension
                    )
                }

                val log = listOf(
                    ToolExecutionLog(
                        toolName = toolName,
                        actionDetail = "Input: '$trimmed'",
                        latencyMs = latency,
                        isSuccess = true
                    )
                )

                val targetIndex = messages.indexOfFirst { it.id == loadingMessageId }
                if (targetIndex != -1) {
                    messages[targetIndex] = TowrChatMessage(
                        id = loadingMessageId,
                        sender = MessageSender.TOWR,
                        messageText = finalMessage,
                        timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                        thinkingProcess = log,
                        foundFiles = if (resultFiles.isNotEmpty()) resultFiles else null,
                        isLoading = false
                    )
                }

                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Scaffold(
        containerColor = TowrBgDark,
        topBar = {
            TowrTopBar(
                modelStatus = modelStatusLabel,
                isReady = isGemmaReady,
                onSelectModelClicked = { modelPickerLauncher.launch(arrayOf("*/*")) },
                onClearChatClicked = {
                    messages.clear()
                    Toast.makeText(context, "Riwayat pesan dibersihkan.", Toast.LENGTH_SHORT).show()
                }
            )
        },
        bottomBar = {
            TowrInputBar(
                inputPrompt = inputPrompt,
                onPromptChanged = { inputPrompt = it },
                onSendClicked = {
                    val txt = inputPrompt
                    inputPrompt = ""
                    sendInstruction(txt)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            QuickActionChipsRow(
                onChipClicked = { chipAction ->
                    when (chipAction) {
                        "INPUT_CARI" -> {
                            inputPrompt = "cari file "
                        }
                        "INPUT_BUKA" -> {
                            inputPrompt = "buka aplikasi "
                        }
                        "CLEAR_CHAT" -> {
                            messages.clear()
                            Toast.makeText(context, "Pesan telah dibersihkan.", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            sendInstruction(chipAction)
                        }
                    }
                }
            )

            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "TOWR Agent AI siap menerima perintah.\nGunakan tombol cepat di atas atau ketik instruksi Anda.",
                        color = TowrTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
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
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TowrTopBar(
    modelStatus: String,
    isReady: Boolean,
    onSelectModelClicked: () -> Unit,
    onClearChatClicked: () -> Unit
) {
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
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSelectModelClicked() }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = modelStatus,
                            color = if (isReady) TowrEmeraldGreen else TowrSkyBlue,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.Edit, contentDescription = "Ganti Model", tint = TowrSkyBlue, modifier = Modifier.size(11.dp))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClearChatClicked, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Bersihkan Chat", tint = TowrTextSecondary, modifier = Modifier.size(19.dp))
                }
                Spacer(modifier = Modifier.width(4.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TowrGreenBg)
                        .border(1.dp, TowrEmeraldGreen, RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TowrEmeraldGreen))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("100% OFFLINE", color = TowrEmeraldGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Divider(color = TowrBorderDim.copy(alpha = 0.5f), thickness = 1.dp)
    }
}

@Composable
fun QuickActionChipsRow(onChipClicked: (String) -> Unit) {
    val scrollState = rememberScrollState()
    val chips = listOf(
        Pair("🔍 Cari File...", "INPUT_CARI"),
        Pair("🕒 24 Jam Terakhir", "cari file 24 jam terakhir"),
        Pair("📑 File Duplikat", "cek file duplikat"),
        Pair("🧹 Bersihkan Duplikat", "hapus file ganda"),
        Pair("🚀 Buka Aplikasi...", "INPUT_BUKA"),
        Pair("📊 Status Aktivitas", "cek aktivitas"),
        Pair("🗑️ Bersihkan Pesan", "CLEAR_CHAT")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { (label, action) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(TowrSurfaceDark)
                    .border(1.dp, TowrSkyBlue.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .clickable { onChipClicked(action) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = TowrSkyBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
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
fun TowrAgentBubble(message: TowrChatMessage, onFileClick: (FileSearchResult) -> Unit) {
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

                if (message.isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = TowrElectricCyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = message.messageText,
                            color = TowrSkyBlue,
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic
                        )
                    }
                } else {
                    if (!message.thinkingProcess.isNullOrEmpty()) {
                        CollapsibleThinkingCard(thinkingLogs = message.thinkingProcess)
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (!message.foundFiles.isNullOrEmpty()) {
                        message.foundFiles.forEach { fileItem ->
                            SingleTapFileCard(file = fileItem, onFileClick = { onFileClick(fileItem) })
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }

                    Text(message.messageText, color = TowrTextPrimary, fontSize = 14.sp, lineHeight = 21.sp)
                }
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
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = TowrEmeraldGreen, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Log Eksekusi (${thinkingLogs.size} Tool • ${totalLatency}ms)", color = TowrSkyBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TowrSkyBlue, modifier = Modifier.size(18.dp).rotate(arrowRotation))
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Divider(color = TowrBorderDim.copy(alpha = 0.5f), thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(8.dp))
                thinkingLogs.forEachIndexed { index, log ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("[${index + 1}]", color = TowrElectricCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(log.toolName, color = TowrElectricCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                Text("${log.latencyMs}ms", color = TowrEmeraldGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text(log.actionDetail, color = TowrTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                        }
                    }
                    if (index < thinkingLogs.size - 1) Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun SingleTapFileCard(file: FileSearchResult, onFileClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TowrSurfaceElevated)
            .border(1.2.dp, TowrElectricCyan, RoundedCornerShape(12.dp))
            .clickable { onFileClick() }
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFDC2626).copy(alpha = 0.2f))
                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(file.extension, color = Color(0xFFFCA5A5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.fileName, color = TowrTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text("${file.fileSizeFormatted} • ${file.lastModifiedFormatted}", color = TowrTextSecondary, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onFileClick,
                colors = ButtonDefaults.buttonColors(containerColor = TowrElectricCyan),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("Buka", color = TowrBgDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun TowrInputBar(inputPrompt: String, onPromptChanged: (String) -> Unit, onSendClicked: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TowrBgDark)
            .padding(16.dp),
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
            "com.offline.aiassistant.provider",
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

        val chooser = Intent.createChooser(intent, "Buka berkas dengan...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Gagal membuka berkas: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}
