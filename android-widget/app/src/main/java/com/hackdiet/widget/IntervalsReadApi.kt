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

    fun getActivityDetail(apiKey: String, activityId: String): JSONObject {
        val url = "$BASE/activity/$activityId"
        return JSONObject(get(url, apiKey))
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
                val name = ev.optString("name", "")
                val desc = ev.optString("description", "")
                // Esclude note con "Coach", "Summary", "Brief" nel titolo
                if (listOf("Coach", "Summary", "Brief").any { name.contains(it, ignoreCase = true) }) {
                    null
                } else {
                    buildString {
                        if (name.isNotBlank()) appendLine(name)
                        if (desc.isNotBlank()) append(desc.trim())
                    }.trim()
                }
            }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }
}
