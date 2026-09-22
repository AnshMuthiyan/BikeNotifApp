package com.anshmuthiyan.ebikeweather

import android.content.Context
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
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

    suspend fun checkEbikeParkingConditions(context: Context, lat: Double, lng: Double): WeatherAlert {
        try {
            val prefs = context.getSharedPreferences("EBikePrefs", Context.MODE_PRIVATE)
            // A torrential forecast at 30% probability scores about 55 with this formula.
            val severityThreshold = prefs.getInt("RAIN_SEVERITY_THRESHOLD", 55).coerceIn(10, 100)
            val response = api.getHourlyForecast(lat, lng)
            val now = LocalDateTime.now()
            val evaluationLimit = CalendarWeatherWindow.forecastEnd(context, now)
            
            for (i in response.hourly.time.indices) {
                val forecastTime = LocalDateTime.parse(response.hourly.time[i])
                
                if (forecastTime.isAfter(now) && forecastTime.isBefore(evaluationLimit)) {
                    val precipMmPerHour = response.hourly.precipitationMm[i]
                    if (precipMmPerHour <= RainScorePolicy.drizzleMaxMmPerHour) {
                        continue
                    }

                    val probabilityPercent = response.hourly.precipitationProbability[i].coerceIn(0, 100)
                    val severityScore = RainScorePolicy.score(probabilityPercent, precipMmPerHour)
                    if (severityScore >= severityThreshold) {
                        val tempC = response.hourly.temperature2m[i]
                        val weatherType = if (tempC <= 0.0) "Snow" else "Rain"
                        val hoursUntil = ChronoUnit.HOURS.between(now, forecastTime).toInt()

                        val alert = WeatherAlert(
                            AlertLevel.IMMEDIATE_THREAT,
                            weatherType,
                            hoursUntil,
                            severityScore,
                            probabilityPercent,
                            precipMmPerHour
                        )
                        cacheAlert(prefs, alert)
                        return alert
                    }
                }
            }
            val clearAlert = WeatherAlert(AlertLevel.ALL_CLEAR, "None", 0)
            cacheAlert(prefs, clearAlert)
            return clearAlert
        } catch (e: Exception) {
            e.printStackTrace()
            return getCachedAlert(context)
        }
    }

    private fun cacheAlert(prefs: android.content.SharedPreferences, alert: WeatherAlert) {
        prefs.edit()
            .putString("CACHED_ALERT_LEVEL", alert.level.name)
            .putString("CACHED_WEATHER_TYPE", alert.weatherType)
            .putInt("CACHED_HOURS_UNTIL", alert.hoursUntil)
            .putInt("CACHED_SEVERITY_SCORE", alert.rainSeverityScore)
            .putInt("CACHED_PROBABILITY", alert.precipitationProbabilityPercent)
            .putFloat("CACHED_PRECIP_MM", alert.precipitationMmPerHour.toFloat())
            .putLong("CACHED_TIMESTAMP", System.currentTimeMillis())
            .apply()
    }

    private fun getCachedAlert(context: Context): WeatherAlert {
        val prefs = context.getSharedPreferences("EBikePrefs", Context.MODE_PRIVATE)
        val ts = prefs.getLong("CACHED_TIMESTAMP", 0L)
        // If cache is older than 6 hours, or doesn't exist, assume all clear
        if (System.currentTimeMillis() - ts > 6 * 60 * 60 * 1000) {
            return WeatherAlert(AlertLevel.ALL_CLEAR, "None", 0)
        }
        val levelStr = prefs.getString("CACHED_ALERT_LEVEL", AlertLevel.ALL_CLEAR.name) ?: AlertLevel.ALL_CLEAR.name
        return WeatherAlert(
            level = AlertLevel.valueOf(levelStr),
            weatherType = prefs.getString("CACHED_WEATHER_TYPE", "None") ?: "None",
            hoursUntil = prefs.getInt("CACHED_HOURS_UNTIL", 0),
            rainSeverityScore = prefs.getInt("CACHED_SEVERITY_SCORE", 0),
            precipitationProbabilityPercent = prefs.getInt("CACHED_PROBABILITY", 0),
            precipitationMmPerHour = prefs.getFloat("CACHED_PRECIP_MM", 0f).toDouble()
        )
    }

    fun classifyRainAmount(precipMmPerHour: Double): String {
        return when {
            precipMmPerHour <= RainScorePolicy.drizzleMaxMmPerHour -> "Drizzle"
            precipMmPerHour <= RainScorePolicy.lightRainUpperMmPerHour -> "Light rain"
            precipMmPerHour <= RainScorePolicy.moderateRainUpperMmPerHour -> "Moderate rain"
            else -> "Heavy rain"
        }
    }
}