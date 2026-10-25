package com.rdsinteractive.divan.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.rdsinteractive.divan.R
import com.rdsinteractive.divan.data.Pin
import kotlinx.coroutines.launch

/**
 * PIN entry with the remote: arrows and digit keys add a symbol, OK confirms, Back erases
 * (and closes when empty). [onSubmit] returns null when the PIN is accepted, else the error.
 * [reveal] shows the symbols instead of dots, for setting a new PIN.
 */
@Composable
fun PinDialog(
    title: String,
    onSubmit: suspend (String) -> String?,
    onDismiss: () -> Unit,
    reveal: Boolean = false,
    message: String? = null,
) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val catcher = remember { FocusRequester() }
    val tooShort = pluralStringResource(R.plurals.pin_too_short, Pin.MIN_LENGTH, Pin.MIN_LENGTH)

    fun submit() {
        if (entered.length < Pin.MIN_LENGTH) {
            error = tooShort
            return
        }
        busy = true
        val pin = entered
        scope.launch {
            val problem = onSubmit(pin)
            busy = false
            if (problem != null) {
                error = problem
                entered = ""
            }
        }
    }

    Dialog(onDismissRequest = { if (entered.isNotEmpty()) entered = entered.dropLast(1) else onDismiss() }) {
        Column(
            Modifier.width(460.dp).background(Color(0xF0201B29), RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Row(
                Modifier
                    .focusRequester(catcher)
                    .onPreviewKeyEvent { event ->
                        val symbol = pinSymbol(event.key.nativeKeyCode)
                        val confirm = event.key.nativeKeyCode in CONFIRM_KEYS
                        val erase = event.key.nativeKeyCode == KeyEvent.KEYCODE_DEL
                        if (symbol == null && !confirm && !erase) return@onPreviewKeyEvent false
                        // Repeats too: the card menu's PIN opens while its long OK is still held (DV-041).
                        if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0 || busy) return@onPreviewKeyEvent true
                        error = null
                        when {
                            symbol != null -> if (entered.length < Pin.MAX_LENGTH) entered += symbol
                            erase -> entered = entered.dropLast(1)
                            else -> submit()
                        }
                        true
                    }
                    .focusable()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val slots = (entered.length + 1).coerceIn(Pin.MIN_LENGTH, Pin.MAX_LENGTH)
                repeat(slots) { index ->
                    val char = entered.getOrNull(index)
                    Box(
                        Modifier.size(42.dp)
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .border(
                                2.dp,
                                if (index == entered.length) Color(0xFF5FE0F5) else Color.White.copy(alpha = 0.2f),
                                RoundedCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (char != null) {
                            Text(if (reveal) Pin.symbol(char) else "●", color = Color.White, fontSize = if (reveal) 22.sp else 16.sp)
                        }
                    }
                }
            }
            Text(
                error ?: message ?: stringResource(R.string.pin_hint),
                color = if (error != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.65f),
                fontSize = 13.sp,
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { catcher.requestFocus() } }
}

/** New PIN typed twice; [onSave] gets it once both match. */
@Composable
fun PinSetupDialog(onSave: suspend (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    val chosen = first
    key(chosen) {
        if (chosen == null) {
            PinDialog(
                title = stringResource(R.string.pin_new),
                reveal = true,
                message = if (mismatch) stringResource(R.string.pin_mismatch) else stringResource(R.string.pin_rule, Pin.MIN_LENGTH, Pin.MAX_LENGTH),
                onSubmit = {
                    first = it
                    null
                },
                onDismiss = onDismiss,
            )
        } else {
            PinDialog(
                title = stringResource(R.string.pin_repeat),
                reveal = true,
                onSubmit = { again ->
                    if (again == chosen) {
                        onSave(again)
                        onDismiss()
                    } else {
                        mismatch = true
                        first = null
                    }
                    null
                },
                onDismiss = { first = null },
            )
        }
    }
}

private val CONFIRM_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

private fun pinSymbol(code: Int): Char? = when (code) {
    KeyEvent.KEYCODE_DPAD_UP -> 'U'
    KeyEvent.KEYCODE_DPAD_DOWN -> 'D'
    KeyEvent.KEYCODE_DPAD_LEFT -> 'L'
    KeyEvent.KEYCODE_DPAD_RIGHT -> 'R'
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (code - KeyEvent.KEYCODE_0)
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - KeyEvent.KEYCODE_NUMPAD_0)
    else -> null
}
