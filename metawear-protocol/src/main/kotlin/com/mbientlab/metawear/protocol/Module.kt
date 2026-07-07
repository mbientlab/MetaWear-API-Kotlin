package com.mbientlab.metawear.protocol

/**
 * MetaWear module opcodes.
 *
 * Every command and notification on the wire begins with a module byte; [from]
 * resolves an incoming byte back to its module.
 */
enum class Module(val value: Int) {
    SWITCH(0x01),
    LED(0x02),
    ACCELEROMETER(0x03),
    TEMPERATURE(0x04),
    GPIO(0x05),
    IBEACON(0x07),
    HAPTIC(0x08),
    DATA_PROCESSOR(0x09),
    EVENT(0x0A),
    LOGGING(0x0B),
    TIMER(0x0C),
    SERIAL(0x0D),
    MACRO(0x0F),
    SETTINGS(0x11),
    BAROMETER(0x12),
    GYRO(0x13),
    AMBIENT_LIGHT(0x14),
    MAGNETOMETER(0x15),
    HUMIDITY(0x16),
    SENSOR_FUSION(0x19),
    DEBUG(0xFE);

    companion object {
        /** Resolve a module from its opcode byte, or `null` if unrecognised. */
        fun from(value: Int): Module? = entries.firstOrNull { it.value == value }
    }
}
