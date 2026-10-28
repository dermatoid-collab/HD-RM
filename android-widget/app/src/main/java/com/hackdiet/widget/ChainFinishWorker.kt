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

        val missingFlag = Prefs.get(ctx, Prefs.KEY_RUN_MISSING)
        // Missing: YES se il Brief ha dati mancanti, NO se la sezione contiene None, "-" se il Brief non c'è
        val missing = when (missingFlag) {
            "1" -> "YES"
            "0" -> "NO"
            else -> "-"
        }
        // Titolo: OK = tutto riuscito e nessun dato mancante; Provisional = riuscito ma mancano dati; NO = sync o Brief falliti
        val title = "HD-RM Sync: " + when {
            !syncOk || !briefOk -> "NO"
            missingFlag == "0" -> "OK"
            else -> "Provisional"
        }
        val text = "Sync: ${if (syncOk) "OK" else "NO"} - Brief: ${if (briefOk) "OK" else "NO"}" +
            " - Missing: $missing$copyNote"

        val nm = NotificationManagerCompat.from(ctx)
        val posted = nm.areNotificationsEnabled() && runCatching {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Esito sync", NotificationManager.IMPORTANCE_HIGH)
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_widget)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }.isSuccess
        if (!posted) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, "$title\n$text", Toast.LENGTH_LONG).show()
            }
        }
        return Result.success()
    }
}
