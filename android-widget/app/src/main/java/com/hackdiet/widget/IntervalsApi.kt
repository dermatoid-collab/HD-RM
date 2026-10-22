package com.hackdiet.widget

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDate

object IntervalsApi {
    private val client = OkHttpClient()
    private val JSON = "application/json".toMediaType()
    private const val BASE = "https://intervals.icu/api/v1"

    private fun formatSleep(minutes: Int, format: String): Any =
        if (format == "hm") "${minutes / 60}h${minutes % 60}m" else minutes

    fun putWellness(
        apiKey: String,
        athleteId: String,
        date: LocalDate,
        kcalConsumed: Int?,
        deepSleepMin: Int?,
        remSleepMin: Int?,
        sleepFormat: String = "hm"
    ) {
        val body = JSONObject().apply {
            kcalConsumed?.let { put("kcalConsumed", it) }
            deepSleepMin?.let { put("DeepSleep", formatSleep(it, sleepFormat)) }
            remSleepMin?.let { put("REMSleep", formatSleep(it, sleepFormat)) }
        }
        if (body.length() == 0) throw Exception("Nessun dato da inviare")

        val req = Request.Builder()
            .url("$BASE/athlete/$athleteId/wellness/${date}")
            .put(body.toString().toRequestBody(JSON))
            .header("Authorization", Credentials.basic("API_KEY", apiKey))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful)
                throw Exception("Intervals.icu HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
        }
    }
}
