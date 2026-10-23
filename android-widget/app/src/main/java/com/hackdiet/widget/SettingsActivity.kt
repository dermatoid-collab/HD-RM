package com.hackdiet.widget

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
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
    private val handler = Handler(Looper.getMainLooper())

    private val REQ_EXPORT = 1001
    private val REQ_IMPORT = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etGarminKey = findViewById<EditText>(R.id.et_garmin_key)
        val etIntervalsKey = findViewById<EditText>(R.id.et_intervals_key)
        val etAthleteId = findViewById<EditText>(R.id.et_athlete_id)
        val btnSave = findViewById<Button>(R.id.btn_save)
        btnRead = findViewById(R.id.btn_read)
        btnSync = findViewById(R.id.btn_sync)
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

        findViewById<Button>(R.id.btn_export).setOnClickListener { launchExport() }
        findViewById<Button>(R.id.btn_import).setOnClickListener { launchImport() }
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

            // Aggiorna i campi visibili
            findViewById<EditText>(R.id.et_garmin_key).setText(Prefs.get(this, Prefs.KEY_GARMIN_API_KEY))
            findViewById<EditText>(R.id.et_intervals_key).setText(Prefs.get(this, Prefs.KEY_INTERVALS_API_KEY))
            findViewById<EditText>(R.id.et_athlete_id).setText(Prefs.get(this, Prefs.KEY_INTERVALS_ATHLETE_ID))
            val fmt = Prefs.get(this, Prefs.KEY_SLEEP_FORMAT, "hm")
            findViewById<RadioGroup>(R.id.rg_sleep_format).check(if (fmt == "min") R.id.rb_min else R.id.rb_hm)

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
