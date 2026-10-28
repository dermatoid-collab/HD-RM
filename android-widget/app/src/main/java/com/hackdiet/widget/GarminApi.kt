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
        val rhr: Int?,
        val sleepScore: Int? = null
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
        debugLog.appendLine(summaryBody.take(2000))

        var kcalConsumed: Int? = null
        var rhr: Int? = null
        if (summaryCode in 200..299) {
            try {
                val root = JSONObject(summaryBody)
                val arr = root.optJSONArray("data")
                val j = if (arr != null && arr.length() > 0) arr.getJSONObject(0) else null
                if (j != null) {
                    // calories and heartRate are nested objects
                    val cal = j.optJSONObject("calories")
                    kcalConsumed = cal?.optInt("consumedValue", 0)?.takeIf { it > 0 }
                    val hr = j.optJSONObject("heartRate")
                    rhr = hr?.optInt("restingValue", 0)?.takeIf { it > 0 }
                }
            } catch (e: Exception) {
                debugLog.appendLine("SUMMARY parse error: ${e.message}")
            }
        }

        // Sleep
        val sleepUrl = "$BASE/sleep-service/sleep/dailySleep?date=$ds"
        val (sleepCode, sleepBody) = fetch(token, sleepUrl)
        debugLog.appendLine("SLEEP $ds → HTTP $sleepCode")
        debugLog.appendLine(sleepBody.take(2000))

        var deepMin: Int? = null
        var remMin: Int? = null
        var totalMin: Int? = null
        var sleepScore: Int? = null
        if (sleepCode in 200..299) {
            try {
                val j = JSONObject(sleepBody)
                // API returns camelCase field names
                fun secs(key: String) = j.optInt(key, 0).takeIf { it > 0 }?.let { (it + 30) / 60 }
                deepMin = secs("deepSleepSeconds")
                remMin = secs("remSleepSeconds")
                totalMin = secs("sleepTimeSeconds") ?: secs("totalSleepSeconds")
                sleepScore = j.optJSONObject("sleepScores")?.optJSONObject("overall")
                    ?.optInt("value", 0)?.takeIf { it > 0 }
            } catch (e: Exception) {
                debugLog.appendLine("SLEEP parse error: ${e.message}")
            }
        }

        return DayData(kcalConsumed, deepMin, remMin, totalMin, rhr, sleepScore)
    }
}
