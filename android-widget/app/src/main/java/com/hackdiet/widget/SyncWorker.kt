package com.hackdiet.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            val garminKey = Prefs.get(ctx, Prefs.KEY_GARMIN_API_KEY)
            val intervalsKey = Prefs.get(ctx, Prefs.KEY_INTERVALS_API_KEY)
            val athleteId = Prefs.get(ctx, Prefs.KEY_INTERVALS_ATHLETE_ID)

            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val now = LocalDateTime.now()

            // Token Garmin
            val token = GarminApi.getToken(garminKey)

            // kcal consumate di ieri
            val yesterdayData = GarminApi.getDayData(token, yesterday)
            // deep + REM di oggi (sonno della notte appena trascorsa)
            val todayData = GarminApi.getDayData(token, today)

            // PUT ieri: solo kcalConsumed
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

            // PUT oggi: deep + REM
            if (todayData.deepSleepMin != null || todayData.remSleepMin != null) {
                IntervalsApi.putWellness(
                    apiKey = intervalsKey,
                    athleteId = athleteId,
                    date = today,
                    kcalConsumed = null,
                    deepSleepMin = todayData.deepSleepMin,
                    remSleepMin = todayData.remSleepMin
                )
            }

            val ts = now.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))

            val detail = buildString {
                appendLine("── $ts ──────────────")
                appendLine("ieri ($yesterday):")
                appendLine("  kcal = ${yesterdayData.kcalConsumed ?: "null"}")
                appendLine("  RHR  = ${yesterdayData.rhr ?: "null"}")
                appendLine("oggi ($today):")
                appendLine("  deep = ${todayData.deepSleepMin?.let { "${it}'" } ?: "null"}")
                appendLine("  REM  = ${todayData.remSleepMin?.let { "${it}'" } ?: "null"}")
                appendLine("  tot  = ${todayData.totalSleepMin?.let { "${it}'" } ?: "null"}")
                val sentYest = if (yesterdayData.kcalConsumed != null) "kcal→ieri" else "-"
                val sentToday = listOfNotNull(
                    if (todayData.deepSleepMin != null) "deep" else null,
                    if (todayData.remSleepMin != null) "REM" else null
                ).joinToString("+").ifEmpty { "-" }
                append("  → inviato: $sentYest | $sentToday")
            }

            val msg = buildString {
                append("✓ ")
                yesterdayData.kcalConsumed?.let { append("${it}kcal ") }
                todayData.deepSleepMin?.let { append("D${it}' ") }
                todayData.remSleepMin?.let { append("R${it}'") }
                if (isEmpty()) append("Nessun dato")
            }

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
