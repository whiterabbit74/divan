package com.rdsinteractive.divan.remote

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * What Divan's own screens are doing, for the remote service (same process). The service gets
 * keys before any window, so it has to know when not to act.
 */
object DivanState {
    /** The rule editor waits for a key to record: the service must not run that key's rule. */
    @Volatile var capturingKey = false

    /**
     * Keys the service recorded for the editor. Android delivers global keys (Netflix, YouTube on
     * the Google TV remote) to no window at all, so the editor would never see them itself.
     */
    val recordedKeys = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** Divan's home screen itself is in front (not its settings): scope «Только на главном экране». */
    @Volatile var homeShown = false
}
