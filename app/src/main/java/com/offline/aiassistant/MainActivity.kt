package com.offline.aiassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import androidx.compose.foundation.verticalScroll
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
import com.offline.aiassistant.ai.GeminiCloudEngine
import com.offline.aiassistant.ai.GemmaEngine
import com.offline.aiassistant.router.ActionRouter
import com.offline.aiassistant.router.PendingDeleteInfo
import com.offline.aiassistant.router.RouterResponse
import com.offline.aiassistant.tools.FileSearchResult
import com.offline.aiassistant.tools.FileTools
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
    val isActionResolved: Boolean = false,
    val isLoading: Boolean = false
)

class MainActivity : ComponentActivity() {

    private lateinit var gemmaEngine: GemmaEngine
    private lateinit var actionRouter: ActionRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 101)
        }

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

fun resolveRealPathFromUri(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.path

    if (DocumentsContract.isDocumentUri(context, uri)) {
        if (uri.authority == "com.android.externalstorage.documents") {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            val relativePath = if (split.size > 1) split[1] else ""
            if ("primary".equals(type, ignoreCase = true)) {
                val candidate = File(Environment.getExternalStorageDirectory(), relativePath)
                if (candidate.exists()) return candidate.absolutePath
            } else {
                val candidate = File("/storage/$type", relativePath)
                if (candidate.exists()) return candidate.absolutePath
            }
        }
    }

    try {
        val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (dataIdx != -1) {
                    val p = cursor.getString(dataIdx)
                    if (!p.isNullOrBlank() && File(p).exists()) return p
                }
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (nameIdx != -1) {
                    val name = cursor.getString(nameIdx)
                    val inDoc = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), name)
                    if (inDoc.exists()) return inDoc.absolutePath
                    val inDown = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
                    if (inDown.exists()) return inDown.absolutePath
                    val inRootDoc = File(Environment.getExternalStorageDirectory(), "Documents/$name")
                    if (inRootDoc.exists()) return inRootDoc.absolutePath
                }
            }
        }
    } catch (_: Exception) {}

    val name = uri.lastPathSegment?.substringAfterLast("/")?.substringAfterLast(":") ?: ""
    if (name.isNotBlank()) {
        val inDoc = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), name)
        if (inDoc.exists()) return inDoc.absolutePath
        val inDown = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
        if (inDown.exists()) return inDown.absolutePath
    }

    return null
}

