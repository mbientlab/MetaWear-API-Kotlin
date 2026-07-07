package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet

// LED module (0x02) patterns and commands.
//
// Register opcodes follow the C++ SDK header `Led.h` (the firmware
// `LedRegister` enum): PLAY = 0x01, STOP = 0x02, SETPATTERN = 0x03.

/**
 * One color channel's LED pulse shape.
 *
 * The LED follows a four-phase envelope per pulse:
 * 1. Ramp from [lowIntensity] to [highIntensity] over [riseTime] ms.
 * 2. Hold at [highIntensity] for [highTime] ms.
 * 3. Ramp from [highIntensity] back to [lowIntensity] over [fallTime] ms.
 * 4. Hold at [lowIntensity] until [pulseDuration] ms have elapsed from the
 *    start of the pulse, then begin the next pulse.
 *
 * [delay] offsets the first pulse, and [repeatCount] controls how many pulses
 * play (0xFF = forever). Use the presets ([solid], [blink], [breathe],
 * [flash]) as a starting point.
 */
data class LedPattern(
    /** LED brightness during the high phase (0–31). */
    val highIntensity: Int = 31,
    /** LED brightness during the low phase (0–31). Usually 0. */
    val lowIntensity: Int = 0,
    /** Time (ms) to ramp from lowIntensity to highIntensity. */
    val riseTime: Int = 0,
    /** Time (ms) to hold at highIntensity. */
    val highTime: Int = 500,
    /** Time (ms) to ramp from highIntensity back to lowIntensity. */
    val fallTime: Int = 0,
    /** Time (ms) to hold at lowIntensity before the next pulse. */
    val pulseDuration: Int = 1000,
    /** Time (ms) before the pattern starts. Useful to phase-offset multiple channels. */
    val delay: Int = 0,
    /**
     * Number of pulses to play (1–254). 0xFF = repeat indefinitely.
     *
     * The firmware treats a raw 0 as undefined behaviour, so the encoder
     * rewrites 0 to 0xFF (indefinite) — matching the C++ SDK's guidance
     * "use 0xFF, not 0; 0 causes undefined behaviour on firmware".
     */
    val repeatCount: Int = 0xFF,
) {
    companion object {
        /** Solid on (no blinking). lowIntensity == highIntensity keeps the LED lit during the low phase. */
        val solid = LedPattern(
            highIntensity = 31, lowIntensity = 31, riseTime = 0, highTime = 500,
            fallTime = 0, pulseDuration = 1000, repeatCount = 0xFF,
        )

        /** Simple blink: 50 ms on, 450 ms off. */
        val blink = LedPattern(
            highIntensity = 31, riseTime = 0, highTime = 50,
            fallTime = 0, pulseDuration = 500, repeatCount = 0xFF,
        )

        /** Soft breathe: ramp up and down over 2 seconds. */
        val breathe = LedPattern(
            highIntensity = 31, riseTime = 725, highTime = 500,
            fallTime = 725, pulseDuration = 2000, repeatCount = 0xFF,
        )

        /** Single short flash (3 pulses of 100 ms). */
        val flash = LedPattern(
            highIntensity = 31, riseTime = 0, highTime = 100,
            fallTime = 0, pulseDuration = 500, repeatCount = 3,
        )
    }
}

/** Namespace for MetaWear LED (module 0x02) commands. */
object Led {

    /** PLAY register: `[mod, 0x01, 0x01]` plays; `0x02` autoplay; `0x00` pauses. */
    private const val REGISTER_PLAY = 0x01

    /** STOP register: `[mod, 0x02, clearFlag]`. `clearFlag = 1` also erases configured patterns. */
    private const val REGISTER_STOP = 0x02

    /** SETPATTERN register: full 17-byte pattern config for one color channel. */
    private const val REGISTER_SET_PATTERN = 0x03

    /**
     * Color channels available on all MetaWear boards.
     * [raw] matches the firmware's channel index.
     */
    enum class Color(val raw: Int) {
        GREEN(0),
        RED(1),
        BLUE(2),
    }

