package com.rdsinteractive.divan.remote

import android.view.KeyEvent
import androidx.annotation.StringRes
import com.rdsinteractive.divan.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Press(@StringRes val title: Int) {
    Single(R.string.press_single),
    Double(R.string.press_double),
    Long(R.string.press_long),
}

enum class Scope(@StringRes val title: Int) {
    Everywhere(R.string.scope_everywhere),
    ExceptHome(R.string.scope_except_home),
    OnlyHome(R.string.scope_only_home),
}

enum class RemapAction(@StringRes val title: Int) {
    OpenApp(R.string.action_open_app),
    OpenUri(R.string.action_open_uri),
    Home(R.string.action_home),
    Back(R.string.action_back),
    Recents(R.string.action_recents),
    VolumeUp(R.string.action_volume_up),
    VolumeDown(R.string.action_volume_down),
    Mute(R.string.action_mute),
    PlayPause(R.string.action_play_pause),
    Next(R.string.action_next),
    Previous(R.string.action_previous),
    Assistant(R.string.action_assistant),
    Notifications(R.string.notifications),
    QuickSettings(R.string.action_quick_settings),
    PowerMenu(R.string.action_power_menu),
    ScreenOff(R.string.action_screen_off),
    DivanSettings(R.string.divan_settings),
    Nothing(R.string.action_nothing),
}

data class RemapRule(
    val id: String = UUID.randomUUID().toString(),
    val keyCode: Int,
    val press: Press,
    val action: RemapAction,
    /** Package for [RemapAction.OpenApp], URI for [RemapAction.OpenUri]. */
    val argument: String? = null,
    val scope: Scope = Scope.Everywhere,
    /** true: the key no longer does its own thing; false: our action runs in addition. */
    val replace: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("key", keyCode)
        .put("press", press.name)
        .put("action", action.name)
        .put("arg", argument)
        .put("scope", scope.name)
        .put("replace", replace)

    companion object {
        fun fromJson(json: JSONObject): RemapRule? = runCatching {
            RemapRule(
                id = json.getString("id"),
                keyCode = json.getInt("key"),
                press = Press.valueOf(json.getString("press")),
                action = RemapAction.valueOf(json.getString("action")),
                argument = json.optString("arg").takeIf { json.has("arg") && !json.isNull("arg") },
                scope = Scope.valueOf(json.optString("scope", Scope.Everywhere.name)),
                replace = json.optBoolean("replace", true),
            )
        }.getOrNull()

        fun listToJson(rules: List<RemapRule>): String = JSONArray(rules.map { it.toJson() }).toString()

        fun listFromJson(text: String?): List<RemapRule> {
            if (text.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { fromJson(array.getJSONObject(it)) }
        }
    }
}

/** Android's name of a key, e.g. "BUTTON_4", "MEDIA_PLAY_PAUSE"; the UI prefers [REMOTE_KEY_NAMES]. */
fun keyName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

/** The Google TV remote's app keys send plain gamepad codes; name them as printed on the remote. */
val REMOTE_KEY_NAMES: Map<Int, Int> = mapOf(
    KeyEvent.KEYCODE_BUTTON_3 to R.string.key_youtube,
    KeyEvent.KEYCODE_BUTTON_4 to R.string.key_netflix,
)

/**
 * Keys a remap may not take over: without them the TV cannot be navigated, and an
 * accessibility service has no way to give them back.
 */
val PROTECTED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
)
