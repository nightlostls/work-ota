package com.work.app.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.work.app.data.WorkRepository
import com.work.app.notification.PinnedTaskNotification
import com.work.app.sync.FirebaseSync
import com.work.app.wallpaper.LockScreenRenderer
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class DailyResetWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val appCtx = applicationContext
        LockScreenRenderer.renderAndApplyLockScreen(appCtx)
        PinnedTaskNotification.showOrUpdate(appCtx)
        if (WorkRepository(appCtx).getSyncRoomCode().isNotBlank()) {
            FirebaseSync.pullFromCloud(appCtx) { ok, _ ->
                if (ok) {
                    LockScreenRenderer.renderAndApplyLockScreen(appCtx)
                    PinnedTaskNotification.showOrUpdate(appCtx)
                }
            }
        }
        MidnightAlarmScheduler.scheduleNextMidnight(appCtx)
        return Result.success()
    }

    companion object {
        fun scheduleDaily(context: Context) {
            val req = PeriodicWorkRequestBuilder<DailyResetWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "work_daily_lockscreen",
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
            MidnightAlarmScheduler.scheduleNextMidnight(context)
        }
    }
}

object MidnightAlarmScheduler {
    fun scheduleNextMidnight(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextMidnight = LocalDate.now().plusDays(1).atTime(0, 1)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val intent = Intent(context, MidnightUpdateReceiver::class.java).apply {
            action = "com.work.app.MIDNIGHT_UPDATE"
        }
        val pi = PendingIntent.getBroadcast(
            context,
            9001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMidnight, pi)
    }
}

class MidnightUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            try {
                LockScreenRenderer.renderAndApplyLockScreen(appCtx)
                PinnedTaskNotification.showOrUpdate(appCtx)
                MidnightAlarmScheduler.scheduleNextMidnight(appCtx)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
