package com.rdsinteractive.divan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.rdsinteractive.divan.R
import com.rdsinteractive.divan.data.AppInfo
import com.rdsinteractive.divan.data.LauncherSettings

data class AutostartActions(
    val setKeepAwake: (Boolean) -> Unit,
    val setApp: (String?) -> Unit,
    val setDelay: (Int) -> Unit,
    val setOnWake: (Boolean) -> Unit,
)

private val DELAY_CHOICES = listOf(0, 3, 5, 10, 20, 30)

@Composable
fun AutostartTab(settings: LauncherSettings, allApps: List<AppInfo>, serviceOn: Boolean, actions: AutostartActions) {
    var picking by remember { mutableStateOf(false) }
    val app = settings.autostartApp
    val label = app?.let { pkg -> allApps.firstOrNull { it.packageName == pkg }?.label ?: pkg }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(0.6f)) {
        item {
            ListItem(
                selected = false,
                onClick = { actions.setKeepAwake(!settings.keepAwake) },
                headlineContent = { Text(stringResource(R.string.power_keep_awake)) },
                supportingContent = {
                    Text(
                        stringResource(if (serviceOn) R.string.power_keep_awake_hint else R.string.power_needs_service),
                        fontSize = 11.sp,
                    )
                },
                trailingContent = { Switch(checked = settings.keepAwake, onCheckedChange = null) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = { picking = true },
                headlineContent = { Text(stringResource(R.string.power_app)) },
                supportingContent = {
                    Text(
                        stringResource(if (app == null) R.string.power_app_none_hint else R.string.power_app_hint),
                        fontSize = 11.sp,
                    )
                },
                trailingContent = { Text(label ?: stringResource(R.string.power_none)) },
            )
        }
        if (app != null) {
            item {
                ListItem(
                    selected = false,
                    onClick = {
                        val next = DELAY_CHOICES[(DELAY_CHOICES.indexOf(settings.autostartDelaySec) + 1) % DELAY_CHOICES.size]
                        actions.setDelay(next)
                    },
                    headlineContent = { Text(stringResource(R.string.power_delay)) },
                    supportingContent = { Text(stringResource(R.string.power_delay_hint), fontSize = 11.sp) },
                    trailingContent = {
                        Text(
                            if (settings.autostartDelaySec == 0) stringResource(R.string.power_delay_none)
                            else stringResource(R.string.seconds_short, settings.autostartDelaySec),
                        )
                    },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { actions.setOnWake(!settings.autostartOnWake) },
                    headlineContent = { Text(stringResource(R.string.power_on_wake)) },
                    supportingContent = {
                        Text(
                            stringResource(if (serviceOn) R.string.power_on_wake_hint else R.string.power_needs_service),
                            fontSize = 11.sp,
                        )
                    },
                    trailingContent = { Switch(checked = settings.autostartOnWake, onCheckedChange = null) },
                )
            }
        }
    }

    if (picking) {
        DialogColumn(title = stringResource(R.string.power_pick_title), onDismiss = { picking = false }, scroll = true) { first ->
            MenuButton(stringResource(R.string.power_pick_nothing), first) {
                actions.setApp(null)
                picking = false
            }
            allApps.filter { it.packageName != "com.rdsinteractive.divan" }.forEach { candidate ->
                MenuButton(candidate.label, null) {
                    actions.setApp(candidate.packageName)
                    picking = false
                }
            }
        }
    }
}
