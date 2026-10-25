package com.rdsinteractive.divan.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.rdsinteractive.divan.R
import com.rdsinteractive.divan.data.AppInfo
import com.rdsinteractive.divan.data.AppLanguage
import com.rdsinteractive.divan.data.LauncherSettings
import com.rdsinteractive.divan.data.PARENTAL_CONTROL
import com.rdsinteractive.divan.data.TvRowsData
import com.rdsinteractive.divan.data.Place
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

data class SettingsActions(
    val listWallpapers: suspend () -> List<File>,
    val thumbnail: suspend (File) -> ImageBitmap?,
    val setWallpaper: (String) -> Unit,
    val setHidden: (AppInfo, Boolean) -> Unit,
    val setTheme: (String) -> Unit,
    val setLanguage: (AppLanguage) -> Unit,
    val setShowClock: (Boolean) -> Unit,
    val setDockLift: (Int) -> Unit,
    val setWeatherEnabled: (Boolean) -> Unit,
    val setWeatherFahrenheit: (Boolean) -> Unit,
    /** null when the search failed (no network). */
    val searchPlaces: suspend (String) -> List<Place>?,
    val setWeatherPlace: (Place) -> Unit,
    val setWeatherAuto: (Boolean) -> Unit,
    val locateNow: suspend () -> Unit,
    val setNotificationBadge: (Boolean) -> Unit,
    val openNotificationAccess: () -> Unit,
)

private enum class SettingsTab(@StringRes val title: Int) {
    Wallpaper(R.string.tab_wallpaper),
    Apps(R.string.tab_apps),
    Rows(R.string.tab_rows),
    Look(R.string.tab_look),
    Weather(R.string.tab_weather),
    Notifications(R.string.notifications),
    Remote(R.string.tab_remote),
    Power(R.string.tab_power),
    Parental(R.string.tab_parental),
    About(R.string.tab_about),
}

private val tabs = SettingsTab.entries.filter { it != SettingsTab.Parental || PARENTAL_CONTROL }

