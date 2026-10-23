package com.hackdiet.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
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

            // Upload icon opacity per in-progress
            rv.setFloat(R.id.iv_upload, "setAlpha", if (isInProgress) 0.4f else 1.0f)

            // Dati testo
            val datetimeText = if (lastSync.isNotEmpty()) lastSync else "--/-- · --:--"
            val kcalText     = if (kcal.isNotEmpty()) "$kcal kcal" else "— kcal"
            val sleepText    = if (sleep.isNotEmpty()) sleep else "D — · R —"

            rv.setTextViewText(R.id.tv2_datetime, datetimeText)
            rv.setTextViewText(R.id.tv2_kcal, kcalText)
            rv.setTextViewText(R.id.tv2_sleep, sleepText)

            rv.setTextColor(R.id.tv2_datetime, textColor)
            rv.setTextColor(R.id.tv2_kcal, textColor)
            rv.setTextColor(R.id.tv2_sleep, textColor)

            // Applica alpha 0.42 alla datetime (RemoteViews non supporta alpha su TextView direttamente,
            // usiamo un colore con alpha calcolato)
            val alpha42 = (textColor and 0x00FFFFFF) or (0x6B shl 24) // 0x6B ≈ 107 ≈ 0.42 * 255
            rv.setTextColor(R.id.tv2_datetime, alpha42)

            mgr.updateAppWidget(id, rv)
        }
    }
}
