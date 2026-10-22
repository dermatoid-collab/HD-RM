package com.hackdiet.widget

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etGarminKey = findViewById<EditText>(R.id.et_garmin_key)
        val etIntervalsKey = findViewById<EditText>(R.id.et_intervals_key)
        val etAthleteId = findViewById<EditText>(R.id.et_athlete_id)
        val btnSave = findViewById<Button>(R.id.btn_save)

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
            finish()
        }
    }
}
