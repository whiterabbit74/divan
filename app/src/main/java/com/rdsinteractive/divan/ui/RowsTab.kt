package com.rdsinteractive.divan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.rdsinteractive.divan.R
import com.rdsinteractive.divan.data.AppInfo
import com.rdsinteractive.divan.data.LauncherSettings
import com.rdsinteractive.divan.data.TvChannel
import com.rdsinteractive.divan.data.TvRowsData

data class RowsActions(
    val setWatchNext: (Boolean) -> Unit,
    val setChannels: (Boolean) -> Unit,
    val setSource: (String, Boolean) -> Unit,
    val setChannel: (TvChannel, Boolean) -> Unit,
    /** Asks Android for READ_TV_LISTINGS; without it the provider shows Divan nothing. */
    val requestAccess: () -> Unit,
)

@Composable
fun RowsTab(settings: LauncherSettings, data: TvRowsData, allApps: List<AppInfo>, hasAccess: Boolean, actions: RowsActions) {
    val labels = allApps.associate { it.packageName to it.label }
    val sources = (data.sources + settings.rowSources).sortedBy { labels[it] ?: it }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(0.6f)) {
        if (!hasAccess) {
            item {
                ListItem(
                    selected = false,
                    onClick = actions.requestAccess,
                    headlineContent = { Text(stringResource(R.string.rows_access)) },
                    supportingContent = { Text(stringResource(R.string.rows_access_hint), fontSize = 11.sp) },
                )
            }
        }
        item {
            ListItem(
                selected = false,
                onClick = { actions.setWatchNext(!settings.watchNextEnabled) },
                headlineContent = { Text(stringResource(R.string.rows_watch_next)) },
                supportingContent = { Text(stringResource(R.string.rows_watch_next_hint), fontSize = 11.sp) },
                trailingContent = { Switch(checked = settings.watchNextEnabled, onCheckedChange = null) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = { actions.setChannels(!settings.channelsEnabled) },
                headlineContent = { Text(stringResource(R.string.rows_channels)) },
                supportingContent = { Text(stringResource(R.string.rows_channels_hint), fontSize = 11.sp) },
                trailingContent = { Switch(checked = settings.channelsEnabled, onCheckedChange = null) },
            )
        }
        item {
            Text(stringResource(R.string.rows_sources), color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        if (sources.isEmpty()) {
            item {
                Text(
                    stringResource(if (hasAccess) R.string.rows_none else R.string.rows_no_access),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        sources.forEach { pkg ->
            val shown = pkg in settings.rowSources
            val channels = data.channels.filter { it.packageName == pkg }
            val channelsOn = channels.count { it.key !in settings.hiddenChannels }
            val watchNext = data.watchNext.count { it.packageName == pkg }
            item(key = pkg) {
                ListItem(
                    selected = false,
                    onClick = { actions.setSource(pkg, !shown) },
                    headlineContent = { Text(labels[pkg] ?: pkg) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                when {
                                    channels.isEmpty() -> null
                                    channelsOn == channels.size -> pluralStringResource(R.plurals.rows_channel_count, channels.size, channels.size)
                                    else -> pluralStringResource(R.plurals.rows_channels_shown, channels.size, channelsOn, channels.size)
                                },
                                if (watchNext > 0) stringResource(R.string.rows_in_watch_next, watchNext) else null,
                            ).joinToString(" · ").ifEmpty { stringResource(R.string.rows_nothing) },
                            fontSize = 11.sp,
                        )
                    },
                    trailingContent = { Switch(checked = shown, onCheckedChange = null) },
                )
            }
            // A shown app with several channels lists them right under it, each with its own switch.
            if (shown && settings.channelsEnabled && channels.size > 1) {
                // By id: two channels of one app may share a name (DV-024); the switch is by name, shared.
                items(channels, key = { "channel_${it.id}" }) { channel ->
                    val on = channel.key !in settings.hiddenChannels
                    ListItem(
                        selected = false,
                        onClick = { actions.setChannel(channel, !on) },
                        headlineContent = { Text(channel.name.ifBlank { labels[pkg] ?: pkg }, fontSize = 14.sp) },
                        supportingContent = { Text(pluralStringResource(R.plurals.rows_card_count, channel.programs.size, channel.programs.size), fontSize = 11.sp) },
                        trailingContent = { Switch(checked = on, onCheckedChange = null) },
                        modifier = Modifier.padding(start = 32.dp),
                    )
                }
            }
        }
    }
}
