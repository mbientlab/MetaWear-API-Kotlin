# MetaWear Android SDK (Kotlin)

A coroutine/Flow-native Kotlin port of the [MetaWear Swift SDK](../MetaWear-API-Swift),
built for Android. Structured **KMP-ready, Android-only**: the pure protocol/parsing
layer has zero Android dependencies and is unit-tested on a plain JVM, while the
transport and app layers are Android-specific.

## Module layout

```
metawear-android/
├── metawear-protocol/   ← pure Kotlin/JVM. Packet builder, parser, value types,
│                          sensor interfaces + configs, BleTransport seam + mock.
│                          Fast JVM unit tests (Steps 1–2 of the build plan).
├── metawear-core/       ← (planned) Scanner, Device, Nordic-backed BleTransport
├── metawear-persistence/← (planned) Room session storage
├── metawear-firmware/   ← (planned) Nordic Android DFU
└── app/                 ← (planned) Jetpack Compose app
```

Only `:metawear-protocol` exists today — it is the foundation everything else
builds on, ported test-first to lock wire-format correctness before any BLE code.

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

The transport *interface* lives here (it is pure JVM: `java.util.UUID`,
`ByteArray`, `Flow`) so the upcoming protocol router and device layer stay
JVM-testable against `MockBleTransport`. Only the Nordic-backed implementation
is Android-specific and belongs in `:metawear-core`.

Android adaptation to note: the Swift `ScanResult.identifier` is a CoreBluetooth
`UUID`; on Android peripherals are identified by MAC address, so the seam uses
an opaque `String`.

The accelerometer is the representative streamable for the first vertical slice;
the remaining 21 modules (gyro, magnetometer, LED, …) land in the module fan-out
(Step 5 of the build plan).

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
- `MockBleTransport` notification flows are backed by unbounded `Channel`s;
  `close(cause)` reproduces `AsyncThrowingStream`'s buffered-then-fail delivery
  on simulated disconnects.
