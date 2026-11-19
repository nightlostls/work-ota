package com.work.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class RepeatType { NONE, DAILY, WEEKDAYS, WEEKENDS, CUSTOM }

enum class Quadrant(val label: String, val colorHex: Long) {
    Q1("Quan trọng & Gấp", 0xFFFB7185),
    Q2("Quan trọng", 0xFF38BDF8),
    Q3("Gấp", 0xFFFBBF24),
    Q4("Để sau", 0xFF94A3B8)
}

enum class LockTemplate(val title: String) {
    TODAY("Hôm nay"),
    MONTH("Lịch tháng"),
    MATRIX("Ma trận 4 ô"),
    FOCUS("Tập trung")
}

data class WorkTask(
    val id: String,
    val title: String,
    val note: String = "",
    val startDate: String, // yyyy-MM-dd
    val time: String? = null, // HH:mm or null
    val repeat: RepeatType = RepeatType.NONE,
    val customDays: List<Int> = emptyList(), // 1=Mon..7=Sun
    val quadrant: Quadrant = Quadrant.Q2,
    val highPriority: Boolean = false
)

data class SavedWallpaper(
    val id: String,
    val name: String,
    val filePath: String
)

object NoteReminderParser {
    fun computeReminderTime(task: WorkTask, defaultUntimed: String = "09:00", defaultBeforeMin: Int = 15): String? {
        val note = task.note.lowercase()
        val explicitRegex = Regex("""(?:lúc|vào|giờ)\s*(\d{1,2})[:h](\d{2})""")
        explicitRegex.find(note)?.let { m ->
            val hh = m.groupValues[1].toInt().coerceIn(0, 23)
            val mm = m.groupValues[2].toInt().coerceIn(0, 59)
            return "%02d:%02d".format(hh, mm)
        }

        val taskTimeStr = task.time ?: return defaultUntimed
        val baseTime = runCatching { LocalTime.parse(taskTimeStr, DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
            ?: return defaultUntimed

        val minRegex = Regex("""trước\s*(\d+)\s*(?:phút|p\b|min)""")
        minRegex.find(note)?.let { m ->
            return baseTime.minusMinutes(m.groupValues[1].toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        val hourRegex = Regex("""trước\s*(\d+)\s*(?:giờ|h\b|tiếng)""")
        hourRegex.find(note)?.let { m ->
            return baseTime.minusHours(m.groupValues[1].toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
        }
        return baseTime.minusMinutes(defaultBeforeMin.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}

class WorkRepository(context: Context) {
    private val prefs = context.getSharedPreferences("work_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getTasks(): List<WorkTask> {
        val json = prefs.getString("tasks", null) ?: return defaultTasks().also { saveTasks(it) }
        val type = object : TypeToken<List<WorkTask>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveTasks(tasks: List<WorkTask>) {
        prefs.edit().putString("tasks", gson.toJson(tasks)).apply()
    }

    fun getCompletedIds(date: LocalDate = LocalDate.now()): Set<String> {
        return prefs.getStringSet("done_${date}", emptySet()) ?: emptySet()
    }

    fun setCompletedIds(ids: Set<String>, date: LocalDate = LocalDate.now()) {
        prefs.edit().putStringSet("done_${date}", ids).apply()
    }

    fun getSyncRoomCode(): String = prefs.getString("sync_room_code", "") ?: ""

    fun setSyncRoomCode(code: String) {
        prefs.edit().putString("sync_room_code", code.trim()).apply()
    }

    fun toggleTaskDone(taskId: String, date: LocalDate = LocalDate.now()) {
        val key = "done_${date}"
        val set = getCompletedIds(date).toMutableSet()
        if (!set.add(taskId)) set.remove(taskId)
        prefs.edit().putStringSet(key, set).apply()
    }

    fun isTaskActiveOnDate(task: WorkTask, date: LocalDate): Boolean {
        val start = LocalDate.parse(task.startDate)
        if (date.isBefore(start)) return false
        return when (task.repeat) {
            RepeatType.NONE -> date == start
            RepeatType.DAILY -> true
            RepeatType.WEEKDAYS -> date.dayOfWeek.value in 1..5
            RepeatType.WEEKENDS -> date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            RepeatType.CUSTOM -> task.customDays.contains(date.dayOfWeek.value)
        }
    }

    fun getTasksForDate(date: LocalDate = LocalDate.now()): List<WorkTask> =
        getTasks().filter { isTaskActiveOnDate(it, date) }

    fun getWallpapers(): List<SavedWallpaper> {
        val json = prefs.getString("wallpapers", "[]")
        val type = object : TypeToken<List<SavedWallpaper>>() {}.type
        return gson.fromJson(json, type)
    }

    fun addWallpaper(wallpaper: SavedWallpaper) {
        val list = getWallpapers().toMutableList()
        list.add(0, wallpaper)
        prefs.edit().putString("wallpapers", gson.toJson(list))
            .putString("active_wallpaper_path", wallpaper.filePath)
            .apply()
    }

    fun getActiveWallpaperPath(): String? = prefs.getString("active_wallpaper_path", null)

    fun setActiveWallpaperPath(path: String) {
        prefs.edit().putString("active_wallpaper_path", path).apply()
    }

    private fun defaultTasks(): List<WorkTask> {
        val today = LocalDate.now().toString()
        return listOf(
            WorkTask("t1", "Exercise (Tập thể dục)", "30 phút mỗi tối", today, "20:59", RepeatType.DAILY, quadrant = Quadrant.Q2),
            WorkTask("t2", "Listen English", "Podcast 15 phút", today, "20:59", RepeatType.DAILY, quadrant = Quadrant.Q2),
            WorkTask("t3", "Read book", "10 trang trước khi ngủ", today, "21:30", RepeatType.DAILY, quadrant = Quadrant.Q4),
            WorkTask("t4", "Kiểm tra email", "Nhắc trước 30 phút", today, "15:00", RepeatType.WEEKDAYS, quadrant = Quadrant.Q1, highPriority = true)
        )
    }
}
