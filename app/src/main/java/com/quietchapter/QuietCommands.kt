package com.quietchapter

/** Parked simulation/debug controls, granted only to this app's UID. */
object QuietCommands {
    const val DEBUG_FREEZE_CLOCK = "com.quietchapter.DEBUG_FREEZE_CLOCK"
    const val IMPORT_AUDIO = "com.quietchapter.IMPORT_AUDIO"
    const val PREPARE = "com.quietchapter.PREPARE_DEMO"
    const val SIMULATE = "com.quietchapter.SIMULATE"
    const val STOP_SIMULATION = "com.quietchapter.STOP_SIMULATION"
    const val SNAPSHOT = "com.quietchapter.SNAPSHOT"
    const val SIMULATED_EVENT = "com.quietchapter.SIMULATED_EVENT"
    const val DELAY_NEXT_PREPARATION = "com.quietchapter.DEBUG_DELAY_NEXT_PREPARATION"
    const val DELAY_NEXT_RESOLUTION = "com.quietchapter.DEBUG_DELAY_NEXT_RESOLUTION"
    const val RECOVERY_MODE = "com.quietchapter.RECOVERY_MODE"
    val debugOnly = setOf(DEBUG_FREEZE_CLOCK, SIMULATE, STOP_SIMULATION, SIMULATED_EVENT, DELAY_NEXT_PREPARATION, DELAY_NEXT_RESOLUTION)
    val all = listOf(RECOVERY_MODE, DEBUG_FREEZE_CLOCK, IMPORT_AUDIO, PREPARE, SIMULATE, STOP_SIMULATION, SNAPSHOT, SIMULATED_EVENT, DELAY_NEXT_PREPARATION, DELAY_NEXT_RESOLUTION)
}
