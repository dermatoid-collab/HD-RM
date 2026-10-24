package com.hackdiet.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ChainFinishWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val CHANNEL_ID = "sync_result"
        private const val NOTIFICATION_ID = 4101
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val syncOk = Prefs.get(ctx, Prefs.KEY_RUN_SYNC_OK) == "1"
        val briefOk = Prefs.get(ctx, Prefs.KEY_RUN_BRIEF_OK) == "1"

        var copyNote = ""
        if (briefOk) {
            val raw = Prefs.get(ctx, Prefs.KEY_DAILY_BRIEF_LAST)
            val content = raw.lines().drop(1).joinToString("\n").trimStart()
            val copied = runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("HS Daily Brief", content))
            }.isSuccess
            if (!copied) copyNote = " (copia negli appunti non riuscita)"
        }

        val line1 = "Sync Garmin -> Intervals: ${if (syncOk) "OK" else "NO"}"
        val line2 = "HS Daily Brief: ${if (briefOk) "OK" else "NO"}$copyNote"

        val nm = NotificationManagerCompat.from(ctx)
        val posted = nm.areNotificationsEnabled() && runCatching {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Esito sync", NotificationManager.IMPORTANCE_HIGH)
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_widget)
                .setContentTitle(line1)
                .setContentText(line2)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }.isSuccess
        if (!posted) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, "$line1\n$line2", Toast.LENGTH_LONG).show()
            }
        }
        return Result.success()
    }
}
