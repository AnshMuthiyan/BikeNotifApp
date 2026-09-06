package com.anshmuthiyan.ebikeweather

import retrofit2.http.GET
import retrofit2.http.Query

interface WeatherApiService {
    @GET("weather")
    suspend fun getWeather(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double
    ): WeatherResponse
}

data class WeatherResponse(
    val temperature: Double,
    val rainProbabilityPercent: Int
)
