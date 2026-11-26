package com.varun.filemanager.work

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.work.*
import com.varun.filemanager.media.NightWindow
import com.varun.filemanager.media.ScanEngine
import com.varun.filemanager.ui.AppSettings
import java.util.concurrent.TimeUnit

/** Nightly scan: runs while charging and idle, saves results and a report. Stopped scans resume next run. */
class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val hasAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        if (!hasAccess) return Result.success() // nothing to do until the user grants access
        val settings = AppSettings(applicationContext as android.app.Application)
        ScanEngine(applicationContext).run("background", settings.thresholds, detectFaces = settings.faceDetection, isCancelled = { isStopped })
        // If the system stopped us part-way, the saved rows let the retry carry on.
        return if (isStopped) Result.retry() else Result.success()
    }
}

object ScanScheduler {
    private const val NAME = "nightly-scan"
    const val HOUR = 2

    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(NAME); return }
        val req = PeriodicWorkRequestBuilder<ScanWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(NightWindow.delayUntil(HOUR, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
