package com.hackdiet.widget

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class SettingsActivity : Activity() {

    private lateinit var tvLog: TextView
    private lateinit var btnRead: Button
    private lateinit var btnSync: Button
    private lateinit var btnDailyBrief: Button
    private val handler = Handler(Looper.getMainLooper())

    private val REQ_EXPORT = 1001
    private val REQ_IMPORT = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
        }

        val etGarminKey = findViewById<EditText>(R.id.et_garmin_key)
        val etIntervalsKey = findViewById<EditText>(R.id.et_intervals_key)
        val etAthleteId = findViewById<EditText>(R.id.et_athlete_id)
        val btnSave = findViewById<Button>(R.id.btn_save)
        btnRead = findViewById(R.id.btn_read)
        btnSync = findViewById(R.id.btn_sync)
        btnDailyBrief = findViewById(R.id.btn_daily_brief)
        val btnViewBrief = findViewById<Button>(R.id.btn_view_brief)
        tvLog = findViewById(R.id.tv_log)
        val rgSleepFormat = findViewById<RadioGroup>(R.id.rg_sleep_format)

        // Ripristina selezione formato sonno
        val savedFormat = Prefs.get(this, Prefs.KEY_SLEEP_FORMAT, "hm")
        rgSleepFormat.check(if (savedFormat == "min") R.id.rb_min else R.id.rb_hm)
        rgSleepFormat.setOnCheckedChangeListener { _, checkedId ->
            Prefs.set(this, Prefs.KEY_SLEEP_FORMAT, if (checkedId == R.id.rb_min) "min" else "hm")
        }

        refreshLog()

        etGarminKey.setText(Prefs.get(this, Prefs.KEY_GARMIN_API_KEY))
        etIntervalsKey.setText(Prefs.get(this, Prefs.KEY_INTERVALS_API_KEY))
        etAthleteId.setText(Prefs.get(this, Prefs.KEY_INTERVALS_ATHLETE_ID))

        btnSave.setOnClickListener {
            val garminKey = etGarminKey.text.toString().trim()
            val intervalsKey = etIntervalsKey.text.toString().trim()
            val athleteId = etAthleteId.text.toString().trim()

            if (garminKey.isBlank() || intervalsKey.isBlank() || athleteId.isBlank()) {
                Toast.makeText(this, "Compila tutti i campi", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Prefs.set(this, Prefs.KEY_GARMIN_API_KEY, garminKey)
            Prefs.set(this, Prefs.KEY_INTERVALS_API_KEY, intervalsKey)
            Prefs.set(this, Prefs.KEY_INTERVALS_ATHLETE_ID, athleteId)

            Toast.makeText(this, "Salvato", Toast.LENGTH_SHORT).show()
        }

        btnRead.setOnClickListener {
            if (!Prefs.isConfigured(this)) {
                Toast.makeText(this, "Salva prima le credenziali", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startWorker(readOnly = true)
        }

        btnSync.setOnClickListener {
            if (!Prefs.isConfigured(this)) {
                Toast.makeText(this, "Salva prima le credenziali", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startWorker(readOnly = false)
        }

        btnDailyBrief.setOnClickListener {
            if (!Prefs.isConfigured(this)) {
                Toast.makeText(this, "Salva prima le credenziali", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startDailyBrief()
        }

        btnViewBrief.setOnClickListener {
            startActivity(Intent(this, DailyBriefActivity::class.java))
        }
        btnViewBrief.isEnabled = Prefs.get(this, Prefs.KEY_DAILY_BRIEF_LAST).isNotBlank()

        findViewById<Button>(R.id.btn_export).setOnClickListener { launchExport() }
        findViewById<Button>(R.id.btn_import).setOnClickListener { launchImport() }

        setupBgColorPicker()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun refreshLog() {
        val log = Prefs.get(this, Prefs.KEY_LOG, "")
        tvLog.text = if (log.isBlank()) "Nessuna sincronizzazione ancora." else log
    }

    private fun startWorker(readOnly: Boolean) {
        val btn = if (readOnly) btnRead else btnSync
        btn.isEnabled = false
        btn.text = "⏳ In corso…"
        Prefs.set(this, Prefs.KEY_LAST_RESULT, "⏳ In corso…")
        SyncWidget.updateAll(this)
        SyncWidget2x1.updateAll(this)

        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_READ_ONLY to readOnly))
            .build()
        WorkManager.getInstance(this).enqueue(req)

        pollWorkState(req.id, btn, if (readOnly) "🔍 Leggi dati da Garmin" else "▶ Sincronizza ora → Intervals")
    }

    private fun startDailyBrief() {
        btnDailyBrief.isEnabled = false
        btnDailyBrief.text = "⏳ Generazione…"

        val req = androidx.work.OneTimeWorkRequestBuilder<DailyBriefWorker>().build()
        androidx.work.WorkManager.getInstance(this).enqueue(req)

        handler.postDelayed(object : Runnable {
            override fun run() {
                val info = try {
                    androidx.work.WorkManager.getInstance(this@SettingsActivity)
                        .getWorkInfoById(req.id).get()
                } catch (_: Exception) { null }

                if (info != null && info.state.isFinished) {
                    btnDailyBrief.isEnabled = true
                    btnDailyBrief.text = "📋 Genera HS Daily Brief"
                    val ok = info.state == androidx.work.WorkInfo.State.SUCCEEDED
                    if (ok) {
                        startActivity(Intent(this@SettingsActivity, DailyBriefActivity::class.java))
                    } else {
                        Toast.makeText(this@SettingsActivity, "Errore generazione Brief", Toast.LENGTH_LONG).show()
                    }
                } else {
                    handler.postDelayed(this, 2000)
                }
            }
        }, 2000)
    }

    private fun setupBgColorPicker() {
        val swatches = listOf(
            R.id.swatch_black    to "#000000",
            R.id.swatch_darkgray to "#1C1C1E",
            R.id.swatch_night    to "#0D1B2A",
            R.id.swatch_indigo   to "#1A1A2E",
            R.id.swatch_white    to "#FFFFFF"
        )

        val currentColor = Prefs.get(this, Prefs.KEY_WIDGET_BG_COLOR, "#000000")
        val tvAlpha = findViewById<TextView>(R.id.tv_bg_alpha)
        val sbAlpha = findViewById<SeekBar>(R.id.sb_bg_alpha)

        val savedAlpha = Prefs.get(this, Prefs.KEY_WIDGET_BG_ALPHA, "75").toIntOrNull() ?: 75
        sbAlpha.progress = savedAlpha
        tvAlpha.text = "$savedAlpha%"

        fun highlightSelected(selectedHex: String) {
            swatches.forEach { (viewId, hex) ->
                val v = findViewById<View>(viewId)
                val d = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor(hex))
                    if (hex == selectedHex) {
                        setStroke(3, Color.WHITE)
                    }
                }
                v.background = d
            }
        }

        highlightSelected(currentColor)

        swatches.forEach { (viewId, hex) ->
            findViewById<View>(viewId).setOnClickListener {
                Prefs.set(this, Prefs.KEY_WIDGET_BG_COLOR, hex)
                highlightSelected(hex)
                SyncWidget.updateAll(this)
                SyncWidget2x1.updateAll(this)
            }
        }

        sbAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvAlpha.text = "$progress%"
                if (fromUser) {
                    Prefs.set(this@SettingsActivity, Prefs.KEY_WIDGET_BG_ALPHA, progress.toString())
                    SyncWidget.updateAll(this@SettingsActivity)
                    SyncWidget2x1.updateAll(this@SettingsActivity)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun launchExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "hackdiet_backup.json")
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_EXPORT -> exportSettings(uri)
            REQ_IMPORT -> importSettings(uri)
        }
    }

    private fun exportSettings(uri: Uri) {
        try {
            val json = JSONObject().apply {
                put(Prefs.KEY_GARMIN_API_KEY, Prefs.get(this@SettingsActivity, Prefs.KEY_GARMIN_API_KEY))
                put(Prefs.KEY_INTERVALS_API_KEY, Prefs.get(this@SettingsActivity, Prefs.KEY_INTERVALS_API_KEY))
                put(Prefs.KEY_INTERVALS_ATHLETE_ID, Prefs.get(this@SettingsActivity, Prefs.KEY_INTERVALS_ATHLETE_ID))
                put(Prefs.KEY_SLEEP_FORMAT, Prefs.get(this@SettingsActivity, Prefs.KEY_SLEEP_FORMAT, "hm"))
                put(Prefs.KEY_WIDGET_BG_COLOR, Prefs.get(this@SettingsActivity, Prefs.KEY_WIDGET_BG_COLOR, "#000000"))
                put(Prefs.KEY_WIDGET_BG_ALPHA, Prefs.get(this@SettingsActivity, Prefs.KEY_WIDGET_BG_ALPHA, "75"))
            }
            contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out).use { it.write(json.toString(2)) }
            }
            Toast.makeText(this, "Impostazioni esportate", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore esportazione: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importSettings(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            } ?: return
            val json = JSONObject(text)
            fun maybeSet(key: String) {
                if (json.has(key)) Prefs.set(this, key, json.getString(key))
            }
            maybeSet(Prefs.KEY_GARMIN_API_KEY)
            maybeSet(Prefs.KEY_INTERVALS_API_KEY)
            maybeSet(Prefs.KEY_INTERVALS_ATHLETE_ID)
            maybeSet(Prefs.KEY_SLEEP_FORMAT)
            maybeSet(Prefs.KEY_WIDGET_BG_COLOR)
            maybeSet(Prefs.KEY_WIDGET_BG_ALPHA)

            // Aggiorna i campi visibili
            findViewById<EditText>(R.id.et_garmin_key).setText(Prefs.get(this, Prefs.KEY_GARMIN_API_KEY))
            findViewById<EditText>(R.id.et_intervals_key).setText(Prefs.get(this, Prefs.KEY_INTERVALS_API_KEY))
            findViewById<EditText>(R.id.et_athlete_id).setText(Prefs.get(this, Prefs.KEY_INTERVALS_ATHLETE_ID))
            val fmt = Prefs.get(this, Prefs.KEY_SLEEP_FORMAT, "hm")
            findViewById<RadioGroup>(R.id.rg_sleep_format).check(if (fmt == "min") R.id.rb_min else R.id.rb_hm)

            setupBgColorPicker()
            SyncWidget.updateAll(this)
            SyncWidget2x1.updateAll(this)
            Toast.makeText(this, "Impostazioni importate", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore importazione: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pollWorkState(id: java.util.UUID, btn: Button, originalLabel: String) {
        handler.postDelayed({
            val info = try {
                WorkManager.getInstance(this).getWorkInfoById(id).get()
            } catch (_: Exception) { null }

            if (info != null && info.state.isFinished) {
                btn.isEnabled = true
                btn.text = originalLabel
                refreshLog()
                val result = Prefs.get(this, Prefs.KEY_LAST_RESULT, "")
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            } else {
                pollWorkState(id, btn, originalLabel)
            }
        }, 2000)
    }
}
