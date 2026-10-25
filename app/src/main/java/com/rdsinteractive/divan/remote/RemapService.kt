package com.rdsinteractive.divan.remote

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.rdsinteractive.divan.MainActivity
import com.rdsinteractive.divan.data.AppRepository
import com.rdsinteractive.divan.data.LauncherSettings
import com.rdsinteractive.divan.data.SettingsStore
import com.rdsinteractive.divan.data.linkIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Remote-key remapping, the job Buttons Remapper used to do. Runs as an accessibility
 * service because that is the only way an ordinary app may see keys meant for other apps.
 */
class RemapService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var settings: LauncherSettings? = null
    private var foregroundPackage: String? = null

    // Presses being decided (single / double / long), per key: keys pressed together or in
    // quick succession do not cancel each other.
    private val longRunnables = mutableMapOf<Int, Runnable>()
    private val longFired = mutableSetOf<Int>()
    private val pendingSingles = mutableMapOf<Int, Runnable>()
    private val swallowNextUp = mutableSetOf<Int>()
    /** Keys pressed while the rule editor was recording: their release goes to Divan as well. */
    private val capturedKeys = mutableSetOf<Int>()

    /** Autostart on wake: the service lives all the time, so it hears the screen come on. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(autostart)
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            val current = settings?.takeIf { it.autostartOnWake && it.autostartApp != null } ?: return
            handler.postDelayed(autostart, current.autostartDelaySec * 1000L)
        }
    }
    private val autostart = Runnable {
        val pkg = settings?.autostartApp ?: return@Runnable
        if (foregroundPackage == pkg) return@Runnable
        Log.i(TAG, "autostart on wake: $pkg")
        perform(RemapAction.OpenApp, pkg)
    }
    private var screenReceiverOn = false

    override fun onServiceConnected() {
        Log.i(TAG, "connected")
        scope.launch {
            SettingsStore(this@RemapService).settings.collect {
                settings = it
                keepAwake(it.keepAwake)
            }
        }
        if (!screenReceiverOn) {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
            screenReceiverOn = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.takeIf { it != "com.android.systemui" }?.let { foregroundPackage = it }
        }
    }

    override fun onInterrupt() = Unit

    /**
     * «Не засыпать при простое». A screen wake lock keeps the power manager from starting the
     * screensaver and from the sleep that follows it (Google TV: 30 min). The power button and
     * the «Выключить экран» action still put the TV to sleep: they ignore wake locks.
     */
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK: the only lock that keeps the screen on from a service.
    private fun keepAwake(on: Boolean) {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "divan:keep-awake")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
            ?: return
        if (on && !lock.isHeld) lock.acquire() else if (!on && lock.isHeld) lock.release()
        Log.i(TAG, "keep awake: $on")
    }
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        if (screenReceiverOn) unregisterReceiver(screenReceiver)
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val current = settings ?: return false
        if (!current.remapEnabled) return false
        val code = event.keyCode
        // The rule editor is recording a key. Navigation keys go to its window (down moves to
        // «Отмена»); any other key the service records itself and keeps from the system: global
        // keys such as Netflix never reach a window, and would open their app. The editor closes
        // its step before the release arrives, so that release is kept back too (DV-023).
        if (DivanState.capturingKey) {
            if (code in PROTECTED_KEYS) return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                capturedKeys += code
                DivanState.recordedKeys.tryEmit(code)
                Log.i(TAG, "${keyName(code)}: recorded for the rule editor")
            }
            return true
        }
        if (code in capturedKeys) {
            if (event.action == KeyEvent.ACTION_UP) capturedKeys -= code
            return true
        }
        if (code in PROTECTED_KEYS) return false
        val rules = current.remapRules.filter { it.keyCode == code && inScope(it.scope) }.associateBy { it.press }
        if (rules.isEmpty()) return false

        val single = rules[Press.Single]
        val double = rules[Press.Double]
        val long = rules[Press.Long]

        // Only a single press mapped: act at once on the first down, no waiting. With «replace»
        // off the system still gets the key and does its usual thing itself.
        if (double == null && long == null) {
            val rule = single ?: return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) run(rule, heldBack = false)
            return rule.replace
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                // Another key waiting for its second press turned out to be a single press.
                (pendingSingles.keys - code).forEach { other ->
                    pendingSingles.remove(other)?.let {
                        handler.removeCallbacks(it)
                        it.run()
                    }
                }
                val waiting = pendingSingles.remove(code)
                if (waiting != null) {
                    // Only keys with a double-press rule ever wait.
                    handler.removeCallbacks(waiting)
                    swallowNextUp += code
                    double?.let { run(it, heldBack = true) }
                    return true
                }
                longFired -= code
                if (long != null) {
                    longRunnables[code] = Runnable {
                        longRunnables.remove(code)
                        longFired += code
                        run(long, heldBack = true)
                    }.also { handler.postDelayed(it, current.longPressMs.toLong()) }
                }
            }
            KeyEvent.ACTION_UP -> {
                longRunnables.remove(code)?.let { handler.removeCallbacks(it) }
                if (swallowNextUp.remove(code)) return true
                if (longFired.remove(code)) return true
                val singlePress = Runnable {
                    pendingSingles.remove(code)
                    if (single != null) run(single, heldBack = true) else passThrough(code)
                }
                if (double != null) {
                    pendingSingles[code] = singlePress
                    handler.postDelayed(singlePress, DOUBLE_PRESS_WINDOW_MS)
                } else {
                    singlePress.run()
                }
            }
        }
        return true
    }

    private fun inScope(scope: Scope): Boolean {
        // Divan's settings are Divan's package too, but not the home screen.
        val onHome = foregroundPackage == packageName && DivanState.homeShown
        return when (scope) {
            Scope.Everywhere -> true
            Scope.ExceptHome -> !onHome
            Scope.OnlyHome -> onHome
        }
    }

    /**
     * [heldBack]: the key was swallowed while deciding the press type, so with «replace» off its
     * usual action is reproduced here. Otherwise the system already got the key itself.
     */
    private fun run(rule: RemapRule, heldBack: Boolean) {
        Log.i(TAG, "${keyName(rule.keyCode)} ${rule.press} -> ${rule.action} ${rule.argument.orEmpty()}")
        perform(rule.action, rule.argument)
        if (!rule.replace && heldBack) passThrough(rule.keyCode)
    }

    private fun perform(action: RemapAction, argument: String?) {
        val audio = getSystemService(AudioManager::class.java)
        when (action) {
            RemapAction.OpenApp -> argument?.let { pkg ->
                // A locked app opens through Divan's PIN screen, which then starts it.
                if (settings?.isLocked(pkg) == true) {
                    start(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_UNLOCK_APP, pkg))
                    return
                }
                AppRepository(this).launchIntent(pkg)?.let { start(it) }
            }
            RemapAction.OpenUri -> argument?.let { uri ->
                start(linkIntent(uri))
            }
            RemapAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            RemapAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            RemapAction.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            RemapAction.Notifications -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            RemapAction.QuickSettings -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            RemapAction.PowerMenu -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            RemapAction.ScreenOff -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            RemapAction.VolumeUp -> audio?.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            RemapAction.VolumeDown -> audio?.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            RemapAction.Mute -> audio?.adjustSuggestedStreamVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            RemapAction.PlayPause -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            RemapAction.Next -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            RemapAction.Previous -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            RemapAction.Assistant -> start(Intent(Intent.ACTION_ASSIST)) || start(Intent(Intent.ACTION_VOICE_COMMAND))
            RemapAction.DivanSettings -> start(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true))
            RemapAction.Nothing -> Unit
        }
    }

    /**
     * Gives a held-back key its normal meaning when it turned out not to be remapped. An
     * accessibility service cannot re-inject keys, so this covers the keys it can reproduce.
     */
    private fun passThrough(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_VOLUME_UP -> perform(RemapAction.VolumeUp, null)
            KeyEvent.KEYCODE_VOLUME_DOWN -> perform(RemapAction.VolumeDown, null)
            KeyEvent.KEYCODE_VOLUME_MUTE -> perform(RemapAction.Mute, null)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> mediaKey(code)
            else -> Log.i(TAG, "${keyName(code)}: cannot be passed through")
        }
    }

    private fun mediaKey(code: Int) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun start(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (error: RuntimeException) {
        // No such activity, SecurityException (protected activity), FileUriExposedException
        // (file:// link): a bad rule must not take the whole launcher process down with it.
        Log.w(TAG, "Cannot start $intent", error)
        false
    }

    private companion object {
        const val TAG = "DivanRemap"
        const val DOUBLE_PRESS_WINDOW_MS = 300L
    }
}
