package com.hackdiet.widget

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private const val FILE = "hackdiet_secure_prefs"
    const val KEY_GARMIN_API_KEY = "garmin_api_key"
    const val KEY_INTERVALS_API_KEY = "intervals_api_key"
    const val KEY_INTERVALS_ATHLETE_ID = "intervals_athlete_id"
    const val KEY_LAST_SYNC = "last_sync"
    const val KEY_LAST_RESULT = "last_result"
    const val KEY_LOG = "sync_log"
    const val KEY_SLEEP_FORMAT = "sleep_format"  // "hm" | "min"
    private const val MAX_LOG_LINES = 50

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx,
        FILE,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(ctx: Context, key: String, default: String = "") =
        prefs(ctx).getString(key, default) ?: default

    fun set(ctx: Context, key: String, value: String) =
        prefs(ctx).edit().putString(key, value).apply()

    fun appendLog(ctx: Context, line: String) {
        val existing = get(ctx, KEY_LOG, "")
        val lines = if (existing.isBlank()) mutableListOf() else existing.split("\n").toMutableList()
        lines.add(0, line)
        if (lines.size > MAX_LOG_LINES) lines.subList(MAX_LOG_LINES, lines.size).clear()
        set(ctx, KEY_LOG, lines.joinToString("\n"))
    }

    fun isConfigured(ctx: Context) =
        get(ctx, KEY_GARMIN_API_KEY).isNotBlank() &&
        get(ctx, KEY_INTERVALS_API_KEY).isNotBlank() &&
        get(ctx, KEY_INTERVALS_ATHLETE_ID).isNotBlank()
}
