package com.work.app.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.*
import com.work.app.calendar.DeviceCalendarSync
import com.work.app.data.RepeatType
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
        val events = if (repo.isSyncCalendarEnabled()) DeviceCalendarSync.getEventsForDate(context, today) else emptyList()

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

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(188, 15, 20, 28) }

        // 2. Top Weekly Calendar Strip Card (T2..CN)
        val weekTop = 730f
        val weekHeight = 250f
        canvas.drawRoundRect(RectF(56f, weekTop, width - 56f, weekTop + weekHeight), 40f, 40f, cardPaint)

        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val dayLabels = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
        val colW = (width - 140f) / 7f

        val dayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 203, 213, 225)
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        val dayNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        for (i in 0..6) {
            val d = monday.plusDays(i.toLong())
            val cx = 70f + colW * i + colW / 2f
            canvas.drawText(dayLabels[i], cx, weekTop + 54f, dayLabelPaint)

            if (d == today) {
                pillPaint.color = Color.WHITE
                canvas.drawCircle(cx, weekTop + 106f, 30f, pillPaint)
                dayNumPaint.color = 0xFF0F172A.toInt()
            } else {
                dayNumPaint.color = Color.WHITE
            }
            canvas.drawText("${d.dayOfMonth}", cx, weekTop + 118f, dayNumPaint)

            val dayTasks = repo.getTasksForDate(d)
            if (dayTasks.isNotEmpty()) {
                val pillRect = RectF(cx - colW * 0.4f, weekTop + 158f, cx + colW * 0.4f, weekTop + 196f)
                pillPaint.color = 0x5538BDF8.toInt()
                canvas.drawRoundRect(pillRect, 10f, 10f, pillPaint)
                pillTextPaint.color = 0xFFE0F2FE.toInt()
                canvas.drawText("${dayTasks.size} việc", cx, weekTop + 184f, pillTextPaint)
            }
        }

        // 3. Today's Tasks Card (like Simulator)
        val cardTop = weekTop + weekHeight + 28f
        val totalItems = tasks.size + events.size
        val visibleRows = (events.take(2).size + tasks.take(6).size).coerceAtLeast(1)
        val cardHeight = 160f + visibleRows * 84f
        canvas.drawRoundRect(RectF(56f, cardTop, width - 56f, cardTop + cardHeight), 40f, 40f, cardPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val dateLabel = today.format(DateTimeFormatter.ofPattern("EEEE, 'ngày' dd 'tháng' MM", Locale("vi", "VN")))
            .replaceFirstChar { it.uppercase() }
        canvas.drawText(dateLabel, 96f, cardTop + 74f, titlePaint)

        // White count badge circle on top right
        pillPaint.color = Color.WHITE
        canvas.drawCircle(width - 116f, cardTop + 60f, 30f, pillPaint)
        dayNumPaint.color = 0xFF0F172A.toInt()
        dayNumPaint.textSize = 30f
        canvas.drawText("$totalItems", width - 116f, cardTop + 71f, dayNumPaint)

        var y = cardTop + 156f
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 203, 213, 225)
            textSize = 30f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f }
        val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        events.take(2).forEach { ev ->
            canvas.drawText("Lịch", 96f, y, timePaint)
            circleFillPaint.color = 0xFFFBBF24.toInt()
            canvas.drawCircle(240f, y - 11f, 20f, circleFillPaint)
            canvas.drawText("★", 240f, y - 3f, symbolPaint)
            textPaint.color = 0xFFFDE68A.toInt()
            textPaint.isStrikeThruText = false
            canvas.drawText(ev.title.take(26), 282f, y, textPaint)
            y += 84f
        }

        tasks.take(6).forEach { task ->
            val isDone = doneIds.contains(task.id)
            val timePrefix = task.time ?: "Cả ngày"
            canvas.drawText(timePrefix, 96f, y, timePaint)

            if (isDone) {
                circleFillPaint.color = 0xFF34D399.toInt()
                canvas.drawCircle(240f, y - 11f, 20f, circleFillPaint)
                canvas.drawText("✓", 240f, y - 3f, symbolPaint)
            } else {
                circleStrokePaint.color = task.quadrant.colorHex.toInt()
                canvas.drawCircle(240f, y - 11f, 18f, circleStrokePaint)
                if (task.repeat != RepeatType.NONE) {
                    val repeatSym = Paint(symbolPaint).apply { color = task.quadrant.colorHex.toInt(); textSize = 22f }
                    canvas.drawText("↻", 240f, y - 4f, repeatSym)
                }
            }

            textPaint.color = if (isDone) Color.argb(170, 148, 163, 184) else Color.WHITE
            textPaint.isStrikeThruText = isDone
            canvas.drawText(task.title.take(26), 282f, y, textPaint)
            y += 84f
        }

        val wm = WallpaperManager.getInstance(context)
        wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
    }
}
