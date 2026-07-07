package com.mbientlab.metawear.persistence

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.DeviceInformation
import com.mbientlab.metawear.model.LoggedSample
import java.util.UUID
import kotlinx.datetime.Instant

// Shared helpers for the store/export tests — ports of the file-private
// helpers in MWPersistenceStoreTests.swift.

internal fun makeStore(): PersistenceStore = PersistenceStore(FakePersistenceDao())

internal fun makeDeviceInfo(serial: String = "AA:BB:CC:DD:EE:FF"): DeviceInformation =
    DeviceInformation(
        manufacturer = "MbientLab Inc.",
        modelNumber = "4",
        serialNumber = serial,
        firmwareRevision = "1.7.0",
        hardwareRevision = "0.4",
    )

/**
 * A unique device identifier. The Swift tests use `UUID()` (CoreBluetooth
 * peripheral IDs); on Android device IDs are opaque strings, so a random UUID
 * string plays the same role.
 */
internal fun randomDeviceID(): String = UUID.randomUUID().toString()

internal fun cartesianSamples(
    count: Int,
    startTickMs: Double = 0.0,
): List<LoggedSample<CartesianFloat>> = (0 until count).map { i ->
    LoggedSample(
        date = Instant.fromEpochSeconds(i.toLong()),
        tickMs = startTickMs + i * 10.0,
        value = CartesianFloat(x = i * 0.1f, y = i * 0.2f, z = i * 0.3f),
    )
}

/** Fixed stand-in for the Swift tests' `Date()` where the timestamp is irrelevant. */
internal val someInstant: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
