package com.hackdiet.widget

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class DailyBriefActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_daily_brief)

        val tvContent = findViewById<TextView>(R.id.tv_brief_content)
        val tvDate    = findViewById<TextView>(R.id.tv_brief_date)
        val btnCopy   = findViewById<Button>(R.id.btn_copy)

        val brief = Prefs.get(this, Prefs.KEY_DAILY_BRIEF_LAST, "")

        if (brief.isBlank()) {
            tvContent.text = "Nessun brief generato.\nTorna alle impostazioni e premi \"Genera HS Daily Brief\"."
            tvDate.text = ""
            btnCopy.isEnabled = false
        } else {
            // La prima riga è l'intestazione con timestamp "── Daily Brief dd/MM/yyyy HH:mm ──…"
            val lines = brief.lines()
            tvDate.text = lines.firstOrNull()?.replace("─", "")?.trim() ?: ""
            tvContent.text = lines.drop(1).joinToString("\n").trimStart()
            btnCopy.isEnabled = true
        }

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("HS Daily Brief", brief))
            Toast.makeText(this, "Brief copiato negli appunti", Toast.LENGTH_SHORT).show()
        }
    }
}