@Composable
fun SettingsScreen(
    settings: LauncherSettings,
    allApps: List<AppInfo>,
    versionName: String,
    notificationAccess: Boolean,
    remapServiceOn: Boolean,
    actions: SettingsActions,
    remoteActions: RemoteActions,
    parentalActions: ParentalActions,
    autostartActions: AutostartActions,
    tvRows: TvRowsData,
    tvAccess: Boolean,
    rowsActions: RowsActions,
) {
    // Saveable: a language change recreates the activity, and settings come back on the same tab.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val openTab = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Color(0xFF14111A)).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text(stringResource(R.string.settings_title), color = Color.White, fontSize = 28.sp)
        // focusRestorer: coming back to the tabs (or losing focus when an item disappears)
        // lands on the open tab instead of the first one, which would switch tabs.
        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(vertical = 16.dp).focusRestorer()) {
            tabs.forEachIndexed { index, item ->
                Tab(
                    selected = tab == index,
                    onFocus = { tab = index },
                    modifier = if (tab == index) Modifier.focusRequester(openTab) else Modifier,
                ) {
                    Text(stringResource(item.title), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp))
                }
            }
        }
        // Focus starts on the open tab: landing on the first one would switch to it.
        LaunchedEffect(Unit) { runCatching { openTab.requestFocus() } }
        Box(Modifier.fillMaxSize()) {
            when (tabs[tab]) {
                SettingsTab.Wallpaper -> WallpaperTab(settings.wallpaperPath, actions)
                SettingsTab.Apps -> AppsTab(allApps, settings.hidden, actions)
                SettingsTab.Rows -> RowsTab(settings, tvRows, allApps, tvAccess, rowsActions)
                SettingsTab.Look -> LookTab(settings, actions)
                SettingsTab.Weather -> WeatherTab(settings, actions)
                SettingsTab.Notifications -> NotificationsTab(settings, notificationAccess, actions)
                SettingsTab.Remote -> RemoteTab(settings, allApps, remapServiceOn, remoteActions)
                SettingsTab.Power -> AutostartTab(settings, allApps, remapServiceOn, autostartActions)
                SettingsTab.Parental -> ParentalTab(settings, allApps, parentalActions)
                SettingsTab.About -> Text(
                    stringResource(R.string.about_text, versionName),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun WallpaperTab(current: String?, actions: SettingsActions) {
    val files by produceState<List<File>?>(null) { value = actions.listWallpapers() }
    val list = files ?: return
    if (list.isEmpty()) {
        Text(
            stringResource(R.string.wallpaper_empty),
            color = Color.White.copy(alpha = 0.8f),
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(6.dp),
    ) {
        items(list, key = { it.path }) { file ->
            val thumb by produceState<ImageBitmap?>(null, file) { value = actions.thumbnail(file) }
            val shape = RoundedCornerShape(8.dp)
            Surface(
                onClick = { actions.setWallpaper(file.path) },
                shape = ClickableSurfaceDefaults.shape(shape),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                modifier = Modifier.aspectRatio(16f / 9f),
            ) {
                thumb?.let {
                    Image(it, contentDescription = file.nameWithoutExtension, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (file.path == current) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = stringResource(R.string.wallpaper_selected),
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppsTab(allApps: List<AppInfo>, hidden: Set<String>, actions: SettingsActions) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(0.6f)) {
        items(allApps, key = { it.packageName }) { app ->
            val shown = app.packageName !in hidden
            ListItem(
                selected = false,
                onClick = { actions.setHidden(app, shown) },
                // The same picture as in the dock, so it is clear which app is which.
                leadingContent = { AppThumb(app) },
                headlineContent = { Text(app.label) },
                supportingContent = { Text(app.packageName, fontSize = 11.sp) },
                trailingContent = { Switch(checked = shown, onCheckedChange = null) },
            )
        }
    }
}

/** 16:9 like the dock card: a banner fills it, a square icon sits in the middle. */
@Composable
private fun AppThumb(app: AppInfo) {
    val image = app.image
    Box(
        Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFF4F4F6)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image == null -> Unit
            app.isBanner -> Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Image(image, contentDescription = null, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun WeatherTab(settings: LauncherSettings, actions: SettingsActions) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var searchFailed by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    // A newer search cancels the older one, so a late answer never replaces the newer list (DV-036).
    val search = {
        searchJob?.cancel()
        searchJob = scope.launch {
            val found = actions.searchPlaces(query)
            searchFailed = found == null
            results = found.orEmpty()
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(0.6f)) {
        item {
            ListItem(
                selected = false,
                onClick = { actions.setWeatherEnabled(!settings.weatherEnabled) },
                headlineContent = { Text(stringResource(R.string.weather_on_home)) },
                supportingContent = { Text(stringResource(R.string.weather_online_note), fontSize = 11.sp) },
                trailingContent = { Switch(checked = settings.weatherEnabled, onCheckedChange = null) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = { actions.setWeatherFahrenheit(!settings.weatherFahrenheit) },
                headlineContent = { Text(stringResource(R.string.weather_units)) },
                trailingContent = { Text(if (settings.weatherFahrenheit) "°F" else "°C") },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = { actions.setWeatherAuto(!settings.weatherAuto) },
                headlineContent = { Text(stringResource(R.string.weather_auto)) },
                supportingContent = {
                    Text(
                        settings.weatherPlaceSource?.let { stringResource(R.string.weather_auto_now, stringResource(it.title)) }
                            ?: stringResource(R.string.weather_auto_sources),
                        fontSize = 11.sp,
                    )
                },
                trailingContent = { Switch(checked = settings.weatherAuto, onCheckedChange = null) },
            )
        }
        if (settings.weatherAuto) {
            item {
                ListItem(
                    selected = false,
                    onClick = { scope.launch { actions.locateNow() } },
                    headlineContent = { Text(stringResource(R.string.weather_locate_now)) },
                )
            }
        }
        item {
            Text(
                stringResource(R.string.weather_city, placeName(settings.weatherPlace.name), settings.weatherPlace.region),
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
        }
        item {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                decorationBox = { field ->
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        if (query.isEmpty()) Text(stringResource(R.string.weather_search_hint), color = Color.White.copy(alpha = 0.5f), fontSize = 16.sp)
                        field()
                    }
                },
            )
        }
        results?.let { found ->
            if (found.isEmpty()) {
                val message = if (searchFailed) R.string.weather_search_offline else R.string.weather_search_empty
                item { Text(stringResource(message), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(8.dp)) }
            }
            // By position: the geocoder may return two places with the same coordinates.
            items(found.size, key = { "place_$it" }) { index ->
                val place = found[index]
                ListItem(
                    selected = false,
                    onClick = {
                        actions.setWeatherPlace(place)
                        results = null
                        query = ""
                    },
                    headlineContent = { Text(place.name) },
                    supportingContent = { Text(place.region, fontSize = 11.sp) },
                )
            }
        }
    }
}

@Composable
private fun NotificationsTab(settings: LauncherSettings, access: Boolean, actions: SettingsActions) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(0.6f)) {
        item {
            ListItem(
                selected = false,
                onClick = actions.openNotificationAccess,
                headlineContent = { Text(stringResource(if (access) R.string.notif_access_on else R.string.notif_access_off)) },
                supportingContent = {
                    Text(
                        stringResource(if (access) R.string.notif_access_on_hint else R.string.notif_access_off_hint),
                        fontSize = 11.sp,
                    )
                },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = { actions.setNotificationBadge(!settings.notificationBadge) },
                headlineContent = { Text(stringResource(R.string.notif_badge)) },
                supportingContent = { Text(stringResource(R.string.notif_badge_hint), fontSize = 11.sp) },
                trailingContent = { Switch(checked = settings.notificationBadge, onCheckedChange = null) },
            )
        }
    }
}

/** Dock heights offered in «Оформление»: % of the screen height under the dock. */
private val DOCK_LIFTS = listOf(
    6 to R.string.dock_lift_bottom,
    12 to R.string.dock_lift_lower,
    19 to R.string.dock_lift_middle,
    26 to R.string.dock_lift_higher,
    32 to R.string.dock_lift_high,
)

@Composable
private fun LookTab(settings: LauncherSettings, actions: SettingsActions) {
    var pickingLanguage by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(0.6f)) {
        item {
            ListItem(
                selected = false,
                onClick = { pickingLanguage = true },
                headlineContent = { Text(stringResource(R.string.look_language)) },
                trailingContent = { Text(stringResource(settings.language.title)) },
            )
        }
        item {
            // One row with the current value, like the dock height: two rows «Тема: …» did not
            // say which one was on.
            val themes = DivanThemes.all
            val current = themes.indexOfFirst { it.id == settings.themeId }.coerceAtLeast(0)
            ListItem(
                selected = false,
                onClick = { actions.setTheme(themes[(current + 1) % themes.size].id) },
                headlineContent = { Text(stringResource(R.string.look_theme)) },
                supportingContent = { Text(stringResource(R.string.look_theme_hint), fontSize = 11.sp) },
                trailingContent = { Text(stringResource(themes[current].title)) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = {
                    val i = DOCK_LIFTS.indexOfFirst { it.first == settings.dockLiftPercent }
                    actions.setDockLift(DOCK_LIFTS[(i + 1) % DOCK_LIFTS.size].first)
                },
                headlineContent = { Text(stringResource(R.string.look_dock_height)) },
                supportingContent = { Text(stringResource(R.string.ok_next), fontSize = 11.sp) },
                trailingContent = {
                    val lift = DOCK_LIFTS.firstOrNull { it.first == settings.dockLiftPercent }
                    Text(lift?.let { stringResource(it.second) } ?: "${settings.dockLiftPercent} %")
                },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = { actions.setShowClock(!settings.showClock) },
                headlineContent = { Text(stringResource(R.string.look_clock)) },
                trailingContent = { Switch(checked = settings.showClock, onCheckedChange = null) },
            )
        }
    }

    if (pickingLanguage) {
        // Each language under its own name; the dialog opens on the current one.
        DialogColumn(title = stringResource(R.string.look_language), onDismiss = { pickingLanguage = false }) { first ->
            AppLanguage.entries.forEach { language ->
                MenuButton(stringResource(language.title), first.takeIf { language == settings.language }) {
                    pickingLanguage = false
                    if (language != settings.language) actions.setLanguage(language)
                }
            }
        }
    }
}
