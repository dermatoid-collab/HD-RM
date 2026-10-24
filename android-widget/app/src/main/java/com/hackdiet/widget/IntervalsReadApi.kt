package com.hackdiet.widget

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object IntervalsReadApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private const val BASE = "https://intervals.icu/api/v1"

    private fun get(url: String, apiKey: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic("API_KEY", apiKey))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful)
                throw Exception("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            return resp.body?.string() ?: "[]"
        }
    }

    // ── Attività ──────────────────────────────────────────────────────────────

    fun getActivitiesForDate(apiKey: String, athleteId: String, date: LocalDate): JSONArray {
        val url = "$BASE/athlete/$athleteId/activities?oldest=$date&newest=$date"
        return JSONArray(get(url, apiKey))
    }

    // Dettaglio completo attività (zone, RPE, max power, ecc.)
    fun getActivityDetail(apiKey: String, activityId: String): JSONObject? {
        return try {
            JSONObject(get("$BASE/activity/$activityId?intervals=true", apiKey))
        } catch (_: Exception) { null }
    }

    // Endpoint separato per gli intervalli dell'attività
    fun getActivityIntervals(apiKey: String, activityId: String): JSONArray {
        return try {
            val obj = JSONObject(get("$BASE/activity/$activityId/intervals", apiKey))
            obj.optJSONArray("icu_intervals") ?: JSONArray()
        } catch (_: Exception) { JSONArray() }
    }

    // Stream watts/heartrate (campioni a 1 Hz) per calcolare il decoupling mancante
    fun getActivityStreams(apiKey: String, activityId: String): JSONArray? {
        return try {
            JSONArray(get("$BASE/activity/$activityId/streams.json?types=watts,heartrate", apiKey))
        } catch (_: Exception) { null }
    }

    // ── Wellness ──────────────────────────────────────────────────────────────

    fun getWellness(apiKey: String, athleteId: String, date: LocalDate): JSONObject? {
        return try {
            JSONObject(get("$BASE/athlete/$athleteId/wellness/$date", apiKey))
        } catch (_: Exception) { null }
    }

    // ── Calendar notes ────────────────────────────────────────────────────────

    fun getCalendarNotes(apiKey: String, athleteId: String, date: LocalDate): List<String> {
        return try {
            val url = "$BASE/athlete/$athleteId/events?oldest=$date&newest=$date&category=NOTE"
            val arr = JSONArray(get(url, apiKey))
            (0 until arr.length()).mapNotNull { i ->
                val ev = arr.getJSONObject(i)
                if (ev.optString("category") != "NOTE") return@mapNotNull null
                val evId = if (ev.isNull("id")) "-" else ev.optString("id", "-")
                val evDate = if (ev.isNull("start_date_local")) "-" else ev.optString("start_date_local", "-")
                val evCat = if (ev.isNull("category")) "-" else ev.optString("category", "-")
                val name = if (ev.isNull("name")) "" else ev.optString("name", "")
                val desc = if (ev.isNull("description")) "" else ev.optString("description", "")
                if (listOf("Coach", "Summary", "Brief").any { name.contains(it, ignoreCase = true) })
                    return@mapNotNull null
                buildString {
                    appendLine("[Date: $evDate | Category: $evCat | ID: $evId]")
                    if (name.isNotBlank()) appendLine(name)
                    if (desc.isNotBlank()) append(desc.trim())
                }.trim()
            }.filter { it.lines().drop(1).any { l -> l.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
    }
}
