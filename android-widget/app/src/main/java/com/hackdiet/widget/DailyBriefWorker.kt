package com.hackdiet.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DailyBriefWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            val apiKey   = Prefs.get(ctx, Prefs.KEY_INTERVALS_API_KEY)
            val athleteId = Prefs.get(ctx, Prefs.KEY_INTERVALS_ATHLETE_ID)
            val today     = LocalDate.now()
            val yesterday = today.minusDays(1)

            // ── 1. Attività di ieri (possono essere più di una) ────────────────
            val activities = IntervalsReadApi.getActivitiesForDate(apiKey, athleteId, yesterday)

            // ── 2. Wellness: ieri (peso, kcal) + oggi (sonno, HRV, ecc.) ───────
            val wellnessYest  = IntervalsReadApi.getWellness(apiKey, athleteId, yesterday)
            val wellnessToday = IntervalsReadApi.getWellness(apiKey, athleteId, today)

            // ── 3. Note calendario ieri + oggi ─────────────────────────────────
            val notesYest  = IntervalsReadApi.getCalendarNotes(apiKey, athleteId, yesterday)
            val notesToday = IntervalsReadApi.getCalendarNotes(apiKey, athleteId, today)

            // ── 4. Costruzione report ──────────────────────────────────────────
            val sb = StringBuilder()
            sb.appendLine("Perform today's HS Daily Brief based on the following data.")
            sb.appendLine()
            sb.appendLine("Today is $today.")
            sb.appendLine()

            if (activities.length() == 0) {
                sb.appendLine("No activity found for $yesterday.")
            } else {
                for (i in 0 until activities.length()) {
                    val act = activities.getJSONObject(i)
                    val actId = act.optString("id", "")
                    val detail = if (actId.isNotBlank())
                        runCatching { IntervalsReadApi.getActivityDetail(apiKey, actId) }.getOrNull()
                    else null

                    sb.appendLine(formatActivity(act, detail, yesterday, i + 1, activities.length()))
                }
            }

            // ── Wellness ────────────────────────────────────────────────────────
            sb.appendLine("SLEEP and REST (today's night, today's morning):")
            sb.appendLine("Sleep: ${wellnessToday.fmtSleep("sleep")}")
            sb.appendLine("Deep sleep: ${wellnessToday.fmtSleep("deepSleep")}")
            sb.appendLine("REM sleep: ${wellnessToday.fmtSleep("remSleep")}")
            sb.appendLine("Resting HR: ${wellnessToday.fmtInt("restingHR", "bpm")}")
            sb.appendLine("Sleep pattern: ${wellnessToday.fmtSleepPattern()}")
            sb.appendLine("Morning HRV (HRV-RM): ${wellnessToday.fmtDec("hrv")}")
            sb.appendLine()
            sb.appendLine("WELLNESS (yesterday):")
            sb.appendLine("Weight: ${wellnessYest.fmtDec("weight", "kg")}")
            sb.appendLine("KCal consumed: ${wellnessYest.fmtInt("kcalConsumed", "kcal")}")
            sb.appendLine()
            sb.appendLine("Notes (yesterday):")
            sb.appendLine(if (notesYest.isEmpty()) "-" else notesYest.joinToString("\n\n"))
            sb.appendLine()
            sb.appendLine("Notes (today):")
            sb.appendLine(if (notesToday.isEmpty()) "-" else notesToday.joinToString("\n\n"))

            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            val report = "── Daily Brief $ts ──────────────\n${sb.toString().trimEnd()}"

            Prefs.set(ctx, Prefs.KEY_DAILY_BRIEF_LAST, report)
            Result.success()
        } catch (e: Exception) {
            Prefs.set(ctx, Prefs.KEY_DAILY_BRIEF_LAST,
                "── Errore generazione Brief ──\n${e.message?.take(500)}")
            Result.failure()
        }
    }

    // ── Formattazione singola attività ─────────────────────────────────────────

    private fun formatActivity(
        act: JSONObject,
        detail: JSONObject?,
        date: LocalDate,
        idx: Int,
        total: Int
    ): String {
        val sb = StringBuilder()
        val header = if (total > 1) "Yesterday Ride Summary #$idx:" else "Yesterday Ride Summary:"
        sb.appendLine(header)
        sb.appendLine()

        val typeRaw = act.optString("type", "-")
        val isIndoor = act.optBoolean("indoor", false) ||
                typeRaw.contains("Virtual", ignoreCase = true) ||
                typeRaw.contains("Indoor", ignoreCase = true)
        val typeLabel = if (isIndoor) "Indoor" else "Outdoor"

        sb.appendLine("Date: $date")
        sb.appendLine("Type: $typeLabel")
        sb.appendLine("Elapsed time: ${act.fmtSecs("elapsed_time")}")
        sb.appendLine("NP: ${act.fmtInt("icu_weighted_average_watts", "W")}")
        sb.appendLine("TSS: ${act.fmtInt("icu_training_load")}")
        sb.appendLine("Work: ${act.fmtInt("total_kilojoules", "kJ")}")

        val src = detail ?: act
        val rpe  = src.optIntOrNull("rpe")
        val feel = src.optIntOrNull("feel")
        sb.appendLine("RPE (1 = Rest / 10 = Max effort): ${rpe ?: "-"}")
        sb.appendLine("Feel (1 = Strong / 5 = Weak): ${feel?.let { feelLabel(it) } ?: "-"}")
        sb.appendLine()

        // TIME / DISTANCE
        sb.appendLine("TIME / DISTANCE")
        sb.appendLine("Start time: ${act.optString("start_date_local", "-").substringAfter("T").take(8).ifBlank { "-" }}")
        sb.appendLine("Elapsed time: ${act.fmtSecs("elapsed_time")}")
        sb.appendLine("Moving time: ${act.fmtSecs("moving_time")}")
        val coasting = (act.optInt("elapsed_time", 0) - act.optInt("moving_time", 0))
            .let { if (it > 0) secsToHms(it) else "-" }
        sb.appendLine("Coasting time: $coasting")
        sb.appendLine("Distance: ${act.fmtKm("distance")}")
        if (!isIndoor) sb.appendLine("Elevation gain: ${act.fmtInt("total_elevation_gain", "m")}")
        sb.appendLine()

        // POWER
        sb.appendLine("POWER")
        sb.appendLine("Avg power: ${act.fmtInt("average_watts", "W")}")
        sb.appendLine("NP: ${act.fmtInt("icu_weighted_average_watts", "W")}")
        sb.appendLine("FTP (set): ${act.fmtInt("icu_ftp", "W")}")
        sb.appendLine("IF: ${act.fmtDecRaw("icu_intensity")}")
        sb.appendLine("VI: ${act.fmtDecRaw("icu_variability_index")}")
        sb.appendLine("Max power: ${act.fmtInt("max_watts", "W")}")
        sb.appendLine("Work: ${act.fmtInt("total_kilojoules", "kJ")}")
        sb.appendLine("Work above FTP: ${src.fmtInt("icu_joules_above_ftp", "J")}")
        sb.appendLine("Calories: ${act.fmtInt("calories", "kcal")}")
        sb.appendLine("CHO used: ${src.fmtInt("icu_carbs", "g")}")
        sb.appendLine("VO2max (latest): ${act.fmtInt("vo2max")}")
        sb.appendLine()

        // LOAD
        sb.appendLine("LOAD")
        sb.appendLine("TSS: ${act.fmtInt("icu_training_load")}")
        sb.appendLine("TRIMP: ${src.fmtDecRaw("trimp")}")
        sb.appendLine("EF: ${src.fmtDecRaw("icu_efficiency_factor")}")
        sb.appendLine("Pw:HR (full ride decoupling): ${src.fmtPercent("icu_hrr")}")
        sb.appendLine()

        // POWER ZONES
        sb.appendLine("POWER ZONES")
        val pz = src.optJSONArray("power_zones") ?: detail?.optJSONArray("power_zones")
        if (pz != null) {
            for (z in 0 until pz.length()) {
                val zone = pz.getJSONObject(z)
                val label = zone.optString("name", "Z${z + 1}")
                val secs  = zone.optInt("secs", 0)
                sb.appendLine("$label: ${secsToHms(secs)}")
            }
        } else {
            listOf("Z1","Z2","Z3","Z4","Z5","Z6+","Sweet Spot").forEach { sb.appendLine("$it: -") }
        }

        // Intervals summary
        val intervals = src.optJSONArray("icu_intervals")
        sb.appendLine("Intervals summary:")
        if (intervals != null && intervals.length() > 0) {
            val mainIntervals = (0 until intervals.length())
                .map { intervals.getJSONObject(it) }
                .filter { it.optBoolean("indoor", false).not() || true }
                .filter { it.optInt("moving_time", 0) >= 60 && !it.optBoolean("rest", false) }
            if (mainIntervals.isEmpty()) {
                sb.appendLine("  -")
            } else {
                mainIntervals.forEach { iv ->
                    val mins = iv.optInt("moving_time", 0) / 60
                    val watts = iv.optInt("average_watts", 0)
                    val hr    = iv.optInt("average_heartrate", 0)
                    val decoup = iv.optDouble("icu_hrr", Double.NaN)
                    val cad   = iv.optDouble("average_cadence", Double.NaN)
                    val decoupStr = if (!decoup.isNaN()) "Pw:HR ${String.format("%.2f", decoup)}%" else ""
                    val cadStr    = if (!cad.isNaN()) "avg Cadence ${cad.toInt()} rpm" else ""
                    sb.appendLine("  * 1 × $mins min @ $watts W, avg HR $hr bpm, $decoupStr, $cadStr".trimEnd().trimEnd(','))
                }
            }
        } else {
            sb.appendLine("  -")
        }
        sb.appendLine()

        // HR
        sb.appendLine("HR")
        sb.appendLine("Avg HR: ${act.fmtInt("average_heartrate", "bpm")}")
        sb.appendLine("Max HR: ${act.fmtInt("max_heartrate", "bpm")}")
        sb.appendLine("LTHR: ${act.fmtInt("icu_lthr", "bpm")}")
        sb.appendLine()

        // HR ZONES
        sb.appendLine("HR ZONES")
        val hrz = src.optJSONArray("hr_zones") ?: detail?.optJSONArray("hr_zones")
        if (hrz != null) {
            for (z in 0 until hrz.length()) {
                val zone = hrz.getJSONObject(z)
                val label = zone.optString("name", "Z${z + 1}")
                val secs  = zone.optInt("secs", 0)
                sb.appendLine("$label: ${secsToHms(secs)}")
            }
        } else {
            listOf("Z1","Z2","Z3","Z4","Z5+").forEach { sb.appendLine("$it: -") }
        }
        sb.appendLine()

        // CADENCE
        sb.appendLine("CADENCE")
        sb.appendLine("Avg cadence: ${act.fmtDecRaw("average_cadence")} rpm")
        sb.appendLine()

        // ENVIRONMENT
        sb.appendLine("ENVIRONMENT")
        sb.appendLine("Avg temp: ${src.fmtDecRaw("average_temp")} °C")
        sb.appendLine("Min temp: ${src.fmtInt("min_temp", "°C")}")
        sb.appendLine("Max temp: ${src.fmtInt("max_temp", "°C")}")
        sb.appendLine()

        // CORE TEMPERATURE
        sb.appendLine("CORE TEMPERATURE")
        sb.appendLine("Avg core temp: ${src.fmtDecRaw("avg_core_temp")}")
        sb.appendLine("Max core temp: ${src.fmtDecRaw("max_core_temp")}")
        sb.appendLine("Time > 38.3 °C: ${src.fmtInt("mins_above_38_3", "min")}")
        sb.appendLine()

        // FITNESS
        sb.appendLine("FITNESS")
        sb.appendLine("CTL: ${act.fmtDecRaw("icu_ctl")}")
        sb.appendLine("ATL: ${act.fmtDecRaw("icu_atl")}")
        sb.appendLine("TSB: ${act.fmtDecRaw("icu_form")}")
        sb.appendLine()

        return sb.toString()
    }

    // ── Helpers estensioni JSONObject ──────────────────────────────────────────

    private fun JSONObject?.fmtInt(key: String, unit: String = ""): String {
        if (this == null) return "-"
        val v = optIntOrNull(key) ?: return "-"
        return if (unit.isNotEmpty()) "$v $unit" else "$v"
    }

    private fun JSONObject?.fmtDecRaw(key: String): String {
        if (this == null) return "-"
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN()) "-" else String.format("%.2f", v)
    }

    private fun JSONObject?.fmtDec(key: String, unit: String = ""): String {
        if (this == null) return "-"
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN()) "-"
        else if (unit.isNotEmpty()) "${String.format("%.1f", v)} $unit"
        else String.format("%.1f", v)
    }

    private fun JSONObject?.fmtPercent(key: String): String {
        if (this == null) return "-"
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN()) "-" else "${String.format("%.2f", v)}%"
    }

    private fun JSONObject?.fmtSecs(key: String): String {
        if (this == null) return "-"
        val s = optIntOrNull(key) ?: return "-"
        return secsToHms(s)
    }

    private fun JSONObject?.fmtKm(key: String): String {
        if (this == null) return "-"
        val m = optDouble(key, Double.NaN)
        return if (m.isNaN()) "-" else "${String.format("%.2f", m / 1000)} km"
    }

    private fun JSONObject?.fmtSleep(key: String): String {
        if (this == null) return "-"
        val v = optIntOrNull(key) ?: return "-"
        return secsToHms(v * 60)
    }

    private fun JSONObject?.fmtSleepPattern(): String {
        if (this == null) return "-"
        return when (optIntOrNull("sleepQuality")) {
            1    -> "Continuous"
            2, 3 -> "Interrupted"
            else -> optString("sleepPattern", "-").ifBlank { "-" }
        }
    }


    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getInt(key) }.getOrNull()
    }

    private fun secsToHms(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun feelLabel(feel: Int) = when (feel) {
        1 -> "1 (Very Good)"
        2 -> "2 (Good)"
        3 -> "3 (Ok)"
        4 -> "4 (Bad)"
        5 -> "5 (Very Bad)"
        else -> "$feel"
    }
}
