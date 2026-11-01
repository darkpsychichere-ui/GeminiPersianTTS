package com.example.geminipersiantts

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = getSharedPreferences(GeminiTtsService.PREFS, MODE_PRIVATE)
        val key = findViewById<EditText>(R.id.apiKey)
        val voice = findViewById<EditText>(R.id.voice)
        key.setText(prefs.getString(GeminiTtsService.KEY_API, ""))
        voice.setText(prefs.getString(GeminiTtsService.KEY_VOICE, GeminiTtsService.DEFAULT_VOICE))
        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.edit()
                .putString(GeminiTtsService.KEY_API, key.text.toString().trim())
                .putString(GeminiTtsService.KEY_VOICE, voice.text.toString().trim().ifEmpty { GeminiTtsService.DEFAULT_VOICE })
                .apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
    }
}
