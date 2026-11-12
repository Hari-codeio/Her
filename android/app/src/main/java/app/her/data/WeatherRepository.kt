package app.her.data

import app.her.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WeatherInfo(val temperatureC: Double, val description: String, val emoji: String, val fetchedAt: Long) {
    val summary: String get() = "${temperatureC.toInt()}°C $description"
}

/**
 * Optional weather for outfit choices, from Open-Meteo (no key). Only the city the user typed is
 * sent, and only when weather-aware outfits are switched on.
 */
class WeatherRepository(private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val _weather = MutableStateFlow<WeatherInfo?>(null)
    val weather: StateFlow<WeatherInfo?> = _weather.asStateFlow()

    fun start() {
        scope.launch {
            settings.settings.map { Triple(it.weatherEnabled, it.weatherLat, it.weatherLon) }.distinctUntilChanged().collect { (on, lat, lon) ->
                if (!on || lat == null || lon == null) _weather.value = null else refresh(lat, lon)
            }
        }
    }

    fun refreshIfStale() {
        val s = settings.settings.value
        val lat = s.weatherLat ?: return
        val lon = s.weatherLon ?: return
        if (!s.weatherEnabled) return
        val age = System.currentTimeMillis() - (_weather.value?.fetchedAt ?: 0L)
        if (age > 60 * 60 * 1000L) scope.launch { refresh(lat, lon) }
    }

    /** Resolves a city name to coordinates and stores it. Returns the resolved place name, or null. */
    suspend fun setCity(city: String): String? = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city.trim(), "UTF-8")
        val body = runCatching { fetch(url) }.getOrNull() ?: return@withContext null
        val result = runCatching {
            app.her.data.remote.HerJson.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull() ?: return@withContext null
        val lat = result["latitude"]?.jsonPrimitive?.double ?: return@withContext null
        val lon = result["longitude"]?.jsonPrimitive?.double ?: return@withContext null
        val name = listOfNotNull(result["name"]?.jsonPrimitive?.content, result["country"]?.jsonPrimitive?.content).joinToString(", ")
        settings.updateNow { it.copy(weatherCity = name, weatherLat = lat, weatherLon = lon, weatherEnabled = true, southernHemisphere = lat < 0) }
        name
    }

    private suspend fun refresh(lat: Double, lon: Double) = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"
        val body = runCatching { fetch(url) }.getOrNull() ?: return@withContext
        runCatching {
            val current = app.her.data.remote.HerJson.parseToJsonElement(body).jsonObject["current"]!!.jsonObject
            val temp = current["temperature_2m"]!!.jsonPrimitive.double
            val code = current["weather_code"]!!.jsonPrimitive.int
            val (desc, emoji) = describe(code)
            _weather.value = WeatherInfo(temp, desc, emoji, System.currentTimeMillis())
        }
    }

    private fun fetch(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }

    private fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "clear" to "☀️"
        1, 2 -> "partly cloudy" to "⛅"
        3 -> "cloudy" to "☁️"
        45, 48 -> "foggy" to "🌫️"
        in 51..67 -> "rainy" to "🌧️"
        in 71..77 -> "snowy" to "❄️"
        in 80..82 -> "showers" to "🌦️"
        in 95..99 -> "stormy" to "⛈️"
        else -> "mild" to "🌤️"
    }
}
