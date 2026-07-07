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
├── metawear-core/       ← Android library. Nordic-backed BleTransport
│                          (NordicBleTransport), unfiltered scan source
│                          (AndroidBleScanSource), AndroidMetaWear entry point,
│                          instrumented hardware smoke tests.
├── metawear-persistence/← Android library. Room-backed log-session storage:
│                          PersistenceStore, session/sample records, CSV export.
│                          JVM store tests + instrumented database tests.
├── metawear-firmware/   ← (planned) Nordic Android DFU
└── app/                 ← (planned) Jetpack Compose app
```

`:metawear-protocol` is the foundation everything else builds on, ported
test-first to lock wire-format correctness before any BLE code. The full
vertical slice — scan → connect → `startStream(accelerometer)` →
`Flow<Timestamped<CartesianFloat>>` — runs end-to-end against `MockBleTransport`
on the JVM, and against real hardware via `:metawear-core`'s smoke suite.

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

All 22 Swift module files are ported, plus the full device-side logging
surface: `startLogging`/`stopLogging` (including polled readables via the
timer→event→logger chain and processor handles), `downloadLogs` with chunk
reassembly and watchdog, `clearLog`/`flushLogPage`, logger/processor query and
recovery, anonymous-signal reconstruction, `factoryReset`, board-state
capture/restore, and `DataTable` CSV export. The only intentional behavioral
deviation: the data-processor stream uses client-side processor-id filtering
rather than the Swift per-id demux.

**Test parity: 994 JVM tests vs 932 in the Swift package's no-hardware suite**
(the Kotlin suite adds coverage for paths Swift only exercises on hardware).

## What's in `:metawear-core`

The Android-only transport layer (`com.mbientlab.metawear.core`), built on the
[Nordic Kotlin BLE Library](https://github.com/NordicSemiconductor/Kotlin-BLE-Library):

| Kotlin | Ported from (Swift) |
|---|---|
| `NordicBleTransport` | `CoreBluetoothPeripheralTransport.swift` (per-peripheral connect/write/read/notify/RSSI) |
| `AndroidBleScanSource` | `MWCentralManager.swift` (the scanning half) |
| `AndroidMetaWear` | `MetaWearScanner()` default wiring |
| `androidTest/HardwareSupport`, `HardwareSmokeTest` | `Tests/MetaWearHardwareTests` essentials |

Transport notes:
- `connect()` retries Android's transient status-133 (`GATT_ERROR`) failures,
  discovers all services (so Device Information Service reads resolve by
  characteristic UUID alone), requests MTU 247 (packed 3-sample streaming does
  not fit the 23-byte default), and enables notifications on `326A9006-…`.
- GATT operations are serialized by the Nordic client's internal per-connection
  mutex — Android allows only one outstanding GATT op.
- Scans run **without** a service-UUID filter: MetaWear boards don't reliably
  advertise the custom service UUID, so `MetaWearScanner` matches the
  "MetaWear" name prefix instead.
- The library declares Bluetooth permissions but never requests them; apps
  must be granted `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+) or the
  legacy `BLUETOOTH`/`ACCESS_FINE_LOCATION` pair (API ≤ 30) first.

## What's in `:metawear-persistence`

Room-backed storage for downloaded log sessions
(`com.mbientlab.metawear.persistence`) — a port of the Swift
`MetaWearPersistence` SwiftData package:

| Kotlin | Ported from (Swift) |
|---|---|
| `SessionRecord`, `SampleRecord` (`@Entity`, cascade foreign key) | `MWSessionRecord`, `MWSampleRecord` (`@Model`) |
| `PersistenceStore` (suspend methods over a Room DAO) | `MWPersistenceStore` (`@ModelActor` actor) |
| `PersistenceDatabase` (one per app) | `ModelContainer` ("one container per app") |
| `SessionSnapshot` | `MWSessionSnapshot` |
| `Persistable` codec objects (`CartesianFloatPersistable`, …) | `MWPersistable` retroactive conformances |
| `PersistenceException` | `MWPersistenceError` |

All six supported sample types persist through one flat `(f0…f3, accuracy)`
record layout: `Float`, `Boolean`, `CartesianFloat`, `CorrectedCartesianFloat`,
`Quaternion`, `EulerAngles`. Swift's static protocol requirements (including on
`Float`/`Bool`) become one singleton codec object per type. `Instant`s persist
as epoch milliseconds; sessions are identified by store-assigned UUID strings
and devices by their Android MAC identifier (the Swift package uses
CoreBluetooth UUIDs for both). `PersistenceStore.exportTable` rebuilds a
`DataTable` for CSV export straight from the database.

Room's annotation processing runs through KSP — the standalone-versioned
KSP ≥ 2.3 line, which works with AGP 9's built-in Kotlin. All 42 Swift
persistence tests are ported as JVM unit tests (store logic runs against an
in-memory fake of the DAO interface), and an instrumented suite re-checks what
the fake can only mirror — the generated SQL's sort orders and counts, the
foreign-key cascade, and the epoch-millis converter — against a real Room
database on-device.

## Build & test

```bash
./gradlew :metawear-protocol:test                    # ported parsing/command/transport tests (JVM)
./gradlew :metawear-core:assembleDebug               # Android transport library (AAR)
./gradlew :metawear-persistence:testDebugUnitTest    # ported persistence tests (JVM)
./gradlew :metawear-persistence:connectedAndroidTest # Room round-trips (needs a device)
```

Opens directly in Android Studio; the Kotlin toolchain targets JDK 21.
`:metawear-core` uses AGP 9's built-in Kotlin (no `org.jetbrains.kotlin.android`
plugin — AGP 8.x does not run on this repo's Gradle 9.6).

### Hardware smoke tests

`:metawear-core` ships an instrumented smoke suite that exercises a real
MetaMotion S (BMI270) end-to-end: scan → connect (device info + module
discovery) → battery read → LED pattern → 2 s accelerometer stream (~1 g at
rest) → disconnect.

1. Connect an Android phone with USB debugging enabled (API 31+).
2. Charge a MetaMotion S and place it within BLE range.
3. Run:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :metawear-core:connectedAndroidTest
```

The suite scans for the first advertised "MetaWear" name for 10 s and **skips
itself cleanly** (JUnit assumption) when no board is in range, so the task is
safe to run on benches without hardware. With several boards in range it picks
the lowest MAC for run-to-run stability.

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
