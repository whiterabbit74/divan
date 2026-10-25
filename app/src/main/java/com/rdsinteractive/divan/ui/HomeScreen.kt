package com.rdsinteractive.divan.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rdsinteractive.divan.R
import com.rdsinteractive.divan.data.AppInfo
import com.rdsinteractive.divan.data.AppLocale
import com.rdsinteractive.divan.data.Weather
import com.rdsinteractive.divan.data.WeatherCondition
import com.rdsinteractive.divan.notify.NotificationItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.io.File

data class AppActions(
    val launch: (AppInfo) -> Unit,
    val move: (AppInfo, Int) -> Unit,
    /** true: to the start of the row, false: to the end. */
    val moveToEdge: (AppInfo, Boolean) -> Unit,
    val hide: (AppInfo) -> Unit,
    val details: (AppInfo) -> Unit,
    val uninstall: (AppInfo) -> Unit,
    val iconChoices: suspend () -> List<File>,
    val thumbnail: suspend (File) -> ImageBitmap?,
    /** null path: automatic icon; "" : the app's own banner. */
    val setIcon: (AppInfo, String?) -> Unit,
    /** Runs the block once the card menu may open (asks the parental PIN when needed). */
    val unlockMenu: (() -> Unit) -> Unit,
)

@Composable
fun HomeScreen(
    row: List<AppInfo>,
    wallpaper: ImageBitmap?,
    theme: DivanTheme,
    showClock: Boolean,
    dockLiftPercent: Int,
    weather: Weather?,
    placeName: String,
    /** null: no notification access (or the bell is off), the bell is hidden. */
    notifications: List<NotificationItem>?,
    notificationActions: NotificationActions,
    actions: AppActions,
    programRows: List<ProgramRow>,
    programActions: ProgramActions,
    /** Bumped on every «Домой» press while home: go back up to the apps. */
    homePresses: Int,
    onOpenSettings: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    var menuFor by remember { mutableStateOf<AppInfo?>(null) }
    var showNotifications by remember { mutableStateOf(false) }
    var iconPickerFor by remember { mutableStateOf<AppInfo?>(null) }
    val rowOffsets = remember { mutableStateMapOf<String, Int>() }
    val firstCard = remember { FocusRequester() }
    val dock = remember { FocusRequester() }
    val rowsEntry = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    // A fresh list position whenever the rows appear or disappear: if they vanish while scrolled
    // down (the last Watch Next item watched), the page must not stay shifted off the screen.
    val listState = remember(programRows.isEmpty()) { LazyListState() }
    val dockLift = (LocalConfiguration.current.screenHeightDp * dockLiftPercent / 100f).dp
    // Focus is down in the rows: the home page (weather, pills, dock) and the rows move as one
    // sheet, one screen up, in a single animation; back on the dock the sheet moves down again.
    // Nothing scrolls during the move: the rows list sits at its top the whole time, so the only
    // per-frame work is two layer translations (no relayout, no recomposition).
    var rowsFocused by remember(listState) { mutableStateOf(false) }
    val pageOut by animateFloatAsState(
        targetValue = if (rowsFocused) 1f else 0f,
        animationSpec = tween(PAGE_SLIDE_MS, easing = FastOutSlowInEasing),
        label = "page",
    )
    val screenHeightPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // «Назад» anywhere in the rows: straight back up to the apps.
    BackHandler(enabled = rowsFocused) { runCatching { dock.requestFocus() } }
    // Once the rows are off screen, put them back at their first row for the next visit (out of
    // sight, so the jump is never seen, and the first row is composed for the dock to focus).
    LaunchedEffect(rowsFocused) {
        if (!rowsFocused && listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset > 0) {
            delay(PAGE_SLIDE_MS.toLong())
            listState.scrollToItem(0)
        }
    }
    // The rows went away while focused (last Watch Next item done): focus must not be lost.
    LaunchedEffect(programRows.isEmpty()) {
        if (programRows.isEmpty() && initialFocusDone) runCatching { dock.requestFocus() }
    }
    // «Домой» while already home: back up to the apps, like on Google TV.
    LaunchedEffect(homePresses) {
        if (homePresses == 0) return@LaunchedEffect
        // The card menu, icon picker and notifications are this screen's own dialogs (DV-026).
        menuFor = null
        iconPickerFor = null
        showNotifications = false
        runCatching { dock.requestFocus() }
    }

    // The wallpaper (or the gradient) is the only opaque full-screen layer: no window or Box
    // background under it. Measured on the Chromecast HD, tinting the wallpaper draw itself
    // cost the GPU more (20 ms a frame) than a separate dim layer on top (18 ms).
    Box(Modifier.fillMaxSize()) {
        if (wallpaper != null) {
            Image(
                wallpaper,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2B2238), Color(0xFF0E0B14)))))
        }
        // Down in the rows: a flat dim, faded in with the move, keeps posters and titles readable
        // on any wallpaper. Not drawn at all on the home page (one full-screen pass less).
        if (pageOut > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageOut }.background(Color.Black.copy(alpha = 0.45f)))

        // The rows: a full-screen list one screen below the home page. Its own focus moves
        // (row to row) scroll it as usual; the page change only translates it.
        if (programRows.isNotEmpty()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = ROWS_TOP),
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        translationY = (1f - pageOut) * screenHeightPx
                        alpha = pageOut
                    }
                    .onFocusChanged { rowsFocused = it.hasFocus },
            ) {
                programRows(programRows, theme, programActions, entry = rowsEntry, dock = dock, offsets = rowOffsets)
            }
        }

        // The page fades out in the first third of the move, so it is never seen crossing the top
        // of the screen; on the way back it appears only as it settles.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationY = -pageOut * screenHeightPx * PAGE_TRAVEL
                alpha = (1f - pageOut * 3f).coerceIn(0f, 1f)
            },
        ) {
            if (weather != null) {
                WeatherPill(
                    weather = weather,
                    placeName = placeName,
                    theme = theme,
                    modifier = Modifier.align(Alignment.TopStart).padding(top = 28.dp, start = 40.dp),
                )
            }

            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusPill(showClock, theme, notifications?.size, onOpenNotifications = { showNotifications = true })
                // The system settings; Divan's own are a card in the row (long press here too,
                // in case that card was taken off the row).
                GlassIconButton(theme, onClick = onOpenSystemSettings, onLongClick = onOpenSettings)
            }

            GlassPanel(
                corner = theme.dockCorner,
                theme = theme,
                edge = true,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    // Height chosen in «Оформление» (default between the bottom and the old Projectivy height).
                    .padding(start = 40.dp, end = 40.dp, bottom = dockLift),
            ) {
                Dock(
                    row = row,
                    theme = theme,
                    // focusRestorer: back from the rows to the app card left last.
                    modifier = Modifier.focusRequester(dock).focusRestorer().onPreviewKeyEvent { event ->
                        val down = event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown
                        down && programRows.isNotEmpty() && runCatching { rowsEntry.requestFocus() }.isSuccess
                    },
                    firstCard = firstCard,
                    onLaunch = actions.launch,
                    onMenu = { app -> actions.unlockMenu { menuFor = app } },
                )
            }
        }

        menuFor?.let { app ->
            AppMenu(
                app = app,
                // By package: after the apps are re-read the same app is a new object.
                canMoveLeft = row.indexOfFirst { it.packageName == app.packageName } > 0,
                canMoveRight = row.indexOfFirst { it.packageName == app.packageName } in 0 until row.lastIndex,
                actions = actions,
                onChangeIcon = { iconPickerFor = app },
                onDismiss = { menuFor = null },
            )
        }
        iconPickerFor?.let { app ->
            IconPicker(app = app, theme = theme, actions = actions, onDismiss = { iconPickerFor = null })
        }
        if (showNotifications && notifications != null) {
            NotificationsPanel(items = notifications, actions = notificationActions, onDismiss = { showNotifications = false })
        }
    }

    LaunchedEffect(row.isNotEmpty()) {
        if (row.isNotEmpty() && !initialFocusDone) {
            initialFocusDone = runCatching { firstCard.requestFocus() }.isSuccess
        }
    }
}

