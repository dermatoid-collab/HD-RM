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
            val apiKey    = Prefs.get(ctx, Prefs.KEY_INTERVALS_API_KEY)
            val athleteId = Prefs.get(ctx, Prefs.KEY_INTERVALS_ATHLETE_ID)
            val today     = LocalDate.now()
            val yesterday = today.minusDays(1)

            val activities    = IntervalsReadApi.getActivitiesForDate(apiKey, athleteId, yesterday)
            val wellnessYest  = IntervalsReadApi.getWellness(apiKey, athleteId, yesterday)
            val wellnessToday = IntervalsReadApi.getWellness(apiKey, athleteId, today)
            val notesYest     = IntervalsReadApi.getCalendarNotes(apiKey, athleteId, yesterday)
            val notesToday    = IntervalsReadApi.getCalendarNotes(apiKey, athleteId, today)

            val sb = StringBuilder()
            sb.appendLine("Perform today's HS Daily Brief based on the following data.")
            sb.appendLine()
            sb.appendLine("Today is $today.")
            sb.appendLine()

            if (activities.length() == 0) {
                sb.appendLine("No activity found for $yesterday.")
                sb.appendLine()
            } else {
                for (i in 0 until activities.length()) {
                    val act = activities.getJSONObject(i)
                    val actId = act.optString("id", "")
                    val detail = if (actId.isNotBlank())
                        runCatching { IntervalsReadApi.getActivityDetail(apiKey, actId) }.getOrNull()
                    else null
                    val intervals = if (actId.isNotBlank())
                        runCatching { IntervalsReadApi.getActivityIntervals(apiKey, actId) }.getOrDefault(JSONArray())
                    else JSONArray()
                    sb.append(formatActivity(act, detail, intervals, yesterday, i + 1, activities.length()))
                }
            }

            // SLEEP (da wellness di oggi)
            sb.appendLine("SLEEP and REST (today's night, today's morning):")
            sb.appendLine("Sleep: ${wellnessToday.fmtSleepSecs("sleepSecs")}")
            sb.appendLine("Deep sleep: ${wellnessToday.fmtHm("DeepSleep")}")
            sb.appendLine("REM sleep: ${wellnessToday.fmtHm("REMSleep")}")
            sb.appendLine("Resting HR: ${wellnessToday.fmtInt("restingHR", "bpm")}")
            sb.appendLine("Sleep pattern: ${wellnessToday.fmtSleepQuality()}")
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

            // Sezione DEBUG temporanea: mostra campi grezzi dal primo risultato
            if (activities.length() > 0) {
                val a0 = activities.getJSONObject(0)
                val actId0 = a0.optString("id", "")
                val det0 = if (actId0.isNotBlank())
                    runCatching { IntervalsReadApi.getActivityDetail(apiKey, actId0) }.getOrNull()
                else null
                sb.appendLine()
                sb.appendLine("── DEBUG (rimuovere dopo verifica) ──")
                sb.appendLine("Lista: perceived_exertion=${a0.opt("perceived_exertion")} rpe=${a0.opt("rpe")} feel=${a0.opt("feel")}")
                sb.appendLine("Lista: icu_max_watts=${a0.opt("icu_max_watts")} max_watts=${a0.opt("max_watts")}")
                sb.appendLine("Lista: icu_power_zone_times=${a0.opt("icu_power_zone_times")}")
                sb.appendLine("Lista: icu_hr_zone_times=${a0.opt("icu_hr_zone_times")}")
                if (det0 != null) {
                    sb.appendLine("Detail: perceived_exertion=${det0.opt("perceived_exertion")} rpe=${det0.opt("rpe")} feel=${det0.opt("feel")}")
                    sb.appendLine("Detail: icu_max_watts=${det0.opt("icu_max_watts")} max_watts=${det0.opt("max_watts")}")
                    sb.appendLine("Detail: icu_power_zone_times=${det0.opt("icu_power_zone_times")}")
                    sb.appendLine("Detail: icu_hr_zone_times=${det0.opt("icu_hr_zone_times")}")
                } else {
                    sb.appendLine("Detail: FETCH FALLITO per id=$actId0")
                }
            }

            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            Prefs.set(ctx, Prefs.KEY_DAILY_BRIEF_LAST,
                "── Daily Brief $ts ──────────────\n${sb.toString().trimEnd()}")
            Result.success()
        } catch (e: Exception) {
            Prefs.set(ctx, Prefs.KEY_DAILY_BRIEF_LAST,
                "── Errore generazione Brief ──\n${e.message?.take(500)}")
            Result.failure()
        }
    }

    // ── Formattazione singola attività ─────────────────────────────────────────

    private fun formatActivity(
        a: JSONObject,
        detail: JSONObject?,
        intervals: JSONArray,
        date: LocalDate,
        idx: Int,
        total: Int
    ): String {
        val sb = StringBuilder()
        val header = if (total > 1) "Yesterday Ride Summary #$idx:" else "Yesterday Ride Summary:"
        sb.appendLine(header)
        sb.appendLine()

        // Indoor: campo "trainer" (boolean) come nel Python
        val isIndoor = a.optBoolean("trainer", false)
        val typeLabel = if (isIndoor) "Indoor" else "Outdoor"

        val np    = a.optIntOrNull("icu_weighted_avg_watts")
        val tss   = a.optIntOrNull("icu_training_load")
        val workJ = a.optLongOrNull("icu_joules")
        val workKj = workJ?.let { (it / 1000).toInt() }

        sb.appendLine("Date: $date")
        sb.appendLine("Type: $typeLabel")
        sb.appendLine("Elapsed time: ${a.fmtSecs("elapsed_time")}")
        sb.appendLine("NP: ${np?.let { "$it W" } ?: "-"}")
        sb.appendLine("TSS: ${tss ?: "-"}")
        sb.appendLine("Work: ${workKj?.let { "$it kJ" } ?: "-"}")
        // RPE: dal dettaglio o dalla lista, field name varia
        val rpe = detail?.optIntOrNull("perceived_exertion")
            ?: detail?.optIntOrNull("rpe")
            ?: a.optIntOrNull("perceived_exertion")
            ?: a.optIntOrNull("rpe")
        val feel = detail?.optIntOrNull("feel") ?: a.optIntOrNull("feel")
        sb.appendLine("RPE (1 = Rest / 10 = Max effort): ${rpe ?: "-"}")
        sb.appendLine("Feel (1 = Strong / 5 = Weak): ${feel?.let { feelLabel(it) } ?: "-"}")
        sb.appendLine()

        // TIME / DISTANCE
        sb.appendLine("TIME / DISTANCE")
        val startRaw = a.optString("start_date_local", "")
        val startTime = if ("T" in startRaw) startRaw.substringAfter("T").take(8) else "-"
        sb.appendLine("Start time: $startTime")
        sb.appendLine("Elapsed time: ${a.fmtSecs("elapsed_time")}")
        sb.appendLine("Moving time: ${a.fmtSecs("moving_time")}")
        val coast = (a.optInt("elapsed_time", 0) - a.optInt("moving_time", 0))
        sb.appendLine("Coasting time: ${if (coast > 0) secsToHms(coast) else "-"}")
        sb.appendLine("Distance: ${a.fmtKm("distance")}")
        if (!isIndoor) sb.appendLine("Elevation gain: ${a.fmtInt("total_elevation_gain", "m")}")
        sb.appendLine()

        // POWER
        sb.appendLine("POWER")
        sb.appendLine("Avg power: ${a.fmtInt("icu_average_watts", "W")}")
        sb.appendLine("NP: ${np?.let { "$it W" } ?: "-"}")
        sb.appendLine("FTP (set): ${a.fmtInt("icu_ftp", "W")}")
        sb.appendLine("IF: ${a.fmtDecRaw("icu_intensity")}")
        sb.appendLine("VI: ${a.fmtDecRaw("icu_variability_index")}")
        // Max power: dal dettaglio o dalla lista
        val maxW = detail?.optIntOrNull("icu_max_watts")
            ?: detail?.optIntOrNull("max_watts")
            ?: detail?.optIntOrNull("max_power")
            ?: a.optIntOrNull("icu_max_watts")
            ?: a.optIntOrNull("max_watts")
        sb.appendLine("Max power: ${maxW?.let { "$it W" } ?: "-"}")
        sb.appendLine("Work: ${workKj?.let { "$it kJ" } ?: "-"}")
        val workAboveKj = a.optLongOrNull("icu_joules_above_ftp")?.let { (it / 1000).toInt() }
        sb.appendLine("Work above FTP: ${workAboveKj?.let { "$it kJ" } ?: "-"}")
        sb.appendLine("Calories: ${a.fmtInt("calories", "kcal")}")
        sb.appendLine("CHO used: ${a.fmtInt("icu_carbs_used", "g")}")
        sb.appendLine("VO2max (latest): ${a.fmtInt("vo2max")}")
        sb.appendLine()

        // LOAD
        sb.appendLine("LOAD")
        sb.appendLine("TSS: ${tss ?: "-"}")
        sb.appendLine("TRIMP: ${a.fmtDecRaw("trimp")}")
        sb.appendLine("EF: ${a.fmtDecRaw("icu_efficiency_factor")}")
        sb.appendLine("Pw:HR (full ride decoupling): ${a.fmtPercent("decoupling")}")
        sb.appendLine()

        // POWER ZONES — dal dettaglio (più affidabile) o dalla lista
        sb.appendLine("POWER ZONES")
        val pzt = detail?.optJSONArray("icu_power_zone_times")
            ?: a.optJSONArray("icu_power_zone_times")
        val pzLabels = listOf("Z1","Z2","Z3","Z4","Z5","Z6+","Sweet Spot")
        if (pzt != null && pzt.length() > 0) {
            for (z in 0 until pzt.length()) {
                val label = pzLabels.getOrElse(z) { "Z${z+1}" }
                sb.appendLine("$label: ${secsToHms(pzt.optInt(z, 0))}")
            }
        } else {
            pzLabels.forEach { sb.appendLine("$it: -") }
        }

        // Intervals: già recuperati dall'endpoint separato, filtra WORK o con label
        sb.appendLine("Intervals summary:")
        val workIntervals = (0 until intervals.length())
            .map { intervals.getJSONObject(it) }
            .filter { iv ->
                iv.optString("type") == "WORK" || iv.optString("label").isNotBlank()
            }
        if (workIntervals.isEmpty()) {
            sb.appendLine("  -")
        } else {
            workIntervals.forEach { iv ->
                val durSecs = iv.optInt("moving_time", 0).takeIf { it > 0 }
                    ?: iv.optInt("elapsed_time", 0)
                val mins  = durSecs / 60
                val watts = iv.optIntOrNull("average_watts") ?: 0
                val hr    = iv.optIntOrNull("average_heartrate")
                val decoup = iv.optDouble("decoupling", Double.NaN)
                val cad   = iv.optDouble("average_cadence", Double.NaN)
                val parts = mutableListOf("1 × $mins min @ $watts W")
                if (hr != null) parts.add("avg HR $hr bpm")
                if (!decoup.isNaN()) parts.add("Pw:HR ${String.format("%.2f", decoup)}%")
                if (!cad.isNaN()) parts.add("avg Cadence ${cad.toInt()} rpm")
                sb.appendLine("  * ${parts.joinToString(", ")}")
            }
        }
        sb.appendLine()

        // HR
        sb.appendLine("HR")
        sb.appendLine("Avg HR: ${a.fmtInt("average_heartrate", "bpm")}")
        sb.appendLine("Max HR: ${a.fmtInt("max_heartrate", "bpm")}")
        sb.appendLine("LTHR: ${a.fmtInt("lthr", "bpm")}")
        sb.appendLine()

        // HR ZONES — dal dettaglio o dalla lista
        sb.appendLine("HR ZONES")
        val hzt = detail?.optJSONArray("icu_hr_zone_times")
            ?: a.optJSONArray("icu_hr_zone_times")
        val hzLabels = listOf("Z1","Z2","Z3","Z4","Z5+")
        if (hzt != null && hzt.length() > 0) {
            for (z in 0 until hzt.length()) {
                val label = hzLabels.getOrElse(z) { "Z${z+1}" }
                sb.appendLine("$label: ${secsToHms(hzt.optInt(z, 0))}")
            }
        } else {
            hzLabels.forEach { sb.appendLine("$it: -") }
        }
        sb.appendLine()

        // CADENCE
        sb.appendLine("CADENCE")
        sb.appendLine("Avg cadence: ${a.fmtDecRaw("average_cadence")} rpm")
        sb.appendLine()

        // ENVIRONMENT
        sb.appendLine("ENVIRONMENT")
        sb.appendLine("Avg temp: ${a.fmtDecRaw("average_temp")} °C")
        sb.appendLine("Min temp: ${a.fmtInt("min_temp", "°C")}")
        sb.appendLine("Max temp: ${a.fmtInt("max_temp", "°C")}")
        sb.appendLine()

        // CORE TEMPERATURE (non disponibile via API standard, placeholder)
        sb.appendLine("CORE TEMPERATURE")
        sb.appendLine("Avg core temp: -")
        sb.appendLine("Max core temp: -")
        sb.appendLine("Time > 38.3 °C: -")
        sb.appendLine()

        // FITNESS — CTL/ATL/TSB vengono dall'attività stessa
        sb.appendLine("FITNESS")
        sb.appendLine("CTL: ${a.fmtDecRaw("icu_ctl")}")
        sb.appendLine("ATL: ${a.fmtDecRaw("icu_atl")}")
        sb.appendLine("TSB: ${a.fmtDecRaw("icu_form")}")
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
        if (v.isNaN()) return "-"
        return if (unit.isNotEmpty()) "${String.format("%.1f", v)} $unit" else String.format("%.1f", v)
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

    // Sonno in secondi (sleepSecs)
    private fun JSONObject?.fmtSleepSecs(key: String): String {
        if (this == null) return "-"
        val secs = optIntOrNull(key) ?: return "-"
        return secsToHms(secs)
    }

    // Sonno come "1h30m" (DeepSleep, REMSleep)
    private fun JSONObject?.fmtHm(key: String): String {
        if (this == null) return "-"
        val raw = optString(key, "").ifBlank { return "-" }
        // già formattato come "1h30m" o intero minuti
        return if (raw.contains("h") || raw.contains("m")) raw
        else {
            val mins = raw.toIntOrNull() ?: return raw
            "${mins / 60}h${mins % 60}m"
        }
    }

    private fun JSONObject?.fmtSleepQuality(): String {
        if (this == null) return "-"
        return when (optIntOrNull("sleepQuality")) {
            1    -> "Continuous"
            2, 3 -> "Interrupted"
            else -> "-"
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getInt(key) }.getOrNull()
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getLong(key) }.getOrNull()
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
