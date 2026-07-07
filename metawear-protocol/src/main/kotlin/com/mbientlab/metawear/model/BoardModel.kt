package com.mbientlab.metawear.model

/**
 * The MetaWear board model in use.
 *
 * The model is derived from the Model Number BLE characteristic (`0x2A24`):
 *
 * | Model number | Board                  | Hardware revisions                     |
 * |:------------:|:-----------------------|:---------------------------------------|
 * | `"5"`        | MetaMotion R / RL      | r0.1, r0.2, r0.3, r0.4, r0.5           |
 * | `"8"`        | MetaMotion S           | r0.1                                   |
 *
 * Anything else maps to [Unknown].
 */
sealed class BoardModel {

    /** MetaMotion R / RL — BMI160 accelerometer + gyroscope (model number "5"). */
    object MotionRL : BoardModel()

    /** MetaMotion S — BMI270 accelerometer + gyroscope, extra flash (model number "8"). */
    object MotionS : BoardModel()

    /** Any board whose model number is not "5" or "8". */
    data class Unknown(val modelNumber: String) : BoardModel()

    val displayName: String
        get() = when (this) {
            MotionRL -> "MetaMotion R / RL"
            MotionS -> "MetaMotion S"
            is Unknown -> "Unknown ($modelNumber)"
        }

    /** MMS has larger flash and requires a flush-page before log download. */
    val hasMMS: Boolean get() = this == MotionS

    /** Hardware revisions known to ship for this model, in shipping order. */
    val supportedHardwareRevisions: List<String>
        get() = when (this) {
            MotionRL -> listOf("r0.1", "r0.2", "r0.3", "r0.4", "r0.5")
            MotionS -> listOf("r0.1")
            is Unknown -> emptyList()
        }

    /**
     * Whether [revision] ships for this model. Accepts both the canonical `r0.X`
     * form and the bare `0.X` form; comparison is case-insensitive and tolerates
     * a missing leading `r`.
     */
    fun isHardwareRevisionSupported(revision: String): Boolean {
        val normalized = normalize(revision)
        return supportedHardwareRevisions.map { normalize(it) }.contains(normalized)
    }

    companion object {
        /** Build from the Model Number BLE characteristic value. */
        fun fromModelNumber(modelNumber: String): BoardModel = when (modelNumber.trim()) {
            "5" -> MotionRL
            "8" -> MotionS
            else -> Unknown(modelNumber)
        }

        /** Collapse "R0.4", "r0.4", and "0.4" all to "0.4". */
        private fun normalize(s: String): String {
            val t = s.trim().lowercase()
            return if (t.startsWith("r")) t.substring(1) else t
        }
    }
}
