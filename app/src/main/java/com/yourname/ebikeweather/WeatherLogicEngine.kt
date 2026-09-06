package com.yourname.ebikeweather

import android.content.Context
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// --- Models ---
data class OpenMeteoResponse(val hourly: HourlyData)
data class HourlyData(
    val time: List<String>,
    @SerializedName("temperature_2m") val temperature2m: List<Double>,
    @SerializedName("precipitation_probability") val precipitationProbability: List<Int>,
    @SerializedName("precipitation") val precipitationMm: List<Double>
)
enum class AlertLevel { IMMEDIATE_THREAT, DELAYED_THREAT, ALL_CLEAR }
data class WeatherAlert(
    val level: AlertLevel,
    val weatherType: String,
    val hoursUntil: Int,
    val rainSeverityScore: Int = 0,
    val precipitationProbabilityPercent: Int = 0,
    val precipitationMmPerHour: Double = 0.0
)

// --- API ---
interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun getHourlyForecast(
        @Query("latitude") lat: Double,
        @Query("longitude") lng: Double,
        @Query("hourly") hourly: String = "temperature_2m,precipitation_probability,precipitation",
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") forecastDays: Int = 2
    ): OpenMeteoResponse

    companion object {
        fun create(): OpenMeteoApi = Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoApi::class.java)
    }
}

// --- Logic ---
class WeatherLogicEngine {
    private val api = OpenMeteoApi.create()

    // Drizzle is ignored by design. We score only meaningful rain rates.
    private val drizzleMaxMmPerHour = 0.2
    private val lightRainUpperMmPerHour = 2.5
    private val moderateRainUpperMmPerHour = 7.6
    private val heavyRainReferenceMmPerHour = 10.0

    suspend fun checkEbikeParkingConditions(context: Context, lat: Double, lng: Double): WeatherAlert {
        try {
            val prefs = context.getSharedPreferences("EBikePrefs", Context.MODE_PRIVATE)
            // A torrential forecast at 30% probability scores about 55 with this formula.
            val severityThreshold = prefs.getInt("RAIN_SEVERITY_THRESHOLD", 55).coerceIn(10, 100)
            val immediateHoursThreshold = prefs.getInt("IMMEDIATE_HOURS_THRESHOLD", 4)

            val response = api.getHourlyForecast(lat, lng)
            val now = LocalDateTime.now()
            val evaluationLimit = now.plusHours(24)
            
            for (i in response.hourly.time.indices) {
                val forecastTime = LocalDateTime.parse(response.hourly.time[i])
                
                if (forecastTime.isAfter(now) && forecastTime.isBefore(evaluationLimit)) {
                    val precipMmPerHour = response.hourly.precipitationMm[i]
                    if (precipMmPerHour <= drizzleMaxMmPerHour) {
                        continue
                    }

                    val probabilityPercent = response.hourly.precipitationProbability[i].coerceIn(0, 100)
                    val severityScore = computeRainSeverityScore(probabilityPercent, precipMmPerHour)
                    if (severityScore >= severityThreshold) {
                        val tempC = response.hourly.temperature2m[i]
                        val weatherType = if (tempC <= 0.0) "Snow" else "Rain"
                        val hoursUntil = ChronoUnit.HOURS.between(now, forecastTime).toInt()

                        return if (hoursUntil <= immediateHoursThreshold) {
                            WeatherAlert(
                                AlertLevel.IMMEDIATE_THREAT,
                                weatherType,
                                hoursUntil,
                                severityScore,
                                probabilityPercent,
                                precipMmPerHour
                            )
                        } else {
                            WeatherAlert(
                                AlertLevel.DELAYED_THREAT,
                                weatherType,
                                hoursUntil,
                                severityScore,
                                probabilityPercent,
                                precipMmPerHour
                            )
                        }
                    }
                }
            }
            return WeatherAlert(AlertLevel.ALL_CLEAR, "None", 0)
        } catch (e: Exception) {
            e.printStackTrace()
            return WeatherAlert(AlertLevel.IMMEDIATE_THREAT, "Rain/Snow", 0)
        }
    }

    private fun computeRainSeverityScore(probabilityPercent: Int, precipMmPerHour: Double): Int {
        val probabilityNorm = (probabilityPercent / 100.0).coerceIn(0.0, 1.0)
        val intensityNorm = normalizeIntensity(precipMmPerHour)
        return (100.0 * sqrt(probabilityNorm * intensityNorm)).roundToInt().coerceIn(0, 100)
    }

    private fun normalizeIntensity(precipMmPerHour: Double): Double {
        if (precipMmPerHour <= drizzleMaxMmPerHour) {
            return 0.0
        }

        val scaled = (precipMmPerHour - drizzleMaxMmPerHour) /
            (heavyRainReferenceMmPerHour - drizzleMaxMmPerHour)
        return scaled.coerceIn(0.0, 1.0)
    }

    fun classifyRainAmount(precipMmPerHour: Double): String {
        return when {
            precipMmPerHour <= drizzleMaxMmPerHour -> "Drizzle"
            precipMmPerHour <= lightRainUpperMmPerHour -> "Light rain"
            precipMmPerHour <= moderateRainUpperMmPerHour -> "Moderate rain"
            else -> "Heavy rain"
        }
    }
}