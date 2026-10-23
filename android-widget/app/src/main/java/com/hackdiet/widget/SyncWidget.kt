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
            WorkManager.getInstance(ctx)
                .enqueue(OneTimeWorkRequestBuilder<SyncWorker>().build())
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

            // Bianco se sync di oggi, rosso se precedente o mai fatto
            val today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM"))
            val isToday = lastSync.startsWith(today)
            val textColor = if (isToday || lastSync.isBlank()) Color.WHITE else Color.rgb(255, 80, 80)
            views.setTextColor(R.id.tv_status, textColor)

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
