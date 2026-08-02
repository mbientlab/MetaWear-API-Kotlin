# MetaWear Android SDK (Kotlin)

A coroutine/Flow-native Kotlin SDK for MbientLab MetaWear sensors, built for
Android. Structured **KMP-ready, Android-only**: the pure protocol/parsing
layer has zero Android dependencies and is unit-tested on a plain JVM, while
the transport and app layers are Android-specific.

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
├── metawear-firmware/   ← Android library. Nordic-DFU firmware updates:
│                          release-catalog client, bootloader interlock,
│                          Flow-based DFU progress, MetaWearDevice extensions.
│                          JVM unit tests (catalog/version/interlock logic).
└── app/                 ← Jetpack Compose demo app: scan, live streaming with
                           ring-buffer decimation, on-device logging + download,
                           session history with CSV export, LED/haptic controls,
                           device settings, firmware updates, and a
                           hardware-free demo mode (protocol-level MetaMotion S
                           emulator).
```

`:metawear-protocol` is the foundation everything else builds on, written
test-first to lock wire-format correctness before any BLE code. The full
vertical slice — scan → connect → `startStream(accelerometer)` →
`Flow<Timestamped<CartesianFloat>>` — runs end-to-end against `MockBleTransport`
on the JVM, and against real hardware via `:metawear-core`'s smoke suite.

The repo carries **1273 JVM tests** across the four testable modules
(1041 protocol + 46 persistence + 66 firmware + 120 app), plus instrumented
suites that need a device.

## What's in `:metawear-protocol`

The pure-JVM protocol layer (`com.mbientlab.metawear`):

- `protocol.Module`, `protocol.Packet`, `protocol.PacketParser` — module
  opcodes, command packet builder, and notification parser. All multi-byte
  payloads are little-endian.
- `protocol.{Sensor, Streamable, Loggable, Readable, Command, CommandSequence,
  Pollable}` — the capability interfaces every sensor module implements.
- `model.*` — pure value types: `CartesianFloat`, `Quaternion`, `EulerAngles`,
  `Timestamped`, `Frequency`, `ModuleInfo`, `BoardModel`, `BoardState`,
  `AnonymousSignal`, `DataTable` (CSV export), and the `MetaWearException`
  hierarchy.
- `sensor.*` — one namespace per board module: accelerometers (BMI160/BMI270
  plus the Bosch interrupt surface and BMI270 step/activity features),
  gyroscopes, magnetometer, sensor fusion + calibration, barometer/altimeter,
  ambient light, thermometer, humidity, LED, haptic, switch, iBeacon, debug,
  settings, timers, events, macros, GPIO, serial passthrough, data processors,
  and misc readables.
- `transport.{BleTransport, ScanResult, WriteType}` + `MockBleTransport` — the
  platform-agnostic BLE seam and its scriptable JVM mock.
- `protocol.ProtocolRouter` — routes BLE notifications between the transport
  and the sensor modules.
- `MetaWearDevice`, `DeviceState`, `MetaWearScanner` — connection state
  machine, streaming, send/read/poll surface, and scanner.

The transport *interface* lives here (it is pure JVM: `java.util.UUID`,
`ByteArray`, `Flow`) so the protocol router and device layer stay
JVM-testable against `MockBleTransport`. Only the Nordic-backed implementation
is Android-specific and belongs in `:metawear-core`.

Peripherals are identified by their Android MAC address, so the transport seam
uses an opaque `String` identifier.

All 22 board modules are covered, plus the full device-side logging
surface: `startLogging`/`stopLogging` (including polled readables via the
timer→event→logger chain and processor handles), `downloadLogs` with chunk
reassembly and watchdog, `clearLog`/`flushLogPage`, logger/processor query and
recovery, anonymous-signal reconstruction, `factoryReset`, board-state
capture/restore, and `DataTable` CSV export. Data-processor streaming uses a
per-id demux: one shared `(0x09, 0x03)` subscription fans packets out to
per-processor-id flows, so multiple processors stream simultaneously; the
flows complete cleanly on an intentional disconnect and fail with the
underlying error on an unexpected one.

**1041 JVM tests** cover this module, including reference byte vectors from
the MetaWear C++ SDK's Python test suite.

## What's in `:metawear-core`

The Android-only transport layer (`com.mbientlab.metawear.core`), built on the
[Nordic Kotlin BLE Library](https://github.com/NordicSemiconductor/Kotlin-BLE-Library):

- `NordicBleTransport` — per-peripheral connect/write/read/notify/RSSI.
- `AndroidBleScanSource` — the unfiltered BLE scan source.
- `AndroidMetaWear` — default `MetaWearScanner()` wiring.
- `androidTest/HardwareSupport`, `HardwareSmokeTest` + 10 per-module suites
  (gyro BMI270, magnetometer, sensor fusion, switch, haptic, GPIO, settings,
  logging round-trip, environment, one-shot reads); suites self-skip without
  a board.

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
(`com.mbientlab.metawear.persistence`):

- `SessionRecord`, `SampleRecord` — Room `@Entity` types with a cascade
  foreign key (deleting a session deletes its samples).
- `PersistenceStore` — suspend methods over a Room DAO.
- `PersistenceDatabase` — create one per app.
- `SessionSnapshot` — immutable session summary.
- `Persistable` codec objects (`CartesianFloatPersistable`, …) — one singleton
  codec per supported sample type.
- `PersistenceException` — the store's error taxonomy.

All six supported sample types persist through one flat `(f0…f3, accuracy)`
record layout: `Float`, `Boolean`, `CartesianFloat`, `CorrectedCartesianFloat`,
`Quaternion`, `EulerAngles`. `Instant`s persist as epoch milliseconds;
sessions are identified by store-assigned UUID strings and devices by their
Android MAC identifier. `PersistenceStore.exportTable` rebuilds a `DataTable`
for CSV export straight from the database.

Room's annotation processing runs through KSP — the standalone-versioned
KSP ≥ 2.3 line, which works with AGP 9's built-in Kotlin. **42 JVM unit
tests** cover the store logic (run against an in-memory fake of the DAO
interface), and an instrumented suite re-checks what the fake can only
mirror — the generated SQL's sort orders and counts, the foreign-key cascade,
and the epoch-millis converter — against a real Room database on-device.

## What's in `:metawear-firmware`

Nordic-DFU firmware updates (`com.mbientlab.metawear.firmware`) on top of the
[Nordic Android DFU Library](https://github.com/NordicSemiconductor/Android-DFU-Library):

- `FirmwareServer`, `FirmwareFetcher` (+ `HttpUrlConnectionFetcher`) — HTTP
  client for MbientLab's firmware release server.
- `FirmwareCatalog` — parser for the release-catalog JSON (hand-rolled, like
  the BoardState codec).
- `FirmwareBuild` — value type describing one firmware artifact.
- `FirmwareException` — sealed error taxonomy with stable messages.
- `BootloaderInterlock` — pure flash-plan decision table.
- `MetaWearVersion.kt` — dotted-numeric version compare.
- `DFUProgress`, `DfuSession` — a cold Flow over the Nordic DFU library's
  broadcasts.
- `MetaBootProbe` — one-shot GATT bootloader-version read.
- `FirmwareUpdate.kt` — `checkForFirmwareUpdate` / `updateFirmware` /
  `updateFirmwareToLatest` extensions on `MetaWearDevice`.

Usage: connect, then collect `device.updateFirmwareToLatest(context)` — a cold
`Flow<DFUProgress>` that walks catalog fetch → download → `[0xFE, 0x02]`
bootloader handoff (`sendExpectingDisconnect`) → Nordic DFU, emitting progress
the whole way; cancelling the collector aborts the transfer. An outdated
bootloader automatically becomes a multi-stage flash (bootloader chain first),
reported through `currentPart`/`totalParts`. When the flow completes, call
`connect()` again — the board rebooted and local device state is stale.

Android specifics:
- The DFU transfer runs inside an Android service. The library declares
  `MetaWearDfuService` (a `DfuBaseService` subclass) in its manifest, and
  manifest merging carries the registration into your app — nothing to add
  unless you subclass it for a foreground notification target, in which case
  also create Nordic's notification channel via
  `DfuServiceInitiator.createDfuNotificationChannel(context)` first.
- Firmware updates need the same `BLUETOOTH_CONNECT` runtime permission as
  the rest of the SDK.
- The catalog JSON is parsed by a minimal hand-rolled parser (android.org.json
  is stubbed out on the JVM unit-test classpath), keeping all **66 firmware
  tests** — version compare, catalog selection, server/mock-fetcher,
  bootloader interlock — plain JVM unit tests. The `DfuSession` /
  `MetaBootProbe` wrappers are hardware-only and carry no unit-test coverage.

## What's in `:app`

A Jetpack Compose demo app, lean but feature-complete:

- **Scan** — runtime BLE permission flow (`BLUETOOTH_SCAN`/`CONNECT` on 31+,
  fine location on 26–30), nearby devices from the SDK scanner's StateFlows
  (name, RSSI), remembered devices persisted by MAC.
- **Device hub** — connection state badge, model/firmware/battery summary,
  identify (LED flash), reconnect/disconnect, feature navigation.
- **Live stream** — multi-sensor picker with a "Motion & Fusion" section
  (accelerometer, gyroscope, magnetometer, and all seven sensor-fusion
  outputs, with ODR/range chips and a 100 Hz BLE bandwidth advisor) and an
  "Environmental" section: polled readables (temperature, humidity, polled
  pressure — 1 s–5 m interval picker) plus streamed barometer pressure,
  altitude, and ambient light with nominal-rate chips. Streamed sensors get a
  dependency-free Canvas line chart, live readout, and true effective-Hz;
  polled sensors get a latest-value tile (fed by `device.poll` one-shot
  reads) plus the same chart; the quaternion output additionally renders a
  live 3D orientation cube (a dependency-free Canvas wireframe with
  orthographic projection and depth cueing) that is **tared**: it shows
  rotation since a reference pose (auto-set from the first valid sample,
  re-zeroed by the Zero button) through the IMU's 90° mounting correction —
  the raw quaternion's absolute frame isn't stable session-to-session. While
  a fusion output streams, a calibration badge polls the accuracy state every
  2 s and coaches per sensor; the bar is MEDIUM, since HIGH is a live score
  the magnetometer legitimately loses indoors.
  The charting hot path keeps sensor-rate work off the UI: samples ingest
  into plain ring buffers on a background coroutine (full-resolution capture
  + 1-in-N decimated display ring) and a ~33 ms ticker snapshots into Compose
  state — nothing touches UI state at sensor rate. Stop archives each channel
  to session history; buffers export as CSV (quaternion buffers gain derived
  `heading,pitch,roll` columns matching the firmware's Euler convention).
- **Logging** — start/stop multi-sensor flash logging, elapsed clock, then a
  single raw download drain with progress, per-sensor typed decode, and
  persistence via `PersistenceStore`. Polled environmental sensors log
  through the SDK's timer → event → logger chain (`PolledLogger`); streamed
  barometer/ambient-light sessions decode to `Float`/lux samples — all export
  through the same CSV path. Pending session records (including the
  board-allocated polled-logger handles) are persisted, so they survive
  process death: on the next download the SDK's `recoverLoggers` rebuilds the
  chunk registry from the board's trigger table before decoding.
- **Group logging** — record the same sensors across a fleet of boards under
  one shared group id: boards are armed sequentially (connect → clear → start
  → verify entries actually land → disconnect), blink a gentle red recording
  heartbeat that re-arms itself via on-board disconnect events (event ids
  persisted for cleanup across app restarts), then stop + download the whole
  batch with per-board progress. A board carrying a **foreign log** (someone
  else's session) is detected on connect via a pure decision table — surfaced
  for download/discard when decodable, cleared silently when it's undecodable
  garbage, left alone when it's this app's own pending session. Foreign
  downloads rebuild decoders from the board's own logger configuration
  (anonymous signals) and label recovered sessions by signal identity.
- **Sessions** — history grouped by board identity (serial-keyed; titles
  prefer the freshest stamped name, then the MAC; shared names get
  disambiguated), swipe-to-delete with optimistic store delete, per-sample-
  type chart styles recovered from the stored kind + capture-time label,
  a 3D quaternion **replay** with play/pause/scrub timeline and 1×/2×/4×
  speeds, and `epoch,elapsed_ms,…` CSV export (quaternion sessions include
  derived Euler columns; filenames carry the capture-time board name and a
  session-id discriminator) shared through the system sheet.
- **Controls** — LED color/pattern presets with play/stop, haptic motor
  strength/pulse-width sliders, buzzer pulse.
- **Settings** — validated advertising rename, advertising interval/timeout,
  TX power, a **Maintenance** section (LED reset, macro clear, event + timer
  clear, restart-without-erase — each scoped so users don't reach for factory
  reset), and a confirm-dialog factory reset.
- **Firmware** — catalog update check plus a Nordic-DFU update flow with
  state/progress UI.
- **Demo mode** — `DemoBleTransport`, a protocol-level MetaMotion S emulator:
  module discovery, device-info / battery / MAC / temperature / humidity /
  pressure / illuminance / log reads, synthetic waveforms on every sensor
  including packed registers, fusion outputs, altitude, and ambient light,
  and full logging round trips — streamed, the polled timer/event chain, and
  recovery across a simulated process restart. The identity fleet (up to 16
  distinguishable boards) backs a three-board simulated fleet in the app, so
  group logging works end-to-end with no hardware. The scan screen offers
  demo mode via a toggle (and suggests it when Bluetooth is off). The demo
  pipeline — including full group-capture walks against the real persistence
  store — is exercised end-to-end by JVM unit tests through the real
  `MetaWearDevice`.

The 3D orientation view is deliberately a Canvas wireframe cube: the
photoreal case model, button/LED window materials, and scene lighting of a
full 3D asset pipeline have no dependency-free equivalent, and only the
quaternion frame math is load-bearing.

Install on a device or emulator:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:installDebug
```

