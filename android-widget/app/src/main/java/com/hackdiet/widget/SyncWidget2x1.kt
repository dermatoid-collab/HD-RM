package com.hackdiet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SyncWidget2x1 : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(ctx, mgr, it) }
    }

    companion object {
        private val COLOR_GREEN = Color.parseColor("#4ade80")
        private val COLOR_RED   = Color.parseColor("#ff5555")
        private val COLOR_WHITE = Color.WHITE
        private val COLOR_GRAY  = Color.parseColor("#555555")

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SyncWidget2x1::class.java))
            ids.forEach { updateWidget(ctx, mgr, it) }
        }

        private fun updateWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val rv = try { buildViews(ctx) } catch (_: Exception) {
                RemoteViews(ctx.packageName, R.layout.widget_layout_2x1)
            }
            mgr.updateAppWidget(id, rv)
        }

        private fun buildViews(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_layout_2x1)

            val lastResult = Prefs.get(ctx, Prefs.KEY_LAST_RESULT, "")
            val lastSync   = Prefs.get(ctx, Prefs.KEY_LAST_SYNC, "")     // "dd/MM HH:mm"
            val kcal       = Prefs.get(ctx, Prefs.KEY_LAST_KCAL, "")
            val sleep      = Prefs.get(ctx, Prefs.KEY_LAST_SLEEP, "")

            val isInProgress = lastResult.startsWith("⏳")

            // Determina lo stato per i colori
            val todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM"))
            val syncDate = lastSync.take(5) // "dd/MM"
            val isToday  = syncDate == todayStr && syncDate.isNotEmpty()

            val (stripeColor, textColor) = when {
                isInProgress -> COLOR_GRAY to COLOR_WHITE
                isToday      -> COLOR_GREEN to COLOR_GREEN
                else         -> COLOR_RED   to COLOR_RED
            }

            // Sfondo widget
            rv.setInt(R.id.widget_root, "setBackgroundColor", Prefs.widgetBgColor(ctx))

            // Striscia
            rv.setInt(R.id.v_stripe, "setBackgroundColor", stripeColor)

            // Icona stato
            when {
                isInProgress -> {
                    rv.setViewVisibility(R.id.iv_status_icon, View.VISIBLE)
                    rv.setViewVisibility(R.id.tv_status_check, View.GONE)
                    rv.setImageViewResource(R.id.iv_status_icon, R.drawable.ic_hourglass)
                }
                isToday -> {
                    rv.setViewVisibility(R.id.iv_status_icon, View.GONE)
                    rv.setViewVisibility(R.id.tv_status_check, View.VISIBLE)
                    rv.setTextColor(R.id.tv_status_check, COLOR_GREEN)
                }
                else -> {
                    rv.setViewVisibility(R.id.iv_status_icon, View.VISIBLE)
                    rv.setViewVisibility(R.id.tv_status_check, View.GONE)
                    rv.setImageViewResource(R.id.iv_status_icon, R.drawable.ic_alarm)
                }
            }

            // Upload icon opacity per in-progress (setImageAlpha: 0-255)
            rv.setInt(R.id.iv_upload, "setImageAlpha", if (isInProgress) 102 else 255)

            // Dati testo
            val datetimeText = if (lastSync.isNotEmpty()) lastSync else "--/-- · --:--"
            val kcalText     = if (kcal.isNotEmpty()) "$kcal kcal" else "— kcal"
            val sleepText    = if (sleep.isNotEmpty()) sleep else "D—·R—"

            rv.setTextViewText(R.id.tv2_datetime, datetimeText)
            rv.setTextViewText(R.id.tv2_kcal, kcalText)
            rv.setTextViewText(R.id.tv2_sleep, sleepText)

            rv.setTextColor(R.id.tv2_datetime, textColor)
            rv.setTextColor(R.id.tv2_kcal, textColor)
            rv.setTextColor(R.id.tv2_sleep, textColor)


            val intent = Intent(ctx, SyncWidget::class.java).apply { action = SyncWidget.ACTION_SYNC }
            val pi = PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_root, pi)

            return rv
        }
    }
}
