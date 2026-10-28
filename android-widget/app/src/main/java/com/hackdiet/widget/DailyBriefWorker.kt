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

    companion object {
        const val KEY_CHAIN = "chain"
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val chain = inputData.getBoolean(KEY_CHAIN, false)
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

            val vo2max = listOf(wellnessToday, wellnessYest)
                .firstNotNullOfOrNull { w -> w?.takeIf { it.has("vo2max") && !it.isNull("vo2max") }?.optDouble("vo2max") }
                ?.let { String.format("%.1f", it) } ?: "-"

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
                    sb.append(formatActivity(act, detail, intervals, yesterday, i + 1, activities.length(), vo2max) {
                        IntervalsReadApi.getActivityStreams(apiKey, actId)
                    })
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
            sb.appendLine("Wellness date: ${wellnessToday.fmtStr("id")}")
            sb.appendLine("Morning HRV-RM (HRVRM): ${wellnessToday.fmtDec("HRVRM")}")
            sb.appendLine("ASIRM: ${wellnessToday.fmtStr("ASIRM")}")
            sb.appendLine("Sleep score: ${wellnessToday.fmtDec("sleepScore")}")
            sb.appendLine("Sleep quality: ${sleepQualityValue(ctx, wellnessToday, today)}")
            sb.appendLine()
            sb.appendLine("WELLNESS (yesterday):")
            sb.appendLine("Wellness date: ${wellnessYest.fmtStr("id")}")
            sb.appendLine("Weight: ${wellnessYest.fmtDec("weight", "kg")}")
            sb.appendLine("KCal consumed: ${wellnessYest.fmtInt("kcalConsumed", "kcal")}")
            sb.appendLine("VO2max: ${wellnessYest.fmtDec("vo2max")}")
            sb.appendLine()
            sb.appendLine("Notes (yesterday):")
            sb.appendLine(if (notesYest.isEmpty()) "-" else notesYest.joinToString("\n\n"))
            sb.appendLine()
            sb.appendLine("Notes (today):")
            sb.appendLine(if (notesToday.isEmpty()) "-" else notesToday.joinToString("\n\n"))


            val missing = missingData(sb.toString())
            if (missing.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Missing data:")
                missing.forEach { sb.appendLine(it) }
            }

            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            Prefs.set(ctx, Prefs.KEY_DAILY_BRIEF_LAST,
                "── Daily Brief $ts ──────────────\n${sb.toString().trimEnd()}")
            Prefs.set(ctx, Prefs.KEY_RUN_BRIEF_OK, "1")
            Result.success()
        } catch (e: Exception) {
            Prefs.set(ctx, Prefs.KEY_DAILY_BRIEF_LAST,
                "── Errore generazione Brief ──\n${e.message?.take(500)}")
            Prefs.set(ctx, Prefs.KEY_RUN_BRIEF_OK, "0")
            if (chain) Result.success() else Result.failure()
        }
    }

    // sleepQuality di Intervals; se manca, dal sleepScore di Intervals; se manca, dal punteggio Garmin della sync
    private fun sleepQualityValue(ctx: Context, w: JSONObject?, today: LocalDate): String {
        w?.optIntOrNull("sleepQuality")?.let { return it.toString() }
        val intervalsScore = w?.takeIf { it.has("sleepScore") && !it.isNull("sleepScore") }
            ?.optDouble("sleepScore", Double.NaN)?.takeIf { !it.isNaN() }
        val garminScore = Prefs.get(ctx, Prefs.KEY_LAST_SLEEP_SCORE, "").split("|")
            .takeIf { it.size == 2 && it[0] == today.toString() }?.get(1)?.toDoubleOrNull()
        val score = intervalsScore ?: garminScore ?: return "-"
        return Prefs.sleepQualityFromScore(score).toString()
    }

    // ── Elenco dati mancanti (righe con valore "-"), raggruppato per sezione ──

    private val MISSING_HEADERS = setOf(
        "TIME / DISTANCE", "POWER", "LOAD", "POWER ZONES", "HR", "HR ZONES", "CADENCE",
        "ENVIRONMENT", "CORE TEMPERATURE", "FITNESS", "ADDITIONAL ACTIVITY FIELDS"
    )
    private val MISSING_LABEL_VALUE = Regex("""^([^:]+):\s+(.*)$""")
    private val MISSING_LABEL_ONLY = Regex("""^([^:]+):$""")
    private val MISSING_DASH_UNIT = Regex("""-\s+[^\d\s]\S*""")

    private fun missingData(text: String): List<String> {
        val bySection = linkedMapOf<String, MutableList<String>>()
        fun add(section: String, label: String) {
            val list = bySection.getOrPut(section) { mutableListOf() }
            if (label !in list) list.add(label)
        }
        var section = "Ride summary"
        var ride = ""
        var lastLabel: String? = null
        var inNotes = false
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            i++
            if (line.startsWith("Notes (")) {
                inNotes = true
                if (i < lines.size && lines[i].trim() == "-") add("Notes", line.substringAfter("(").substringBefore(")"))
                continue
            }
            if (inNotes) continue
            val t = line.trim()
            if (line.startsWith("Yesterday Ride Summary")) {
                ride = line.substringAfter("Summary").removeSuffix(":").trim()
                section = (if (ride.isNotEmpty()) "$ride " else "") + "Ride summary"
                lastLabel = null
                continue
            }
            if (line.startsWith("No activity found")) {
                add("Ride summary", "no activity found")
                continue
            }
            val isSleep = line.startsWith("SLEEP and REST")
            val isWellness = line.startsWith("WELLNESS (yesterday)")
            if (t in MISSING_HEADERS || isSleep || isWellness) {
                section = when {
                    isSleep -> "SLEEP and REST"
                    isWellness -> "WELLNESS (yesterday)"
                    else -> (if (ride.isNotEmpty()) "$ride " else "") + t
                }
                lastLabel = null
                continue
            }
            val lv = MISSING_LABEL_VALUE.find(line)
            if (lv != null) {
                val value = lv.groupValues[2]
                if (value == "-" || MISSING_DASH_UNIT.matches(value)) add(section, lv.groupValues[1])
                lastLabel = null
                continue
            }
            val lo = MISSING_LABEL_ONLY.find(line)
            if (lo != null) {
                lastLabel = lo.groupValues[1]
                continue
            }
            if (t == "-" && lastLabel != null) add(section, lastLabel!!)
        }
        return bySection.map { (sec, labels) -> "- $sec: ${labels.joinToString(", ")}" }
    }

    // ── Formattazione singola attività ─────────────────────────────────────────

    private fun formatActivity(
        a: JSONObject,
        detail: JSONObject?,
        intervals: JSONArray,
        date: LocalDate,
        idx: Int,
        total: Int,
        vo2max: String,
        streamsProvider: () -> JSONArray?
    ): String {
        val streams by lazy { runCatching { streamsProvider() }.getOrNull() }
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
        val x = detail ?: a
        sb.appendLine("Activity ID: ${x.fmtStr("id")}")
        sb.appendLine("Activity type: ${x.fmtStr("type")}")
        sb.appendLine("Type: $typeLabel")
        sb.appendLine("Elapsed time: ${a.fmtSecs("elapsed_time")}")
        sb.appendLine("NP: ${np?.let { "$it W" } ?: "-"}")
        sb.appendLine("TSS: ${tss ?: "-"}")
        sb.appendLine("Work: ${workKj?.let { "$it kJ" } ?: "-"}")
        // icu_rpe e feel — nomi da spec OpenAPI ufficiale
        val rpe  = detail?.optIntOrNull("icu_rpe") ?: a.optIntOrNull("icu_rpe")
        val feel = detail?.optIntOrNull("feel")    ?: a.optIntOrNull("feel")
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
        // Max power: p_max — nome da spec OpenAPI ufficiale
        val maxW = detail?.optIntOrNull("p_max") ?: a.optIntOrNull("p_max")
        sb.appendLine("Max power: ${maxW?.let { "$it W" } ?: "-"}")
        sb.appendLine("Work: ${workKj?.let { "$it kJ" } ?: "-"}")
        val workAboveKj = a.optLongOrNull("icu_joules_above_ftp")?.let { (it / 1000).toInt() }
        sb.appendLine("Work above FTP: ${workAboveKj?.let { "$it kJ" } ?: "-"}")
        sb.appendLine("Calories: ${a.fmtInt("calories", "kcal")}")
        sb.appendLine("CHO used: ${a.fmtInt("carbs_used", "g")}")
        sb.appendLine("VO2max (latest): $vo2max")
        sb.appendLine()

        // LOAD
        sb.appendLine("LOAD")
        sb.appendLine("TSS: ${tss ?: "-"}")
        sb.appendLine("TRIMP: ${a.fmtDecRaw("trimp")}")
        sb.appendLine("EF: ${a.fmtDecRaw("icu_efficiency_factor")}")
        sb.appendLine("Pw:HR (full ride decoupling): ${a.fmtPercent("decoupling")}")
        sb.appendLine()

        // POWER ZONES — icu_zone_times: array di oggetti {"id":"Z1","secs":109}
        sb.appendLine("POWER ZONES")
        val pzt = detail?.optJSONArray("icu_zone_times")
            ?: a.optJSONArray("icu_zone_times")
        val pzFallbackLabels = listOf("Z1","Z2","Z3","Z4","Z5","Z6","Z7","SS")
        if (pzt != null && pzt.length() > 0) {
            for (z in 0 until pzt.length()) {
                val item = pzt.optJSONObject(z)
                val label = item?.optString("id") ?: pzFallbackLabels.getOrElse(z) { "Z${z+1}" }
                val secs  = item?.optInt("secs", 0) ?: 0
                sb.appendLine("$label: ${secsToHms(secs)}")
            }
        } else {
            pzFallbackLabels.forEach { sb.appendLine("$it: -") }
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
                if (durSecs == 0) return@forEach
                val watts = iv.optIntOrNull("average_watts") ?: 0
                val hr    = iv.optIntOrNull("average_heartrate")
                val apiDecoup = iv.optDouble("decoupling", Double.NaN)
                val calcDecoup = if (apiDecoup.isNaN())
                    calcDecoupling(streams, iv.optInt("start_index", -1), iv.optInt("end_index", -1))
                else null
                val decoup = if (!apiDecoup.isNaN()) apiDecoup else calcDecoup ?: Double.NaN
                val cad   = iv.optDouble("average_cadence", Double.NaN)
                val parts = mutableListOf("1 × ${mmss(durSecs)} @ $watts W")
                if (hr != null) parts.add("avg HR $hr bpm")
                if (!decoup.isNaN()) parts.add("Pw:HR ${String.format("%.2f", decoup)}%${if (calcDecoup != null) " (calc)" else ""}")
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

        // HR ZONES — dal dettaglio o dalla lista (7 zone in Intervals.icu)
        sb.appendLine("HR ZONES")
        val hzt = detail?.optJSONArray("icu_hr_zone_times")
            ?: a.optJSONArray("icu_hr_zone_times")
        val hzLabels = listOf("Z1","Z2","Z3","Z4","Z5","Z6","Z7")
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

        // CORE TEMPERATURE: da stream core_temperature, altrimenti derivata dagli intervalli (heat.avg/heat.max)
        val coreIvs = x.optJSONArray("icu_intervals")
        var maxCore = Double.NaN
        var wSum = 0.0
        var tSum = 0
        if (coreIvs != null) for (i in 0 until coreIvs.length()) {
            val heat = coreIvs.optJSONObject(i)?.optJSONObject("heat") ?: continue
            val m = heat.optDouble("max", Double.NaN)
            if (!m.isNaN() && (maxCore.isNaN() || m > maxCore)) maxCore = m
            val avgI = heat.optDouble("avg", Double.NaN)
            val dur = coreIvs.optJSONObject(i)?.optInt("t", 0) ?: 0
            if (!avgI.isNaN() && dur > 0) { wSum += avgI * dur; tSum += dur }
        }
        var avgCore = Double.NaN
        val coreSeries = seriesOf(streams, "core_temperature")
        if (coreSeries != null) {
            var sum = 0.0
            var n = 0
            var streamMax = Double.NaN
            for (i in 0 until coreSeries.length()) {
                if (coreSeries.isNull(i)) continue
                val v = coreSeries.optDouble(i)
                if (v.isNaN()) continue
                sum += v
                n++
                if (streamMax.isNaN() || v > streamMax) streamMax = v
            }
            if (n > 0) {
                avgCore = sum / n
                maxCore = streamMax
            }
        }
        if (avgCore.isNaN() && tSum > 0) avgCore = wSum / tSum
        sb.appendLine("CORE TEMPERATURE")
        sb.appendLine("Avg core temp: ${if (avgCore.isNaN()) "-" else String.format("%.2f °C", avgCore)}")
        sb.appendLine("Max core temp: ${if (maxCore.isNaN()) "-" else String.format("%.2f °C", maxCore)}")
        sb.appendLine("CORE Heat Training Load: ${x.fmtDecRaw("COREHeatTrainingLoad")}")
        val heatThrMin = x.optDouble("TimeAboveHeatThreshold", Double.NaN)
        sb.appendLine("Time above heat threshold: ${if (heatThrMin.isNaN()) "-" else mmss(Math.round(heatThrMin * 60).toInt())}")
        sb.appendLine()

        // FITNESS — CTL/ATL/TSB (icu_form non sempre presente, calcolo da CTL-ATL)
        sb.appendLine("FITNESS")
        val ctl = a.optDouble("icu_ctl", Double.NaN)
        val atl = a.optDouble("icu_atl", Double.NaN)
        val tsbVal = if (!ctl.isNaN() && !atl.isNaN()) ctl - atl else Double.NaN
        sb.appendLine("CTL: ${if (ctl.isNaN()) "-" else String.format("%.2f", ctl)}")
        sb.appendLine("ATL: ${if (atl.isNaN()) "-" else String.format("%.2f", atl)}")
        sb.appendLine("TSB: ${if (tsbVal.isNaN()) "-" else String.format("%.2f", tsbVal)}")
        sb.appendLine()

        sb.appendLine("ADDITIONAL ACTIVITY FIELDS")
        sb.appendLine("Activity name: ${x.fmtStr("name")}")
        sb.appendLine("Activity source: ${x.fmtStr("source")}")
        sb.appendLine("Device: ${x.fmtStr("device_name")}")
        sb.appendLine("Power meter: ${x.fmtStr("power_meter")}")
        sb.appendLine("Activity updated: ${x.fmtStr("analyzed")}")
        sb.appendLine()

        return sb.toString()
    }

    // ── Helpers estensioni JSONObject ──────────────────────────────────────────

    private fun JSONObject?.fmtStr(key: String): String {
        if (this == null || !has(key) || isNull(key)) return "-"
        return optString(key, "").ifBlank { "-" }
    }

    // Pw:HR = variazione % del rapporto potenza media / HR media tra prima e seconda metà
    private fun calcDecoupling(streams: JSONArray?, start: Int, end: Int): Double? {
        if (streams == null || start < 0 || end - start < 4) return null
        val w = seriesOf(streams, "watts") ?: return null
        val hr = seriesOf(streams, "heartrate") ?: return null
        val e = minOf(end, w.length(), hr.length())
        if (e - start < 4) return null
        fun ratio(a: Int, b: Int): Double? {
            var sw = 0.0
            var sh = 0.0
            for (i in a until b) {
                if (w.isNull(i) || hr.isNull(i)) continue
                sw += w.optDouble(i)
                sh += hr.optDouble(i)
            }
            return if (sh == 0.0) null else sw / sh
        }
        val mid = start + (e - start) / 2
        val r1 = ratio(start, mid) ?: return null
        val r2 = ratio(mid, e) ?: return null
        return if (r1 == 0.0) null else (r1 - r2) / r1 * 100
    }

    private fun seriesOf(streams: JSONArray?, type: String): JSONArray? {
        if (streams == null) return null
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i)
            if (s != null && s.optString("type") == type) return s.optJSONArray("data")
        }
        return null
    }

    private fun mmss(secs: Int) = String.format("%02d:%02d", secs / 60, secs % 60)

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