## Build & test

```bash
./gradlew :metawear-protocol:test                    # parsing/command/transport tests (JVM)
./gradlew :metawear-core:assembleDebug               # Android transport library (AAR)
./gradlew :metawear-persistence:testDebugUnitTest    # persistence tests (JVM)
./gradlew :metawear-persistence:connectedAndroidTest # Room round-trips (needs a device)
./gradlew :metawear-firmware:testDebugUnitTest       # firmware tests (JVM)
./gradlew :app:testDebugUnitTest                     # app logic + demo-emulator tests (JVM)
./gradlew :app:assembleDebug                         # Compose app APK
```

Opens directly in Android Studio; the Kotlin toolchain targets JDK 21.
`:metawear-core` uses AGP 9's built-in Kotlin (no `org.jetbrains.kotlin.android`
plugin — AGP 8.x does not run on this repo's Gradle 9.6). `:app` adds the
Compose compiler Gradle plugin (`org.jetbrains.kotlin.plugin.compose`) pinned
to **2.2.10 — AGP 9.2.1's embedded Kotlin compiler version**, not the 2.1.20
used by the pure-JVM module's explicit Kotlin plugin.

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

- **`ByteArray` equality** is reference-based in Kotlin — tests compare command
  bytes with `assertArrayEquals`, never `==`; value classes wrapping `ByteArray`
  (`ScanResult`, `MockBleTransport.Write`) override `equals` with `contentEquals`.
- Bytes are built from `Int` literals via a `bytes(…)` test helper to avoid the
  `byteArrayOf(0x80)` "does not fit in Byte" compile error.
- `MockBleTransport` notification flows deliver termination **in-band** (as
  sealed events through the channel) rather than via `Channel.close(cause)`:
  closing a channel does not wake a receiver parked on a coroutines-test
  `backgroundScope` dispatcher, while `trySend` does. In-band markers also
  guarantee that packets buffered before a failure are delivered first, then
  the failure is thrown.
- In tests, prefer `runCurrent()` (or suspending the test body) over
  `advanceUntilIdle()` when the thing you're waiting on runs in
  `backgroundScope` — `advanceUntilIdle` only drains foreground tasks.
- The protocol router's read timeout uses `withTimeout` +
  `invokeOnCancellation` (atomic with respect to resume), so the waiter map is
  simply pruned — no tombstoning needed to stay consistent under cancellation.
- Expected-failure `async` blocks in tests catch inside the block
  (`async { runCatching { … } }`) — a failed `async` child cancels the test
  scope even if the `await` is wrapped.