/**
 * The app row: exactly [DivanTheme.dockCards] whole cards across the panel, the same gap between
 * cards and at the edges. More apps scroll by whole cards, so a cut card never shows.
 */
@Composable
private fun Dock(
    row: List<AppInfo>,
    theme: DivanTheme,
    modifier: Modifier,
    firstCard: FocusRequester,
    onLaunch: (AppInfo) -> Unit,
    onMenu: (AppInfo) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = theme.dockCards
        val gap = ((maxWidth - theme.cardWidth * fit) / (fit + 1)).coerceAtLeast(8.dp)
        val listState = rememberLazyListState()
        var first by remember { mutableIntStateOf(0) }
        var focused by remember { mutableStateOf<String?>(null) }
        var focusedIndex by remember { mutableIntStateOf(0) }
        val requesters = remember { mutableMapOf<String, FocusRequester>() }
        // By package, not index: moving the focused app in the menu changes its index.
        LaunchedEffect(focused, row) {
            var index = row.indexOfFirst { it.packageName == focused }
            if (index >= 0) focusedIndex = index
            // The focused app left the row («Убрать», uninstalled): Compose drops the focus with
            // the card, so hand it to the card that took its place (DV-038).
            if (index < 0 && focused != null && row.isNotEmpty()) {
                index = focusedIndex.coerceAtMost(row.lastIndex)
                // Not `focused = …` here: that would restart this effect before the request;
                // the card's onFocusChanged sets it.
                withFrameNanos { } // the new card is laid out before it is asked to take focus
                runCatching { requesters[row[index].packageName]?.requestFocus() }
            }
            first = when {
                index < 0 -> first
                index < first -> index
                index >= first + fit -> index - fit + 1
                else -> first
            }.coerceIn(0, (row.size - fit).coerceAtLeast(0))
            if (listState.firstVisibleItemIndex != first || listState.firstVisibleItemScrollOffset != 0) {
                listState.animateScrollToItem(first)
            }
        }
        // The list's own scroll-to-focus would stop at any pixel; the effect above scrolls instead.
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) {
            LazyRow(
                state = listState,
                modifier = modifier,
                // Above and below as at the sides, so the cards sit evenly framed (capped for «Компакт»).
                contentPadding = PaddingValues(horizontal = gap, vertical = gap.coerceAtMost(20.dp)),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(row, key = { _, app -> app.packageName }) { index, app ->
                    AppCard(
                        app = app,
                        theme = theme,
                        modifier = (if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
                            .focusRequester(remember(app.packageName) { requesters.getOrPut(app.packageName) { FocusRequester() } })
                            .onFocusChanged { if (it.isFocused) focused = app.packageName },
                        onClick = { onLaunch(app) },
                        onLongClick = { onMenu(app) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val NoBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/** Space above the first row once the page has slid away. */
private val ROWS_TOP = 44.dp
private const val PAGE_SLIDE_MS = 320
/** The page only drifts up a quarter screen while it fades; the rows travel the full screen. */
private const val PAGE_TRAVEL = 0.25f

/**
 * Flat translucent panel: no blur, cheap to draw on the Chromecast's GPU. The hairline edge
 * is for the dock only; the pills at the top go without it.
 */
@Composable
private fun GlassPanel(
    corner: Dp,
    theme: DivanTheme,
    modifier: Modifier,
    edge: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .clip(shape)
            .background(theme.glassTint)
            .then(if (edge) Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), shape) else Modifier),
        content = content,
    )
}

@Composable
private fun WeatherPill(weather: Weather, placeName: String, theme: DivanTheme, modifier: Modifier) {
    GlassPanel(corner = 22.dp, theme = theme, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val (icon, tint) = weatherIcon(weather)
            val condition = stringResource(weather.condition.title)
            Icon(icon, contentDescription = condition, tint = tint, modifier = Modifier.size(40.dp))
            Column {
                Text(placeName(placeName), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                Text("${weather.temperature}°", color = Color.White, fontSize = 34.sp)
            }
            Column {
                Text(condition, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                Text(
                    stringResource(R.string.weather_high_low, weather.high, weather.low),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** A place the geocoder could not name is stored without a name: «Здесь». */
@Composable
internal fun placeName(name: String): String = name.ifEmpty { stringResource(R.string.place_here) }

private fun weatherIcon(weather: Weather): Pair<ImageVector, Color> = when (weather.condition) {
    WeatherCondition.Clear ->
        if (weather.isDay) Icons.Outlined.WbSunny to Color(0xFFFFD34E) else Icons.Outlined.Bedtime to Color(0xFFE6E9F5)
    WeatherCondition.PartlyCloudy -> Icons.Outlined.WbCloudy to Color.White
    WeatherCondition.Cloudy -> Icons.Outlined.Cloud to Color.White
    WeatherCondition.Fog -> Icons.Outlined.Dehaze to Color.White
    WeatherCondition.Drizzle -> Icons.Outlined.Grain to Color(0xFF9FD3FF)
    WeatherCondition.Rain -> Icons.Outlined.Umbrella to Color(0xFF9FD3FF)
    WeatherCondition.Snow -> Icons.Outlined.AcUnit to Color.White
    WeatherCondition.Thunder -> Icons.Outlined.Thunderstorm to Color(0xFFFFD34E)
}

@Composable
private fun StatusPill(showClock: Boolean, theme: DivanTheme, notificationCount: Int?, onOpenNotifications: () -> Unit) {
    val context = LocalContext.current
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            // Wake at the turn of the minute, so the clock never lags behind the TV's own.
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    // Divan's language, not the system's; a change of it recreates the activity, nothing to observe.
    val locale = AppLocale.locale()
    val wifi = rememberOnWifi()
    GlassPanel(corner = 28.dp, theme = theme, modifier = Modifier.height(48.dp)) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (notificationCount != null) {
                NotificationBell(notificationCount, theme, onOpenNotifications)
                Box(Modifier.width(1.dp).height(22.dp).background(Color.White.copy(alpha = 0.35f)))
            }
            Icon(
                if (wifi) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                contentDescription = stringResource(if (wifi) R.string.wifi_on else R.string.wifi_off),
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
            if (showClock) {
                Box(Modifier.width(1.dp).height(22.dp).background(Color.White.copy(alpha = 0.35f)))
                Text(
                    SimpleDateFormat(pattern, locale).format(now),
                    color = Color.White,
                    fontSize = 22.sp,
                )
            }
        }
    }
}

/** Focusable part of the status pill: bell plus count, OK opens the list. */
@Composable
private fun NotificationBell(count: Int, theme: DivanTheme, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.3f),
        ),
        modifier = Modifier.height(36.dp),
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (count > 0) Icons.Outlined.Notifications else Icons.Outlined.NotificationsNone,
                contentDescription = pluralStringResource(R.plurals.notifications_count, count, count),
                tint = Color.White.copy(alpha = if (count > 0) 1f else 0.6f),
                modifier = Modifier.size(22.dp),
            )
            if (count > 0) {
                Text("$count", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun GlassIconButton(theme: DivanTheme, onClick: () -> Unit, onLongClick: () -> Unit) {
    GlassPanel(corner = 24.dp, theme = theme, modifier = Modifier.size(48.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.android_settings),
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(24.dp),
            )
        }
    }
}

@Composable
private fun AppCard(
    app: AppInfo,
    theme: DivanTheme,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(theme.cardCorner)
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(theme.cardWidth).aspectRatio(16f / 9f),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.1f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, theme.accent), shape = shape)),
        colors = CardDefaults.colors(containerColor = Color(0xFFF4F4F6)),
    ) {
        val image = app.image
        if (image != null && app.isBanner) {
            Image(image, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (image != null) {
                    Image(image, contentDescription = null, modifier = Modifier.fillMaxHeight(0.55f).aspectRatio(1f))
                }
                Text(
                    app.label,
                    color = Color(0xFF1C1B1F),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AppMenu(
    app: AppInfo,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    actions: AppActions,
    onChangeIcon: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // The menu opens while OK is still held (long press). TV buttons click on release, so that
    // release would hit the first item («Сдвинуть…») and close the menu: swallow it, and the
    // auto-repeat downs a real remote keeps sending while held. Only a fresh press (repeat 0)
    // made inside the menu counts.
    var pressedHere by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(360.dp).background(Color(0xF0201B29), RoundedCornerShape(16.dp)).padding(20.dp)
                .onPreviewKeyEvent { event ->
                    val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                    when {
                        !ok -> false
                        event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
                            pressedHere = true
                            false
                        }
                        else -> !pressedHere
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(app.label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            val ownPackage = LocalContext.current.packageName
            val items = buildList {
                if (canMoveLeft) add(R.string.menu_move_left to { actions.move(app, -1) })
                if (canMoveRight) add(R.string.menu_move_right to { actions.move(app, 1) })
                if (canMoveLeft) add(R.string.menu_move_first to { actions.moveToEdge(app, true) })
                if (canMoveRight) add(R.string.menu_move_last to { actions.moveToEdge(app, false) })
                add(R.string.menu_change_icon to onChangeIcon)
                add(R.string.menu_hide to { actions.hide(app) })
                // Divan's own settings card: uninstalling from here would remove the launcher
                if (app.packageName != ownPackage) {
                    add(R.string.menu_app_info to { actions.details(app) })
                    add(R.string.menu_uninstall to { actions.uninstall(app) })
                }
            }
            items.forEachIndexed { index, (title, action) ->
                Button(
                    onClick = {
                        action()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                ) {
                    Text(stringResource(title))
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun IconPicker(app: AppInfo, theme: DivanTheme, actions: AppActions, onDismiss: () -> Unit) {
    val files by produceState<List<File>?>(null) { value = actions.iconChoices() }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(720.dp).background(Color(0xF0201B29), RoundedCornerShape(16.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.icon_title, app.label), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.icon_hint),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(6.dp),
                modifier = Modifier.height(320.dp),
            ) {
                item(key = "auto") {
                    IconChoice(
                        label = stringResource(R.string.icon_auto),
                        accent = theme.accent,
                        image = null,
                        modifier = Modifier.focusRequester(first),
                        onClick = {
                            // <package>.png from the icons folder when there is one, else the banner.
                            actions.setIcon(app, null)
                            onDismiss()
                        },
                    )
                }
                item(key = "banner") {
                    IconChoice(
                        label = stringResource(R.string.icon_banner),
                        accent = theme.accent,
                        image = null,
                        modifier = Modifier,
                        onClick = {
                            actions.setIcon(app, "")
                            onDismiss()
                        },
                    )
                }
                items(files.orEmpty(), key = { it.path }) { file ->
                    val thumb by produceState<ImageBitmap?>(null, file) { value = actions.thumbnail(file) }
                    IconChoice(
                        label = file.nameWithoutExtension,
                        accent = theme.accent,
                        image = thumb,
                        modifier = Modifier,
                        onClick = {
                            actions.setIcon(app, file.path)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun IconChoice(label: String, image: ImageBitmap?, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, accent), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        // Dark when focused too: the text labels are white.
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF2C2636), focusedContainerColor = Color(0xFF3D3550)),
        modifier = modifier.aspectRatio(16f / 9f),
    ) {
        if (image != null) {
            Image(image, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                label,
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp),
            )
        }
    }
}

private fun isOnWifi(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return manager.getNetworkCapabilities(manager.activeNetwork)?.isLocal() == true
}

private fun NetworkCapabilities.isLocal() =
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

/** Follows the default network as it changes, not on the clock's minute tick (DV-037). */
@Composable
private fun rememberOnWifi(): Boolean {
    val context = LocalContext.current
    return produceState(isOnWifi(context), context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@produceState
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                value = capabilities.isLocal()
            }

            override fun onLost(network: Network) {
                value = false
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitDispose { manager.unregisterNetworkCallback(callback) }
    }.value
}
