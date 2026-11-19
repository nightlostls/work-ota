package com.work.app.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.*
import com.work.app.calendar.DeviceCalendarSync
import com.work.app.data.WorkRepository
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object LockScreenRenderer {
    fun renderAndApplyLockScreen(context: Context) {
        val repo = WorkRepository(context)
        val today = LocalDate.now()
        val tasks = repo.getTasksForDate(today)
        val doneIds = repo.getCompletedIds(today)
        val events = DeviceCalendarSync.getEventsForDate(context, today)

        val width = 1080
        val height = 2400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Background image or gradient
        val bgPath = repo.getActiveWallpaperPath()
        val bgFile = bgPath?.let { File(it) }
        if (bgFile != null && bgFile.exists()) {
            val raw = BitmapFactory.decodeFile(bgFile.absolutePath)
            if (raw != null) {
                val scale = maxOf(width.toFloat() / raw.width, height.toFloat() / raw.height)
                val dw = raw.width * scale
                val dh = raw.height * scale
                val rect = RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
                canvas.drawBitmap(raw, null, rect, null)
            }
        } else {
            val bgPaint = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF0B132B.toInt(), 0xFF1C2541.toInt(), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }

        // 2. Translucent card below clock area
        val cardTop = 760f
        val itemCount = (tasks.size + events.size).coerceAtLeast(1).coerceAtMost(8)
        val cardHeight = 160f + itemCount * 84f
        val cardRect = RectF(64f, cardTop, width - 64f, cardTop + cardHeight)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 15, 20, 28) }
        canvas.drawRoundRect(cardRect, 42f, 42f, cardPaint)

        // 3. Card Header
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val dateLabel = today.format(DateTimeFormatter.ofPattern("EEEE, dd/MM", Locale("vi", "VN")))
        canvas.drawText(dateLabel, 110f, cardTop + 76f, titlePaint)

        var y = cardTop + 160f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        events.take(2).forEach { ev ->
            dotPaint.color = 0xFFFBBF24.toInt()
            canvas.drawCircle(130f, y - 12f, 14f, dotPaint)
            textPaint.color = 0xFFFDE68A.toInt()
            canvas.drawText("📅 ${ev.title.take(30)}", 170f, y, textPaint)
            y += 84f
        }

        tasks.take(6).forEach { task ->
            val isDone = doneIds.contains(task.id)
            dotPaint.color = if (isDone) 0xFF34D399.toInt() else task.quadrant.colorHex.toInt()
            canvas.drawCircle(130f, y - 12f, 16f, dotPaint)

            textPaint.color = if (isDone) Color.LTGRAY else Color.WHITE
            textPaint.isStrikeThruText = isDone
            val timePrefix = task.time?.let { "$it  " } ?: ""
            canvas.drawText("$timePrefix${task.title.take(28)}", 170f, y, textPaint)
            y += 84f
        }

        val wm = WallpaperManager.getInstance(context)
        wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
    }
}
