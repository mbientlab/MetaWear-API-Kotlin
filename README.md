# MetaWear Android SDK (Kotlin)

A coroutine/Flow-native Kotlin port of the [MetaWear Swift SDK](../MetaWear-API-Swift),
built for Android. Structured **KMP-ready, Android-only**: the pure protocol/parsing
layer has zero Android dependencies and is unit-tested on a plain JVM, while the
transport and app layers are Android-specific.

## Module layout

```
metawear-android/
├── metawear-protocol/   ← pure Kotlin/JVM. Packet builder, parser, value types,
│                          sensor interfaces, sensor configs. Fast JVM unit tests.
│                          (this is what Step 1 builds)
├── metawear-core/       ← (planned) Scanner, Device, BleTransport + Nordic impl
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

The accelerometer is the representative streamable for the first vertical slice;
the remaining 21 modules (gyro, magnetometer, LED, …) land in the module fan-out
(Step 5 of the build plan).

## Build & test

Requires a JDK (17+). The Gradle toolchain is configured to auto-provision JDK 17.

```bash
./gradlew :metawear-protocol:test     # run the ported parsing/command tests
```

This repo ships `gradle/wrapper/gradle-wrapper.properties` but **not** the
`gradle-wrapper.jar` binary. In Android Studio that's fine — on import, Studio
provisions Gradle 8.11.1 (per the properties) and can regenerate the wrapper. To
materialise the wrapper from a CLI, run `gradle wrapper` once from any system
Gradle, then `./gradlew :metawear-protocol:test`.

## Design notes

- **Kotlin idiom map** for the wider port: Swift `actor` → a single `Channel`-drained
  worker coroutine (which also serializes Android GATT ops); `AsyncThrowingStream`
  → cold `Flow` via `callbackFlow`; `CheckedContinuation` → `suspendCancellableCoroutine`.
- **`ByteArray` equality** is reference-based in Kotlin — tests compare command
  bytes with `assertArrayEquals`, never `==`.
- Bytes are built from `Int` literals via a `bytes(…)` test helper to avoid the
  `byteArrayOf(0x80)` "does not fit in Byte" compile error.
```
