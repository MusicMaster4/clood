package dev.slop.clood

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class AndroidUpdateManifest(
    val channel: String,
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val sha256: String,
) {
    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

    fun toJson(): String = JSONObject()
        .put("channel", channel)
        .put("versionName", versionName)
        .put("versionCode", versionCode)
        .put("apkUrl", apkUrl)
        .put("sha256", sha256)
        .toString()

    companion object {
        fun fromJson(raw: String): AndroidUpdateManifest {
            val json = JSONObject(raw)
            val channel = json.getString("channel")
            require(channel == "stable" || channel == "testing") { "Unknown update channel." }
            val sha256 = json.getString("sha256").lowercase()
            require(sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid update checksum." }
            val apkUrl = json.getString("apkUrl")
            require(URI(apkUrl).run { scheme == "https" && host == "github.com" }) {
                "Update download must be hosted by GitHub."
            }
            return AndroidUpdateManifest(
                channel = channel,
                versionName = json.getString("versionName"),
                versionCode = json.getInt("versionCode"),
                apkUrl = apkUrl,
                sha256 = sha256,
            )
        }
    }
}

object UpdateClient {
    fun manifestUrl(channel: String, repo: String = BuildConfig.GITHUB_REPO): String =
        if (channel == "testing") {
            "https://github.com/$repo/releases/download/channel-testing/android-update-beta.json"
        } else {
            "https://github.com/$repo/releases/latest/download/android-update.json"
        }

    fun fetch(channel: String = BuildConfig.UPDATE_CHANNEL): AndroidUpdateManifest {
        val conn = URL(manifestUrl(channel)).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Clood-Android/${BuildConfig.VERSION_NAME}")
            val status = conn.responseCode
            if (status !in 200..299) error("GitHub returned HTTP $status.")
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            AndroidUpdateManifest.fromJson(raw).also {
                require(it.channel == channel) { "Update belongs to another channel." }
            }
        } finally {
            conn.disconnect()
        }
    }
}

class AppUpdater(private val context: Context) {
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("clood-updater", Context.MODE_PRIVATE)

    fun enqueue(manifest: AndroidUpdateManifest): Long {
        val target = updateFile()
        if (target.exists() && !target.delete()) error("Could not clear previous update.")
        val req = DownloadManager.Request(Uri.parse(manifest.apkUrl))
            .setTitle("Clood ${manifest.versionName}")
            .setDescription("Downloading verified ${if (manifest.channel == "testing") "beta" else "stable"} update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, UPDATE_FILE)
        val id = downloads.enqueue(req)
        prefs.edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_MANIFEST, manifest.toJson())
            .apply()
        return id
    }

    fun isPendingDownload(id: Long): Boolean = id == prefs.getLong(KEY_DOWNLOAD_ID, -1L)

    fun verifyAndInstall(id: Long) {
        require(isPendingDownload(id)) { "Unknown download." }
        downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            require(cursor.moveToFirst()) { "Download missing." }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) error("Download failed.")
        }
        val manifest = prefs.getString(KEY_MANIFEST, null)
            ?.let { AndroidUpdateManifest.fromJson(it) }
            ?: error("Manifest missing.")
        val file = updateFile()
        require(file.isFile) { "APK missing." }
        if (!file.sha256().equals(manifest.sha256, ignoreCase = true)) {
            file.delete()
            clearPending()
            error("APK SHA-256 verification failed.")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        clearPending()
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    fun clearPending() {
        prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_MANIFEST).apply()
    }

    private fun updateFile(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("Downloads directory unavailable.")
        return File(dir, UPDATE_FILE)
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val UPDATE_FILE = "clood-update.apk"
        private const val KEY_DOWNLOAD_ID = "download-id"
        private const val KEY_MANIFEST = "manifest"
    }
}
