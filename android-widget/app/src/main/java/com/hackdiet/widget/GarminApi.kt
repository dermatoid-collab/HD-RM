package com.hackdiet.widget

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDate

object GarminApi {
    private val client = OkHttpClient()
    private const val BASE = "https://personal.connectapi.garmin.com"
    private const val TOKEN_URL = "$BASE/personalapitoken-service/personal-api/personal-api-key/token"

    fun getToken(apiKey: String): String {
        val req = Request.Builder()
            .url(TOKEN_URL)
            .post("".toRequestBody())
            .header("Authorization", "ApiKey $apiKey")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Garmin auth fallita: HTTP ${resp.code}")
            return resp.body?.string()?.trim()
                ?: throw Exception("Token Garmin vuoto")
        }
    }

    data class DayData(
        val kcalConsumed: Int?,
        val deepSleepMin: Int?,
        val remSleepMin: Int?,
        val totalSleepMin: Int?,
        val rhr: Int?
    )

    // Loggato nel sync log per debug — mostra URL, status HTTP e primi 500 char del body
    private fun fetch(token: String, url: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            return Pair(resp.code, body)
        }
    }

    fun getDayData(token: String, date: LocalDate, debugLog: StringBuilder): DayData {
        val ds = date.toString()

        // Daily summary
        val summaryUrl = "$BASE/usersummary-service/v2/dailysummaries?startDate=$ds&endDate=$ds"
        val (summaryCode, summaryBody) = fetch(token, summaryUrl)
        debugLog.appendLine("SUMMARY $ds → HTTP $summaryCode")
        debugLog.appendLine(summaryBody.take(500))

        var kcalConsumed: Int? = null
        var rhr: Int? = null
        if (summaryCode in 200..299) {
            try {
                val root = JSONObject(summaryBody)
                val arr = root.optJSONArray("data")
                val j = if (arr != null && arr.length() > 0) arr.getJSONObject(0) else null
                if (j != null) {
                    val active = j.optInt("active_kilocalories", 0)
                    val bmr = j.optInt("bmr_kilocalories", 0)
                    val total = active + bmr
                    kcalConsumed = if (total > 0) total else null
                    rhr = j.optInt("resting_heart_rate", 0).takeIf { it > 0 }
                }
            } catch (e: Exception) {
                debugLog.appendLine("SUMMARY parse error: ${e.message}")
            }
        }

        // Sleep
        val sleepUrl = "$BASE/sleep-service/sleep/dailySleep?date=$ds"
        val (sleepCode, sleepBody) = fetch(token, sleepUrl)
        debugLog.appendLine("SLEEP $ds → HTTP $sleepCode")
        debugLog.appendLine(sleepBody.take(500))

        var deepMin: Int? = null
        var remMin: Int? = null
        var totalMin: Int? = null
        if (sleepCode in 200..299) {
            try {
                val j = JSONObject(sleepBody)
                fun secs(key: String) = j.optInt(key, 0).takeIf { it > 0 }?.let { it / 60 }
                deepMin = secs("deep_sleep_seconds")
                remMin = secs("rem_sleep_seconds")
                totalMin = secs("sleep_time_seconds") ?: secs("total_sleep_seconds")
            } catch (e: Exception) {
                debugLog.appendLine("SLEEP parse error: ${e.message}")
            }
        }

        return DayData(kcalConsumed, deepMin, remMin, totalMin, rhr)
    }
}
