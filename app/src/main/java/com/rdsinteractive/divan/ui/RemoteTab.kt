package com.rdsinteractive.divan.ui

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.rdsinteractive.divan.R
import com.rdsinteractive.divan.data.AppInfo
import com.rdsinteractive.divan.data.LauncherSettings
import com.rdsinteractive.divan.remote.DivanState
import com.rdsinteractive.divan.remote.PROTECTED_KEYS
import com.rdsinteractive.divan.remote.REMOTE_KEY_NAMES
import com.rdsinteractive.divan.remote.Press
import com.rdsinteractive.divan.remote.RemapAction
import com.rdsinteractive.divan.remote.RemapRule
import com.rdsinteractive.divan.remote.Scope
import com.rdsinteractive.divan.remote.keyName

data class RemoteActions(
    val setEnabled: (Boolean) -> Unit,
    val setLongPressMs: (Int) -> Unit,
    val saveRule: (RemapRule) -> Unit,
    val deleteRule: (RemapRule) -> Unit,
    val openAccessibilitySettings: () -> Unit,
)

private val LONG_PRESS_CHOICES = listOf(300, 500, 700, 1000)

@Composable
fun RemoteTab(settings: LauncherSettings, allApps: List<AppInfo>, serviceOn: Boolean, actions: RemoteActions) {
    var editing by remember { mutableStateOf<RemapRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<RemapRule?>(null) }
    val labels = remember(allApps) { allApps.associate { it.packageName to it.label } }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(0.7f)) {
        if (!serviceOn) {
            item {
                ListItem(
                    selected = false,
                    onClick = actions.openAccessibilitySettings,
                    headlineContent = { Text(stringResource(R.string.remote_service_off)) },
                    supportingContent = { Text(stringResource(R.string.remote_service_off_hint), fontSize = 11.sp) },
                )
            }
        }
        item {
            ListItem(
                selected = false,
                onClick = { actions.setEnabled(!settings.remapEnabled) },
                headlineContent = { Text(stringResource(R.string.remote_enabled)) },
                supportingContent = { Text(stringResource(R.string.remote_enabled_hint), fontSize = 11.sp) },
                trailingContent = { Switch(checked = settings.remapEnabled, onCheckedChange = null) },
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = {
                    val next = LONG_PRESS_CHOICES[(LONG_PRESS_CHOICES.indexOf(settings.longPressMs) + 1) % LONG_PRESS_CHOICES.size]
                    actions.setLongPressMs(next)
                },
                headlineContent = { Text(stringResource(R.string.remote_long_press)) },
                trailingContent = { Text(stringResource(R.string.millis_short, settings.longPressMs)) },
            )
        }
        item {
            Text(stringResource(R.string.remote_rules), color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        items(settings.remapRules, key = { it.id }) { rule ->
            ListItem(
                selected = false,
                onClick = { menuFor = rule },
                headlineContent = { Text("${keyLabel(rule.keyCode)} · ${stringResource(rule.press.title)}") },
                supportingContent = {
                    val parts = listOfNotNull(
                        describe(rule, labels),
                        stringResource(rule.scope.title),
                        if (rule.replace) null else stringResource(R.string.remote_with_default),
                    )
                    Text("→ " + parts.joinToString(" · "), fontSize = 11.sp)
                },
            )
        }
        item {
            ListItem(selected = false, onClick = { creating = true }, headlineContent = { Text(stringResource(R.string.remote_new)) })
        }
    }

    menuFor?.let { rule ->
        RuleMenu(
            rule = rule,
            onEdit = { editing = rule },
            onToggleReplace = { actions.saveRule(rule.copy(replace = !rule.replace)) },
            onDelete = { actions.deleteRule(rule) },
            onDismiss = { menuFor = null },
        )
    }
    if (creating || editing != null) {
        RuleEditor(
            initial = editing,
            allApps = allApps,
            onSave = actions.saveRule,
            onDismiss = {
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun describe(rule: RemapRule, labels: Map<String, String>): String = when (rule.action) {
    RemapAction.OpenApp -> stringResource(R.string.remote_open, labels[rule.argument] ?: rule.argument.orEmpty())
    RemapAction.OpenUri -> stringResource(R.string.remote_open, rule.argument.orEmpty())
    else -> stringResource(rule.action.title)
}

/** A remote key as printed on the remote («YouTube button»), else Android's name for it. */
@Composable
private fun keyLabel(keyCode: Int): String = REMOTE_KEY_NAMES[keyCode]?.let { stringResource(it) } ?: keyName(keyCode)

@Composable
private fun RuleMenu(
    rule: RemapRule,
    onEdit: () -> Unit,
    onToggleReplace: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogColumn(title = "${keyLabel(rule.keyCode)} · ${stringResource(rule.press.title)}", onDismiss = onDismiss) { first ->
        MenuButton(stringResource(R.string.remote_edit), first) { onEdit(); onDismiss() }
        MenuButton(stringResource(if (rule.replace) R.string.remote_keep_default else R.string.remote_replace_default), null) {
            onToggleReplace(); onDismiss()
        }
        MenuButton(stringResource(R.string.remote_delete), null) { onDelete(); onDismiss() }
    }
}

private enum class Step { Key, Press, Action, App, Uri, Scope }

@Composable
private fun RuleEditor(initial: RemapRule?, allApps: List<AppInfo>, onSave: (RemapRule) -> Unit, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(Step.Key) }
    var draft by remember { mutableStateOf(initial ?: RemapRule(keyCode = 0, press = Press.Single, action = RemapAction.Nothing)) }
    var protectedKey by remember { mutableStateOf(false) }

    when (step) {
        Step.Key -> DialogColumn(title = stringResource(R.string.remote_press_key), onDismiss = onDismiss) { _ ->
            val catcher = remember { FocusRequester() }
            // Without this the remote service would run the key's current rule instead.
            DisposableEffect(Unit) {
                DivanState.capturingKey = true
                Log.i("DivanRemap", "key capture on")
                onDispose {
                    DivanState.capturingKey = false
                    Log.i("DivanRemap", "key capture off")
                }
            }
            Box(
                Modifier.fillMaxWidth().height(90.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .focusRequester(catcher)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val code = event.key.nativeKeyCode
                        // «Вниз» moves to «Отмена»: «Назад» itself can be remapped, so it is recorded.
                        if (code == KeyEvent.KEYCODE_DPAD_DOWN) return@onPreviewKeyEvent false
                        if (code in PROTECTED_KEYS) {
                            protectedKey = true
                            return@onPreviewKeyEvent true
                        }
                        Log.i("DivanRemap", "recorded ${keyName(code)}")
                        draft = draft.copy(keyCode = code)
                        step = Step.Press
                        true
                    }
                    .focusable(),
            ) {
                Text(
                    when {
                        protectedKey -> stringResource(R.string.remote_protected_key)
                        initial != null -> stringResource(R.string.remote_current_key, keyLabel(initial.keyCode))
                        else -> stringResource(R.string.remote_waiting)
                    },
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(20.dp),
                )
            }
            MenuButton(stringResource(R.string.cancel), null, onDismiss)
            LaunchedEffect(Unit) { runCatching { catcher.requestFocus() } }
        }
        Step.Press -> DialogColumn(title = stringResource(R.string.remote_which_press, keyLabel(draft.keyCode)), onDismiss = onDismiss) { first ->
            Press.entries.forEachIndexed { index, press ->
                MenuButton(stringResource(press.title), if (index == 0) first else null) {
                    draft = draft.copy(press = press)
                    step = Step.Action
                }
            }
        }
        Step.Action -> DialogColumn(title = stringResource(R.string.remote_what), onDismiss = onDismiss, scroll = true) { first ->
            RemapAction.entries.forEachIndexed { index, action ->
                MenuButton(stringResource(action.title), if (index == 0) first else null) {
                    draft = draft.copy(action = action, argument = null)
                    step = when (action) {
                        RemapAction.OpenApp -> Step.App
                        RemapAction.OpenUri -> Step.Uri
                        else -> Step.Scope
                    }
                }
            }
        }
        Step.App -> DialogColumn(title = stringResource(R.string.remote_which_app), onDismiss = onDismiss, scroll = true) { first ->
            allApps.forEachIndexed { index, app ->
                MenuButton(app.label, if (index == 0) first else null) {
                    draft = draft.copy(argument = app.packageName)
                    step = Step.Scope
                }
            }
        }
        Step.Uri -> DialogColumn(title = stringResource(R.string.remote_link), onDismiss = onDismiss) { first ->
            var text by remember { mutableStateOf(draft.argument.orEmpty()) }
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(12.dp),
            )
            MenuButton(stringResource(R.string.next), first) {
                if (text.isNotBlank()) {
                    draft = draft.copy(argument = text.trim())
                    step = Step.Scope
                }
            }
        }
        Step.Scope -> DialogColumn(title = stringResource(R.string.remote_where), onDismiss = onDismiss) { first ->
            Scope.entries.forEachIndexed { index, scope ->
                MenuButton(stringResource(scope.title), if (index == 0) first else null) {
                    onSave(draft.copy(scope = scope))
                    onDismiss()
                }
            }
        }
    }
}

@Composable
internal fun DialogColumn(
    title: String,
    onDismiss: () -> Unit,
    scroll: Boolean = false,
    content: @Composable (first: FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(440.dp).background(Color(0xF0201B29), RoundedCornerShape(16.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            if (scroll) {
                Column(Modifier.height(380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content(first)
                }
            } else {
                content(first)
            }
        }
    }
    LaunchedEffect(title) { runCatching { first.requestFocus() } }
}

@Composable
internal fun MenuButton(title: String, focus: FocusRequester?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
    ) {
        Text(title)
    }
}
