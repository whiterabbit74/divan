package com.rdsinteractive.divan.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.rdsinteractive.divan.R
import com.rdsinteractive.divan.notify.NotificationItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NotificationActions(
    val open: (NotificationItem) -> Unit,
    val dismiss: (NotificationItem) -> Unit,
    val dismissAll: () -> Unit,
)

/** The list behind the bell: OK opens a notification, long OK swipes it away. */
@Composable
fun NotificationsPanel(items: List<NotificationItem>, actions: NotificationActions, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    // Long OK swipes on its first auto-repeat, with OK still held: the item goes away, focus moves
    // on, and the next repeat or the release would long-click or click whatever got the focus
    // (DV-025). Until a fresh press, OK repeats and the release are swallowed.
    var swallowOk by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(640.dp).background(Color(0xF0201B29), RoundedCornerShape(16.dp))
                .onPreviewKeyEvent { event ->
                    if (!swallowOk || event.key !in OK_KEYS) return@onPreviewKeyEvent false
                    when {
                        event.type == KeyEventType.KeyUp -> { swallowOk = false; true }
                        event.nativeKeyEvent.repeatCount > 0 -> true
                        else -> { swallowOk = false; false }
                    }
                }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.notifications), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    if (items.isNotEmpty()) {
                        Text(stringResource(R.string.notifications_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
                if (items.any { it.clearable }) {
                    Button(onClick = actions.dismissAll) { Text(stringResource(R.string.notifications_dismiss_all)) }
                }
            }
            if (items.isEmpty()) {
                Text(stringResource(R.string.notifications_empty), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                        val icon = appIcon(item.packageName)
                        ListItem(
                            selected = false,
                            onClick = {
                                actions.open(item)
                                onDismiss()
                            },
                            onLongClick = {
                                if (item.clearable) {
                                    swallowOk = true
                                    actions.dismiss(item)
                                }
                            },
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                            leadingContent = {
                                if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(32.dp)) else Spacer(Modifier.size(32.dp))
                            },
                            overlineContent = { Text("${item.appLabel} · ${formatTime(context, item.postTime)}", fontSize = 11.sp) },
                            headlineContent = { Text(item.title.ifBlank { item.appLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (item.text.isNotBlank()) {
                                { Text(item.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(items.isEmpty()) { runCatching { first.requestFocus() } }
}

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

@Composable
private fun appIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) { value = loadIcon(context, packageName) }
    return icon
}

private suspend fun loadIcon(context: Context, packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(64, 64).asImageBitmap() }.getOrNull()
}

/** Time only for today, else «24 окт., 18:40»; 12/24 h as set on the TV. */
private fun formatTime(context: Context, millis: Long): String {
    val date = if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
    return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME or date)
}
