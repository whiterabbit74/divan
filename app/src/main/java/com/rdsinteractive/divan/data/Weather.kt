package com.rdsinteractive.divan.data

import androidx.annotation.StringRes
import com.rdsinteractive.divan.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import java.net.URLEncoder
import kotlin.math.roundToInt

data class Place(val name: String, val region: String, val latitude: Double, val longitude: Double)

data class Weather(
    val temperature: Int,
    val high: Int,
    val low: Int,
    val condition: WeatherCondition,
    val isDay: Boolean,
)

/**
 * The last weather on disk, shown right after boot while there is no network yet: without it
 * the weather pill (and the city with it) was gone for the first minutes. A plain file, not
 * DataStore: a write every half hour would wake every settings observer.
 */
class WeatherCache(private val file: File) {
    fun save(place: Place, fahrenheit: Boolean, weather: Weather) {
        runCatching {
            file.writeText(
                JSONObject()
                    .put("lat", place.latitude).put("lon", place.longitude).put("f", fahrenheit)
                    .put("at", System.currentTimeMillis())
                    .put("t", weather.temperature).put("hi", weather.high).put("lo", weather.low)
                    .put("c", weather.condition.name).put("day", weather.isDay)
                    .toString(),
            )
        }
    }

    /** Only for the same place and units, and not older than [maxAgeMs]: stale weather misleads. */
    fun load(place: Place, fahrenheit: Boolean, maxAgeMs: Long): Weather? = runCatching {
        val json = JSONObject(file.readText())
        val samePlace = abs(json.getDouble("lat") - place.latitude) < 0.05 && abs(json.getDouble("lon") - place.longitude) < 0.05
        if (!samePlace || json.getBoolean("f") != fahrenheit) return null
        if (System.currentTimeMillis() - json.getLong("at") > maxAgeMs) return null
        Weather(
            temperature = json.getInt("t"),
            high = json.getInt("hi"),
            low = json.getInt("lo"),
            condition = WeatherCondition.valueOf(json.getString("c")),
            isDay = json.getBoolean("day"),
        )
    }.getOrNull()
}

enum class WeatherCondition(@StringRes val title: Int) {
    Clear(R.string.condition_clear),
    PartlyCloudy(R.string.condition_partly_cloudy),
    Cloudy(R.string.condition_cloudy),
    Fog(R.string.condition_fog),
    Drizzle(R.string.condition_drizzle),
    Rain(R.string.condition_rain),
    Snow(R.string.condition_snow),
    Thunder(R.string.condition_thunder);

    companion object {
        /** WMO weather interpretation codes, as Open-Meteo reports them. */
        fun fromWmo(code: Int): WeatherCondition = when (code) {
            0 -> Clear
            1, 2 -> PartlyCloudy
            3 -> Cloudy
            45, 48 -> Fog
            in 51..57 -> Drizzle
            in 61..67, in 80..82 -> Rain
            in 71..77, 85, 86 -> Snow
            in 95..99 -> Thunder
            else -> Cloudy
        }
    }
}

/**
 * Weather from Open-Meteo: no key or account; requests carry the chosen city's coordinates and
 * nothing about the device. The launcher's other network use is the city by IP and row posters.
 */
class WeatherRepository {
    suspend fun current(place: Place, fahrenheit: Boolean): Weather = withContext(Dispatchers.IO) {
        val unit = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val json = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=1$unit",
        )
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        Weather(
            temperature = current.getDouble("temperature_2m").roundToInt(),
            high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            condition = WeatherCondition.fromWmo(current.getInt("weather_code")),
            isDay = current.optInt("is_day", 1) == 1,
        )
    }

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        val name = URLEncoder.encode(query.trim(), "UTF-8")
        val results = get("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=8&language=${AppLocale.geoLanguage()}")
            .optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).map { index ->
            val item = results.getJSONObject(index)
            Place(
                name = item.getString("name"),
                region = listOfNotNull(item.optString("admin1").ifBlank { null }, item.optString("country").ifBlank { null })
                    .joinToString(", "),
                latitude = item.getDouble("latitude"),
                longitude = item.getDouble("longitude"),
            )
        }
    }

    private fun get(url: String): JSONObject = JSONObject(String(httpGet(url, 10_000)))
}
