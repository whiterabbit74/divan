package com.rdsinteractive.divan

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rdsinteractive.divan.data.AppInfo
import com.rdsinteractive.divan.data.AppLanguage
import com.rdsinteractive.divan.data.AppLocale
import com.rdsinteractive.divan.data.AppRepository
import com.rdsinteractive.divan.data.LauncherSettings
import com.rdsinteractive.divan.data.LocationResolver
import com.rdsinteractive.divan.data.Pin
import com.rdsinteractive.divan.data.Place
import com.rdsinteractive.divan.data.PlaceSource
import com.rdsinteractive.divan.data.SettingsStore
import com.rdsinteractive.divan.data.TvProgram
import com.rdsinteractive.divan.data.TvChannel
import com.rdsinteractive.divan.data.decodeSampled
import com.rdsinteractive.divan.data.listImages
import com.rdsinteractive.divan.data.TvRowsData
import com.rdsinteractive.divan.data.TvRowsRepository
import com.rdsinteractive.divan.data.Weather
import com.rdsinteractive.divan.data.WeatherCache
import com.rdsinteractive.divan.data.WeatherRepository
import com.rdsinteractive.divan.remote.RemapRule
import com.rdsinteractive.divan.data.arrangeRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PinCheck {
    data object Ok : PinCheck
    data object Wrong : PinCheck
    data class TooMany(val seconds: Int) : PinCheck
}

