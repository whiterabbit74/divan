package com.rdsinteractive.divan.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.annotation.StringRes
import com.rdsinteractive.divan.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/** [accuracyM]: how far off the source typically is, when the fix itself does not say. */
enum class PlaceSource(@StringRes val title: Int, val accuracyM: Float) {
    Device(R.string.place_source_device, 1_000f),
    Ip(R.string.place_source_ip, 25_000f),
    TimeZone(R.string.place_source_time_zone, 300_000f),
    Manual(R.string.place_source_manual, 0f),
}

/** [accuracyM]: the fix's own error radius (metres) when Android reports one. */
data class ResolvedPlace(val place: Place, val source: PlaceSource, val accuracyM: Float = source.accuracyM)

/**
 * Finds the TV's city without GPS. Best first:
 * 1. Android location — Google's Wi-Fi positioning, metres-accurate on the Chromecast;
 * 2. IP geolocation — tens of km off on a phone hotspot (carrier gateway);
 * 3. the time zone's main city.
 */
class LocationResolver(private val context: Context) {
    suspend fun resolve(): ResolvedPlace? =
        deviceLocation()?.let { location ->
            ResolvedPlace(
                reverseGeocode(location.latitude, location.longitude),
                PlaceSource.Device,
                if (location.hasAccuracy()) location.accuracy else PlaceSource.Device.accuracyM,
            )
        } ?: ipLocation() ?: timeZonePlace()

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun deviceLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        val ageMs = last?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
        if (last != null && ageMs != null && ageMs < FRESH_MS) return last

        val provider = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return last
        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { continuation.resume(it) }
            }
        }
        return fresh ?: last
    }

    private suspend fun reverseGeocode(latitude: Double, longitude: Double): Place = withContext(Dispatchers.IO) {
        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.forLanguageTag(AppLocale.geoLanguage())).getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }.getOrNull()
        Place(
            name = address?.locality ?: address?.subAdminArea ?: address?.adminArea ?: UNNAMED,
            region = listOfNotNull(address?.adminArea, address?.countryName).joinToString(", "),
            latitude = latitude,
            longitude = longitude,
        )
    }

    private suspend fun ipLocation(): ResolvedPlace? = withContext(Dispatchers.IO) {
        runCatching {
            val lang = AppLocale.geoLanguage().let { if (it == "zh") "zh-CN" else it }
            val body = httpGet("https://ipwho.is/?lang=$lang&fields=success,city,region,country,latitude,longitude", 8_000)
            val json = JSONObject(String(body))
            if (!json.optBoolean("success")) return@runCatching null
            ResolvedPlace(
                Place(
                    name = json.getString("city"),
                    region = listOf(json.optString("region"), json.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                    latitude = json.getDouble("latitude"),
                    longitude = json.getDouble("longitude"),
                ),
                PlaceSource.Ip,
            )
        }.getOrNull()
    }

    private fun timeZonePlace(): ResolvedPlace? =
        TIME_ZONE_CITIES[TimeZone.getDefault().id]?.let { ResolvedPlace(it.place(AppLocale.text(context)), PlaceSource.TimeZone) }

    companion object {
        /** Name of a place the geocoder could not name (no network yet); the UI shows «Здесь». */
        const val UNNAMED = ""

        private const val FRESH_MS = 6 * 60 * 60 * 1000L
        private const val FIX_TIMEOUT_MS = 20_000L

        private val TIME_ZONE_CITIES = mapOf(
            "America/Los_Angeles" to City(R.string.city_los_angeles, R.string.region_california, 34.0522, -118.2437),
            "America/Denver" to City(R.string.city_denver, R.string.region_colorado, 39.7392, -104.9903),
            "America/Chicago" to City(R.string.city_chicago, R.string.region_illinois, 41.8781, -87.6298),
            "America/New_York" to City(R.string.city_new_york, R.string.region_usa, 40.7128, -74.0060),
            "Europe/Moscow" to City(R.string.city_moscow, R.string.region_russia, 55.7558, 37.6173),
            "Europe/Kyiv" to City(R.string.city_kyiv, R.string.region_ukraine, 50.4501, 30.5234),
            "Europe/Minsk" to City(R.string.city_minsk, R.string.region_belarus, 53.9006, 27.5590),
            "Europe/Berlin" to City(R.string.city_berlin, R.string.region_germany, 52.5200, 13.4050),
            "Europe/London" to City(R.string.city_london, R.string.region_uk, 51.5074, -0.1278),
            "Asia/Almaty" to City(R.string.city_almaty, R.string.region_kazakhstan, 43.2389, 76.8897),
            "Asia/Tbilisi" to City(R.string.city_tbilisi, R.string.region_georgia, 41.7151, 44.8271),
            "Asia/Yerevan" to City(R.string.city_yerevan, R.string.region_armenia, 40.1792, 44.4991),
        )
    }
}

/** A city Divan knows by itself; named in the UI language when it becomes a [Place]. */
class City(@StringRes val name: Int, @StringRes val region: Int, val latitude: Double, val longitude: Double) {
    fun place(context: Context) = Place(context.getString(name), context.getString(region), latitude, longitude)
}
