package com.hackdiet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SyncWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(ctx, mgr, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_SYNC) {
            if (!Prefs.isConfigured(ctx)) {
                ctx.startActivity(
                    Intent(ctx, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            Prefs.set(ctx, Prefs.KEY_LAST_RESULT, "⏳ Sync in corso…")
            updateAll(ctx)
            SyncWidget2x1.updateAll(ctx)
            Prefs.set(ctx, Prefs.KEY_RUN_SYNC_OK, "")
            Prefs.set(ctx, Prefs.KEY_RUN_BRIEF_OK, "")
            val sync = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(SyncWorker.KEY_CHAIN to true)).build()
            val brief = OneTimeWorkRequestBuilder<DailyBriefWorker>()
                .setInputData(workDataOf(DailyBriefWorker.KEY_CHAIN to true)).build()
            val finish = OneTimeWorkRequestBuilder<ChainFinishWorker>().build()
            WorkManager.getInstance(ctx).beginWith(sync).then(brief).then(finish).enqueue()
        }
    }

    companion object {
        const val ACTION_SYNC = "com.hackdiet.widget.SYNC_ACTION"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SyncWidget::class.java))
            ids.forEach { updateWidget(ctx, mgr, it) }
        }

        private fun updateWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_layout)

            val status = Prefs.get(ctx, Prefs.KEY_LAST_RESULT, "↑ Sync")
            val lastSync = Prefs.get(ctx, Prefs.KEY_LAST_SYNC, "")
            views.setTextViewText(R.id.tv_status, status)
            views.setTextViewText(R.id.tv_last, "")

            // Verde se sync di oggi riuscita; bianco se in corso o mai fatta; rosso se errore o sync di un giorno precedente
            val today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM"))
            val isToday = lastSync.startsWith(today)
            val textColor = when {
                status.startsWith("⏳") -> Color.WHITE
                status.startsWith("✗") -> Color.rgb(255, 80, 80)
                isToday -> Color.parseColor("#4ade80")
                lastSync.isBlank() -> Color.WHITE
                else -> Color.rgb(255, 80, 80)
            }
            views.setTextColor(R.id.tv_status, textColor)

            // Barra in alto: stesse regole del 2x1 (grigio in corso, verde sync di oggi, rosso altrimenti; rosso anche se errore)
            val barColor = when {
                status.startsWith("⏳") -> Color.parseColor("#555555")
                status.startsWith("✗") -> Color.parseColor("#ff5555")
                isToday -> Color.parseColor("#4ade80")
                else -> Color.parseColor("#ff5555")
            }
            views.setInt(R.id.v1_bar, "setBackgroundColor", barColor)

            views.setInt(R.id.widget_root, "setBackgroundColor", Prefs.widgetBgColor(ctx))

            val intent = Intent(ctx, SyncWidget::class.java).apply { action = ACTION_SYNC }
            val pi = PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tv_status, pi)
            views.setOnClickPendingIntent(R.id.tv_last, pi)

            mgr.updateAppWidget(id, views)
        }
    }
}
