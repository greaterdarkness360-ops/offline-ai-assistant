package com.offline.aiassistant.tools

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.util.Calendar

class UsageStatsTool(private val context: Context) {

    // 1. Memeriksa apakah izin akses aktivitas sudah diizinkan di HP
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // 2. Membaca ringkasan aplikasi aktif dan durasi penggunaannya dalam 24 jam terakhir
    fun getRecentUsageSummary(): String {
        if (!hasUsageStatsPermission()) {
            return "PERHATIAN: Izin pemantauan aktivitas belum aktif.\n" +
                    "Silakan buka Pengaturan HP > Akses Penggunaan (Usage Access) > Aktifkan izin untuk aplikasi TOWR."
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.add(Calendar.HOUR_OF_DAY, -24) // Pantau 24 jam ke belakang
        val startTime = calendar.timeInMillis

        val statsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        if (statsList.isNullOrEmpty()) {
            return "Tidak ada data riwayat aplikasi yang tercatat dalam 24 jam terakhir."
        }

        val packageManager = context.packageManager

        // Mengelompokkan aplikasi dan menghitung total menit berjalan
        val topApps = statsList
            .filter { it.totalTimeInForeground > 60 * 1000 } // Minimal berjalan 1 menit
            .groupBy { it.packageName }
            .mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }
            .toList()
            .sortedByDescending { it.second }
            .take(6) // Tampilkan 6 aplikasi terbanyak

        if (topApps.isEmpty()) {
            return "Belum ada aplikasi yang tercatat aktif dalam 24 jam terakhir."
        }

        val reportLines = topApps.mapIndexed { index, (pkg, durationMs) ->
            val appLabel = try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                pkg
            }
            val minutes = durationMs / (1000 * 60)
            "${index + 1}. $appLabel: $minutes menit"
        }

        return "📊 LAPORAN AKTIVITAS APLIKASI (24 Jam Terakhir):\n" +
                reportLines.joinToString("\n")
    }
}
