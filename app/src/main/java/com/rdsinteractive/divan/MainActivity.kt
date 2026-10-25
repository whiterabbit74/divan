package com.rdsinteractive.divan

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.rdsinteractive.divan.data.AppLanguage
import com.rdsinteractive.divan.data.AppLocale
import com.rdsinteractive.divan.data.LauncherSettings
import com.rdsinteractive.divan.data.TvRowsData
import com.rdsinteractive.divan.notify.NotificationCenter
import com.rdsinteractive.divan.notify.NotificationService
import com.rdsinteractive.divan.remote.DivanState
import com.rdsinteractive.divan.ui.AppActions
import com.rdsinteractive.divan.ui.AutostartActions
import com.rdsinteractive.divan.ui.ProgramActions
import com.rdsinteractive.divan.ui.ProgramRow
import com.rdsinteractive.divan.ui.RowsActions
import com.rdsinteractive.divan.ui.DivanThemes
import com.rdsinteractive.divan.ui.HomeScreen
import com.rdsinteractive.divan.ui.NotificationActions
import com.rdsinteractive.divan.ui.ParentalActions
import com.rdsinteractive.divan.ui.PinDialog
import com.rdsinteractive.divan.ui.RemoteActions
import com.rdsinteractive.divan.ui.SettingsActions
import com.rdsinteractive.divan.ui.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: LauncherViewModel by viewModels()
    private var showSettings by mutableStateOf(false)
    private var notificationAccess by mutableStateOf(false)
    private var tvAccess by mutableStateOf(false)
    /** Re-read on every resume: the user may come back from the accessibility settings. */
    private var remapServiceOn by mutableStateOf(false)
    /** «Домой» pressed while home: the home screen goes back up to the apps. */
    private var homePresses by mutableIntStateOf(0)
    private var resumed = false

    /** PIN screen waiting for input, and what runs once the PIN is right. */
    private class PinRequest(val title: String, val onSuccess: () -> Unit)
    private var pinRequest by mutableStateOf<PinRequest?>(null)

    /** The PIN was entered during this visit to settings; reset when settings close. */
    private var settingsUnlocked = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = viewModel.reloadApps()
    }

    /** The system's languages as last seen, to notice the user changing them. */
    private var systemLocales = Resources.getSystem().configuration.locales

    override fun attachBaseContext(newBase: Context) {
        AppLocale.load(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.applyDefault()
        // Compose redraws in the new system language by itself; the settings card's label is the
        // view model's. With a language picked in Divan nothing changes (configChanges has locale).
        val locales = Resources.getSystem().configuration.locales
        if (locales != systemLocales && AppLocale.language == AppLanguage.System) viewModel.reloadApps()
        systemLocales = locales
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // A language change recreates the activity: settings stay open on the same tab.
        outState.putBoolean(STATE_SETTINGS, showSettings)
        outState.putBoolean(STATE_UNLOCKED, settingsUnlocked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            showSettings = it.getBoolean(STATE_SETTINGS)
            settingsUnlocked = it.getBoolean(STATE_UNLOCKED)
        }
        requestStorageIfNeeded()
        // Recreated (theme, locale, font scale): the extras were already handled once.
        if (savedInstanceState == null) handleIntent(intent)
        // For the activity's whole life, not only while started: no package change is missed,
        // so returning home need not re-read every app and re-decode its banner.
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })

        val appActions = AppActions(
            launch = { app -> if (app.packageName == packageName) openSettings() else openApp(app.packageName, app.label) },
            move = viewModel::move,
            moveToEdge = viewModel::moveToEdge,
            hide = { app -> viewModel.setHidden(app, true) },
            details = { app -> startSafely(viewModel.details(app)) },
            uninstall = { app -> startSafely(viewModel.uninstall(app)) },
            iconChoices = viewModel::listIcons,
            thumbnail = viewModel::thumbnail,
            setIcon = { app, path -> viewModel.setIcon(app, path) },
            unlockMenu = { block -> withSettings { unlockSettings(it, getString(R.string.pin_for_menu), block, remember = false) } },
        )
        val settingsActions = SettingsActions(
            listWallpapers = viewModel::listWallpapers,
            thumbnail = viewModel::thumbnail,
            setWallpaper = { viewModel.setWallpaper(it) },
            setHidden = viewModel::setHidden,
            setTheme = { viewModel.setTheme(it) },
            setLanguage = { language ->
                lifecycleScope.launch {
                    viewModel.setLanguage(language)
                    recreate()
                }
            },
            setShowClock = { viewModel.setShowClock(it) },
            setDockLift = { viewModel.setDockLift(it) },
            setWeatherEnabled = { viewModel.setWeatherEnabled(it) },
            setWeatherFahrenheit = { viewModel.setWeatherFahrenheit(it) },
            searchPlaces = viewModel::searchPlaces,
            setWeatherPlace = { viewModel.setWeatherPlace(it) },
            setWeatherAuto = { viewModel.setWeatherAuto(it) },
            locateNow = { viewModel.locateNow() },
            setNotificationBadge = { viewModel.setNotificationBadge(it) },
            openNotificationAccess = {
                startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        val parentalActions = ParentalActions(
            unlock = { block -> withSettings { unlockSettings(it, getString(R.string.pin_for_parental), block, always = true) } },
            setPin = { pin ->
                viewModel.setPin(pin)
                settingsUnlocked = true
            },
            clearPin = { viewModel.clearPin() },
            setEnabled = { viewModel.setParentalEnabled(it) },
            setLockSettings = { viewModel.setLockSettings(it) },
            setAppLocked = { pkg, locked -> viewModel.setAppLocked(pkg, locked) },
        )
        val autostartActions = AutostartActions(
            setKeepAwake = { viewModel.setKeepAwake(it) },
            setApp = { viewModel.setAutostartApp(it) },
            setDelay = { viewModel.setAutostartDelay(it) },
            setOnWake = { viewModel.setAutostartOnWake(it) },
        )
        val rowsActions = RowsActions(
            setWatchNext = { on ->
                viewModel.setWatchNextEnabled(on)
                if (on) requestTvAccessIfNeeded()
            },
            setChannels = { on ->
                viewModel.setChannelsEnabled(on)
                if (on) requestTvAccessIfNeeded()
            },
            setSource = viewModel::setRowSource,
            setChannel = viewModel::setChannelShown,
            requestAccess = ::requestTvAccessIfNeeded,
        )
        val programActions = ProgramActions(
            open = { program ->
                guarded(program.packageName) {
                    // A stale deep link that nothing opens: open the app itself (DV-029).
                    val opened = viewModel.programIntent(program)?.let(::startSafely) ?: false
                    if (!opened) viewModel.launch(program.packageName)?.let(::startSafely)
                }
            },
            poster = viewModel::poster,
            cachedPoster = viewModel::cachedPoster,
        )
        val notificationActions = NotificationActions(
            open = { item -> guarded(item.packageName) { NotificationCenter.open(this, item) } },
            dismiss = NotificationCenter::dismiss,
            dismissAll = NotificationCenter::dismissAll,
        )

        val remoteActions = RemoteActions(
            setEnabled = { viewModel.setRemapEnabled(it) },
            setLongPressMs = { viewModel.setLongPressMs(it) },
            saveRule = { viewModel.saveRule(it) },
            deleteRule = { viewModel.deleteRule(it) },
            openAccessibilitySettings = { startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        )

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
            val weather by viewModel.weather.collectAsStateWithLifecycle()
            val notifications by NotificationCenter.notifications.collectAsStateWithLifecycle()
            val tvRows by viewModel.tvRows.collectAsStateWithLifecycle()
            val settings = state.settings ?: return@setContent
            // The window's own black background is only for the moment before the first frame;
            // after that the home screen covers it and drawing it would be one more full-screen pass.
            LaunchedEffect(Unit) { window.setBackgroundDrawable(null) }
            // Switches and the like in Divan's cyan, as the focus frame and the AirPlay receiver.
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF5FE0F5), onPrimary = Color(0xFF14111A))) {
                // A home screen has nowhere to go back to.
                BackHandler(enabled = !showSettings) {}
                BackHandler(enabled = showSettings) { closeSettings() }
                if (showSettings) {
                    SettingsScreen(
                        settings = settings,
                        allApps = state.allApps,
                        versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                        notificationAccess = notificationAccess,
                        remapServiceOn = remapServiceOn,
                        actions = settingsActions,
                        remoteActions = remoteActions,
                        parentalActions = parentalActions,
                        autostartActions = autostartActions,
                        tvRows = tvRows,
                        tvAccess = tvAccess,
                        rowsActions = rowsActions,
                    )
                } else {
                    HomeScreen(
                        row = state.row,
                        wallpaper = wallpaper,
                        theme = DivanThemes.byId(settings.themeId),
                        showClock = settings.showClock,
                        dockLiftPercent = settings.dockLiftPercent,
                        weather = weather,
                        placeName = settings.weatherPlace.name,
                        notifications = notifications.takeIf { notificationAccess && settings.notificationBadge },
                        notificationActions = notificationActions,
                        actions = appActions,
                        programRows = programRows(settings, tvRows),
                        programActions = programActions,
                        homePresses = homePresses,
                        onOpenSettings = ::openSettings,
                        onOpenSystemSettings = {
                            startSafely(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                    )
                }
                pinRequest?.let { request ->
                    PinDialog(
                        title = request.title,
                        onSubmit = { pin ->
                            checkPin(pin).also { problem ->
                                if (problem == null) {
                                    pinRequest = null
                                    request.onSuccess()
                                }
                            }
                        },
                        onDismiss = { pinRequest = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // "Home" pressed while already home: leave settings and any PIN screen.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            closeSettings()
            pinRequest = null
            homePresses++
        }
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) openSettings()
        intent.getStringExtra(EXTRA_UNLOCK_APP)?.let { pkg ->
            val label = viewModel.state.value.allApps.firstOrNull { it.packageName == pkg }?.label ?: pkg
            openApp(pkg, label)
        }
    }

    /** Settings are read from DataStore; never decide on a lock before they are loaded. */
    private fun withSettings(block: (LauncherSettings) -> Unit) {
        lifecycleScope.launch { viewModel.state.first { it.settings != null }.settings?.let(block) }
    }

    private fun openApp(pkg: String, label: String) = guarded(pkg, label) { viewModel.launch(pkg)?.let(::startSafely) }

    /** Everything Divan opens on behalf of an app (card, row, notification, autostart) passes the app's lock. */
    private fun guarded(pkg: String, label: String? = null, start: () -> Unit) = withSettings { settings ->
        val name = label ?: viewModel.state.value.allApps.firstOrNull { it.packageName == pkg }?.label ?: pkg
        if (settings.isLocked(pkg)) pinRequest = PinRequest(getString(R.string.pin_for_app, name), start) else start()
    }

    private fun openSettings() = withSettings { settings ->
        unlockSettings(settings, getString(R.string.pin_for_settings), {
            showSettings = true
            syncHomeShown()
        })
    }

    /** Tells the remote service whether Divan's home screen (not its settings) is in front. */
    private fun syncHomeShown() {
        DivanState.homeShown = resumed && !showSettings
    }

    /**
     * Runs [block] when settings may be changed: protection off, the settings lock off
     * (unless [always]), or the PIN already entered during this visit. [remember] false:
     * the PIN covers this one action only (the card menu, outside settings).
     */
    private fun unlockSettings(
        settings: LauncherSettings,
        title: String,
        block: () -> Unit,
        always: Boolean = false,
        remember: Boolean = true,
    ) {
        val needed = settings.parentalActive && (always || settings.lockSettings) && !settingsUnlocked
        if (!needed) {
            block()
            return
        }
        pinRequest = PinRequest(title) {
            if (remember) settingsUnlocked = true
            block()
        }
    }

    private fun closeSettings() {
        showSettings = false
        settingsUnlocked = false
        syncHomeShown()
    }

    private suspend fun checkPin(pin: String): String? = when (val result = viewModel.checkPin(pin)) {
        PinCheck.Ok -> null
        PinCheck.Wrong -> getString(R.string.pin_wrong)
        is PinCheck.TooMany -> getString(R.string.pin_too_many, result.seconds)
    }

    override fun onResume() {
        super.onResume()
        // The user may have just come back from the notification access screen.
        notificationAccess = getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(NotificationService.component(this)) == true
        tvAccess = checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED
        remapServiceOn = isRemapServiceOn()
        resumed = true
        syncHomeShown()
        autostartAfterBoot()
    }

    override fun onPause() {
        resumed = false
        syncHomeShown()
        super.onPause()
    }

    /** Rows above the dock: only what the user turned on, Watch Next first. */
    private fun programRows(settings: LauncherSettings, data: TvRowsData): List<ProgramRow> = buildList {
        if (settings.watchNextEnabled) {
            val items = data.watchNext.filter { it.packageName in settings.rowSources }
            if (items.isNotEmpty()) add(ProgramRow("watch_next", getString(R.string.watch_next), items))
        }
        if (settings.channelsEnabled) {
            val labels = viewModel.state.value.allApps.associate { it.packageName to it.label }
            data.channels.filter { it.packageName in settings.rowSources && it.key !in settings.hiddenChannels }.forEach { channel ->
                val app = labels[channel.packageName] ?: channel.packageName
                val title = if (channel.name.isBlank() || channel.name == app) app else "$app · ${channel.name}"
                add(ProgramRow("channel_${channel.id}", title, channel.programs))
            }
        }
    }

    private val tvAccessRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tvAccess = granted
        if (granted) viewModel.reloadTvRows()
    }

    private fun requestTvAccessIfNeeded() {
        if (checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            tvAccessRequest.launch(READ_TV_LISTINGS)
        }
    }

    /**
     * After a reboot the system opens Divan as the home screen; the first time per boot it
     * starts the chosen app. Skipped if the user has left the home screen in the meantime.
     */
    private fun autostartAfterBoot() {
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot < 0) return
        lifecycleScope.launch {
            val settings = viewModel.state.first { it.settings != null }.settings ?: return@launch
            val pkg = settings.autostartApp ?: return@launch
            Log.i("Divan", "autostart after boot $boot: $pkg in ${settings.autostartDelaySec} s")
            delay(settings.autostartDelaySec * 1000L)
            // Claimed after the wait: if the activity is recreated meanwhile, this coroutine is
            // cancelled and the new one still gets the boot (DV-028).
            if (!viewModel.claimBoot(boot)) return@launch
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !showSettings && pinRequest == null) {
                guarded(pkg) { viewModel.launch(pkg)?.let(::startSafely) }
            }
        }
    }

    companion object {
        /** A remote key mapped to «Divan settings» opens straight into settings (behind the PIN if set). */
        const val EXTRA_OPEN_SETTINGS = "open_settings"

        /** A remote key aimed at a locked app: ask the PIN, then start the app. */
        const val EXTRA_UNLOCK_APP = "unlock_app"

        private const val STATE_SETTINGS = "settings_open"
        private const val STATE_UNLOCKED = "settings_unlocked"

        /** Runtime permission for other apps' channels; the SDK constant is a hidden system API. */
        private const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
    }

    override fun onStart() {
        super.onStart()
        // Channels and Watch Next change while other apps are in front.
        viewModel.reloadTvRows()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    private val storagePermission = if (android.os.Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[storagePermission] == true) {
            viewModel.reloadWallpaper()
            viewModel.reloadApps() // custom icons live on shared storage too
        }
        viewModel.onLocationPermission(result[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }

    private fun requestStorageIfNeeded() {
        val missing = listOf(storagePermission, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    private fun isRemapServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    /**
     * Links come from other apps (TV rows, notifications): no activity, a protected one or a
     * `file://` link (FileUriExposedException, DV-030) must not take the launcher down.
     */
    private fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (error: RuntimeException) {
        Log.w("Divan", "Cannot start $intent", error)
        false
    }
}
