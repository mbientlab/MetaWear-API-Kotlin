package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.sensor.Settings
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented port of the Swift `SettingsTests` — battery state, device
 * name, TX power, and advertising, on the settings module (0x11).
 *
 * Deviation from Swift: settings_setAndRestoreDeviceName verifies the new
 * name over the air via disconnect/rescan cycles (~30 s of scanning); here the
 * name write is exercised command-only and restored to the factory default,
 * keeping the suite bench-friendly. RSSI plausibility rides along as the
 * radio-side sanity check instead.
 */
@RunWith(AndroidJUnit4::class)
class SettingsHardwareTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun battery_readsRealisticChargeAndVoltage() =
        HardwareSupport.withConnectedDevice { device ->
            val battery = device.read(Settings.ReadBatteryState()).value
            assertTrue("charge out of range: ${battery.charge} %", battery.charge in 0..100)
            // Swift battery_readReturnsBatteryState pins a realistic Li-Po
            // window: 2.5–5.0 V.
            assertTrue(
                "voltage implausible for a Li-Po cell: ${battery.voltage} mV",
                battery.voltage in 2500..5000,
            )
        }

    @Test
    fun deviceName_setAndRestore_doesNotThrow() =
        HardwareSupport.withConnectedDevice { device ->
            device.send(Settings.SetDeviceName("MWTest"))
            delay(200)
            // Restore the factory default so the bench board keeps its
            // discoverable name for the other suites' scans.
            device.send(Settings.SetDeviceName("MetaWear"))
            delay(200)
        }

    @Test
    fun txPower_setEachLevel_doesNotThrow() =
        HardwareSupport.withConnectedDevice { device ->
            device.send(Settings.SetTxPower(Settings.TxPower.PLUS_4))
            delay(100)
            device.send(Settings.SetTxPower(Settings.TxPower.ZERO)) // radio default
            delay(100)
        }

    @Test
    fun rssi_whileConnected_isPlausiblyNegative() =
        HardwareSupport.withConnectedDevice { device ->
            val rssi = device.readRSSI()
            // Live BLE RSSI is strictly negative; below -126 is off the
            // protocol floor (same bounds as the scan smoke test).
            assertTrue("RSSI out of plausible range: $rssi dBm", rssi in -126..-1)
        }

    @Test
    fun startAdvertising_doesNotThrow() =
        HardwareSupport.withConnectedDevice { device ->
            device.send(Settings.StartAdvertising())
        }
}
