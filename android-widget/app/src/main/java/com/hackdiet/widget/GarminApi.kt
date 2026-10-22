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

    fun getDayData(token: String, date: LocalDate): DayData {
        val ds = date.toString()
        val authHeader = "Bearer $token"

        // Daily summary — returns { "data": [ { active_kilocalories, bmr_kilocalories, resting_heart_rate, … } ] }
        val summaryUrl = "$BASE/usersummary-service/v2/dailysummaries?startDate=$ds&endDate=$ds"
        val summaryReq = Request.Builder()
            .url(summaryUrl)
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .build()
        var kcalConsumed: Int? = null
        var rhr: Int? = null
        client.newCall(summaryReq).execute().use { resp ->
            if (resp.isSuccessful) {
                val root = JSONObject(resp.body?.string() ?: "{}")
                val arr = root.optJSONArray("data")
                val j = if (arr != null && arr.length() > 0) arr.getJSONObject(0) else null
                if (j != null) {
                    val active = j.optInt("active_kilocalories", 0)
                    val bmr = j.optInt("bmr_kilocalories", 0)
                    val total = active + bmr
                    kcalConsumed = if (total > 0) total else null
                    rhr = j.optInt("resting_heart_rate", 0).takeIf { it > 0 }
                }
            }
        }

        // Sleep — returns { deep_sleep_seconds, rem_sleep_seconds, sleep_time_seconds, … }
        val sleepReq = Request.Builder()
            .url("$BASE/sleep-service/sleep/dailySleep?date=$ds")
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .build()
        var deepMin: Int? = null
        var remMin: Int? = null
        var totalMin: Int? = null
        client.newCall(sleepReq).execute().use { resp ->
            if (resp.isSuccessful) {
                val j = JSONObject(resp.body?.string() ?: "{}")
                fun secs(key: String) = j.optInt(key, 0).takeIf { it > 0 }?.let { it / 60 }
                deepMin = secs("deep_sleep_seconds")
                remMin = secs("rem_sleep_seconds")
                totalMin = secs("sleep_time_seconds") ?: secs("total_sleep_seconds")
            }
        }

        return DayData(kcalConsumed, deepMin, remMin, totalMin, rhr)
    }
}