@Composable
fun TowrMainScreen(gemmaEngine: GemmaEngine, actionRouter: ActionRouter) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val prefs = remember { context.getSharedPreferences("towr_prefs", Context.MODE_PRIVATE) }

    var inputPrompt by remember { mutableStateOf("") }
    var engineStatusText by remember { mutableStateOf("Memeriksa model on-device...") }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var hybridModeEnabled by remember { mutableStateOf(prefs.getBoolean("hybrid_mode_enabled", false)) }
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var selectedCloudModel by remember { mutableStateOf(prefs.getString("gemini_selected_model", "gemini-3.8-flash") ?: "gemini-3.8-flash") }

    val messages = remember {
        mutableStateListOf(
            TowrChatMessage(
                sender = MessageSender.TOWR,
                messageText = "TOWR Executive Assistant aktif. Siap mengeksekusi berkas, WhatsApp, dan email Anda.",
                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            )
        )
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            coroutineScope.launch {
                val realPath = resolveRealPathFromUri(context, uri)
                if (realPath != null && File(realPath).exists()) {
                    prefs.edit().putString("saved_gemma_model_path", realPath).apply()
                    engineStatusText = "Memuat model..."
                    val res = withContext(Dispatchers.IO) {
                        gemmaEngine.loadModel(realPath)
                    }
                    val fileName = File(realPath).name
                    engineStatusText = if (gemmaEngine.isModelLoaded) "Gemma: ${fileName.take(14)} (GPU)" else res
                    Toast.makeText(context, "Model Gemma berhasil terhubung!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Lokasi berkas model tidak dapat dibaca.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val savedPath = prefs.getString("saved_gemma_model_path", null)
            if (!savedPath.isNullOrBlank() && File(savedPath).exists()) {
                val fName = File(savedPath).name
                engineStatusText = "Memuat ${fName.take(12)}..."
                val res = gemmaEngine.loadModel(savedPath)
                engineStatusText = if (gemmaEngine.isModelLoaded) "Gemma: ${fName.take(14)} (GPU)" else res
            } else {
                val inDoc = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "gemma-4-E2B-it-gpu.litertlm")
                val inDown = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "gemma-4-E2B-it-gpu.litertlm")
                val fallbackPath = when {
                    inDoc.exists() -> inDoc.absolutePath
                    inDown.exists() -> inDown.absolutePath
                    else -> null
                }

                if (fallbackPath != null) {
                    prefs.edit().putString("saved_gemma_model_path", fallbackPath).apply()
                    val fName = File(fallbackPath).name
                    engineStatusText = "Memuat ${fName.take(12)}..."
                    val res = gemmaEngine.loadModel(fallbackPath)
                    engineStatusText = if (gemmaEngine.isModelLoaded) "Gemma: ${fName.take(14)} (GPU)" else res
                } else {
                    engineStatusText = "Ketuk untuk memilih model .litertlm"
                }
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
        TowrTopBar(
            statusText = engineStatusText,
            isLoaded = gemmaEngine.isModelLoaded,
            isHybrid = hybridModeEnabled && apiKey.isNotBlank(),
            onModelTextClick = {
                modelPickerLauncher.launch(arrayOf("*/*"))
            },
            onBadgeClick = {
                if (hybridModeEnabled && apiKey.isNotBlank()) {
                    coroutineScope.launch {
                        Toast.makeText(context, "Memeriksa status Gemini Cloud...", Toast.LENGTH_SHORT).show()
                        val testRes = GeminiCloudEngine.testConnection(apiKey, selectedCloudModel)
                        Toast.makeText(context, testRes.second, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, "Mode 100% Offline (Tanpa Internet)", Toast.LENGTH_SHORT).show()
                }
            },
            onSettingsClick = { showSettingsDialog = true },
            onClearHistoryClick = {
                messages.clear()
                FileTools.lastSearchResults.clear()
                FileTools.lastSearchQuery = ""
                messages.add(
                    TowrChatMessage(
                        sender = MessageSender.TOWR,
                        messageText = "Riwayat obrolan dan memori konteks dibersihkan.",
                        timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    )
                )
                Toast.makeText(context, "Riwayat dibersihkan", Toast.LENGTH_SHORT).show()
            }
        )

        QuickActionChipsRow(onChipClicked = { inputPrompt = it })

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
                                        actionRouter.executeConfirmedDelete(pendingInfo)
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
                                        messageText = "Penghapusan ${msg.pendingDelete?.fileCount ?: 1} berkas dibatalkan."
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

                    val loadingMessageId = java.util.UUID.randomUUID().toString()
                    val isCloudActive = hybridModeEnabled && apiKey.isNotBlank()
                    val loadingText = if (isCloudActive) "⚡ Perintah diterima, menganalisis via Gemini Flash..." 
                                      else "⚡ Perintah diterima, menganalisis via on-device engine..."

                    messages.add(
                        TowrChatMessage(
                            id = loadingMessageId,
                            sender = MessageSender.TOWR,
                            messageText = loadingText,
                            timestamp = now,
                            isLoading = true
                        )
                    )

                    coroutineScope.launch {
                        listState.animateScrollToItem(messages.size - 1)

                        val startTime = System.currentTimeMillis()
                        var rawAiOutput = ""
                        var usedEngine = ""
                        var cloudDiagnosticError = ""

                        val recentTurns = messages.takeLast(6).filter { !it.isLoading }
                        val historyBuilder = StringBuilder()
                        for (turn in recentTurns) {
                            val role = if (turn.sender == MessageSender.USER) "Pengguna" else "TOWR"
                            historyBuilder.append("$role: ${turn.messageText}\n")
                        }
                        if (FileTools.lastSearchResults.isNotEmpty()) {
                            historyBuilder.append("[KONTEKS TERAKHIR DI LAYAR]: Pencarian '${FileTools.lastSearchQuery}' baru saja menampilkan ${FileTools.lastSearchResults.size} berkas:\n")
                            FileTools.lastSearchResults.take(5).forEachIndexed { i, f ->
                                historyBuilder.append("  ${i + 1}. ${f.name}\n")
                            }
                        }

                        val systemInstruction = """
                            Kamu adalah TOWR, asisten AI eksekutif Android on-device dan hybrid yang dibuat oleh Natanael.
                            PANDUAN:
                            1. Jika pengguna meminta aksi sistem HP, jawab HANYA dalam format JSON satu baris:
                               - Cari file: {"action": "search_files", "query": "kata_kunci"}
                               - Hapus file: {"action": "delete_file", "file_name": "nama_atau_nomor_atau_semua"}
                               - Cek email masuk: {"action": "check_email", "query": "is:unread"}
                               - WhatsApp: {"action": "share_wa", "contact_name": "NamaKontak", "text": "isi pesan"}
                               - Buka app: {"action": "open_app", "app_name": "NamaApp"}
                            2. Jika pertanyaan umum, sapaan santai ("halo, siapakah kau"), atau pertanyaan evaluasi konteks ("apakah sudah ini semuanya?"), jawab ramah secara manusiawi TANPA format JSON.
                        """.trimIndent()

                        val finalRouterResponse = withContext(Dispatchers.IO) {
                            if (isCloudActive) {
                                val cloudRes = GeminiCloudEngine.askGeminiFlash(
                                    apiKey = apiKey,
                                    systemPrompt = systemInstruction,
                                    conversationHistory = historyBuilder.toString(),
                                    userPrompt = promptText,
                                    customModelName = selectedCloudModel
                                )
                                if (!cloudRes.startsWith("ERROR")) {
                                    rawAiOutput = cloudRes
                                    usedEngine = "Gemini Flash ($selectedCloudModel)"
                                } else {
                                    cloudDiagnosticError = cloudRes
                                }
                            }

                            if (rawAiOutput.isBlank()) {
                                if (gemmaEngine.isModelLoaded) {
                                    val fullLocalPrompt = "$systemInstruction\n\n$historyBuilder\nPengguna: $promptText\nTOWR:"
                                    rawAiOutput = gemmaEngine.askGemma(fullLocalPrompt)
                                    usedEngine = if (cloudDiagnosticError.isNotBlank()) "Gemma 2B (Fallback GPU)" else "Gemma 2B INT4 (Local GPU)"
                                } else {
                                    usedEngine = if (cloudDiagnosticError.isNotBlank()) "Direct Router (Cloud Error)" else "Direct Action Router"
                                }
                            }

                            if (rawAiOutput.isNotBlank() && rawAiOutput.contains("\"action\"")) {
                                actionRouter.processInstruction(rawAiOutput)
                            } else if (rawAiOutput.isNotBlank() && !rawAiOutput.startsWith("MODEL_NOT_READY")) {
                                val prefixNotice = if (cloudDiagnosticError.isNotBlank()) "⚠️ $cloudDiagnosticError\n\n" else ""
                                RouterResponse(message = prefixNotice + rawAiOutput)
                            } else {
                                val fallbackNotice = if (cloudDiagnosticError.isNotBlank()) "⚠️ $cloudDiagnosticError\n\n" else ""
                                val directRes = actionRouter.processInstruction(promptText)
                                directRes.copy(message = fallbackNotice + directRes.message)
                            }
                        }

                        val executionLatency = System.currentTimeMillis() - startTime

                        val executionLog = listOf(
                            ToolExecutionLog(
                                toolName = usedEngine,
                                actionDetail = if (rawAiOutput.isNotBlank()) "Output: ${rawAiOutput.take(50)}" else (cloudDiagnosticError.ifBlank { "Pattern Matched" }),
                                latencyMs = executionLatency,
                                isSuccess = true
                            )
                        )

                        val loadingIndex = messages.indexOfFirst { it.id == loadingMessageId }
                        if (loadingIndex != -1) {
                            messages[loadingIndex] = TowrChatMessage(
                                id = loadingMessageId,
                                sender = MessageSender.TOWR,
                                messageText = finalRouterResponse.message,
                                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                                thinkingProcess = executionLog,
                                foundFiles = finalRouterResponse.files.ifEmpty { null },
                                pendingDelete = finalRouterResponse.pendingDelete,
                                isLoading = false
                            )
                        }

                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
            }
        )
    }

    if (showSettingsDialog) {
        TowrSettingsDialog(
            prefs = prefs,
            onDismiss = {
                showSettingsDialog = false
                hybridModeEnabled = prefs.getBoolean("hybrid_mode_enabled", false)
                apiKey = prefs.getString("gemini_api_key", "") ?: ""
                selectedCloudModel = prefs.getString("gemini_selected_model", "gemini-3.8-flash") ?: "gemini-3.8-flash"
            }
        )
    }
}

@Composable
fun TowrTopBar(
    statusText: String,
    isLoaded: Boolean,
    isHybrid: Boolean,
    onModelTextClick: () -> Unit,
    onBadgeClick: () -> Unit,
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

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable { onModelTextClick() }
                ) {
                    Text(
                        text = "TOWR AGENT AI",
                        color = TowrTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = if (isLoaded) statusText else "📁 $statusText (Ketuk untuk pilih)",
                        color = if (isLoaded) TowrSkyBlue else Color(0xFFFFB74D),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Pengaturan", tint = TowrSkyBlue, modifier = Modifier.size(19.dp))
                }

                IconButton(onClick = onClearHistoryClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Hapus Riwayat", tint = TowrTextSecondary, modifier = Modifier.size(19.dp))
                }

                if (isHybrid) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0C2A3A))
                            .border(1.dp, TowrElectricCyan, RoundedCornerShape(8.dp))
                            .clickable { onBadgeClick() }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TowrElectricCyan))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("HYBRID CLOUD", color = TowrElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(TowrGreenBg)
                            .border(1.dp, TowrEmeraldGreen, RoundedCornerShape(8.dp))
                            .clickable { onBadgeClick() }
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
}

