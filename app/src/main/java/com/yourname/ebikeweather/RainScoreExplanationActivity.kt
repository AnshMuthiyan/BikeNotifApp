package com.anshmuthiyan.ebikeweather

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class RainScoreExplanationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rain_score_explanation)

        val prefs = getSharedPreferences("EBikePrefs", MODE_PRIVATE)
        val threshold = prefs.getInt("RAIN_SEVERITY_THRESHOLD", 55).coerceIn(10, 100)
        val intensityRange = String.format(
            Locale.US,
            "%.1f to %.1f mm/h",
            RainScorePolicy.drizzleMaxMmPerHour,
            RainScorePolicy.heavyRainReferenceMmPerHour
        )

        findViewById<TextView>(R.id.tvRainScoreDetails).text = getString(
            R.string.rain_score_explanation,
            threshold,
            intensityRange,
            RainScorePolicy.drizzleMaxMmPerHour,
            RainScorePolicy.lightRainUpperMmPerHour,
            RainScorePolicy.moderateRainUpperMmPerHour,
            RainScorePolicy.heavyRainReferenceMmPerHour
        )
    }
}