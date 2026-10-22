package com.hackdiet.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_READ_ONLY = "read_only"
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val readOnly = inputData.getBoolean(KEY_READ_ONLY, false)
        return try {
            val garminKey = Prefs.get(ctx, Prefs.KEY_GARMIN_API_KEY)
            val intervalsKey = Prefs.get(ctx, Prefs.KEY_INTERVALS_API_KEY)
            val athleteId = Prefs.get(ctx, Prefs.KEY_INTERVALS_ATHLETE_ID)

            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val now = LocalDateTime.now()

            val sleepFormat = Prefs.get(ctx, Prefs.KEY_SLEEP_FORMAT, "hm")
            val token = GarminApi.getToken(garminKey)
            val debugLog = StringBuilder()

            val yesterdayData = GarminApi.getDayData(token, yesterday, debugLog)
            val todayData = GarminApi.getDayData(token, today, debugLog)

            val ts = now.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))

            if (!readOnly) {
                if (yesterdayData.kcalConsumed != null) {
                    IntervalsApi.putWellness(
                        apiKey = intervalsKey,
                        athleteId = athleteId,
                        date = yesterday,
                        kcalConsumed = yesterdayData.kcalConsumed,
                        deepSleepMin = null,
                        remSleepMin = null
                    )
                }
                if (todayData.deepSleepMin != null || todayData.remSleepMin != null) {
                    IntervalsApi.putWellness(
                        apiKey = intervalsKey,
                        athleteId = athleteId,
                        date = today,
                        kcalConsumed = null,
                        deepSleepMin = todayData.deepSleepMin,
                        remSleepMin = todayData.remSleepMin,
                        sleepFormat = sleepFormat
                    )
                }
            }

            val sentLabel = if (readOnly) "solo lettura" else {
                val sentYest = if (yesterdayData.kcalConsumed != null) "kcal→ieri" else "-"
                val sentToday = listOfNotNull(
                    if (todayData.deepSleepMin != null) "deep" else null,
                    if (todayData.remSleepMin != null) "REM" else null
                ).joinToString("+").ifEmpty { "-" }
                "$sentYest | $sentToday"
            }

            val detail = buildString {
                appendLine("── $ts ${if (readOnly) "[lettura]" else "[sync]"} ──")
                appendLine("ieri ($yesterday):")
                appendLine("  kcal = ${yesterdayData.kcalConsumed ?: "null"}")
                appendLine("  RHR  = ${yesterdayData.rhr ?: "null"}")
                appendLine("oggi ($today):")
                appendLine("  deep = ${todayData.deepSleepMin?.let { "${it}'" } ?: "null"}")
                appendLine("  REM  = ${todayData.remSleepMin?.let { "${it}'" } ?: "null"}")
                appendLine("  tot  = ${todayData.totalSleepMin?.let { "${it}'" } ?: "null"}")
                appendLine("  → $sentLabel (formato: $sleepFormat)")
                appendLine("── HTTP debug ───────")
                append(debugLog.toString().trimEnd())
            }

            val prefix = if (readOnly) "🔍" else "✓"
            val lines = mutableListOf<String>()
            lines.add("$prefix $ts")
            yesterdayData.kcalConsumed?.let { lines.add("${it} kcal") }
            val sleepLine = listOfNotNull(
                todayData.deepSleepMin?.let { "D ${it}'" },
                todayData.remSleepMin?.let { "R ${it}'" }
            ).joinToString(" • ")
            if (sleepLine.isNotEmpty()) lines.add(sleepLine)
            val msg = lines.joinToString("\n")

            val finalMsg = msg.trim()
            Prefs.set(ctx, Prefs.KEY_LAST_RESULT, finalMsg)
            Prefs.set(ctx, Prefs.KEY_LAST_SYNC, ts)
            Prefs.appendLog(ctx, detail)
            SyncWidget.updateAll(ctx)
            Result.success()
        } catch (e: Exception) {
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
            val errMsg = "✗ ${e.message?.take(80)}"
            Prefs.set(ctx, Prefs.KEY_LAST_RESULT, errMsg)
            Prefs.appendLog(ctx, "── $ts ──────────────\n  ERRORE: ${e.message?.take(200)}")
            SyncWidget.updateAll(ctx)
            Result.failure()
        }
    }
}
