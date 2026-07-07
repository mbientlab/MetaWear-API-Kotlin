# MetaWear Android SDK (Kotlin)

A coroutine/Flow-native Kotlin port of the [MetaWear Swift SDK](../MetaWear-API-Swift),
built for Android. Structured **KMP-ready, Android-only**: the pure protocol/parsing
layer has zero Android dependencies and is unit-tested on a plain JVM, while the
transport and app layers are Android-specific.

## Module layout

```
metawear-android/
├── metawear-protocol/   ← pure Kotlin/JVM. Packet builder, parser, value types,
│                          sensor interfaces + configs, BleTransport seam + mock,
│                          protocol router, MetaWearDevice, MetaWearScanner.
│                          Fast JVM unit tests (Steps 1–3 of the build plan).
├── metawear-core/       ← (planned) Nordic-backed BleTransport, Android wiring
├── metawear-persistence/← (planned) Room session storage
├── metawear-firmware/   ← (planned) Nordic Android DFU
└── app/                 ← (planned) Jetpack Compose app
```

Only `:metawear-protocol` exists today — it is the foundation everything else
builds on, ported test-first to lock wire-format correctness before any BLE code.
The full vertical slice — scan → connect → `startStream(accelerometer)` →
`Flow<Timestamped<CartesianFloat>>` — runs end-to-end against `MockBleTransport`.

## What's in `:metawear-protocol`

| Kotlin | Ported from (Swift) |
|---|---|
| `protocol.Module`, `protocol.Packet` | `MWModule.swift` |
| `protocol.PacketParser` | `MWPacketParser.swift` |
| `protocol.{Sensor, Streamable, Loggable, Readable, Command, CommandSequence, Pollable}` | `MWActions.swift` |
| `model.{CartesianFloat, Quaternion, EulerAngles, …, Timestamped, Frequency, ModuleInfo}` | `MWTypes.swift` |
| `model.MetaWearException` | `MWError.swift` |
| `model.BoardModel` | `MWModel.swift` |
| `sensor.BoschImuSensor` | `MWBoschIMUSensor.swift` |
| `sensor.{AccelerometerBmi160, AccelerometerBmi270}` | `MWAccelerometer.swift` |
| `transport.{BleTransport, ScanResult, WriteType}` | `BLETransport.swift` |
| `transport.MockBleTransport` | `MockBLETransport.swift` |
| `transport.Uuids` | `MWUUIDs.swift` |
| `protocol.ProtocolRouter` | `MWProtocolLayer.swift` |
| `MetaWearDevice`, `DeviceState` | `MetaWearDevice.swift` (connection, state machine, streaming, send/read/poll slice) |
| `MetaWearScanner` | `MetaWearScanner.swift` |
| `sensor.{Gyroscope*, Magnetometer, AccelerometerBosch, AccelerometerBmi270Features/Steps}` | `MWGyroscope/MWMagnetometer/MWAccelerometer.swift` |
| `sensor.{SensorFusion*, SensorFusionCalibration}` | `MWSensorFusion.swift` |
| `sensor.{Barometer, Altimeter, AmbientLight, Thermometer, Humidity}` | `MWBarometer/MWAmbientLight/MWTemperature/MWHumidity.swift` |
| `sensor.{Led, Haptic, Switch, IBeacon, Debug, Settings}` | `MWLED/MWHaptic/MWSwitch/MWiBeacon/MWDebug/MWSettings.swift` |
| `sensor.{MetaWearTimer, Event, Macro, Gpio, Serial}` | `MWTimer/MWEvent/MWMacro/MWGPIO/MWSerial.swift` |
| `sensor.{DataProcessor, DataProcessorSignals, MiscReadables}` | `MWDataProcessor/MWMiscReadables.swift` |

The transport *interface* lives here (it is pure JVM: `java.util.UUID`,
`ByteArray`, `Flow`) so the upcoming protocol router and device layer stay
JVM-testable against `MockBleTransport`. Only the Nordic-backed implementation
is Android-specific and belongs in `:metawear-core`.

Android adaptation to note: the Swift `ScanResult.identifier` is a CoreBluetooth
`UUID`; on Android peripherals are identified by MAC address, so the seam uses
an opaque `String`.

All 22 Swift module files are ported. Known deferred items (they need the
device-logging port): polled-loggable conformances, processor-handle logging,
`factoryReset`, and log download/decode. The data-processor stream uses
client-side processor-id filtering rather than the Swift per-id demux.

## Build & test

```bash
./gradlew :metawear-protocol:test     # run the ported parsing/command/transport tests
```

Opens directly in Android Studio; the Kotlin toolchain targets JDK 21.

## Design notes

- **Kotlin idiom map** for the wider port: Swift `actor` → a single `Channel`-drained
  worker coroutine (which also serializes Android GATT ops); `AsyncThrowingStream`
  → cold `Flow` via `callbackFlow`; `CheckedContinuation` → `suspendCancellableCoroutine`.
- **`ByteArray` equality** is reference-based in Kotlin — tests compare command
  bytes with `assertArrayEquals`, never `==`; value classes wrapping `ByteArray`
  (`ScanResult`, `MockBleTransport.Write`) override `equals` with `contentEquals`.
- Bytes are built from `Int` literals via a `bytes(…)` test helper to avoid the
  `byteArrayOf(0x80)` "does not fit in Byte" compile error.
- `MockBleTransport` notification flows deliver termination **in-band** (as
  sealed events through the channel) rather than via `Channel.close(cause)`:
  closing a channel does not wake a receiver parked on a coroutines-test
  `backgroundScope` dispatcher, while `trySend` does. In-band markers also
  reproduce `AsyncThrowingStream`'s buffered-then-fail delivery exactly.
- In tests, prefer `runCurrent()` (or suspending the test body) over
  `advanceUntilIdle()` when the thing you're waiting on runs in
  `backgroundScope` — `advanceUntilIdle` only drains foreground tasks.
- Where the Swift router needs a task-group race plus tombstoned waiter IDs for
  its read timeout, the Kotlin port uses `withTimeout` + `invokeOnCancellation`
  (atomic with respect to resume), so the waiter map is simply pruned.
- Expected-failure `async` blocks in tests catch inside the block
  (`async { runCatching { … } }`) — a failed `async` child cancels the test
  scope even if the `await` is wrapped.