@Composable
fun QuickActionChipsRow(onChipClicked: (String) -> Unit) {
    val quickActions = listOf(
        Pair("🔍 Cari File...", "cari file "),
        Pair("⏰ 24 Jam Terakhir", "cari file paling baru 24 jam terakhir"),
        Pair("📬 Cek Email", "cek email masuk"),
        Pair("📑 File Duplikat", "file ganda"),
        Pair("📱 Pemakaian HP", "pemakaian aplikasi"),
        Pair("💬 Chat WhatsApp", "kirim wa ke ")
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

                if (message.isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = TowrElectricCyan,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(message.messageText, color = TowrSkyBlue, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                } else {
                    if (!message.thinkingProcess.isNullOrEmpty()) {
                        CollapsibleThinkingCard(thinkingLogs = message.thinkingProcess)
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Text(message.messageText, color = TowrTextPrimary, fontSize = 14.sp, lineHeight = 21.sp)

                    if (message.pendingDelete != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ConfirmationDeleteCard(
                            pending = message.pendingDelete,
                            isResolved = message.isActionResolved,
                            onConfirm = { onConfirmDelete(message.pendingDelete) },
                            onCancel = onCancelDelete
                        )
                    }

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
                Text(pending.summaryTitle, color = TowrRedDanger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))

            pending.files.take(3).forEach { f ->
                Text("• ${f.name} (${f.size})", color = TowrTextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pending.files.size > 3) {
                Text("• ...dan ${pending.files.size - 3} berkas lainnya", color = TowrTextSecondary, fontSize = 11.sp)
            }

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
                        val btnText = if (pending.files.size > 1) "Hapus Semua (${pending.files.size} Berkas)" else "Hapus Sekarang"
                        Text(btnText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
// 8. DIALOG PENGATURAN TOWR LENGKAP: SCROLLABLE & BEBAS TERTUTUP
// =========================================================================
@Composable
fun TowrSettingsDialog(
    prefs: android.content.SharedPreferences,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var localKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var localModel by remember { mutableStateOf(prefs.getString("gemini_selected_model", "gemini-3.8-flash") ?: "gemini-3.8-flash") }
    var localWebhook by remember { mutableStateOf(prefs.getString("gas_webhook_url", "") ?: "") }
    var localHybrid by remember { mutableStateOf(prefs.getBoolean("hybrid_mode_enabled", false)) }
    var temp by remember { mutableStateOf(prefs.getFloat("ai_temperature", 0.8f)) }

    var testStatusText by remember { mutableStateOf("") }
    var isTestingConnection by remember { mutableStateOf(false) }

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
            // Kolom dapat digulir bebas (verticalScroll) agar tidak tertutup di layar HP
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Kolom Webhook Google Apps Script (P3 Email)
                Text("Otomatisasi Cloud (Google Apps Script):", color = TowrTextSecondary, fontSize = 12.sp)
                OutlinedTextField(
                    value = localWebhook,
                    onValueChange = { localWebhook = it },
                    label = { Text("Webhook URL GAS (akhiri /exec)", fontSize = 11.sp) },
                    placeholder = { Text("https://script.google.com/macros/s/.../exec", fontSize = 10.sp, color = TowrTextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TowrTextPrimary,
                        unfocusedTextColor = TowrTextPrimary,
                        focusedBorderColor = TowrElectricCyan,
                        unfocusedBorderColor = TowrBorderDim
                    )
                )

                // 2. Mode Hybrid Cloud Gemini
                Text("Mode Hybrid Cloud (Gemini Free Tier):", color = TowrTextSecondary, fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (localHybrid) "Aktif (Cloud + Offline)" else "Nonaktif (100% Offline)", color = TowrTextPrimary, fontSize = 13.sp)
                    Switch(
                        checked = localHybrid,
                        onCheckedChange = { localHybrid = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TowrElectricCyan, checkedTrackColor = TowrGreenBg)
                    )
                }

                if (localHybrid) {
                    OutlinedTextField(
                        value = localKey,
                        onValueChange = { localKey = it },
                        label = { Text("Gemini API Key", fontSize = 11.sp) },
                        placeholder = { Text("Tempel API Key resmi Anda (...hz34)", fontSize = 11.sp, color = TowrTextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TowrTextPrimary,
                            unfocusedTextColor = TowrTextPrimary,
                            focusedBorderColor = TowrElectricCyan,
                            unfocusedBorderColor = TowrBorderDim
                        )
                    )

                    OutlinedTextField(
                        value = localModel,
                        onValueChange = { localModel = it },
                        label = { Text("Model Cloud (Default: gemini-3.8-flash)", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TowrTextPrimary,
                            unfocusedTextColor = TowrTextPrimary,
                            focusedBorderColor = TowrElectricCyan,
                            unfocusedBorderColor = TowrBorderDim
                        )
                    )

                    // Tombol Tes Diagnostik Realtime
                    Button(
                        onClick = {
                            isTestingConnection = true
                            testStatusText = "Menghubungi server Google AI Studio..."
                            coroutineScope.launch {
                                val result = GeminiCloudEngine.testConnection(localKey, localModel)
                                isTestingConnection = false
                                testStatusText = result.second
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TowrSurfaceElevated),
                        border = BorderStroke(1.dp, TowrElectricCyan),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(color = TowrElectricCyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("⚡ Tes Koneksi Cloud Sekarang", color = TowrElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Tampilan Hasil Diagnostik dalam Kotak Khusus yang Jelas & Terbuka
                    if (testStatusText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(TowrSurfaceElevated)
                                .border(
                                    1.dp,
                                    when {
                                        testStatusText.startsWith("🟢") -> TowrEmeraldGreen
                                        testStatusText.startsWith("🟡") -> Color(0xFFFFB74D)
                                        else -> TowrRedDanger
                                    },
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Text(
                                text = testStatusText,
                                color = when {
                                    testStatusText.startsWith("🟢") -> TowrEmeraldGreen
                                    testStatusText.startsWith("🟡") -> Color(0xFFFFB74D)
                                    else -> TowrRedDanger
                                },
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 3. Kontrol Suhu AI (Kreativitas vs Kepastian Jawaban)
                Column {
                    Text("Temperatur AI: ${String.format(Locale.US, "%.1f", temp)}", color = TowrTextSecondary, fontSize = 12.sp)
                    Text("0.1 = Sangat teliti & patuh perintah | 0.8 = Percakapan luwes seimbang", color = TowrBorderDim, fontSize = 10.sp)
                    Slider(
                        value = temp,
                        onValueChange = { temp = it },
                        valueRange = 0.1f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = TowrElectricCyan, activeTrackColor = TowrSkyBlue)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    prefs.edit()
                        .putString("gas_webhook_url", localWebhook.trim())
                        .putString("gemini_api_key", localKey.trim())
                        .putString("gemini_selected_model", localModel.trim().ifBlank { "gemini-3.8-flash" })
                        .putBoolean("hybrid_mode_enabled", localHybrid)
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
