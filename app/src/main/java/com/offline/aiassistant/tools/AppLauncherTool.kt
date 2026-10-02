package com.offline.aiassistant.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

class AppLauncherTool(private val context: Context) {

    // Membuka aplikasi di HP berdasarkan nama aplikasinya
    fun openAppByName(appNameQuery: String): String {
        val packageManager: PackageManager = context.packageManager
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

        var matchedPackage: String? = null
        var matchedLabel: String? = null

        // Cari aplikasi yang namanya paling mendekati perintah Anda
        for (app in installedApps) {
            val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            // Pastikan aplikasi memiliki ikon peluncur (bukan proses tersembunyi)
            val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
            if (launchIntent != null) {
                val appLabel = packageManager.getApplicationLabel(app).toString()
                if (appLabel.contains(appNameQuery.trim(), ignoreCase = true)) {
                    matchedPackage = app.packageName
                    matchedLabel = appLabel
                    break
                }
            }
        }

        if (matchedPackage == null) {
            return "Maaf, aplikasi dengan nama '$appNameQuery' tidak ditemukan di HP Anda."
        }

        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(matchedPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "Berhasil membuka aplikasi $matchedLabel."
            } else {
                "Gagal: Tidak dapat meluncurkan $matchedLabel."
            }
        } catch (e: Exception) {
            "Terjadi kesalahan saat membuka aplikasi: ${e.localizedMessage}"
        }
    }
}
