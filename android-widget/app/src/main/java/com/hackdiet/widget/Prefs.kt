package com.hackdiet.widget

import android.content.Context
import android.graphics.Color
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
    const val KEY_LAST_KCAL = "last_kcal"
    const val KEY_LAST_SLEEP = "last_sleep"
    const val KEY_WIDGET_BG_COLOR = "widget_bg_color"   // hex es. "#000000"
    const val KEY_WIDGET_BG_ALPHA = "widget_bg_alpha"   // 0–100 come stringa
    const val KEY_WIDGET_BG_MODE = "widget_bg_mode"     // "fixed" | "state"
    const val KEY_WIDGET_BG_OK = "widget_bg_ok"         // sfondo se la sync di oggi è riuscita
    const val KEY_WIDGET_BG_PROGRESS = "widget_bg_progress"
    const val KEY_WIDGET_BG_STALE = "widget_bg_stale"   // errore o sync non di oggi
    // nero, bianco, giallo, verde, indaco, viola, rosso, arancione
    val BG_PALETTE = listOf("#000000", "#FFFFFF", "#FBC02D", "#2E7D32", "#3F51B5", "#7B1FA2", "#C62828", "#EF6C00")
    const val KEY_DAILY_BRIEF_LAST = "daily_brief_last"
    const val KEY_RUN_SYNC_OK = "run_sync_ok"      // "1" = ok, altro = no (tap sul widget)
    const val KEY_RUN_BRIEF_OK = "run_brief_ok"
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

    fun isStateBg(ctx: Context) = get(ctx, KEY_WIDGET_BG_MODE, "fixed") == "state"

    private fun bgHex(ctx: Context, state: BgState): String =
        if (isStateBg(ctx)) when (state) {
            BgState.OK -> get(ctx, KEY_WIDGET_BG_OK, "#2E7D32")
            BgState.PROGRESS -> get(ctx, KEY_WIDGET_BG_PROGRESS, "#FBC02D")
            BgState.STALE -> get(ctx, KEY_WIDGET_BG_STALE, "#C62828")
        } else get(ctx, KEY_WIDGET_BG_COLOR, "#000000")

    fun widgetBgColor(ctx: Context, state: BgState): Int {
        val alpha = get(ctx, KEY_WIDGET_BG_ALPHA, "75").toIntOrNull()?.coerceIn(0, 100) ?: 75
        val base  = runCatching { Color.parseColor(bgHex(ctx, state)) }.getOrDefault(Color.BLACK)
        val a255  = (alpha * 255 / 100)
        return Color.argb(a255, Color.red(base), Color.green(base), Color.blue(base))
    }

    fun isConfigured(ctx: Context) =
        get(ctx, KEY_GARMIN_API_KEY).isNotBlank() &&
        get(ctx, KEY_INTERVALS_API_KEY).isNotBlank() &&
        get(ctx, KEY_INTERVALS_ATHLETE_ID).isNotBlank()
}

enum class BgState { OK, PROGRESS, STALE }
