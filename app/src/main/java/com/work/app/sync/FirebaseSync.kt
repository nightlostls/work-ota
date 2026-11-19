package com.work.app.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.work.app.data.WorkRepository
import com.work.app.data.WorkTask
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

data class SyncPayload(
    val tasks: List<WorkTask> = emptyList(),
    val doneToday: List<String> = emptyList(),
    val doneDate: String = LocalDate.now().toString(),
    val updatedAt: Long = System.currentTimeMillis()
)

object FirebaseSync {
    private const val BASE_URL = "https://worksync-9a7d6-default-rtdb.asia-southeast1.firebasedatabase.app"
    private val gson = Gson()

    fun pushToCloud(context: Context, onDone: ((String) -> Unit)? = null) {
        val repo = WorkRepository(context)
        val room = repo.getSyncRoomCode().trim()
        if (room.isBlank()) {
            onDone?.invoke("Chưa nhập mã phòng đồng bộ")
            return
        }
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val payload = SyncPayload(
                    tasks = repo.getTasks(),
                    doneToday = repo.getCompletedIds().toList(),
                    doneDate = LocalDate.now().toString(),
                    updatedAt = System.currentTimeMillis()
                )
                val url = URL("$BASE_URL/rooms/$room.json")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                conn.outputStream.use { it.write(gson.toJson(payload).toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                mainHandler.post {
                    onDone?.invoke(if (code in 200..299) "Đã đẩy dữ liệu lên đám mây ($room)" else "Lỗi đẩy dữ liệu (HTTP $code)")
                }
            } catch (e: Exception) {
                mainHandler.post { onDone?.invoke("Lỗi mạng: ${e.message}") }
            }
        }.start()
    }

    fun pullFromCloud(context: Context, onApplied: (Boolean, String) -> Unit) {
        val repo = WorkRepository(context)
        val room = repo.getSyncRoomCode().trim()
        if (room.isBlank()) {
            onApplied(false, "Chưa nhập mã phòng đồng bộ")
            return
        }
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val url = URL("$BASE_URL/rooms/$room.json")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val body = conn.inputStream.bufferedReader().readText()
                if (body.isBlank() || body == "null") {
                    mainHandler.post { onApplied(false, "Phòng '$room' chưa có dữ liệu. Hãy bấm 'Đẩy lên' từ máy chính trước.") }
                    return@Thread
                }
                val type = object : TypeToken<SyncPayload>() {}.type
                val payload: SyncPayload = gson.fromJson(body, type)
                repo.saveTasks(payload.tasks)
                if (payload.doneDate == LocalDate.now().toString()) {
                    repo.setCompletedIds(payload.doneToday.toSet())
                }
                mainHandler.post { onApplied(true, "Đã đồng bộ ${payload.tasks.size} việc từ phòng '$room'") }
            } catch (e: Exception) {
                mainHandler.post { onApplied(false, "Lỗi đồng bộ: ${e.message}") }
            }
        }.start()
    }
}
