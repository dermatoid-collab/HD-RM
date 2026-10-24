package com.hackdiet.widget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ChainFinishWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

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

        val msg = "Sync Garmin -> Intervals: ${if (syncOk) "OK" else "NO"}\n" +
            "HS Daily Brief: ${if (briefOk) "OK" else "NO"}$copyNote"
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
        return Result.success()
    }
}
