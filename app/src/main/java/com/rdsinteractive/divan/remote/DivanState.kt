package com.rdsinteractive.divan.remote

/**
 * What Divan's own screens are doing, for the remote service (same process). The service gets
 * keys before any window, so it has to know when not to act.
 */
object DivanState {
    /** The rule editor waits for a key to record: the service must not run that key's rule. */
    @Volatile var capturingKey = false

    /** Divan's home screen itself is in front (not its settings): scope «Только на главном экране». */
    @Volatile var homeShown = false
}