    /**
     * Write a pattern to one color channel.
     * Must be followed by [Play] to start the animation.
     */
    class SetPattern(val color: Color, val pattern: LedPattern) : Command {
        override val commandData: ByteArray
            get() {
                // Firmware UB guard: a raw repeat count of 0 is undefined on the
                // board — encode it as 0xFF (indefinite) instead.
                val repeat = if (pattern.repeatCount == 0) 0xFF else pattern.repeatCount
                val payload = byteArrayOf(
                    color.raw.toByte(), 0x02,
                    pattern.highIntensity.toByte(), pattern.lowIntensity.toByte(),
                ) + le16(pattern.riseTime) +
                    le16(pattern.highTime) +
                    le16(pattern.fallTime) +
                    le16(pattern.pulseDuration) +
                    le16(pattern.delay) +
                    byteArrayOf(repeat.toByte())
                return Packet.command(Module.LED, REGISTER_SET_PATTERN, payload)
            }
    }

    /** Start LED playback (plays all configured channels). */
    class Play : Command {
        override val commandData: ByteArray = Packet.command(Module.LED, REGISTER_PLAY, 0x01)
    }

    /**
     * Start LED playback and immediately play any patterns programmed in the future.
     * Use this instead of [Play] when you want subsequent [SetPattern] commands to
     * start automatically.
     */
    class Autoplay : Command {
        override val commandData: ByteArray = Packet.command(Module.LED, REGISTER_PLAY, 0x02)
    }

    /** Pause playback without clearing patterns. */
    class Pause : Command {
        override val commandData: ByteArray = Packet.command(Module.LED, REGISTER_PLAY, 0x00)
    }

    /** Stop playback. */
    class Stop(
        /** When `true`, also erases all configured patterns from the board. */
        val clearPattern: Boolean = true,
    ) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.LED, REGISTER_STOP, if (clearPattern) 0x01 else 0x00)
    }

    /**
     * Configure multiple color channels at once and optionally start playback
     * immediately.
     *
     * A multi-channel setup cannot be encoded as a single BLE packet; callers
     * should use [MetaWearDevice.setLed] instead, which sends one command per
     * channel. [commandData] is provided for [Command] conformance only and
     * encodes the first non-null channel.
     */
    class SetAllChannels(
        val red: LedPattern? = null,
        val green: LedPattern? = null,
        val blue: LedPattern? = null,
        /** When `true`, a [Play] command is appended after the pattern bytes. */
        val autoPlay: Boolean = true,
    ) : Command {
        override val commandData: ByteArray
            get() = when {
                green != null -> SetPattern(Color.GREEN, green).commandData
                red != null -> SetPattern(Color.RED, red).commandData
                blue != null -> SetPattern(Color.BLUE, blue).commandData
                else -> ByteArray(0)
            }
    }
}

/** 16-bit little-endian encoding for LED pattern timing fields. */
private fun le16(value: Int): ByteArray =
    byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

// ---- MetaWearDevice LED convenience ----

/**
 * Set one or more LED color channels and optionally start playback.
 *
 * ```kotlin
 * device.setLed(red = LedPattern.blink, blue = LedPattern.solid, autoPlay = true)
 * ```
 */
suspend fun MetaWearDevice.setLed(
    red: LedPattern? = null,
    green: LedPattern? = null,
    blue: LedPattern? = null,
    autoPlay: Boolean = true,
) {
    // Always reset first — required by firmware before writing new patterns.
    send(Led.Stop(clearPattern = true))
    green?.let { send(Led.SetPattern(Led.Color.GREEN, it)) }
    red?.let { send(Led.SetPattern(Led.Color.RED, it)) }
    blue?.let { send(Led.SetPattern(Led.Color.BLUE, it)) }
    if (autoPlay) send(Led.Play())
}

/** Stop all LED channels and optionally clear patterns. */
suspend fun MetaWearDevice.stopLed(clearPattern: Boolean = true) {
    send(Led.Stop(clearPattern))
}
