package com.rdsinteractive.divan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.rdsinteractive.divan.R
import com.rdsinteractive.divan.data.AppInfo
import com.rdsinteractive.divan.data.Pin
import com.rdsinteractive.divan.data.LauncherSettings

data class ParentalActions(
    /** Runs the block right away, or after the PIN while parental control is on and not yet unlocked. */
    val unlock: (() -> Unit) -> Unit,
    val setPin: suspend (String) -> Unit,
    val clearPin: () -> Unit,
    val setEnabled: (Boolean) -> Unit,
    val setLockSettings: (Boolean) -> Unit,
    val setAppLocked: (String, Boolean) -> Unit,
)

@Composable
fun ParentalTab(settings: LauncherSettings, allApps: List<AppInfo>, actions: ParentalActions) {
    var settingPin by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(0.7f)) {
        // One item for both states, so focus stays on it when a PIN is set or removed.
        item(key = "master") {
            val hasPin = settings.hasPin
            ListItem(
                selected = false,
                onClick = { if (hasPin) actions.unlock { actions.setEnabled(!settings.parentalEnabled) } else settingPin = true },
                headlineContent = { Text(stringResource(if (hasPin) R.string.parental_title else R.string.parental_setup)) },
                supportingContent = {
                    Text(
                        if (hasPin) stringResource(R.string.parental_switch_hint)
                        else stringResource(R.string.parental_pin_hint, Pin.MIN_LENGTH, Pin.MAX_LENGTH),
                        fontSize = 11.sp,
                    )
                },
                trailingContent = { if (hasPin) Switch(checked = settings.parentalEnabled, onCheckedChange = null) },
            )
        }
        if (settings.hasPin) {
            item {
                ListItem(
                    selected = false,
                    onClick = { actions.unlock { settingPin = true } },
                    headlineContent = { Text(stringResource(R.string.parental_change)) },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { actions.unlock { actions.clearPin() } },
                    headlineContent = { Text(stringResource(R.string.parental_remove)) },
                    supportingContent = { Text(stringResource(R.string.parental_remove_hint), fontSize = 11.sp) },
                )
            }
            item {
                ListItem(
                    selected = false,
                    onClick = { actions.unlock { actions.setLockSettings(!settings.lockSettings) } },
                    headlineContent = { Text(stringResource(R.string.parental_lock_settings)) },
                    supportingContent = { Text(stringResource(R.string.parental_lock_settings_hint), fontSize = 11.sp) },
                    trailingContent = { Switch(checked = settings.lockSettings, onCheckedChange = null) },
                )
            }
            item {
                Text(stringResource(R.string.parental_apps), color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(allApps, key = { it.packageName }) { app ->
                val locked = app.packageName in settings.lockedApps
                ListItem(
                    selected = false,
                    onClick = { actions.unlock { actions.setAppLocked(app.packageName, !locked) } },
                    headlineContent = { Text(app.label) },
                    supportingContent = { Text(app.packageName, fontSize = 11.sp) },
                    trailingContent = { Switch(checked = locked, onCheckedChange = null) },
                )
            }
            item {
                Text(
                    stringResource(R.string.parental_note),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    if (settingPin) {
        PinSetupDialog(onSave = actions.setPin, onDismiss = { settingPin = false })
    }
}
