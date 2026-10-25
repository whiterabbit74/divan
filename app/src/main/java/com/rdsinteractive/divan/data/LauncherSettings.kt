package com.rdsinteractive.divan.data

import android.content.Context
import android.view.KeyEvent
import com.rdsinteractive.divan.R
import com.rdsinteractive.divan.remote.Press
import com.rdsinteractive.divan.remote.RemapAction
import com.rdsinteractive.divan.remote.RemapRule
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "divan")

/**
 * Parental control is set aside until the PIN can't be bypassed (see HANDOFF.md). Off: the
 * «Защита» tab is hidden and no lock is ever asked, even if a PIN was stored earlier.
 */
const val PARENTAL_CONTROL = false

data class LauncherSettings(
    val wallpaperPath: String?,
    /** Explicit row order; apps not listed (newly installed) follow in label order. */
    val rowOrder: List<String>,
    val hidden: Set<String>,
    val themeId: String,
    val showClock: Boolean,
    val weatherEnabled: Boolean,
    val weatherFahrenheit: Boolean,
    val weatherPlace: Place,
    /** Find the city by itself (Wi-Fi location → IP → time zone); off once the user picks one. */
    val weatherAuto: Boolean,
    val weatherPlaceSource: PlaceSource?,
    /** When the automatic city was last confirmed by its source (ms); 0 if never. */
    val weatherPlaceTime: Long,
    /** Error radius (m) of the fix that named the city; null: not known (set before 0.7.1). */
    val weatherPlaceAccuracy: Float?,
    /** package → icon file; an empty path pins the app's own banner. */
    val iconOverrides: Map<String, String>,
    /** Master switch of the remote-key remapping (the accessibility service stays on). */
    val remapEnabled: Boolean,
    val remapRules: List<RemapRule>,
    val longPressMs: Int,
    /** Bell with the notification count in the status pill (needs notification access). */
    val notificationBadge: Boolean,
    val parentalEnabled: Boolean,
    /** Salted PBKDF2 of the parental PIN, see [Pin]; null until one is set. */
    val pinSalt: String?,
    val pinHash: String?,
    val lockedApps: Set<String>,
    /** Divan's own settings (and the card menu) ask for the PIN too. */
    val lockSettings: Boolean,
    /** App to open when the TV is switched on; null: stay on the home screen. */
    val autostartApp: String?,
    val autostartDelaySec: Int,
    /** Also when the TV wakes from standby, not only after a reboot (needs the remote service). */
    val autostartOnWake: Boolean,
    /** «Смотреть далее» and «Каналы» rows above the dock; off until the user turns them on. */
    val watchNextEnabled: Boolean,
    val channelsEnabled: Boolean,
    /** Apps whose channels and Watch Next entries are shown; each one is switched on by hand. */
    val rowSources: Set<String>,
    /** Channels the user switched off inside a shown app ([TvChannel.key]); new channels show up. */
    val hiddenChannels: Set<String>,
    /** Gap under the dock, % of the screen height: how high the app row sits. */
    val dockLiftPercent: Int,
    /** Keep the TV awake: no screensaver, no sleep when idle (the remote service holds a wake lock). */
    val keepAwake: Boolean,
    val language: AppLanguage,
) {
    val hasPin: Boolean get() = pinHash != null && pinSalt != null
    val parentalActive: Boolean get() = PARENTAL_CONTROL && parentalEnabled && hasPin
    fun isLocked(packageName: String): Boolean = parentalActive && packageName in lockedApps
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val wallpaper = stringPreferencesKey("wallpaper_path")
        val rowOrder = stringPreferencesKey("row_order")
        val hidden = stringSetPreferencesKey("hidden")
        val theme = stringPreferencesKey("theme")
        val clock = booleanPreferencesKey("show_clock")
        val weather = booleanPreferencesKey("weather_enabled")
        val fahrenheit = booleanPreferencesKey("weather_fahrenheit")
        val placeName = stringPreferencesKey("weather_place_name")
        val placeRegion = stringPreferencesKey("weather_place_region")
        val placeLat = doublePreferencesKey("weather_place_lat")
        val placeLon = doublePreferencesKey("weather_place_lon")
        val icons = stringSetPreferencesKey("icon_overrides")
        val weatherAuto = booleanPreferencesKey("weather_auto")
        val remapEnabled = booleanPreferencesKey("remap_enabled")
        val remapRules = stringPreferencesKey("remap_rules")
        val longPress = intPreferencesKey("remap_long_press_ms")
        val remapSeeded = booleanPreferencesKey("remap_seeded")
        val youtubeKeySeeded = booleanPreferencesKey("remap_youtube_key_seeded")
        val placeSource = stringPreferencesKey("weather_place_source")
        val placeTime = longPreferencesKey("weather_place_time")
        val placeAccuracy = floatPreferencesKey("weather_place_accuracy_m")
        val seeded = booleanPreferencesKey("seeded")
        val settingsCardSeeded = booleanPreferencesKey("settings_card_seeded")
        val notificationBadge = booleanPreferencesKey("notification_badge")
        val parental = booleanPreferencesKey("parental_enabled")
        val pinSalt = stringPreferencesKey("pin_salt")
        val pinHash = stringPreferencesKey("pin_hash")
        val lockedApps = stringSetPreferencesKey("locked_apps")
        val lockSettings = booleanPreferencesKey("lock_settings")
        val autostartApp = stringPreferencesKey("autostart_app")
        val autostartDelay = intPreferencesKey("autostart_delay_sec")
        val autostartWake = booleanPreferencesKey("autostart_on_wake")
        val autostartBoot = intPreferencesKey("autostart_boot_count")
        val watchNext = booleanPreferencesKey("rows_watch_next")
        val channels = booleanPreferencesKey("rows_channels")
        val rowSources = stringSetPreferencesKey("rows_sources")
        val hiddenChannels = stringSetPreferencesKey("rows_hidden_channels")
        val dockLift = intPreferencesKey("dock_lift_percent")
        val keepAwake = booleanPreferencesKey("keep_awake")
        val language = stringPreferencesKey("language")
    }

    val settings: Flow<LauncherSettings> = context.dataStore.data.map { prefs ->
        LauncherSettings(
            wallpaperPath = prefs[Keys.wallpaper],
            rowOrder = prefs[Keys.rowOrder]?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
            hidden = prefs[Keys.hidden].orEmpty(),
            themeId = prefs[Keys.theme] ?: DEFAULT_THEME,
            showClock = prefs[Keys.clock] ?: true,
            weatherEnabled = prefs[Keys.weather] ?: true,
            weatherFahrenheit = prefs[Keys.fahrenheit] ?: false,
            weatherPlace = Place(
                name = prefs[Keys.placeName] ?: AppLocale.text(context).getString(DEFAULT_PLACE.name),
                region = prefs[Keys.placeRegion] ?: AppLocale.text(context).getString(DEFAULT_PLACE.region),
                latitude = prefs[Keys.placeLat] ?: DEFAULT_PLACE.latitude,
                longitude = prefs[Keys.placeLon] ?: DEFAULT_PLACE.longitude,
            ),
            weatherAuto = prefs[Keys.weatherAuto] ?: true,
            weatherPlaceSource = prefs[Keys.placeSource]?.let { runCatching { PlaceSource.valueOf(it) }.getOrNull() },
            weatherPlaceTime = prefs[Keys.placeTime] ?: 0L,
            weatherPlaceAccuracy = prefs[Keys.placeAccuracy],
            iconOverrides = prefs[Keys.icons].orEmpty()
                .mapNotNull { entry -> entry.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap(),
            remapEnabled = prefs[Keys.remapEnabled] ?: true,
            remapRules = RemapRule.listFromJson(prefs[Keys.remapRules]),
            longPressMs = prefs[Keys.longPress] ?: DEFAULT_LONG_PRESS_MS,
            notificationBadge = prefs[Keys.notificationBadge] ?: true,
            parentalEnabled = prefs[Keys.parental] ?: false,
            pinSalt = prefs[Keys.pinSalt],
            pinHash = prefs[Keys.pinHash],
            lockedApps = prefs[Keys.lockedApps].orEmpty(),
            lockSettings = prefs[Keys.lockSettings] ?: true,
            autostartApp = prefs[Keys.autostartApp],
            autostartDelaySec = prefs[Keys.autostartDelay] ?: DEFAULT_AUTOSTART_DELAY_SEC,
            autostartOnWake = prefs[Keys.autostartWake] ?: false,
            watchNextEnabled = prefs[Keys.watchNext] ?: false,
            channelsEnabled = prefs[Keys.channels] ?: false,
            rowSources = prefs[Keys.rowSources].orEmpty(),
            hiddenChannels = prefs[Keys.hiddenChannels].orEmpty(),
            dockLiftPercent = prefs[Keys.dockLift] ?: DEFAULT_DOCK_LIFT_PERCENT,
            keepAwake = prefs[Keys.keepAwake] ?: false,
            language = languageOf(prefs[Keys.language]),
        )
    }

    /** For [AppLocale.load], which has to know the language before anything is shown. */
    suspend fun language(): AppLanguage = languageOf(context.dataStore.data.first()[Keys.language])

    private fun languageOf(name: String?): AppLanguage =
        name?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.System

    suspend fun setLanguage(language: AppLanguage) = edit { it[Keys.language] = language.name }

    /** First run on this TV: mirror the row that was set up in Projectivy. */
    suspend fun seedIfNeeded(installed: Set<String>) {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.seeded] == true) return@edit
            prefs[Keys.seeded] = true
            prefs[Keys.rowOrder] = DEFAULT_ROW.filter { it in installed }.joinToString("\n")
            prefs[Keys.hidden] = installed - DEFAULT_ROW.toSet()
            if (prefs[Keys.wallpaper] == null) prefs[Keys.wallpaper] = DEFAULT_WALLPAPER
        }
        context.dataStore.edit { prefs ->
            if (prefs[Keys.remapSeeded] == true) return@edit
            prefs[Keys.remapSeeded] = true
            // The one binding the user had in Buttons Remapper, pointed at the Kinopub app that is installed now.
            if ("watch.kino" in installed && prefs[Keys.remapRules] == null) {
                prefs[Keys.remapRules] = RemapRule.listToJson(
                    listOf(RemapRule(keyCode = KeyEvent.KEYCODE_BUTTON_4, press = Press.Single, action = RemapAction.OpenApp, argument = "watch.kino")),
                )
            }
        }
    }

    /**
     * Once: Divan's settings card takes the system Settings' place in the row, and the
     * system app leaves the row (the gear at the top opens it now).
     */
    suspend fun seedSettingsCard() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.settingsCardSeeded] == true) return@edit
            prefs[Keys.settingsCardSeeded] = true
            val order = prefs[Keys.rowOrder]?.split("\n")?.filter { it.isNotEmpty() }.orEmpty()
            if (DIVAN !in order) {
                val at = order.indexOf(SYSTEM_SETTINGS)
                prefs[Keys.rowOrder] = (if (at >= 0) order.toMutableList().also { it[at] = DIVAN } else order + DIVAN).joinToString("\n")
            }
            prefs[Keys.hidden] = prefs[Keys.hidden].orEmpty() + SYSTEM_SETTINGS - DIVAN
        }
    }

    /**
     * The remote's YouTube key (BUTTON_3) was handled by the Google TV launcher, which is disabled
     * now; without a rule it does nothing.
     */
    suspend fun seedYouTubeKey(installed: Set<String>) {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.youtubeKeySeeded] == true) return@edit
            prefs[Keys.youtubeKeySeeded] = true
            val rules = RemapRule.listFromJson(prefs[Keys.remapRules])
            if (YOUTUBE in installed && rules.none { it.keyCode == KeyEvent.KEYCODE_BUTTON_3 }) {
                prefs[Keys.remapRules] = RemapRule.listToJson(
                    rules + RemapRule(keyCode = KeyEvent.KEYCODE_BUTTON_3, press = Press.Single, action = RemapAction.OpenApp, argument = YOUTUBE),
                )
            }
        }
    }

    suspend fun setRemapEnabled(enabled: Boolean) = edit { it[Keys.remapEnabled] = enabled }

    suspend fun setLongPressMs(ms: Int) = edit { it[Keys.longPress] = ms }

    suspend fun saveRule(rule: RemapRule) = edit { prefs ->
        val rules = RemapRule.listFromJson(prefs[Keys.remapRules])
            // One rule per key and press type: a new one replaces the old.
            .filterNot { it.id == rule.id || (it.keyCode == rule.keyCode && it.press == rule.press) }
        prefs[Keys.remapRules] = RemapRule.listToJson(rules + rule)
    }

    suspend fun deleteRule(id: String) = edit { prefs ->
        prefs[Keys.remapRules] = RemapRule.listToJson(RemapRule.listFromJson(prefs[Keys.remapRules]).filterNot { it.id == id })
    }

    suspend fun setNotificationBadge(show: Boolean) = edit { it[Keys.notificationBadge] = show }

    /** A new PIN also turns parental control on: that is why one sets it. */
    suspend fun setPin(hashed: Pin.Hashed) = edit { prefs ->
        prefs[Keys.pinSalt] = hashed.salt
        prefs[Keys.pinHash] = hashed.hash
        prefs[Keys.parental] = true
    }

    /** Back to "no PIN": parental control off; the list of locked apps is kept for next time. */
    suspend fun clearPin() = edit { prefs ->
        prefs.remove(Keys.pinSalt)
        prefs.remove(Keys.pinHash)
        prefs[Keys.parental] = false
    }

    suspend fun setParentalEnabled(enabled: Boolean) = edit { it[Keys.parental] = enabled }

    suspend fun setLockSettings(lock: Boolean) = edit { it[Keys.lockSettings] = lock }

    suspend fun setAppLocked(packageName: String, locked: Boolean) = edit { prefs ->
        val current = prefs[Keys.lockedApps].orEmpty()
        prefs[Keys.lockedApps] = if (locked) current + packageName else current - packageName
    }

    suspend fun setAutostartApp(packageName: String?) = edit { prefs ->
        if (packageName == null) prefs.remove(Keys.autostartApp) else prefs[Keys.autostartApp] = packageName
    }

    suspend fun setAutostartDelay(seconds: Int) = edit { it[Keys.autostartDelay] = seconds }

    suspend fun setAutostartOnWake(enabled: Boolean) = edit { it[Keys.autostartWake] = enabled }

    /** True once per boot: the first caller after a reboot gets to run the autostart. */
    suspend fun claimBoot(bootCount: Int): Boolean {
        var first = false
        context.dataStore.edit { prefs ->
            first = prefs[Keys.autostartBoot] != bootCount
            prefs[Keys.autostartBoot] = bootCount
        }
        return first
    }

    suspend fun setWatchNextEnabled(enabled: Boolean) = edit { it[Keys.watchNext] = enabled }

    suspend fun setChannelsEnabled(enabled: Boolean) = edit { it[Keys.channels] = enabled }

    suspend fun setRowSource(packageName: String, shown: Boolean) = edit { prefs ->
        val current = prefs[Keys.rowSources].orEmpty()
        prefs[Keys.rowSources] = if (shown) current + packageName else current - packageName
    }

    suspend fun setChannelShown(key: String, shown: Boolean) = edit { prefs ->
        val current = prefs[Keys.hiddenChannels].orEmpty()
        prefs[Keys.hiddenChannels] = if (shown) current - key else current + key
    }

    suspend fun setKeepAwake(on: Boolean) = edit { it[Keys.keepAwake] = on }

    suspend fun setDockLift(percent: Int) = edit { it[Keys.dockLift] = percent }

    suspend fun setWallpaper(path: String?) = edit { prefs ->
        if (path == null) prefs.remove(Keys.wallpaper) else prefs[Keys.wallpaper] = path
    }

    suspend fun setRowOrder(order: List<String>) = edit { it[Keys.rowOrder] = order.joinToString("\n") }

    suspend fun setHidden(packageName: String, hidden: Boolean) = edit { prefs ->
        val current = prefs[Keys.hidden].orEmpty()
        prefs[Keys.hidden] = if (hidden) current + packageName else current - packageName
    }

    suspend fun setTheme(id: String) = edit { it[Keys.theme] = id }

    suspend fun setShowClock(show: Boolean) = edit { it[Keys.clock] = show }

    suspend fun setWeatherEnabled(enabled: Boolean) = edit { it[Keys.weather] = enabled }

    suspend fun setWeatherFahrenheit(fahrenheit: Boolean) = edit { it[Keys.fahrenheit] = fahrenheit }

    suspend fun setWeatherAuto(auto: Boolean) = edit { it[Keys.weatherAuto] = auto }

    suspend fun setWeatherPlace(place: Place, source: PlaceSource, accuracyM: Float = source.accuracyM) = edit { prefs ->
        if (source == PlaceSource.Manual) prefs[Keys.weatherAuto] = false
        prefs[Keys.placeSource] = source.name
        prefs[Keys.placeTime] = System.currentTimeMillis()
        prefs[Keys.placeAccuracy] = accuracyM
        prefs[Keys.placeName] = place.name
        prefs[Keys.placeRegion] = place.region
        prefs[Keys.placeLat] = place.latitude
        prefs[Keys.placeLon] = place.longitude
    }

    /** The place is still where we are: only its time moves, the weather is not refetched. */
    suspend fun confirmWeatherPlace(accuracyM: Float) = edit {
        it[Keys.placeTime] = System.currentTimeMillis()
        it[Keys.placeAccuracy] = accuracyM
    }

    /** [path] null: back to automatic (`<package>.png` in the icons folder, else the banner). */
    suspend fun setIcon(packageName: String, path: String?) = edit { prefs ->
        val others = prefs[Keys.icons].orEmpty().filterNot { it.startsWith("$packageName\t") }.toSet()
        prefs[Keys.icons] = if (path == null) others else others + "$packageName\t$path"
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    companion object {
        const val DEFAULT_THEME = "classic"
        const val DEFAULT_LONG_PRESS_MS = 500
        const val DEFAULT_AUTOSTART_DELAY_SEC = 5
        /** Between the original 6 % (dock at the bottom) and 32 % (the old Projectivy height). */
        const val DEFAULT_DOCK_LIFT_PERCENT = 19
        const val YOUTUBE = "com.google.android.youtube.tv"
        const val WALLPAPER_DIR = "/sdcard/Pictures/Wallpapers"
        const val DEFAULT_WALLPAPER = "$WALLPAPER_DIR/01_Turquoise_Coast.jpg"
        /** Shown until the first fix (weather needs some place); replaced as soon as one comes. */
        val DEFAULT_PLACE = City(R.string.city_concord, R.string.region_california, 37.97798, -122.03107)
        val DEFAULT_ROW = listOf(
            "watch.kino",
            "com.google.android.youtube.tv",
            "com.esaba.downloader",
            "miada.tv.webbrowser",
            DIVAN,
        )
        const val DIVAN = "com.rdsinteractive.divan"
        const val SYSTEM_SETTINGS = "com.android.tv.settings"
    }
}

/** Visible row: explicit order first, then the rest alphabetically, hidden apps dropped. */
fun arrangeRow(apps: List<AppInfo>, settings: LauncherSettings): List<AppInfo> {
    val byPackage = apps.associateBy { it.packageName }
    val ordered = settings.rowOrder.mapNotNull { byPackage[it] }
    val rest = apps.filter { it.packageName !in settings.rowOrder }
    return (ordered + rest).filter { it.packageName !in settings.hidden }
}
