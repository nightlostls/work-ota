package com.work.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.work.app.MainActivity
import com.work.app.data.WorkRepository
import com.work.app.wallpaper.LockScreenRenderer

object PinnedTaskNotification {
    private const val CHANNEL_ID = "work_pinned_tasks"
    private const val NOTIF_ID = 1001

    fun showOrUpdate(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Việc hôm nay (Ghim)", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        val repo = WorkRepository(context)
        val tasks = repo.getTasksForDate()
        val doneIds = repo.getCompletedIds()
        val pending = tasks.filter { !doneIds.contains(it.id) }

        val openIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Work · ${doneIds.size}/${tasks.size} việc đã xong")
            .setContentText(pending.firstOrNull()?.title ?: "Tuyệt vời! Bạn đã xong hết việc hôm nay")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)

        val inbox = NotificationCompat.InboxStyle()
        tasks.take(5).forEach { t ->
            val mark = if (doneIds.contains(t.id)) "✅" else "⭕"
            inbox.addLine("$mark ${t.time ?: "Cả ngày"} · ${t.title}")
        }
        builder.setStyle(inbox)

        // Add quick-complete action for next pending task directly on notification & lock screen
        pending.firstOrNull()?.let { nextTask ->
            val doneIntent = Intent(context, TaskDoneReceiver::class.java).putExtra("task_id", nextTask.id)
            val pi = PendingIntent.getBroadcast(
                context, nextTask.id.hashCode(), doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.checkbox_on_background, "✓ Xong: ${nextTask.title.take(14)}", pi)
        }

        nm.notify(NOTIF_ID, builder.build())
    }
}

class TaskDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("task_id") ?: return
        WorkRepository(context).toggleTaskDone(taskId)
        PinnedTaskNotification.showOrUpdate(context)
        LockScreenRenderer.renderAndApplyLockScreen(context)
    }
}
