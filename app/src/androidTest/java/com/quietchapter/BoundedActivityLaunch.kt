package com.quietchapter

import android.app.Instrumentation
import android.content.Intent

/** Observe our Activity with a public bounded monitor; don't wait for the whole automotive host to idle. */
internal fun Instrumentation.launchParkedActivity(): MainActivity {
    val monitor = addMonitor(MainActivity::class.java.name, null, false)
    try {
        targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return monitor.waitForActivityWithTimeout(10_000) as? MainActivity
            ?: error("Parked Activity did not launch within10s")
    } finally { removeMonitor(monitor) }
}
