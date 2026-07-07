package com.mbientlab.metawear.app.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant

/**
 * Advertisement-freshness gate for the nearby-devices list: a device is shown
 * only while its last advertisement is younger than [WINDOW]; the boundary is
 * exclusive (exactly [WINDOW] old = stale) and a never-seen device is stale.
 */
object DeviceFreshness {

    /** How long a device stays "nearby" after its last advertisement. */
    val WINDOW: Duration = 2.minutes

    fun isFresh(lastSeen: Instant?, now: Instant): Boolean {
        if (lastSeen == null) return false
        return now - lastSeen < WINDOW
    }
}
