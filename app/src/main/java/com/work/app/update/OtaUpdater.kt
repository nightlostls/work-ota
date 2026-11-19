package com.work.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object OtaUpdater {
    // Repo GitHub OTA riêng cho app Work
    private const val REPO = "nightlostls/work-ota"

    fun getCurrentVersion(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    fun checkAndInstallUpdate(context: Context, onStatus: (String) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        fun postStatus(msg: String) = mainHandler.post { onStatus(msg) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            postStatus("Hãy bật quyền 'Cài đặt ứng dụng không xác định' cho Work rồi bấm lại.")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        postStatus("Đang kiểm tra bản mới trên GitHub...")
        Thread {
            try {
                val apiUrl = URL("https://api.github.com/repos/$REPO/releases/latest")
                val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                if (conn.responseCode != 200) {
                    postStatus("Chưa có bản phát hành nào trên $REPO (HTTP ${conn.responseCode}).")
                    return@Thread
                }

                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.optString("tag_name", "").removePrefix("v")
                val current = getCurrentVersion(context)
                if (tag.isBlank() || tag == current) {
                    postStatus("Bạn đang dùng bản mới nhất (v$current).")
                    return@Thread
                }

                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (apkUrl == null) {
                    postStatus("Tìm thấy v$tag nhưng chưa có file .apk đính kèm.")
                    return@Thread
                }

                postStatus("Đang tải bản cập nhật v$tag...")
                val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updateDir, "work-update.apk")
                URL(apkUrl).openStream().use { input ->
                    apkFile.outputStream().use { out -> input.copyTo(out) }
                }

                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                postStatus("Đang mở trình cài đặt v$tag...")
                mainHandler.post { context.startActivity(installIntent) }
            } catch (e: Exception) {
                postStatus("Lỗi kiểm tra cập nhật: ${e.message}")
            }
        }.start()
    }
}
