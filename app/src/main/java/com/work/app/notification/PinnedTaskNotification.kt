package com.work.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.work.app.MainActivity
import com.work.app.R
import com.work.app.data.Quadrant
import com.work.app.data.WorkRepository
import com.work.app.sync.FirebaseSync
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

        val openIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rv = RemoteViews(context.packageName, R.layout.notif_pinned_tasks)
        rv.setTextViewText(R.id.notif_header, "Work · Việc hôm nay")
        rv.setTextViewText(R.id.notif_progress, "${doneIds.size}/${tasks.size} xong")

        val rowIds = intArrayOf(R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4)
        val circleIds = intArrayOf(R.id.circle_0, R.id.circle_1, R.id.circle_2, R.id.circle_3, R.id.circle_4)
        val titleIds = intArrayOf(R.id.title_0, R.id.title_1, R.id.title_2, R.id.title_3, R.id.title_4)

        for (i in 0 until 5) {
            if (i < tasks.size) {
                val t = tasks[i]
                val isDone = doneIds.contains(t.id)
                rv.setViewVisibility(rowIds[i], View.VISIBLE)

                val circleRes = if (isDone) {
                    R.drawable.circle_done
                } else {
                    when (t.quadrant) {
                        Quadrant.Q1 -> R.drawable.circle_q1
                        Quadrant.Q2 -> R.drawable.circle_q2
                        Quadrant.Q3 -> R.drawable.circle_q3
                        Quadrant.Q4 -> R.drawable.circle_q4
                    }
                }
                rv.setInt(circleIds[i], "setBackgroundResource", circleRes)
                rv.setTextViewText(circleIds[i], if (isDone) "✓" else "")

                val timeStr = t.time?.let { "$it · " } ?: ""
                rv.setTextViewText(titleIds[i], "$timeStr${t.title}")
                rv.setTextColor(titleIds[i], if (isDone) Color.parseColor("#64748B") else Color.parseColor("#F1F5F9"))

                // Unique Action + Data URI prevents OEM PendingIntent collision across rows
                val toggleIntent = Intent(context, TaskDoneReceiver::class.java).apply {
                    action = "com.work.app.TOGGLE_TASK_${t.id}"
                    data = Uri.parse("work://task/${t.id}")
                    putExtra("task_id", t.id)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    100 + i,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                rv.setOnClickPendingIntent(circleIds[i], pi)
                rv.setOnClickPendingIntent(rowIds[i], pi)
            } else {
                rv.setViewVisibility(rowIds[i], View.GONE)
            }
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(openIntent)
            .build()

        nm.notify(NOTIF_ID, notif)
    }
}

class TaskDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("task_id") ?: intent.data?.lastPathSegment ?: return
        val appCtx = context.applicationContext
        WorkRepository(appCtx).toggleTaskDone(taskId)
        // Update notification immediately (< 5ms)
        PinnedTaskNotification.showOrUpdate(appCtx)
        // Render lockscreen & sync in background so notification never lags
        Thread {
            LockScreenRenderer.renderAndApplyLockScreen(appCtx)
            if (WorkRepository(appCtx).getSyncRoomCode().isNotBlank()) {
                FirebaseSync.pushToCloud(appCtx)
            }
        }.start()
    }
}
