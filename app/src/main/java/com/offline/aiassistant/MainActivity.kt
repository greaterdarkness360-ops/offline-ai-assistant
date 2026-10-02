package com.offline.aiassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var queryText by remember { mutableStateOf("") }
    var terminalOutput by remember {
        mutableStateOf(
            "STATUS: SISTEM ONLINE\n" +
            "OTAK: GEMMA LOCAL INT4\n" +
            "JARINGAN: 100% OFFLINE TERISOLASI\n\n" +
            "TOWR siap menerima instruksi native."
        )
    }

    val skyBlue = Color(0xFF38BDF8)
    val electricBlue = Color(0xFF00E5FF)
    val containerColor = Color(0xFF0F172A)
    val borderDim = Color(0xFF1E293B)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
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

        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = containerColor,
            border = BorderStroke(1.dp, electricBlue.copy(alpha = 0.35f))
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionChip(title = "🔍 Cari File", accent = skyBlue, bg = containerColor) {
                queryText = "Cari file "
            }
            ActionChip(title = "🚀 Buka Aplikasi", accent = skyBlue, bg = containerColor) {
                queryText = "Buka aplikasi "
            }
            ActionChip(title = "📊 Cek Aktivitas HP", accent = skyBlue, bg = containerColor) {
                queryText = "Periksa aktivitas latar belakang"
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                placeholder = {
                    Text("Ketik instruksi untuk TOWR...", color = Color.Gray, fontSize = 13.sp)
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
                        terminalOutput += "\n\n> USER: $queryText\n> TOWR: Mengeksekusi instruksi..."
                        queryText = ""
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
