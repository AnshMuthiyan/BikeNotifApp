package com.anshmuthiyan.ebikeweather

import kotlin.math.roundToInt
import kotlin.math.sqrt

object RainScorePolicy {
    const val drizzleMaxMmPerHour = 0.2
    const val lightRainUpperMmPerHour = 2.5
    const val moderateRainUpperMmPerHour = 7.6
    const val heavyRainReferenceMmPerHour = 10.0

    fun score(probabilityPercent: Int, precipitationMmPerHour: Double): Int {
        val probabilityNorm = (probabilityPercent / 100.0).coerceIn(0.0, 1.0)
        val intensityNorm = normalizedIntensity(precipitationMmPerHour)
        return (100.0 * sqrt(probabilityNorm * intensityNorm)).roundToInt().coerceIn(0, 100)
    }

    fun normalizedIntensity(precipitationMmPerHour: Double): Double {
        if (precipitationMmPerHour <= drizzleMaxMmPerHour) {
            return 0.0
        }

        val scaled = (precipitationMmPerHour - drizzleMaxMmPerHour) /
            (heavyRainReferenceMmPerHour - drizzleMaxMmPerHour)
        return scaled.coerceIn(0.0, 1.0)
    }
}