data class HomeState(
    val allApps: List<AppInfo> = emptyList(),
    val row: List<AppInfo> = emptyList(),
    val settings: LauncherSettings? = null,
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppRepository(application)
    private val store = SettingsStore(application)
    private val apps = MutableStateFlow<List<AppInfo>>(emptyList())

    val state: StateFlow<HomeState> = combine(apps, store.settings) { apps, settings ->
        HomeState(allApps = apps, row = arrangeRow(apps, settings), settings = settings)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeState())

    private val _wallpaper = MutableStateFlow<ImageBitmap?>(null)
    val wallpaper: StateFlow<ImageBitmap?> = _wallpaper

    private val weatherRepository = WeatherRepository()
    private val locationResolver = LocationResolver(application)
    private val weatherCache = WeatherCache(File(application.filesDir, "weather.json"))
    private val tvRowsRepository = TvRowsRepository(application)
    private val _tvRows = MutableStateFlow(TvRowsData.Empty)
    /** Everything apps published; the home screen applies the per-app switches. */
    val tvRows: StateFlow<TvRowsData> = _tvRows

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather

    init {
        reloadApps()
        viewModelScope.launch {
            store.settings.distinctUntilChangedBy { it.wallpaperPath }.map { it.wallpaperPath }.collectLatest { path ->
                // Right after boot shared storage is not readable yet: keep trying for a while.
                repeat(WALLPAPER_ATTEMPTS) {
                    _wallpaper.value = path?.let { decodeWallpaper(it) }
                    if (path == null || _wallpaper.value != null) return@collectLatest
                    delay(WALLPAPER_RETRY_MS)
                }
            }
        }
        viewModelScope.launch {
            // Picking an icon re-reads the banners; drop(1): the first value is the initial load.
            store.settings.map { it.iconOverrides }.distinctUntilChanged().drop(1).collect { reloadApps() }
        }
        viewModelScope.launch {
            store.settings.map { it.weatherEnabled && it.weatherAuto }.distinctUntilChanged().collectLatest { auto ->
                while (auto) {
                    val source = locateNow()
                    delay(if (source == PlaceSource.Device) LOCATE_REFRESH_MS else LOCATE_RETRY_MS)
                }
            }
        }
        viewModelScope.launch {
            store.settings
                .map { Triple(it.weatherEnabled, it.weatherFahrenheit, it.weatherPlace) }
                .distinctUntilChanged()
                .collectLatest { (enabled, fahrenheit, place) ->
                    // The last weather for this city stays up until a fresh one comes (no network
                    // right after boot); retries are quick at first, the network is usually seconds away.
                    _weather.value = if (enabled) withContext(Dispatchers.IO) { weatherCache.load(place, fahrenheit, WEATHER_CACHE_MAX_AGE_MS) } else null
                    var failures = 0
                    while (enabled) {
                        val result = runCatching { weatherRepository.current(place, fahrenheit) }
                        result.onSuccess { weather ->
                            _weather.value = weather
                            failures = 0
                            withContext(Dispatchers.IO) { weatherCache.save(place, fahrenheit, weather) }
                        }
                        if (result.isFailure) failures++
                        delay(
                            when {
                                result.isSuccess -> WEATHER_REFRESH_MS
                                failures <= WEATHER_QUICK_RETRIES -> WEATHER_QUICK_RETRY_MS
                                else -> WEATHER_RETRY_MS
                            },
                        )
                    }
                }
        }
    }

    private var appsJob: Job? = null

    /** A package change comes as several broadcasts: only the newest reload may finish and win. */
    fun reloadApps() {
        appsJob?.cancel()
        appsJob = viewModelScope.launch {
            val loaded = repository.loadApps(store.settings.first().iconOverrides)
            val installed = loaded.map { it.packageName }.toSet()
            store.seedIfNeeded(installed)
            store.seedYouTubeKey(installed)
            store.seedSettingsCard()
            apps.value = loaded
        }
    }

    /** Wallpaper decoding needs the storage permission; call again once it is granted. */
    fun reloadWallpaper() {
        viewModelScope.launch {
            val path = store.settings.first().wallpaperPath ?: return@launch
            _wallpaper.value = decodeWallpaper(path)
        }
    }

    private var tvRowsJob: Job? = null

    /** Like [reloadApps]: an older load finishing late must not overwrite a newer one (DV-039). */
    fun reloadTvRows() {
        tvRowsJob?.cancel()
        tvRowsJob = viewModelScope.launch { loadTvRows() }
    }

    private suspend fun loadTvRows() {
        val data = tvRowsRepository.load()
        _tvRows.value = data
        // Posters of the rows on screen are fetched now, not while the user scrolls to them.
        val settings = store.settings.first()
        val shown = buildList {
            if (settings.watchNextEnabled) addAll(data.watchNext)
            if (settings.channelsEnabled) data.channels.filter { it.key !in settings.hiddenChannels }.forEach { addAll(it.programs) }
        }.filter { it.packageName in settings.rowSources }
        tvRowsRepository.prefetch(shown.mapNotNull { it.posterUri })
    }
    fun cachedPoster(uri: String): ImageBitmap? = tvRowsRepository.cachedPoster(uri)
    fun programIntent(program: TvProgram): Intent? = tvRowsRepository.intent(program)
    suspend fun poster(uri: String): ImageBitmap? = tvRowsRepository.poster(uri)

    fun launch(packageName: String): Intent? = repository.launchIntent(packageName)
    fun details(app: AppInfo): Intent = repository.detailsIntent(app.packageName)
    fun uninstall(app: AppInfo): Intent = repository.uninstallIntent(app.packageName)

    /** Moves an app one step left (-1) or right (+1) in the visible row. */
    fun move(app: AppInfo, delta: Int) {
        val row = state.value.row.map { it.packageName }.toMutableList()
        val from = row.indexOf(app.packageName)
        val to = from + delta
        if (from < 0 || to !in row.indices) return
        row.add(to, row.removeAt(from))
        viewModelScope.launch { store.setRowOrder(row) }
    }

    /** Moves an app to the start ([toStart]) or the end of the visible row. */
    fun moveToEdge(app: AppInfo, toStart: Boolean) {
        val row = state.value.row.map { it.packageName }.toMutableList()
        if (!row.remove(app.packageName)) return
        if (toStart) row.add(0, app.packageName) else row.add(app.packageName)
        viewModelScope.launch { store.setRowOrder(row) }
    }

    fun setHidden(app: AppInfo, hidden: Boolean) {
        viewModelScope.launch {
            if (!hidden) {
                // A newly shown app goes to the end of the row, not back to its alphabetical slot.
                val row = state.value.row.map { it.packageName }
                store.setRowOrder(row - app.packageName + app.packageName)
            }
            store.setHidden(app.packageName, hidden)
        }
    }

    fun setWallpaper(path: String) = viewModelScope.launch { store.setWallpaper(path) }
    fun setIcon(app: AppInfo, path: String?) = viewModelScope.launch { store.setIcon(app.packageName, path) }

    /** The icon folder for the picker; another language's banners (`x.ru.png` in English) are left out. */
    suspend fun listIcons(): List<File> = withContext(Dispatchers.IO) {
        val lang = AppLocale.locale().language
        listImages(AppRepository.ICONS_DIR).filter { file ->
            val suffix = file.nameWithoutExtension.substringAfterLast('.', "")
            suffix.length != 2 || suffix == lang
        }
    }

    fun setTheme(id: String) = viewModelScope.launch { store.setTheme(id) }

    /** The new language holds at once; the caller then recreates the activity to redraw in it. */
    suspend fun setLanguage(language: AppLanguage) {
        AppLocale.set(language)
        store.setLanguage(language)
        reloadApps() // the settings card's label
        // the city and region were named in the old language: ask again in the new one
        viewModelScope.launch { locateNow() }
    }
    fun setShowClock(show: Boolean) = viewModelScope.launch { store.setShowClock(show) }
    fun setDockLift(percent: Int) = viewModelScope.launch { store.setDockLift(percent) }
    fun setKeepAwake(on: Boolean) = viewModelScope.launch { store.setKeepAwake(on) }
    fun setWeatherEnabled(enabled: Boolean) = viewModelScope.launch { store.setWeatherEnabled(enabled) }
    fun setWeatherFahrenheit(fahrenheit: Boolean) = viewModelScope.launch { store.setWeatherFahrenheit(fahrenheit) }
    fun setWeatherPlace(place: Place) = viewModelScope.launch { store.setWeatherPlace(place, PlaceSource.Manual) }
    fun setRemapEnabled(enabled: Boolean) = viewModelScope.launch { store.setRemapEnabled(enabled) }
    fun setLongPressMs(ms: Int) = viewModelScope.launch { store.setLongPressMs(ms) }
    fun saveRule(rule: RemapRule) = viewModelScope.launch { store.saveRule(rule) }
    fun deleteRule(rule: RemapRule) = viewModelScope.launch { store.deleteRule(rule.id) }

    fun setWeatherAuto(auto: Boolean) = viewModelScope.launch { store.setWeatherAuto(auto) }
    fun setNotificationBadge(show: Boolean) = viewModelScope.launch { store.setNotificationBadge(show) }
    fun setParentalEnabled(enabled: Boolean) = viewModelScope.launch { store.setParentalEnabled(enabled) }
    fun setLockSettings(lock: Boolean) = viewModelScope.launch { store.setLockSettings(lock) }
    fun setAppLocked(packageName: String, locked: Boolean) = viewModelScope.launch { store.setAppLocked(packageName, locked) }
    fun setAutostartApp(packageName: String?) = viewModelScope.launch { store.setAutostartApp(packageName) }
    fun setAutostartDelay(seconds: Int) = viewModelScope.launch { store.setAutostartDelay(seconds) }
    fun setAutostartOnWake(enabled: Boolean) = viewModelScope.launch { store.setAutostartOnWake(enabled) }
    fun setWatchNextEnabled(enabled: Boolean) = viewModelScope.launch { store.setWatchNextEnabled(enabled) }
    fun setChannelsEnabled(enabled: Boolean) = viewModelScope.launch { store.setChannelsEnabled(enabled) }
    fun setRowSource(packageName: String, shown: Boolean) = viewModelScope.launch { store.setRowSource(packageName, shown) }
    fun setChannelShown(channel: TvChannel, shown: Boolean) = viewModelScope.launch { store.setChannelShown(channel.key, shown) }
    suspend fun claimBoot(bootCount: Int): Boolean = store.claimBoot(bootCount)

    suspend fun setPin(pin: String) {
        store.setPin(withContext(Dispatchers.Default) { Pin.hash(pin) })
    }

    fun clearPin() = viewModelScope.launch { store.clearPin() }

    private var failedPins = 0
    private var pinBlockedUntil = 0L

    /** Every [PIN_ATTEMPTS] wrong tries in a row the PIN screen stops accepting input for a while. */
    suspend fun checkPin(pin: String): PinCheck {
        val now = System.currentTimeMillis()
        if (now < pinBlockedUntil) return PinCheck.TooMany(((pinBlockedUntil - now + 999) / 1000).toInt())
        val current = store.settings.first()
        val salt = current.pinSalt ?: return PinCheck.Ok
        val hash = current.pinHash ?: return PinCheck.Ok
        if (withContext(Dispatchers.Default) { Pin.matches(pin, salt, hash) }) {
            failedPins = 0
            return PinCheck.Ok
        }
        failedPins++
        if (failedPins % PIN_ATTEMPTS == 0) {
            pinBlockedUntil = now + PIN_BLOCK_MS
            return PinCheck.TooMany((PIN_BLOCK_MS / 1000).toInt())
        }
        return PinCheck.Wrong
    }

    /** One lookup; saves the city only when it really changed, so weather is not refetched for nothing. */
    suspend fun locateNow(): PlaceSource? {
        val resolved = locationResolver.resolve()
        Log.i("Divan", "location: ${resolved?.place?.name ?: "none"} (${resolved?.source ?: "-"})")
        resolved ?: return null
        val current = store.settings.first()
        if (!current.weatherAuto) return resolved.source
        // Right after boot the geocoder has no network yet and names the place «Здесь»: keep the
        // known city if we have not moved, and ask again soon (null = retry) instead of in hours.
        val unnamed = resolved.place.name == LocationResolver.UNNAMED
        if (unnamed && distanceKm(current.weatherPlace, resolved.place) <= 3) return null
        // A fix renames the city only when it is about as precise as the one that named it.
        // Right after boot Android may give a fix kilometres wide, and near a city line the
        // geocoder then names the wrong side (a suburb across a river became the big city next to
        // it); IP and the time zone are coarser still. A metres-precise fix always
        // names the place, and a city not confirmed for a day yields to anything (TV moved,
        // permission taken). The same name only confirms the city (no weather refetch, DV-035).
        val fresh = System.currentTimeMillis() - current.weatherPlaceTime < FINE_PLACE_FRESH_MS
        val known = current.weatherPlaceAccuracy ?: Float.MAX_VALUE
        val preciseEnough = resolved.accuracyM <= maxOf(known * 2, PRECISE_FIX_M)
        if (fresh && !preciseEnough) return null
        val same = resolved.place.name == current.weatherPlace.name && resolved.place.region == current.weatherPlace.region &&
            resolved.source == current.weatherPlaceSource
        // confirming keeps the best precision seen, so a run of slightly worse fixes cannot widen it
        if (same) store.confirmWeatherPlace(minOf(resolved.accuracyM, known))
        else store.setWeatherPlace(resolved.place, resolved.source, resolved.accuracyM)
        return if (unnamed) null else resolved.source
    }

    fun onLocationPermission(granted: Boolean) {
        if (granted) viewModelScope.launch { locateNow() }
    }

    private fun distanceKm(a: Place, b: Place): Double {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0] / 1000.0
    }

    /** null: the search itself failed (no network), not «nothing found». */
    suspend fun searchPlaces(query: String): List<Place>? =
        if (query.isBlank()) emptyList() else runCatching { weatherRepository.search(query) }.getOrNull()

    suspend fun listWallpapers(): List<File> = withContext(Dispatchers.IO) { listImages(SettingsStore.WALLPAPER_DIR) }

    suspend fun thumbnail(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        decode(file.path, targetWidth = 320)
    }

    private suspend fun decodeWallpaper(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        decode(path, targetWidth = 1920)
    }

    private fun decode(path: String, targetWidth: Int): ImageBitmap? = runCatching {
        decodeSampled(path, targetWidth, Bitmap.Config.RGB_565)?.also { it.prepareToDraw() }?.asImageBitmap()
    }.getOrNull()

    private companion object {
        const val WALLPAPER_ATTEMPTS = 30
        const val WALLPAPER_RETRY_MS = 2_000L
        const val WEATHER_REFRESH_MS = 30 * 60 * 1000L
        const val WEATHER_RETRY_MS = 2 * 60 * 1000L
        /** A fix this precise (m) may always name the city. */
        const val PRECISE_FIX_M = 200f
        const val WEATHER_QUICK_RETRY_MS = 10_000L
        const val WEATHER_QUICK_RETRIES = 12
        const val WEATHER_CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        const val LOCATE_REFRESH_MS = 6 * 60 * 60 * 1000L
        const val LOCATE_RETRY_MS = 30 * 60 * 1000L
        const val FINE_PLACE_FRESH_MS = 24 * 60 * 60 * 1000L
        const val PIN_ATTEMPTS = 5
        const val PIN_BLOCK_MS = 30_000L
    }
}
