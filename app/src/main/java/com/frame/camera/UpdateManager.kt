package com.frame.camera

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAsset(val name: String, val url: String)
data class AppUpdate(val version: String, val asset: ReleaseAsset)

fun isNewerVersion(latest: String, current: String): Boolean {
    val latestParts = latest.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val currentParts = current.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    repeat(maxOf(latestParts.size, currentParts.size)) { index ->
        val comparison = (latestParts.getOrElse(index) { 0 }).compareTo(currentParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return false
}

fun selectApk(assets: List<ReleaseAsset>, abi: String): ReleaseAsset? =
    assets.firstOrNull { it.name.endsWith("-$abi-release.apk") }

class UpdateManager(private val context: Context) {
    fun check(currentVersion: String): AppUpdate? {
        val connection = URL("https://api.github.com/repos/TheGeeKing/frame/releases/latest").openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Frame-Android")
        return try {
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = release.getString("tag_name")
            if (!isNewerVersion(version, currentVersion)) return null
            val jsonAssets = release.getJSONArray("assets")
            val assets = (0 until jsonAssets.length()).map { index ->
                jsonAssets.getJSONObject(index).let { ReleaseAsset(it.getString("name"), it.getString("browser_download_url")) }
            }
            val asset = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { selectApk(assets, it) } ?: return null
            AppUpdate(version.removePrefix("v"), asset)
        } finally {
            connection.disconnect()
        }
    }

    fun install(update: AppUpdate) {
        val downloads = context.getSystemService(DownloadManager::class.java)
        val id = downloads.enqueue(
            DownloadManager.Request(Uri.parse(update.asset.url))
                .setTitle("Frame ${update.version}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Frame-${update.version}-${System.currentTimeMillis()}.apk",
                ),
        )
        context.getSharedPreferences("frame", Context.MODE_PRIVATE).edit().putLong(DOWNLOAD_ID, id).commit()
    }
}

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val preferences = context.getSharedPreferences("frame", Context.MODE_PRIVATE)
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE || id != preferences.getLong(DOWNLOAD_ID, -1)) return
        preferences.edit().remove(DOWNLOAD_ID).apply()
        val apk = context.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(id) ?: return
        context.startActivity(
            Intent(Intent.ACTION_VIEW, apk)
                .setDataAndType(apk, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}

private const val DOWNLOAD_ID = "updateDownloadId"
