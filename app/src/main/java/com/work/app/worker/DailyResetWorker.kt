package com.work.app.worker

import android.content.Context
import androidx.work.*
import com.work.app.notification.PinnedTaskNotification
import com.work.app.sync.FirebaseSync
import com.work.app.wallpaper.LockScreenRenderer
import java.util.concurrent.TimeUnit

class DailyResetWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        FirebaseSync.pullFromCloud(applicationContext) { _, _ ->
            LockScreenRenderer.renderAndApplyLockScreen(applicationContext)
            PinnedTaskNotification.showOrUpdate(applicationContext)
        }
        LockScreenRenderer.renderAndApplyLockScreen(applicationContext)
        PinnedTaskNotification.showOrUpdate(applicationContext)
        return Result.success()
    }

    companion object {
        fun scheduleDaily(context: Context) {
            val req = PeriodicWorkRequestBuilder<DailyResetWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "work_daily_lockscreen",
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
