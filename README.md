# MetaWear Android SDK (Kotlin)

A coroutine/Flow-native Kotlin implementation of the [MetaWear protocol](https://github.com/mbientlab/MetaWear-API) for Android.

This repository contains both the reusable SDK modules and the MetaWear Android app:

- Use `:metawear-protocol` + `:metawear-core` when you need the scanner, device state machine, BLE transport, protocol router, and every sensor/module API.
- Add `:metawear-persistence` when you want Room-backed session storage and CSV export.
- Add `:metawear-firmware` only when your app needs over-the-air (Nordic DFU) firmware updates.
- Open `app/` for the full MetaWear app — see [The MetaWear App](#the-metawear-app).

The SDK is structured **KMP-ready, Android-only**: the pure protocol/parsing layer has zero Android dependencies and is unit-tested on a plain JVM, while the transport, persistence, firmware, and app layers are Android-specific.

## Table of Contents

| Start here | Use it for |
|------------|------------|
| [Requirements](#requirements) | Toolchain and Android versions |
| [Supported boards](#supported-boards) | Which MetaMotion boards the SDK targets and how the model is detected |
| [What ships in this repository](#what-ships-in-this-repository) | The five Gradle modules and what each one is for |
| [The MetaWear App](#the-metawear-app) | The full Jetpack Compose app — what it does, running it, and how it's built |
| [Development workflow](#development-workflow) | Repository layout, common Gradle commands, toolchain notes |
| [Quick Start](#quick-start) | Adding the SDK, permissions, scanning, connecting, streaming, and sending simple commands |
| [Architecture](#architecture) | Understanding the scanner/device/protocol/transport layering |
| [Layer-by-layer breakdown](#layer-by-layer-breakdown) | Scanner, device state machine and key signatures, transport split, protocol router |
| [Sensor protocols](#sensor-protocols) | The capability interfaces every sensor implements, plus generic read/poll |
| [Supported sensors and modules](#supported-sensors-and-modules) | Finding the Kotlin type and configuration shape for each MetaWear module |
| [Logging](#logging) | On-device flash logging, typed downloads, polled loggers, anonymous logger recovery, and CSV export |
| [Firmware updates](#firmware-updates) | Over-the-air DFU — catalog checks, flashing, bootloader chaining |
| [Persistence (Room)](#persistence-room) | Saving downloaded sessions and reconstructing typed samples |
| [Data modes](#data-modes) | Streaming vs. logging, and the flash entry layout |
| [BLE packet format](#ble-packet-format) | Wire format, characteristics, module IDs |
| [Testing](#testing) | Running JVM unit tests, the mock transport, and the instrumented hardware suites |
| [Demo transport (test fixture)](#demo-transport-test-fixture) | The protocol-level MetaMotion S emulator that drives the app's JVM tests |
| [Design notes](#design-notes) | Kotlin/coroutines gotchas the codebase relies on |
| [What's not yet implemented](#whats-not-yet-implemented) | Known gaps |

## Requirements

| Requirement            | Version                                                     |
|------------------------|-------------------------------------------------------------|
| Android (runtime)      | 8.0+ (`minSdk 26`); Android 12+ (API 31) recommended for the new `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` permissions |
| Android (compile)      | `compileSdk 36`, `targetSdk 36`                             |
| JDK (build)            | 21 (Android Studio's bundled JBR works)                     |
| Gradle / AGP           | Gradle 9.6, Android Gradle Plugin 9.2.1                     |
| Kotlin                 | 2.1.20 (pure-JVM module), AGP 9 built-in Kotlin (Android modules) |
| IDE                    | Android Studio                                              |
| Hardware for the app / instrumented tests | An Android phone with Bluetooth LE and a MetaMotion board |

Key library versions: kotlinx-coroutines 1.10.1, kotlinx-datetime 0.6.2, Room 2.8.4 (KSP 2.3.9), Nordic Kotlin BLE Library 1.3.1, Nordic Android DFU Library 2.9.0, Compose BOM 2025.06.01.

---

## Supported boards

This SDK targets MbientLab MetaMotion boards. Anything else (legacy MetaWear R / RG / RPro / C / CPro, MetaMotion C, MetaEnvironment, MetaTracker, MetaHealth) decodes as `BoardModel.Unknown` — connect / read may still work but module-level behaviour is not validated.

| Board             | Model number (`0x2A24`) | Hardware revisions (`0x2A27`)          | Verification status                              |
|-------------------|:-----------------------:|:---------------------------------------|:-------------------------------------------------|
| MetaMotion R / RL | `5`                     | `r0.1`, `r0.2`, `r0.3`, `r0.4`, `r0.5` | BMI160 wire-vector tests (JVM)                    |
| MetaMotion S      | `8`                     | `r0.1`                                 | BMI270 — verified on hardware (Pixel 7 + MetaMotion S) |

`BoardModel` decodes the Model Number characteristic into a typed value; `DeviceInformation.model` and `DeviceInformation.isHardwareRevisionSupported` cross-check the revision against the table above:

```kotlin
val info = device.deviceInfo ?: return
println(info.model.displayName)                 // "MetaMotion R / RL" or "MetaMotion S"
println(info.model.supportedHardwareRevisions)  // ["r0.1", ..., "r0.5"]  or  ["r0.1"]
println(info.model.hasMMS)                      // true on MetaMotion S (larger flash, flush-page before download)
if (!info.isHardwareRevisionSupported) {
    // Either an unsupported board model or a revision not on file.
}
```

The validator is forgiving about formatting — `"r0.4"`, `"R0.4"`, and `"0.4"` all match.

---

## What ships in this repository

```
metawear-android/
├── metawear-protocol/   ← pure Kotlin/JVM. Packet builder, parser, value types,
│                          sensor interfaces + configs, BleTransport seam + mock,
│                          protocol router, MetaWearDevice, MetaWearScanner.
│                          Fast JVM unit tests.
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
└── app/                 ← Jetpack Compose MetaWear app: scan, live streaming with
                           ring-buffer decimation, on-device logging + download,
                           group (fleet) logging, session history with CSV export,
                           LED/haptic controls, device settings, and firmware
                           updates. Its JVM tests drive the real device stack
                           against DemoBleTransport, a protocol-level
                           MetaMotion S emulator (test source set only).
```

The modules are intentionally split so an app can take just protocol + core without pulling in Room or the DFU library. `:metawear-protocol` is the foundation everything else builds on, written test-first to lock wire-format correctness before any BLE code. The full vertical slice — scan → connect → `startStream(accelerometer)` → `Flow<Timestamped<CartesianFloat>>` — runs end-to-end against `MockBleTransport` on the JVM, and against real hardware via `:metawear-core`'s instrumented suites.

The repo carries **1275 JVM tests** across the four testable modules (1043 protocol + 46 persistence + 66 firmware + 120 app), plus **43 BLE + 13 persistence instrumented tests** that need a phone (both hardware-verified on a Pixel 7 + MetaMotion S).

---

## The MetaWear App

`app/` is the MetaWear app — a Jetpack Compose app built on the four SDK modules (`:metawear-protocol` + `:metawear-core` for BLE + protocol, `:metawear-persistence` for storage and CSV, `:metawear-firmware` for DFU). It doubles as the reference consumer of the SDK: the public APIs an app needs are exercised here end to end.

### What you can do

| Area | What it does |
|------|--------------|
| **Scan & connect** | Runtime BLE permission flow, nearby MetaMotion boards with live name + RSSI from the scanner's `StateFlow`s, remembered devices persisted by MAC, reconnect |
| **Device hub** | Connection state badge, model / firmware / battery summary, identify (LED flash), reconnect / disconnect, feature navigation |
| **Live stream** | Multi-sensor picker in three collapsible groups — Motion (accelerometer, gyroscope, magnetometer with ODR/range chips), Fusion (all seven sensor-fusion outputs), and Environmental (polled temperature / humidity / pressure with a 1 s–5 min interval picker, plus streamed barometer pressure, altitude, and ambient light) — each group opening only when one of its sensors is selected, with a 100 Hz BLE bandwidth advisor. Canvas line charts, live readouts, true effective-Hz, a tared 3D orientation cube on the quaternion output, and a fusion calibration badge |
| **Logging & download** | Start / stop multi-sensor flash logging with an elapsed clock — the board keeps recording while disconnected — then reconnect and download with live progress (entry count and percent), per-sensor typed decode, and persistence, all shown above the sensor picker. The screen reconciles with the board on entry: a session the app didn't start (an earlier run, another app) is surfaced as "Logging" with a Stop action that keeps the recorded entries, and stale local records for cleared data are dropped. Pending sessions survive process death (`recoverLoggers` rebuilds the chunk registry) |
| **Group logging** | Record the same sensors across a fleet of boards under one shared group id, with a red recording heartbeat that re-arms itself via on-board disconnect events, then stop + download the whole batch with per-board progress. Foreign logs (another app's session) are detected on connect and offered for download via anonymous signals |
| **Session history** | Browse saved sessions grouped by board, re-plot them, replay quaternion sessions in 3D with a scrub timeline (1×/2×/4×), swipe-to-delete, and export any session to CSV through the system share sheet |
| **Controls** | LED color / pattern presets with play / stop, haptic motor strength and pulse-width sliders, buzzer pulse |
| **Settings** | Validated advertising rename, firmware update, a Logging section (entries in flash, every logger armed on the board with its module / register / byte range, refresh, and a confirmed Clear Logs & Loggers), advertising interval / timeout, TX power, a Maintenance section (reset LED, clear macros, clear events + timers, restart-without-erase), and a confirm-dialog factory reset |
| **Firmware** | Catalog update check plus a Nordic-DFU update flow with state / progress UI |

### Running it

1. Open the repo in Android Studio (JDK 21). The launch configuration is the **app** module.
2. Run on a physical Android phone (Android 8.0+, Android 12+ recommended) and connect a MetaMotion board over Bluetooth. Grant the Bluetooth permission prompts on first scan. The app needs real hardware — with Bluetooth off, the scan screen shows a plain "turn it on to scan" note; the hardware-free path is the JVM test suite (see [Demo transport (test fixture)](#demo-transport-test-fixture)).

Or install from the command line:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:installDebug
```

### How it's built

- **Jetpack Compose + Material 3**, single-activity (`MainActivity`) with Navigation Compose string routes (`"scan"`, `"group"`, `"device"`, `"info"`, `"stream"`, `"logging"`, `"sessions"`, `"controls"`, `"settings"`, `"firmware"`). No DI framework — `AppContainer` (owned by `MetaWearApplication`) is the process-wide root state: the shared `MetaWearScanner`, `PersistenceStore`, remembered-device store, log-session registry, and the `GroupCaptureCoordinator`. Each screen is driven by a focused ViewModel in `vm/`.
- **High-rate streaming pipeline** — samples ingest into plain ring buffers on a background coroutine (a 600-sample full-resolution capture ring plus a 180-sample 1-in-N decimated display ring, `Channel.kt` / `RingBuffer.kt`), and a ~33 ms ticker snapshots into Compose state (`StreamSessionViewModel`), so nothing touches UI state at sensor rate and charts stay smooth at 200 Hz.
- **3D orientation** — the quaternion output renders a dependency-free Canvas wireframe cube (`QuaternionCube`, `TaredQuaternionCube`) that is **tared**: it shows rotation since a reference pose (auto-set from the first valid sample, re-zeroed by the Zero button) through the IMU's 90° mounting correction, because the raw quaternion's absolute frame isn't stable session-to-session. While a fusion output streams, a calibration badge polls `SensorFusionCalibrationState` every 2 s; the bar is MEDIUM, since HIGH is a live score the magnetometer legitimately loses indoors.
- **Logging** — polled environmental sensors log through the SDK's timer → event → logger chain (`PolledLogger`); pending session records including the board-allocated polled-logger handles are persisted, so a fresh process can `recoverLoggers` and finish the download. Group capture arms boards sequentially (connect → clear → start → verify entries land → disconnect) and detects foreign logs with a pure decision table (`ForeignLog.kt`).
- **CSV export** — live buffers export as `time,…` (quaternion buffers gain derived `heading,pitch,roll` matching the firmware's Euler convention); persisted sessions export as `epoch,elapsed_ms,…` via `PersistenceStore.exportTable`. Filenames carry the capture-time board name and a session-id discriminator; sharing goes through a `FileProvider`.

### Where the code lives

| Path (`app/src/main/kotlin/com/mbientlab/metawear/app/`) | Contents |
|------|----------|
| `MetaWearApplication.kt`, `MainActivity.kt`, `AppContainer.kt` | Entry point, single activity, root app state |
| `core/` | Pure-Kotlin logic: `RingBuffer`, `EffectiveHz`, `BandwidthAdvisor`, `CalibrationReadiness`, `QuaternionCube`, `QuaternionFrame`, `ReplayTimeline`, `SensorSelection`, `SessionHistoryGrouping` |
| `data/` | Device-facing adapters: `ConfiguredSensor` (+ `openStream` / `startLoggingOn` / `decodeAndSave`), `LogDownloader`, `LogSessionRegistry`, `ForeignLog.kt`, `PolledPressure`, `RecordingHeartbeat`, `RememberedDeviceStore` |
| `export/` | `LiveBufferCsvExporter`, `ExportFilename`, `CsvShare` |
| `vm/` | ViewModels (`ScannerViewModel`, `DeviceViewModel`, `StreamSessionViewModel`, `LogSessionViewModel`, `DownloadViewModel`, `SessionHistoryViewModel`, `ControlsViewModel`, `SettingsViewModel`, `FirmwareUpdateViewModel`), `Channel`, `GroupCaptureCoordinator` |
| `ui/` | `AppNavHost`, one folder per screen (`scan`, `device`, `stream`, `logging`, `sessions`, `controls`, `settings`, `firmware`), shared `components/` (`LineChart`, `TaredQuaternionCube`, `FusionCalibrationBadge`, glass cards / badges), `theme/` |

`app/src/test` holds 120 JVM tests, including end-to-end walks through the real `MetaWearDevice` against `DemoBleTransport` (a test-only MetaMotion S emulator under `app/src/test/kotlin/com/mbientlab/metawear/app/demo/`) and full group-capture runs against the real persistence store.

---

## Development workflow

### Repository layout

| Path | Purpose |
|------|---------|
| `metawear-protocol/src/main/kotlin/com/mbientlab/metawear/` | `MetaWearDevice`, `DeviceLogging.kt`, `DeviceAnonymousSignals.kt`, `MetaWearScanner`; `protocol/` (module opcodes, packet builder/parser, capability interfaces, `ProtocolRouter`, `PolledLogger`); `model/` (value types, `BoardModel`, `BoardState`, `AnonymousSignal`, `DataTable`, exceptions); `sensor/` (one file per board module); `transport/` (`BleTransport`, `MockBleTransport`, `Uuids`) |
| `metawear-protocol/src/test` | JVM unit tests for parsing, commands, modules, device state, logging, and mock-transport behaviour |
| `metawear-core/src/main` | `NordicBleTransport`, `AndroidBleScanSource`, `AndroidMetaWear` |
| `metawear-core/src/androidTest` | Instrumented hardware suites (`HardwareSmokeTest` + 10 per-module suites, `HardwareSupport`) |
| `metawear-persistence/src/main` | Room entities, DAO, `PersistenceDatabase`, `PersistenceStore`, `Persistable` codecs |
| `metawear-persistence/src/test`, `src/androidTest` | JVM store tests (fake DAO) and instrumented real-database tests |
| `metawear-firmware/src/main` | `FirmwareServer`, `FirmwareCatalog`, `FirmwareBuild`, `BootloaderInterlock`, `DfuSession`, `MetaBootProbe`, `FirmwareUpdate.kt`, `MetaWearDfuService` |
| `metawear-firmware/src/test` | Catalog, version, server, interlock tests |
| `app/` | The Compose app (see above) |
| `HARDWARE.md` | Step-by-step hardware verification guide (phone setup, running the instrumented suites, manual app pass) |
| `.github/workflows/ci.yml` | CI: JDK 21, all JVM test tasks, `assembleDebug` for every module |

### Common commands

```bash
./gradlew :metawear-protocol:test                    # parsing/command/transport/device tests (JVM)
./gradlew :metawear-core:assembleDebug               # Android transport library (AAR)
./gradlew :metawear-persistence:testDebugUnitTest    # persistence tests (JVM)
./gradlew :metawear-persistence:connectedAndroidTest # Room round-trips (needs a phone)
./gradlew :metawear-firmware:testDebugUnitTest       # firmware tests (JVM)
./gradlew :app:testDebugUnitTest                     # app logic + demo-transport tests (JVM)
./gradlew :app:assembleDebug                         # Compose app APK
./gradlew :metawear-core:connectedAndroidTest        # hardware suites (needs a phone + board)

# Run one suite while developing a module.
./gradlew :metawear-protocol:test --tests '*LedTest*'
```

Opens directly in Android Studio; the Kotlin toolchain targets JDK 21. Set `JAVA_HOME` to a JDK 21 (Android Studio's bundled JBR: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

### Toolchain notes

- `:metawear-core`, `:metawear-persistence`, `:metawear-firmware`, and `:app` use **AGP 9's built-in Kotlin** (no `org.jetbrains.kotlin.android` plugin — AGP 8.x does not run on this repo's Gradle 9.6). `:metawear-protocol` is a plain `kotlin("jvm")` module on Kotlin 2.1.20.
- `:app` adds the Compose compiler Gradle plugin (`org.jetbrains.kotlin.plugin.compose`) pinned to **2.2.10 — AGP 9.2.1's embedded Kotlin compiler version**, not the 2.1.20 used by the pure-JVM module's explicit Kotlin plugin.
- Room's annotation processing runs through **KSP** — the standalone-versioned KSP ≥ 2.3 line (2.3.9), which works with AGP 9's built-in Kotlin.
- Unit tests are JUnit 5 (Jupiter) with `kotlinx-coroutines-test` and Turbine; instrumented tests are JUnit 4 on `AndroidJUnitRunner`.

### Documentation standards

Public SDK types carry KDoc (`/** … */`) because they surface in Android Studio quick documentation and generated symbol docs. Implementation comments explain protocol quirks, firmware ordering constraints, or concurrency reasoning; avoid comments that merely restate a line of Kotlin. Markdown docs prefer small, runnable snippets and call out whether hardware is required.

---

## Quick Start

### Add the SDK to your build

The SDK is **not yet published to Maven Central**. Until it is, consume it from source in one of two ways.

**Option A — include the modules in your build.** Clone (or add as a git submodule) next to your project and include the modules you need in `settings.gradle.kts`:

```kotlin
// settings.gradle.kts
include(":metawear-protocol", ":metawear-core", ":metawear-persistence", ":metawear-firmware")
project(":metawear-protocol").projectDir    = file("../MetaWear-API-Kotlin/metawear-protocol")
project(":metawear-core").projectDir        = file("../MetaWear-API-Kotlin/metawear-core")
project(":metawear-persistence").projectDir = file("../MetaWear-API-Kotlin/metawear-persistence")
project(":metawear-firmware").projectDir    = file("../MetaWear-API-Kotlin/metawear-firmware")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":metawear-protocol"))
    implementation(project(":metawear-core"))
    implementation(project(":metawear-persistence"))   // optional
    implementation(project(":metawear-firmware"))      // optional
}
```

**Option B — composite build.** Keep the SDK as an included build and substitute the planned coordinates:

```kotlin
// settings.gradle.kts
includeBuild("../MetaWear-API-Kotlin") {
    dependencySubstitution {
        substitute(module("com.mbientlab:metawear-protocol")).using(project(":metawear-protocol"))
        substitute(module("com.mbientlab:metawear-core")).using(project(":metawear-core"))
        substitute(module("com.mbientlab:metawear-persistence")).using(project(":metawear-persistence"))
        substitute(module("com.mbientlab:metawear-firmware")).using(project(":metawear-firmware"))
    }
}
```

```kotlin
// app/build.gradle.kts — planned Maven coordinates (not yet published; version TBD)
dependencies {
    implementation("com.mbientlab:metawear-protocol:<version>")
    implementation("com.mbientlab:metawear-core:<version>")
    implementation("com.mbientlab:metawear-persistence:<version>")   // optional
    implementation("com.mbientlab:metawear-firmware:<version>")      // optional
}
```

The consuming project needs JDK 21, AGP 9.x, and `minSdk >= 26`.

### Permissions

The library declares the Bluetooth permissions in its manifest (merged into yours) but never requests them. Before calling `startScan()` or `connect()`, your app must have been **granted**:

- **API 31+**: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`
- **API 26–30**: `BLUETOOTH` / `BLUETOOTH_ADMIN` and `ACCESS_FINE_LOCATION`

```kotlin
val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
// request via ActivityResultContracts.RequestMultiplePermissions()
```

Scanning with the permissions missing fails the scan flow; connecting without `BLUETOOTH_CONNECT` throws a `SecurityException` from the Android stack.

### Scan and connect

```kotlin
import com.mbientlab.metawear.core.AndroidMetaWear

// One scanner per app — owns the shared scan source and vends one MetaWearDevice per board.
val scanner = AndroidMetaWear.scanner(applicationContext)
scanner.startScan()

// Drive a Compose list from scanner.discoveredDevices (StateFlow<Map<String, MetaWearDevice>>).
delay(5.seconds)
scanner.stopScan()

val device = scanner.discoveredDevices.value.values.firstOrNull() ?: return
device.connect()          // suspends until Idle: device info + module discovery done
println(device.deviceInfo?.model?.displayName)
```

### Stream the accelerometer

```kotlin
import com.mbientlab.metawear.sensor.AccelerometerBmi160

val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
val stream: Flow<Timestamped<CartesianFloat>> = device.startStream(sensor)

val job = launch {
    stream.collect { sample ->
        println("${sample.time} ${sample.value.x} ${sample.value.y} ${sample.value.z}")
    }
}
delay(5.seconds)
device.stopStreaming(sensor)
job.cancel()
```

On a MetaMotion S use `AccelerometerBmi270(...)` — or let the SDK pick from the module table with `Accelerometer.make(...)` (see [Auto-select accelerometer](#auto-select-accelerometer)).

### Control the LED

```kotlin
import com.mbientlab.metawear.sensor.Led
import com.mbientlab.metawear.sensor.LedPattern

// Set a pattern then play it
device.send(Led.SetPattern(Led.Color.GREEN, LedPattern.breathe))
device.send(Led.Play())

// Stop and clear after 3 seconds
delay(3.seconds)
device.send(Led.Stop())
```

### Buzz the haptic motor

```kotlin
import com.mbientlab.metawear.sensor.Haptic

device.send(Haptic.motor(dutyCycle = 80, pulseWidth = 500))
```

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  Your App / Compose UI                               │
│  (ViewModels collecting StateFlow / Flow)            │
├──────────────────────────────────────────────────────┤
│  MetaWearScanner   (StateFlow maps)                  │
│  MetaWearDevice    (StateFlow<DeviceState>, Mutex)   │
├──────────────────────────────────────────────────────┤
│  Module layer  (sensor/*)                            │
│  AccelerometerBmi160/270, Gyroscope…, Led, Timer,    │
│  Event, Macro, Gpio, DataProcessor, Settings, …      │
├──────────────────────────────────────────────────────┤
│  ProtocolRouter    (internal, per device)            │
│  Packet / PacketParser (static helpers)              │
├──────────────────────────────────────────────────────┤
│  BleTransport      (interface, pure JVM)             │
├────────────────────────┬─────────────────────────────┤
│  AndroidBleScanSource  │  NordicBleTransport         │
│  (shared scan)         │  (per device, Nordic BLE)   │
├────────────────────────┴─────────────────────────────┤
│  MockBleTransport  (JVM unit tests)                  │
│  DemoBleTransport  (app JVM tests, test-only)        │
│  connectedAndroidTest (hardware suites)              │
└──────────────────────────────────────────────────────┘
```

`:metawear-protocol` holds everything above the platform line — including the `BleTransport` *interface* (it is pure JVM: `java.util.UUID`, `ByteArray`, `Flow`) — so the protocol router and device layer stay JVM-testable against `MockBleTransport`. Only the Nordic-backed implementation is Android-specific and belongs in `:metawear-core`. Peripherals are identified by their Android MAC address, so the transport seam uses an opaque `String` identifier.

### Key design decisions

| Concern             | Choice                                                    | Why                                                                                          |
|---------------------|-----------------------------------------------------------|----------------------------------------------------------------------------------------------|
| Async streams       | `kotlinx.coroutines.Flow` (cold) + `StateFlow` for state  | Errors propagate on BLE drop; cancellation stops the stream; Compose collects natively        |
| Thread safety       | `MutableStateFlow.compareAndSet` for connection transitions + one `Mutex` (`opMutex`) serializing stream/log start-stop | Two sensors' command sequences can never interleave on the wire |
| BLE wrapper         | Nordic Kotlin BLE Library behind the `BleTransport` seam  | Coroutine-native GATT with per-connection serialization; the seam keeps the SDK testable      |
| Scan admission      | Unfiltered scan + `isMetaWearAdvertisement` (name prefix or service UUID) | Boards don't reliably advertise the service UUID; renamed boards still admitted     |
| Time                | `kotlinx.datetime.Instant`                                | Pure-JVM, no `java.time` dependency in the protocol module                                   |
| Serialization       | Hand-rolled JSON (`BoardState`, firmware catalog)          | No serialization plugin or Android JSON dependency in JVM-tested code                        |
| Hardware tests      | Instrumented (`connectedAndroidTest`) that **self-skip** without a board | Same task is safe on a bench without hardware                                   |

---

## Layer-by-layer breakdown

### MetaWearScanner

Exposes discovery state as `StateFlow` maps for Compose / ViewModel consumption. One scanner per app. The scan itself runs on the shared `AndroidBleScanSource`; each discovered peripheral gets its own `NordicBleTransport` and `MetaWearDevice` from the device factory wired by `AndroidMetaWear.scanner(context)`.

```kotlin
class MetaWearScanner(
    scanTransport: BleTransport,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    deviceFactory: (identifier: String) -> MetaWearDevice,
) {
    val discoveredDevices: StateFlow<Map<String, MetaWearDevice>>   // MAC → device, admitted boards only
    val advertisedNames:   StateFlow<Map<String, String>>           // most-recent local name per MAC
    val advertisementRssi: StateFlow<Map<String, Int>>              // most-recent RSSI (dBm) per MAC
    val isScanning:        StateFlow<Boolean>

    fun startScan()
    fun stopScan()
    fun noteAdvertisedName(identifier: String, name: String)   // seed the cache after a rename
    fun clearAdvertisedName(identifier: String)                // force next scan to recapture
    fun deviceForKnownIdentifier(identifier: String): MetaWearDevice   // remembered device, no re-discovery

    companion object {
        fun isMetaWearAdvertisement(name: String, serviceUUIDs: List<String>): Boolean
    }
}
```

- Scans run **without** a service-UUID filter: MetaWear boards don't reliably include the custom service UUID in advertisements. Admission goes through `isMetaWearAdvertisement`, which accepts either the default `"MetaWear"` local-name prefix **or** the MetaWear service UUID when the packet carries it — so a board renamed to `"bob"` via `Settings.SetDeviceName` is still admitted. MetaBoot-mode boards (name `"MetaBoot"`, Nordic DFU service) match neither and stay excluded on purpose — the normal connect flow can't talk to a bootloader.
- `advertisedNames` and `advertisementRssi` are updated on every advertisement, **before** the admission filter, and only when the value actually changed (advertisements repeat several times a second per board; `StateFlow` subscribers shouldn't be invalidated at advertising rate).
- `deviceForKnownIdentifier` returns the same `MetaWearDevice` instance every time for one MAC, and if the scanner later sees that MAC on air the **same** instance is promoted into `discoveredDevices` — callers never observe two devices (two transports racing over one peripheral) for one identifier.
- After a rename, a connected board doesn't advertise, so the name cache is known-stale until disconnect; `noteAdvertisedName` lets UI update immediately and the next real advertisement reconciles it.

### MetaWearDevice

The main entry point for one board. All operations are `suspend` functions; state transitions are guarded by an atomic compare-and-set on the state flow (connection) and a `Mutex` serializing stream/log start-stop command sequences. Invalid transitions throw `MetaWearException.InvalidState`.

```
Disconnected → Connecting → Idle → Streaming
                                 → Logging
                                 → Downloading(progress)
```

```kotlin
sealed class DeviceState {
    data object Disconnected : DeviceState()
    data object Connecting : DeviceState()
    data object Idle : DeviceState()
    data object Streaming : DeviceState()
    data object Logging : DeviceState()
    data class Downloading(val progress: Double) : DeviceState()   // 0.0..1.0
}
```

Key members:

```kotlin
class MetaWearDevice(
    val identifier: String,                 // MAC address on Android
    transport: BleTransport,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val state: StateFlow<DeviceState>
    var deviceInfo: DeviceInformation?          // populated by connect()
    var modules: Map<Module, ModuleInfo>        // module-discovery table from connect()
    var logReferenceDate: Instant?              // wall-clock instant of device tick 0
    var onUnexpectedDisconnect: ((Throwable) -> Unit)?

    suspend fun connect()
    suspend fun reconnect()
    suspend fun disconnect()
    suspend fun sendExpectingDisconnect(command: Command, timeout: Duration = 5.seconds)
    suspend fun factoryReset()
    suspend fun restart()

    suspend fun <S> startStream(sensor: Streamable<S>, usePacked: Boolean = true): Flow<Timestamped<S>>
    suspend fun <S> stopStreaming(sensor: Streamable<S>)

    suspend fun send(command: Command)
    suspend fun send(sequence: CommandSequence)

    suspend fun <S> read(readable: Readable<S>): Timestamped<S>
    fun <S> poll(readable: Pollable<S>, every: Duration): Flow<Timestamped<S>>
    suspend fun readRSSI(): Int

    fun moduleInfo(module: Module): ModuleInfo?
    val hasGyroscope: Boolean; val hasMagnetometer: Boolean
    val hasBarometer: Boolean; val hasSensorFusion: Boolean

    fun captureBoardState(): BoardState?
    fun restoreBoardState(state: BoardState)
}

// Logging surface — extension functions in DeviceLogging.kt
suspend fun <S> MetaWearDevice.startLogging(loggable: Loggable<S>)
suspend fun <S> MetaWearDevice.stopLogging(loggable: Loggable<S>)
suspend fun <S> MetaWearDevice.startLogging(logger: PolledLogger<S>): PolledLoggerHandles
suspend fun <S> MetaWearDevice.stopLogging(logger: PolledLogger<S>, handles: PolledLoggerHandles)
suspend fun MetaWearDevice.startLogging(handle: ProcessorHandle, key: String)
suspend fun MetaWearDevice.stopLogging(key: String)
suspend fun MetaWearDevice.downloadLogs(expectEntries: Boolean = false): Flow<Download<List<RawLogEntry>>>
suspend fun <S> MetaWearDevice.downloadLogs(loggable: Loggable<S>): Flow<Download<List<LoggedSample<S>>>>
suspend fun <S> MetaWearDevice.downloadLogs(logger: PolledLogger<S>): Flow<Download<List<LoggedSample<S>>>>
suspend fun <S> MetaWearDevice.downloadLogs(key: String, decode: (ByteArray) -> S): Flow<Download<List<LoggedSample<S>>>>
suspend fun MetaWearDevice.clearLog()
suspend fun MetaWearDevice.flushLogPage(): Boolean
suspend fun MetaWearDevice.stopOnBoardLogging()
suspend fun MetaWearDevice.queryActiveLoggers(): List<ActiveLogger>
suspend fun MetaWearDevice.queryActiveProcessors(): List<ActiveProcessor>
suspend fun <S> MetaWearDevice.recoverLoggers(loggable: Loggable<S>)
suspend fun <S> MetaWearDevice.recoverLoggers(logger: PolledLogger<S>)
suspend fun MetaWearDevice.createAnonymousDataSignals(): List<AnonymousSignal>   // DeviceAnonymousSignals.kt
```

Notes on the lifecycle:

- `connect()` atomically moves `Disconnected → Connecting`; a concurrent `connect()` fails fast. It connects the transport, starts the protocol router, reads the Device Information Service, discovers modules, and reads the log time reference; on any failure the link is torn down so the reported `Disconnected` is the truth.
- `disconnect()` is intentional: it finishes processor flows cleanly, stops the router, and suppresses `onUnexpectedDisconnect` for that drop. An unexpected drop moves the device to `Disconnected`, fails open streams with the underlying error, and invokes `onUnexpectedDisconnect` — but **preserves the logger registry**, so a reconnect can download without re-registering.
- `sendExpectingDisconnect(command)` is for commands that reboot the board or drop the link (`Debug.Reset()`, `Debug.JumpToBootloader()`, …): it swaps in a one-shot drop signal before sending, waits for the drop (or falls back to a local teardown after `timeout`), and converges on `Disconnected` without firing `onUnexpectedDisconnect`.
- `factoryReset()` issues the seven-write scrub sequence — stop logging, drop all log entries, remove all logger triggers, remove all events, remove all data processors, erase all macros, reset-after-GC — followed by an immediate `Debug.Reset()` fallback for firmware revisions (notably MMS 1.5.0) that ignore reset-after-GC when nothing is pending. `restart()` reboots **without** erasing anything: flash-resident data (log entries, macros) survives; volatile state (timers, events, sensor enables) is cleared. Reconnect after ~1 s in both cases.
- Streaming guards: the same `(module, register)` can't be started twice; **sensor fusion and the raw IMU sensors (accelerometer / gyroscope / magnetometer) are mutually exclusive**; all simultaneous fusion outputs must share one mode/range (a second output with a different config is rejected with `InvalidState` instead of being silently ignored by the board). If a start sequence fails mid-way the sensor is rolled back so a retry isn't blocked with "already streaming".

### BLE transport split

The shared scan source is separate from the per-peripheral connection transport:

| Type                    | Module               | Role                                                                                                                                       |
|-------------------------|----------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| `BleTransport`          | `:metawear-protocol` | The seam: `scan(services)`, `connect(identifier)`, `disconnect()`, `write(data, characteristic, type)`, `read(characteristic)`, `notifications(characteristic)`, `readRSSI()` |
| `AndroidBleScanSource`  | `:metawear-core`     | Unfiltered BLE scan → `Flow<ScanResult>` (`identifier` = MAC, `name`, `rssi`, `manufacturerData`, `serviceUUIDs`). Scan-only; connection calls throw. |
| `NordicBleTransport`    | `:metawear-core`     | One per device. GATT connect / write / read / notify / RSSI on the Nordic Kotlin BLE Library.                                                |
| `MockBleTransport`      | `:metawear-protocol` | Public in-memory transport for unit tests. Inject notifications with `inject(notification, characteristic)`; inspect `writtenCommands`. No hardware required. |
| `DemoBleTransport`      | `:app` (test only)   | Protocol-level MetaMotion S emulator behind the same seam (see [Demo transport (test fixture)](#demo-transport-test-fixture)).              |

`NordicBleTransport` connect flow and transport notes:

- GATT connect with `autoConnect = false` and a bounded retry (3 attempts, 500 ms apart, 20 s timeout each) that absorbs Android's transient status-133 (`GATT_ERROR`) failures.
- Full service discovery; every characteristic of every service is indexed by UUID, so Device Information Service reads (service `0x180A`, not the MetaWear service) resolve by characteristic UUID alone.
- MTU request for **247** bytes: the default 23-byte MTU leaves a 20-byte payload, too small for packed streaming (3 samples × 6 bytes + 2-byte header, and firmware past 1.4 pads further).
- Notifications enabled on the MetaWear notify characteristic (`326A9006-…`) up front, so the router can subscribe without an extra descriptor round-trip.
- GATT operations are serialized by the Nordic client's internal per-connection mutex — Android allows only one outstanding GATT operation — so the transport adds no locking of its own.
- Write-without-response for commands; write-with-response for macro recording.

### ProtocolRouter

Internal to each device. Routes raw BLE notification bytes to the right handler by `(module_id, register_id & 0x3F)` key:

- Read responses (register `| 0x80` bit set) → resume the FIFO-queued suspended reader
- Unsolicited notifications → one-shot notify waiters (I2C/SPI reads, ADD/ENTRY acknowledgements) and ongoing subscription channels (streaming sensors)
- Multiple concurrent reads on the same key are queued and resolved FIFO
- Read timeout is 5 s (`ProtocolRouter.READ_TIMEOUT`) → `MetaWearException.Timeout`; slot-enumeration probes use a 1 s timeout because the firmware never answers an empty slot
- Per-key subscription buffers hold 256 packets and drop the **oldest** on overflow (sensor notifications arrive at up to ~200 packets/s; a stalled collector must not grow memory unbounded)
- All waiters and streams are failed when `stop()` is called or the transport's notification flow terminates (disconnect)
- Module discovery reads register `0x00` of every `Module` and records `implementation`, `revision`, and `extra` bytes into `ModuleInfo` (`isPresent` = implementation ≠ `0xFF`)

Data-processor streaming uses a per-id demux on top of the router: one shared `(0x09, 0x03)` subscription fans packets out to per-processor-id flows, so multiple processors stream simultaneously; the flows complete cleanly on an intentional disconnect and fail with the underlying error on an unexpected one.

---

## Sensor protocols

Every sensor type is a plain Kotlin value that implements one of these interfaces (`com.mbientlab.metawear.protocol`):

```kotlin
interface Sensor {
    val module: Module
    val dataRegister: Int
    val packedDataRegister: Int? get() = null
}

// Streams data continuously (accelerometer, gyro, magnetometer, sensor fusion, barometer, switch, GPIO)
interface Streamable<out S> : Sensor {
    val configureCommands: List<ByteArray>
    val enableCommand: ByteArray
    val startCommand: ByteArray
    val stopCommand: ByteArray
    val disableCommand: ByteArray
    val warmupCommands: List<ByteArray> get() = emptyList()   // e.g. magnetometer power-on (+ warmupDelayNanos)
    fun parseSample(packet: ByteArray): S
    fun parsePackedSamples(packet: ByteArray): List<S> = emptyList()
}

// Streams AND logs to on-device flash
interface Loggable<out S> : Streamable<S> {
    val loggerKey: String
    val logDataChunks: List<LogChunk> get() = listOf(LogChunk(0, 4), LogChunk(4, 2))
    fun parseLogSample(data: ByteArray): S
}

// One-shot read sensors (battery, MAC, humidity, log length, …)
interface Readable<out S> : Sensor {
    val readCommand: ByteArray
    fun parseSample(packet: ByteArray): S
}

// A readable whose value changes over time — works with device.poll(readable, every)
interface Pollable<out S> : Readable<S>

// A readable that can be logged on-board via the timer → event → logger chain
interface PolledLoggable<out S> : Readable<S> {
    val logDataChunks: List<LogChunk>
    val loggerTriggerIndex: Int get() = 0xFF
}

// Fire-and-forget commands (LED, haptic, debug, timer control, GPIO, macro)
interface Command {
    val commandData: ByteArray
}

// Fire-and-forget actions that require more than one BLE write
// (e.g. BMI270 feature enable/disable pairs, long scan-response splits)
interface CommandSequence {
    val commands: List<ByteArray>
}
```

`device.send(...)` is overloaded for both `Command` and `CommandSequence` — a single call site regardless of whether the action emits one or many writes.

### Generic read and poll

Any `Readable` works with `device.read(...)`; any `Pollable` also works with `device.poll(readable, every)`, which returns a cold `Flow`:

```kotlin
val humidity = device.read(Humidity())                    // Timestamped<Float>, % RH
val entries  = device.read(LogLength())                   // Timestamped<Long>
val mac      = device.read(Settings.ReadMacAddress())     // Timestamped<String>  (alias: MacAddress)
val reset    = device.read(LastResetTime())               // Timestamped<LastResetTime.Reading> — { epoch: Instant, resetUID: Int }
val celsius  = device.read(Thermometer(channel = 0))      // Timestamped<Float>

device.poll(Settings.ReadBatteryState(), every = 30.seconds).collect { sample ->
    updateBatteryUI(sample.value.charge, sample.value.voltage)   // BatteryState(voltage mV, charge %)
}
```

`poll` reads the sensor immediately, emits the timestamped sample, delays `every`, and repeats. Cancelling the collecting coroutine stops the loop; a read error terminates the flow.

Built-in `Pollable` implementations: `LoggingEnabled`, `LogLength`, `LastResetTime`, `Settings.ReadBatteryState`, `Settings.ReadMacAddress`, `Settings.ReadPowerStatus`, `Settings.ReadChargeStatus`, `Humidity`, `BarometerPressureRead`, `Thermometer`, `SensorFusionCalibrationState`.

---

## Supported sensors and modules

All 22 board modules are covered. Each subsection names the Kotlin type, the module ID, and the configuration shape.

### Accelerometer (module 0x03)

```kotlin
// BMI160 (MetaMotion R / RL — model 5)
// Odr: HZ0_78, HZ1_56, HZ3_12, HZ6_25, HZ12_5, HZ25, HZ50, HZ100, HZ200, HZ400, HZ800, HZ1600
// Range: G2, G4, G8, G16
val acc = AccelerometerBmi160(odr = AccelerometerBmi160.Odr.HZ100, range = AccelerometerBmi160.Range.G2)

// BMI270 (MetaMotion S — model 8)
// Same Odr / Range entries as BMI160 (different config bytes on the wire)
val acc = AccelerometerBmi270(odr = AccelerometerBmi270.Odr.HZ100, range = AccelerometerBmi270.Range.G2)
```

Both implement `Loggable<CartesianFloat>` (`loggerKey = "acceleration"`, data register `0x04`). Packed data (3 samples per BLE packet) is used automatically when `usePacked = true` (default) — packed register `0x1C` on the BMI160, `0x05` on the BMI270. Scale factors (LSB/g): ±2 g = 16384, ±4 g = 8192, ±8 g = 4096, ±16 g = 2048.

`Accelerometer` is the type-erased sealed wrapper (`Accelerometer.Bmi160(sensor)` / `Accelerometer.Bmi270(sensor)`) with `odrHz`, `rangeG`, `withOdr(...)`, `withRange(...)`, and the `Accelerometer.make(impl, odrHz, rangeG)` factory — see [Auto-select accelerometer](#auto-select-accelerometer).

#### Step counter / detector

```kotlin
// BMI160 — register 0x18 config, read on 0x1A, detector notifications on 0x19
device.send(AccelerometerBmi160Steps.ConfigureStepCounter(AccelerometerBmi160Steps.StepCounterMode.NORMAL))  // NORMAL / SENSITIVE / ROBUST
device.send(AccelerometerBmi160Steps.EnableStepDetector())
val steps = AccelerometerBmi160Steps.parseStepCount(packet)      // from a ReadStepCounter response

// BMI270 — watermark-triggered counter (CommandSequence), detector notifications on 0x0B
device.send(AccelerometerBmi270Steps.ConfigureStepCounter(trigger = 1))
device.send(AccelerometerBmi270Steps.EnableStepDetector())
val steps = AccelerometerBmi270Steps.parseStepCount(packet)
```

### Gyroscope (module 0x13)

```kotlin
// BMI160 (MetaMotion R / RL — model 5)
// Odr: HZ25, HZ50, HZ100, HZ200, HZ400, HZ800, HZ1600, HZ3200
// Range: DPS2000, DPS1000, DPS500, DPS250, DPS125
val gyro = GyroscopeBmi160(odr = GyroscopeBmi160.Odr.HZ100, range = GyroscopeBmi160.Range.DPS2000)

// BMI270 (MetaMotion S — model 8)
// Same options as BMI160
val gyro = GyroscopeBmi270(odr = GyroscopeBmi270.Odr.HZ100, range = GyroscopeBmi270.Range.DPS2000)

// BMI270 only: write axis offsets
device.send(GyroscopeBmi270.Offsets(x = 0, y = 0, z = 0))
```

Both implement `Loggable<CartesianFloat>` (`loggerKey = "angular-velocity"`). Data register is `0x05` on the BMI160 (packed `0x07`) and `0x04` on the BMI270 (packed `0x05`). `Gyroscope` is the sealed wrapper with `Gyroscope.make(impl, odrHz, rangeDps)` (impl `0` = BMI160, `1` = BMI270 from `moduleInfo(Module.GYRO)?.implementation`).

### Bosch motion detectors (accelerometer interrupts)

Orientation, any-motion, and tap interrupts are generated on-chip by the BMI160 / BMI270. `AccelerometerBosch` ships the configure / enable / disable commands and the packet decoders.

```kotlin
// Orientation — fires when the device rotates through one of 8 states (notification register 0x11)
// BMI160-only; constructing with ChipVariant.BMI270 throws MetaWearException.OperationFailed.
device.send(AccelerometerBosch.EnableOrientation(chip = AccelerometerBosch.ChipVariant.BMI160))
val orientation = AccelerometerBosch.parseOrientation(packet)   // AccelerometerBosch.SensorOrientation.FACE_UP_PORTRAIT_UPRIGHT …

// Any-motion — fires when motion exceeds threshold on any axis (config 0x0A, enable 0x09, notify 0x0B)
device.send(AccelerometerBosch.ConfigureAnyMotion(
    chip = AccelerometerBosch.ChipVariant.BMI270, count = 4, thresholdG = 0.75f, rangeG = 8.0f,
))
device.send(AccelerometerBosch.EnableAnyMotion())
val event = AccelerometerBosch.parseAnyMotion(packet)   // event.isPositive, event.xAxisActive / yAxisActive / zAxisActive

// Tap — single + double tap (config 0x0D, enable 0x0C, notify 0x0E)
device.send(AccelerometerBosch.ConfigureTap(
    shockTime = AccelerometerBosch.TapShockTime.MS50,
    quietTime = AccelerometerBosch.TapQuietTime.MS30,
    doubleTapWindow = AccelerometerBosch.DoubleTapWindow.MS250,
    thresholdG = 2.0f, rangeG = 8.0f,
))
device.send(AccelerometerBosch.EnableTap(single = true, double = true))
val tap = AccelerometerBosch.parseTap(packet)           // tap.type (SINGLE / DOUBLE), tap.isPositive
```

To receive the interrupt packets, wrap the notification register in a small `Streamable` and use `startStream` — the interface is public and `startStream` handles the subscribe write and the accelerometer start for you:

```kotlin
class TapInterrupt : Streamable<AccelerometerBosch.TapEvent> {
    override val module = Module.ACCELEROMETER
    override val dataRegister = 0x0E
    override val configureCommands = listOf(
        AccelerometerBosch.ConfigureTap(thresholdG = 2.0f, rangeG = 8.0f).commandData,
    )
    override val enableCommand = AccelerometerBosch.EnableTap(single = true, double = true).commandData
    override val startCommand = Packet.command(Module.ACCELEROMETER, 0x01, 0x01)   // start sampling
    override val stopCommand = Packet.command(Module.ACCELEROMETER, 0x01, 0x00)
    override val disableCommand = AccelerometerBosch.DisableTap().commandData
    override fun parseSample(packet: ByteArray) = AccelerometerBosch.parseTap(packet)
}

device.startStream(TapInterrupt()).collect { tap -> println(tap.value.type) }
```

An interrupt stream shares the accelerometer module with the data stream, so the streaming guard treats it as the same module — run one or the other, or fold the interrupt into a data processor / event instead.

### BMI270 extra features (activity, wrist, no-motion, significant motion, downsampling)

BMI270-only features exposed via `AccelerometerBmi270Features`. `Configure…` and `SetDownsampling` are `Command`s; the `Enable…` / `Disable…` pairs (which emit both `FEATURE_INTERRUPT_ENABLE` and `FEATURE_ENABLE` writes) are `CommandSequence`s. Both ship through the same `device.send(...)` entry point.

```kotlin
// Activity classification — STILL / WALKING / RUNNING / UNKNOWN (register 0x0C)
device.send(AccelerometerBmi270Features.EnableActivityDetection())
val activity = AccelerometerBmi270Features.parseActivity(packet)          // AccelerometerBmi270Features.Activity

// Wrist gesture (register 0x0A) — PUSH_ARM_DOWN, PIVOT_UP, SHAKE, ARM_FLICK_IN, ARM_FLICK_OUT
device.send(AccelerometerBmi270Features.ConfigureWristGesture(arm = AccelerometerBmi270Features.WristArm.RIGHT))
device.send(AccelerometerBmi270Features.EnableWristGesture())
val event = AccelerometerBmi270Features.parseWristEvent(packet)           // event.kind (WAKEUP / GESTURE), event.gestureCode

// Wrist wakeup (register 0x0A) — shares the parse path with wrist gesture
device.send(AccelerometerBmi270Features.ConfigureWristWakeup())
device.send(AccelerometerBmi270Features.EnableWristWakeup())

// No-motion (register 0x09) — distinct from any-motion
device.send(AccelerometerBmi270Features.ConfigureNoMotion(
    duration = 5, threshold = 0xAA, selectX = true, selectY = true, selectZ = true,
))
device.send(AccelerometerBmi270Features.EnableNoMotion())

// Significant motion
device.send(AccelerometerBmi270Features.ConfigureSignificantMotion(blocksize = 250))
device.send(AccelerometerBmi270Features.EnableSignificantMotion())

// FIFO downsampling — reduce the logged sample rate per axis-group
device.send(AccelerometerBmi270Features.SetDownsampling(
    gyroOrdinal = 2, gyroFilterData = true,
    accOrdinal = 2, accFilterData = true,
))
```

Every feature has a matching `Disable…` sequence (`DisableActivityDetection()`, `DisableWristGesture()`, `DisableWristWakeup()`, `DisableNoMotion()`, `DisableSignificantMotion()`). Notification registers are exposed as constants (`ACTIVITY_REGISTER`, `WRIST_EVENT_REGISTER`, `AccelerometerBmi270Steps.STEP_REGISTER`).

### Magnetometer (BMM150, module 0x15)

```kotlin
val mag = Magnetometer(preset = Magnetometer.Preset.LOW_POWER)
// Presets: LOW_POWER, REGULAR, ENHANCED_REGULAR, HIGH_ACCURACY
// Manual: Magnetometer(xyReps = 9, zReps = 15, odr = Magnetometer.Odr.HZ10)   // Odr: HZ2 … HZ30
// Scale: 16 LSB/µT; Loggable<CartesianFloat>, loggerKey "magnetic-field"
// Data register 0x05, packed register 0x09 (module revision ≥ 1)

device.send(Magnetometer.Suspend())      // low-power suspend
```

`startStream` sends the magnetometer's warm-up (power-mode) command and waits 200 ms before configuring — the `warmupCommands` / `warmupDelayNanos` hooks on `Streamable`.

### Barometer (BMP280 / BME280, module 0x12)

```kotlin
// BMP280 (motion boards)
val baro = Barometer(
    oversampling = Barometer.Oversampling.STANDARD,     // SKIP, ULTRA_LOW_POWER, LOW_POWER, STANDARD, HIGH, ULTRA_HIGH
    iirFilter = Barometer.IirFilter.AVG4,               // OFF, AVG2, AVG4, AVG8, AVG16
    standbyTime = Barometer.BmpStandbyTime.MS62_5,      // MS0_5 … MS4000
)
// BME280 (environmental boards) — standby indices 6/7 differ
val baroBme = Barometer(Barometer.Oversampling.STANDARD, Barometer.IirFilter.OFF, Barometer.BmeStandbyTime.MS10)

device.startStream(baro).collect { p -> println("${p.value} Pa") }        // Loggable<Float>, register 0x01, loggerKey "pressure"
device.startStream(Altimeter(barometerConfig = baro)).collect { m -> println("${m.value} m") }   // Streamable<Float>, register 0x02

val pressure = device.read(BarometerPressureRead())   // one-shot Pollable<Float> (barometer must be running in cyclic mode)
```

`moduleInfo(Module.BAROMETER)?.implementation` reports the chip variant (`Barometer.Variant.BMP280` = 0, `BME280` = 1).

### Ambient light (LTR329, module 0x14)

```kotlin
val als = AmbientLight(
    gain = AmbientLight.Gain.X1,                              // X1 X2 X4 X8 X48 X96
    integrationTime = AmbientLight.IntegrationTime.MS100,     // MS50 … MS400
    measurementRate = AmbientLight.MeasurementRate.MS500,     // MS50 … MS2000
)
device.startStream(als).collect { sample ->
    val lux = AmbientLight.lux(sample.value)   // raw Long milli-lux → Float lux
    println("ambient: $lux lx")
}
```

`AmbientLight` is `Loggable<Long>` (`loggerKey = "illuminance"`, register `0x03`); the app decodes logged sessions to lux the same way.

### Humidity (BME280, module 0x16 — MetaEnvironment)

```kotlin
// One-shot read
val percent = device.readHumidity()                    // Float, % RH
// or through the generic pipeline
val sample = device.read(Humidity())                    // Timestamped<Float>

// Configure oversampling once per session (X1 X2 X4 X8 X16)
device.setHumidityOversampling(Humidity.Oversampling.X4)
```

`Humidity` is both `Pollable<Float>` and `PolledLoggable<Float>` — it can be polled live or logged on-board through a `PolledLogger` (see [Logging polled readables](#logging-polled-readables)).

### Sensor fusion (BMM150 + BMI160/270, module 0x19)

```kotlin
SensorFusionQuaternion(mode = SensorFusionMode.NDOF)          // → Quaternion  (w, x, y, z)
SensorFusionEuler(mode = SensorFusionMode.NDOF)               // → EulerAngles (heading, pitch, roll, yaw)
SensorFusionGravity(mode = SensorFusionMode.IMU_PLUS)         // → CartesianFloat (g)
SensorFusionLinearAcceleration(mode = SensorFusionMode.NDOF)  // → CartesianFloat (g, gravity removed)
SensorFusionCorrectedAcc()                                    // → CorrectedCartesianFloat (g, + accuracy)
SensorFusionCorrectedGyro()                                   // → CorrectedCartesianFloat (dps, + accuracy)
SensorFusionCorrectedMag()                                    // → CorrectedCartesianFloat (µT, + accuracy)
// Modes: NDOF (9-DOF), IMU_PLUS (6-DOF), COMPASS, M4G  (SLEEP disables the engine)
// Ranges: accRange = SensorFusionAccRange.G2 … G16, gyroRange = SensorFusionGyroRange.DPS2000 … DPS250
```

All seven outputs are `Loggable` (logger keys `"quaternion"`, `"euler-angles"`, `"gravity"`, `"linear-acceleration"`, `"corrected-acceleration"`, `"corrected-angular-velocity"`, `"corrected-magnetic-field"`). Several can stream simultaneously — they share one on-board engine, so `startStream` only sends the config once and adds each output's enable bit; stopping one output while others run only clears that bit.

The fusion module is fed by the underlying accelerometer + gyroscope (+ magnetometer for `NDOF` / `COMPASS` / `M4G`) on the same board, and `startStream` / `startLogging` configures and starts those sensors for you. The BMI160 and BMI270 encode their config bytes differently, so the fusion signal needs to know which chip is on the board. Pass it via `chip` (defaults to `SensorFusionChip.BMI160`):

```kotlin
// Auto-detect from the gyro module's implementation byte
// (0 = BMI160 on MetaMotion R / RL, 1 = BMI270 on MetaMotion S).
val chip = device.moduleInfo(Module.GYRO)?.implementation
    ?.let { SensorFusionChip.fromGyroImpl(it) }
    ?: SensorFusionChip.BMI160

val q = SensorFusionQuaternion(mode = SensorFusionMode.NDOF, chip = chip)
device.startStream(q).collect { s -> println(s.value) }
```

If you skip the chip argument, the SDK assumes BMI160. On a MetaMotion S (BMI270) the underlying acc/gyro would receive the wrong config bytes silently — the fusion algorithm runs, but at the wrong ODR / range — so always pass the detected chip on those boards.

Related commands: `SensorFusionClearEnabledMask()`, `SensorFusionResetOrientation()`. Calibration lives in `SensorFusionCalibration.kt` — see [Sensor fusion calibration](#sensor-fusion-calibration).

### LED (module 0x02)

```kotlin
// Single channel
device.send(Led.SetPattern(Led.Color.GREEN, LedPattern.blink))
device.send(Led.Play())
device.send(Led.Stop())                        // stop + clear (default)
device.send(Led.Stop(clearPattern = false))    // stop only
device.send(Led.Pause())                       // pause without clearing
device.send(Led.Autoplay())                    // play now and auto-play future patterns

// Built-in presets (LedPattern companion)
// LedPattern.solid   — always on (lowIntensity == highIntensity, never dims)
// LedPattern.blink   — 50 ms on / 450 ms off
// LedPattern.breathe — 725 ms rise / 500 ms high / 725 ms fall over a 2 s pulse
// LedPattern.flash   — 3 short 100 ms pulses

// Multi-channel shorthand — resets, sets patterns, and plays in one call
device.setLed(
    red   = LedPattern(highIntensity = 10, riseTime = 100, highTime = 200,
                       fallTime = 100, pulseDuration = 800, repeatCount = 0xFF),
    green = LedPattern(highIntensity = 31, riseTime = 100, highTime = 300,
                       fallTime = 100, pulseDuration = 800, repeatCount = 0xFF),
    autoPlay = true,
)
device.stopLed()
```

Colors: `GREEN` (0), `RED` (1), `BLUE` (2). Intensity 0–31. `repeatCount = 0xFF` = infinite; a raw `0` is undefined behaviour on the firmware, so the encoder rewrites it to `0xFF`. `LedPattern` also has a `delay` field (ms before the pattern starts) to phase-offset channels.

### GPIO (module 0x05)

```kotlin
// Digital output
device.send(Gpio.SetHigh(pin = 0))
device.send(Gpio.SetLow(pin = 0))
device.send(Gpio.SetPull(pin = 0, pull = Gpio.Pull.UP))   // UP / DOWN / NONE

// One-shot reads
val state: Boolean = device.readDigital(pin = 0)
val adcCount: Int  = device.readAnalogADC(pin = 0)        // raw 10-bit ADC count (0–1023)
val voltage: Int   = device.readAnalogAbsolute(pin = 0)   // millivolts (0–3300)

// Analog reads accept optional pull-up / pull-down / virtual-pin / delay parameters
device.readAnalogADC(pin = 0, parameters = Gpio.AnalogReadParameters(pullupPin = 1, delayMicroseconds = 100))

// Pin-change stream
val signal = GpioPinChange(pin = 0, changeType = Gpio.ChangeType.ANY)   // RISING / FALLING / ANY
device.startStream(signal).collect { sample ->
    println("pin ${sample.value.pin} → ${if (sample.value.isHigh) "high" else "low"}")   // GpioSample
}
```

Command classes are also available directly: `Gpio.ConfigurePinChange(pin, type)`, `Gpio.StartPinMonitor(pin)`, `Gpio.StopPinMonitor(pin)`, `Gpio.DigitalRead(pin, silent)`, `Gpio.AnalogRead(mode, pin, silent, parameters)`. `GpioAnalogSignal(pin, mode)` exposes an analog pin as a data-processor source.

### Switch / button (module 0x01)

```kotlin
device.startStream(Switch()).collect { event ->
    println(if (event.value) "pressed" else "released")   // Streamable<Boolean>
}
```

### Haptic (module 0x08)

```kotlin
device.send(Haptic.motor(dutyCycle = 80, pulseWidth = 500))   // ERM motor: duty 0–100 %, pulse ms
device.send(Haptic.buzzer(pulseWidth = 200))                  // piezo buzzer
// Explicit form: Haptic.Pulse(mode = Haptic.Mode.MOTOR, dutyCycle = 100, pulseWidth = 500)
```

### Temperature (module 0x04)

```kotlin
val celsius = device.read(Thermometer(channel = 0)).value
// Channel sources: NRF_DIE (0), EXT_THERMISTOR, BMP280, PRESET_THERMISTOR — layout varies by board
// (MetaWear R: [NRF_DIE, EXT_THERM]; RPro: [NRF_DIE, PRESET_THERM, EXT_THERM, BMP280])

// Configure an external thermistor's pin mapping
device.send(ThermometerConfigureExt(channel = 1, dataPin = 0, pulldownPin = 1, activeHigh = false))
```

`Thermometer(channel, silent = false)` is `Pollable<Float>` and `PolledLoggable<Float>` (`loggerTriggerIndex = channel`), so each channel can be polled live or logged on-board. `TemperatureChannel` carries named constants (`NRF`, `EXTERNAL_THERMISTOR`, `BMP280`, `BOSCH`, …) and `ThermometerSource` the source enum. The module-discovery `extra` bytes for `Module.TEMPERATURE` list the source of each channel on the connected board.

### Timer (module 0x0C)

On-device periodic timer — fires completely independently of BLE once started.

```kotlin
// Create and start a 500 ms repeating timer
val timer = device.createTimer(periodMs = 500)
device.setTimerNotify(timer, enabled = true)
device.startTimer(timer)

// Stream tick notifications over BLE (Flow<Int> of the firing timer id)
device.streamTimer(timer).collect { timerId -> /* … */ }

// Tear down
device.stopTimer(timer)
device.setTimerNotify(timer, enabled = false)
device.removeTimer(timer)
device.removeAllTimers()      // broad-stroke cleanup of all 8 timer slots

// Parameters
device.createTimer(periodMs = 1000, repetitions = MetaWearTimer.INFINITE, immediate = false)
// repetitions: 0xFFFF = MetaWearTimer.INFINITE; immediate = true fires at t=0
```

### Event (module 0x0A)

Bind a board signal (timer tick, button press, GPIO change, disconnect) to a command that executes on-board — no BLE connection required once configured.

```kotlin
// Flash the green LED every time the timer fires — works even if BLE disconnects
val timer = device.createTimer(periodMs = 500)
device.send(Led.SetPattern(Led.Color.GREEN, LedPattern.blink))
val event = device.createEvent(
    source = EventSource.timerFired(timer),
    action = EventAction.from(Led.Play()),
)
device.startTimer(timer)

// Other event sources
EventSource.buttonChanged()        // fires on every button state change
EventSource.gpioChanged(pin = 0)   // fires on GPIO pin-change notification
EventSource.disconnected()         // fires when the host drops the connection (settings rev ≥ 2)
EventSource(module = Module.TIMER, register = 0x06, dataId = 0xFF)   // any (module, register, id) triple

// Tear down
device.removeEvent(event)
device.removeAllEvents()
```

`EventAction(module, register, params)` describes the destination command; `EventAction.from(command)` splits any `Command`'s bytes into that shape.

#### Source → destination data slicing (`EventDataToken`)

Optional instruction appended to the ENTRY command that tells the firmware to copy `length` bytes starting at `sourceOffset` of the source signal's payload into the destination command's params starting at `destOffset` when the event fires. Without a token the destination params are written as-is.

```kotlin
// Route 4 bytes from source offset 2 into destination offset 3
val event = device.createEvent(
    source = EventSource.timerFired(timer),
    action = action,
    dataToken = EventDataToken(length = 4, sourceOffset = 2, destOffset = 3),
)
// Constraints: length 1…7 (3 bits), sourceOffset 0…15 (4 bits), destOffset any byte
```

### Macro (module 0x0F)

Record a sequence of commands into device flash. Execute manually or automatically on every power-on.

```kotlin
// Record: set pattern + play (stored in flash)
val macro = device.recordMacro(
    executeOnBoot = false,
    commands = listOf(
        Led.SetPattern(Led.Color.GREEN, LedPattern.blink),
        Led.Play(),
    ),
)

// Execute manually
device.executeMacro(macro)

// Or record a boot macro — runs automatically on every power-on
val bootMacro = device.recordMacro(
    executeOnBoot = true,
    commands = listOf(Led.SetPattern(Led.Color.BLUE, LedPattern.flash), Led.Play()),
)

// Erase all macros
device.eraseAllMacros()
```

Commands longer than 13 bytes are split into ADD_PARTIAL + ADD_COMMAND packets automatically. Macro writes use write-with-response.

#### Embedding `createEvent` in a macro

Use the body-based overload when the macro needs to embed a multi-write action — `createEvent(...)` being the primary case. The `MacroRecorder` buffers each call's wire bytes and replays them under one BEGIN…END recording session, so the firmware re-creates the event binding every time the macro runs.

```kotlin
// Bind button → green LED flash, persisted across reboots
val macro = device.recordMacro(executeOnBoot = true) { recorder ->
    recorder.send(Led.SetPattern(Led.Color.GREEN, LedPattern.flash))
    recorder.createEvent(
        source = EventSource.buttonChanged(),
        action = EventAction.from(Led.Play()),
    )
}
```

`MacroRecorder` exposes `send(command: Command)`, `send(sequence: CommandSequence)`, `sendRaw(data: ByteArray)`, and `createEvent(source, action, dataToken)`. Embedded events do not return an `Event.id` — the firmware assigns a fresh ID at replay time. Use `removeAllEvents()` (or `eraseAllMacros()` to also clear persistence) for cleanup.

### Serial passthrough — I2C / SPI (module 0x0D)

Communicate with external sensors or ICs wired to the MetaWear's I2C or SPI bus.

```kotlin
// I2C write — 0x00 to register 0x6B of the device at address 0x68 (e.g. wake an MPU-6050)
device.send(Serial.I2cWrite(deviceAddress = 0x68, registerAddress = 0x6B, data = byteArrayOf(0x00)))

// I2C read — 1 byte from register 0x75 (WHO_AM_I) of the device at 0x68
val bytes = device.i2cRead(deviceAddress = 0x68, registerAddress = 0x75, length = 1)
println("WHO_AM_I: " + bytes.joinToString { "0x%02X".format(it) })

// SPI — pin/mode/clock parameter block
val spi = Serial.SpiParameters(
    slaveSelectPin = 10, clockPin = 0, mosiPin = 11, misoPin = 7,
    mode = Serial.SpiMode.MODE3,            // MODE0 (CPOL=0/CPHA=0) … MODE3 (CPOL=1/CPHA=1)
    frequency = Serial.SpiClock.F1_MHZ,     // F125_KHZ F250_KHZ F500_KHZ F1_MHZ F2_MHZ F4_MHZ F8_MHZ
    lsbFirst = false, useNrfPins = false,
)

// SPI write — send 0x9F (READ_ID)
device.send(Serial.SpiWrite(parameters = spi, data = byteArrayOf(0x9F.toByte())))

// SPI read — 3 bytes (e.g. flash JEDEC ID), optionally writing 0x9F first
val id = device.spiRead(parameters = spi, length = 3, writeData = byteArrayOf(0x9F.toByte()))
```

I2C writes carry at most `Serial.I2cWrite.MAX_PAYLOAD_LENGTH` (10) bytes. Reads reply with a plain notification (not a bit-7 read response), which the device awaits for you.

### iBeacon (module 0x07)

```kotlin
device.send(IBeacon.SetUuid(UUID.randomUUID()))
device.send(IBeacon.SetMajor(1))
device.send(IBeacon.SetMinor(2))
device.send(IBeacon.SetRxPower(-55))
device.send(IBeacon.SetTxPower(-4))
device.send(IBeacon.SetPeriod(periodMs = 700))
device.send(IBeacon.Enable())
// ...
device.send(IBeacon.Disable())
```

### Data processor (module 0x09)

The data processor lets you chain on-device signal transforms so the board filters and reduces data before it ever reaches your app over BLE.

#### Create a processor

```kotlin
// RSS of raw accelerometer — reduces 3-axis to a scalar magnitude
val rssHandle = device.createProcessor(DataProcessor.Rss(), source = AccelerometerSignal())

// Average the RSS output over a 4-sample window
val avgHandle = device.createProcessor(DataProcessor.Average(sampleSize = 4), source = rssHandle)

// Threshold — emit when the average crosses 0.5 g (= 8192 at the ±2 g 16384 LSB/g scale)
val threshHandle = device.createProcessor(
    DataProcessor.Threshold(boundary = 8192, hysteresis = 0,
                            mode = DataProcessor.Threshold.Mode.BINARY, signed = false),
    source = avgHandle,
)
```

The processor chain only produces output while its root sensor is sampling. Start the source either by streaming it (`startStream(acc)`) or — when you don't want the raw notifications on the air — by sending the sensor's configure / enable / start commands yourself through a tiny `Command` wrapper (this is what the app does for its prepare / teardown steps):

```kotlin
class RawCommand(override val commandData: ByteArray) : Command

val acc = AccelerometerBmi270(AccelerometerBmi270.Odr.HZ50, AccelerometerBmi270.Range.G2)
for (cmd in acc.configureCommands + acc.enableCommands + acc.startCommands) device.send(RawCommand(cmd))
// … later …
for (cmd in acc.stopCommands + acc.disableCommands) device.send(RawCommand(cmd))
```

#### Stream a processor's output

```kotlin
device.streamProcessor(threshHandle).collect { packet ->
    // packet = [0x09, 0x03, proc_id, data_bytes...]
    val value = PacketParser.parseInt32LE(packet, 3)     // BINARY threshold outputs an Int32 ±1
    println("threshold crossed: " + if (value > 0) "above" else "below")
}
```

#### Stop and remove

```kotlin
device.stopStreamingProcessor(threshHandle)
device.removeProcessor(threshHandle)

// Remove everything:
device.removeAllProcessors()
```

#### Available processor types

| Type          | Class                       | Output                                                    |
|---------------|-----------------------------|-----------------------------------------------------------|
| Passthrough   | `DataProcessor.Passthrough` | Gate — `Mode.ALL` / `CONDITIONAL` / `COUNT`               |
| Accumulator   | `DataProcessor.Accumulator` | Running sum                                               |
| Counter       | `DataProcessor.Counter`     | Event count                                               |
| Average (LPF) | `DataProcessor.Average`     | Rolling average                                           |
| RMS combiner  | `DataProcessor.Rms`         | Scalar magnitude (root-mean-square)                       |
| RSS combiner  | `DataProcessor.Rss`         | Scalar magnitude (root-sum-square)                        |
| Time delay    | `DataProcessor.Time`        | Rate-limited samples (`Mode.ABSOLUTE` or `DIFFERENTIAL`)  |
| Math          | `DataProcessor.Math`        | Arithmetic transform (`ADD MULTIPLY DIVIDE MODULO EXPONENT SQRT LSHIFT RSHIFT SUBTRACT ABS CONSTANT`) |
| Sample delay  | `DataProcessor.Sample`      | Burst of N buffered samples                               |
| Comparator    | `DataProcessor.Comparator`  | Filter by compare against a reference (`EQ NEQ LT LTE GT GTE`) |
| Threshold     | `DataProcessor.Threshold`   | Crossing events (`Mode.ABSOLUTE` or `BINARY` ±1)          |
| Delta         | `DataProcessor.Delta`       | Emit when input changes by ≥ magnitude (`ABSOLUTE` / `DIFFERENTIAL` / `BINARY`) |
| Pulse         | `DataProcessor.Pulse`       | Detect pulses → `Output.WIDTH` / `AREA` / `PEAK` / `ON_DETECT` |
| Buffer        | `DataProcessor.Buffer`      | Hold last sample (read on demand or fused)                |
| Packer        | `DataProcessor.Packer`      | Pack N samples per BLE packet                             |
| Accounter     | `DataProcessor.Accounter`   | Prepend timestamp (`Mode.TIME`) or packet counter (`COUNT`) |
| Fuser         | `DataProcessor.Fuser`       | Combine latest primary + up to 12 buffered secondaries    |

Chaining — `ProcessorHandle` implements `Signal`, so any processor's output can feed directly into the next `createProcessor` call. The handle carries the board-assigned `id` plus the output `nChannels`, `channelSize`, and `isSigned` so the next stage's config bytes are computed correctly without any manual bookkeeping. Sensor sources ship as `Signal` values: `SwitchSignal`, `AccelerometerSignal`, `GyroscopeSignal`, `GpioAnalogSignal(pin, mode)`, `TemperatureSignal(channel)`, `SensorFusionQuaternionSignal`, `SensorFusionEulerSignal`, `SensorFusionGravitySignal`, `SensorFusionLinearAccelerationSignal`.

#### Recipes

Common processor chains. Each recipe lists how many on-device processor slots it consumes (the board has a fixed pool — typically 28).

**Fire on every Nth event** — count, take mod N, compare. Pair two comparators sharing the modulo output to split a stream into N classes (e.g. odd/even).

```kotlin
// Bind switch presses
val pressed = device.createProcessor(
    DataProcessor.Comparator(DataProcessor.Comparator.Operation.EQ, reference = 1, signed = false),
    source = SwitchSignal())                                                   // slot 1

// Counter → Math(% N) → two Comparators
val counter = device.createProcessor(DataProcessor.Counter(outputSize = 1), source = pressed)   // slot 2
val modN = device.createProcessor(
    DataProcessor.Math(DataProcessor.Math.Operation.MODULO, rhs = 2, signed = false, outputSize = 1),
    source = counter)                                                          // slot 3
val isEven = device.createProcessor(
    DataProcessor.Comparator(DataProcessor.Comparator.Operation.EQ, reference = 0, signed = false),
    source = modN)                                                             // slot 4
val isOdd = device.createProcessor(
    DataProcessor.Comparator(DataProcessor.Comparator.Operation.EQ, reference = 1, signed = false),
    source = modN)                                                             // slot 5

// isEven / isOdd now act as event sources:
device.createEvent(
    source = EventSource(Module.DATA_PROCESSOR, register = 0x03, dataId = isEven.id),
    action = EventAction.from(Led.Play()),
)
```

Slot cost: 5 (or 4 if you only need one of odd/even, or 3 if you don't need the press-edge filter and the source already gives you a single-sample-per-event signal).

**Activity gate (magnitude crosses threshold)** — reduce 3-axis to scalar, then threshold or compare. Useful for activity / freefall / impact detection without streaming raw axes.

```kotlin
val mag = device.createProcessor(DataProcessor.Rss(), source = AccelerometerSignal())    // slot 1
val active = device.createProcessor(
    DataProcessor.Threshold(boundary = 8192,        // 0.5 g at ±2 g range
                            hysteresis = 0,
                            mode = DataProcessor.Threshold.Mode.BINARY,
                            signed = false),
    source = mag)                                                                        // slot 2
// `active` emits +1 on rising edge, –1 on falling edge.
```

Slot cost: 2. Bind `active` as an event source to drive an LED, or feed it into `startLogging(handle, key)` (see [Logging a processor handle](#logging-a-processor-handle)) to record activity transitions to flash.

**Throttle a high-rate signal before logging** — `DataProcessor.Time(periodMs = 1000, mode = Mode.ABSOLUTE)` on `SensorFusionEulerSignal()` takes one sample per period; log its handle with `startLogging(throttle, key = "euler-1hz")`. The full walk-through is in [Logging a processor handle](#logging-a-processor-handle).

Slot cost: 1. `Mode.DIFFERENTIAL` outputs the *delta* between successive periods instead of a raw sample — handy for derivative-style telemetry.

> Cleanup: chains hold each processor's slot until you call `device.removeProcessor(handle)` (or `removeAllProcessors()`). Events bound to a processor must be torn down first (`removeAllEvents()`) — events reference processors, processors reference each other, and the firmware will reject a remove that has live downstream consumers.

### Settings (module 0x11)

```kotlin
device.send(Settings.SetDeviceName("MySensor"))                 // max 26 ASCII bytes (truncates)
device.send(Settings.SetDeviceName.validating("MySensor"))      // throws OperationFailed on invalid chars / length
Settings.isNameValid("MySensor")                                // pre-check: [A-Za-z0-9_- ], ≤ 26

device.send(Settings.SetTxPower(Settings.TxPower.MINUS_4))      // PLUS_4, ZERO, MINUS_4 … MINUS_40
device.send(Settings.SetAdvertisingInterval(intervalMs = 417, timeoutSec = 0))   // 20–10240 ms; 0 = advertise forever
device.send(Settings.StartAdvertising())
device.send(Settings.SetConnectionParameters.lowLatency)        // 7.5 ms interval — high-rate streaming
device.send(Settings.SetConnectionParameters.balanced)          // 30 ms
device.send(Settings.SetConnectionParameters.powerSaving)       // longer interval, latency 4
device.setScanResponse(byteArrayOf(/* raw scan-response payload */))   // long payloads split into a CommandSequence

// Reads (all Pollable)
val battery = device.read(Settings.ReadBatteryState()).value    // BatteryState(voltage, charge)
val power   = device.read(Settings.ReadPowerStatus()).value     // Int
val charge  = device.read(Settings.ReadChargeStatus()).value    // Int

// Whitelist / radio extras (settings revision permitting)
device.send(Settings.SetWhitelistFilterMode(Settings.WhitelistFilterMode.SCAN_AND_CONNECTION_REQUESTS))
device.send(Settings.AddWhitelistAddress(index = 0, address = Settings.BluetoothAddress.parse("AA:BB:CC:DD:EE:FF")))
device.send(Settings.SetThreeVoltPower(enabled = true))
device.send(Settings.SetForce1MPhy(enabled = true))
```

**Verifying a device-name change.** The firmware exposes `SetDeviceName` (register 0x11/0x01) as a write-only opcode — there is no protocol read-back — and a connected board doesn't advertise. To confirm a rename took effect you must disconnect and observe the next advertisement:

```kotlin
device.send(Settings.SetDeviceName("MySensor"))
device.disconnect()
delay(500.milliseconds)                                 // let the radio resume advertising

scanner.clearAdvertisedName(device.identifier)          // discard pre-rename cache
scanner.startScan()
// observe scanner.advertisedNames.value[device.identifier] …
```

Because admission also matches the MetaWear service UUID, a renamed board keeps appearing in `discoveredDevices` — you don't need to keep the `"MetaWear"` prefix.

### Debug (module 0xFE)

```kotlin
device.sendExpectingDisconnect(Debug.Reset())            // soft reset (BLE drops)
device.sendExpectingDisconnect(Debug.JumpToBootloader()) // DFU mode
device.sendExpectingDisconnect(Debug.Disconnect())       // board-initiated disconnect
device.send(Debug.ResetAfterGc())                        // reset after macro/log garbage collection
device.send(Debug.EnablePowerSave())                     // low-power sleep
device.send(Debug.SetStackOverflowAssertion(enable = true))
val overflow = device.read(Debug.ReadStackOverflowState()).value   // OverflowState(length, assertEnabled)
val queues   = device.read(Debug.ReadScheduleQueueUsage()).value   // List<Int>
device.send(Debug.SpoofButtonEvent(value = 1))           // fake a button press on the board
```

Use `sendExpectingDisconnect` for the commands that drop the link so the device converges on `Disconnected` without firing `onUnexpectedDisconnect`.

---

## Logging

### Start / stop / download

```kotlin
val sensor = AccelerometerBmi270(AccelerometerBmi270.Odr.HZ50, AccelerometerBmi270.Range.G2)

device.startLogging(sensor)
// ... time passes, board logs to flash at up to 800 Hz, with or without a BLE link ...
device.stopLogging(sensor)

// Typed download — cumulative progress + decoded samples
device.downloadLogs(sensor).collect { progress ->
    println("${(progress.percentComplete * 100).toInt()}%  ${progress.data.size} samples so far")
}

device.clearLog()
```

- `startLogging` can be called once per sensor while the device is already `Logging` to stack multiple distinct sensors into one session; registering the same `loggerKey` twice is rejected (it would orphan a logger ID on flash and clobber the registry).
- `stopLogging` doesn't require `Logging` state (the first stop in a multi-sensor session already moved the device to `Idle`); it flushes the in-RAM page while the module is still live, stops the logging module, and sends the sensor's stop/disable commands. Logger IDs stay in the local registry so `downloadLogs` can decode afterwards.
- `downloadLogs()` (raw) moves the device to `Downloading(progress)`, force-flushes the active page, reads and settles `LOG_LENGTH`, and streams cumulative `Download<List<RawLogEntry>>` snapshots (`data`, `percentComplete`, `totalEntries`, `entriesDownloaded`). A 60 s inactivity watchdog aborts with `MetaWearException.Timeout` if the firmware stops sending. Pass `expectEntries = true` when you *know* the board logged (local records exist) — a zero `LOG_LENGTH` then triggers a longer flush-settle wait instead of an instant empty download.
- `downloadLogs(loggable)` maps the raw flow through the registry into `Download<List<LoggedSample<S>>>`; `LoggedSample` carries `date` (wall clock), `tickMs` (ms since reset), and `value`.
- `clearLog()` drops all entries and, on MMS (logging revision ≥ 3), waits up to 60 s for the firmware's Drop-Entries completion notice so the next session doesn't arm loggers on a board still grinding through NAND garbage collection. `stopOnBoardLogging()` just stops the logging module.

### Flushing the last log page (MMS only)

MetaMotion S boards (logging revision ≥ 3) buffer the final partial flash page in RAM. Both `stopLogging` and `downloadLogs` call `flushLogPage()` for you, so a short session (a few seconds at low ODR) is never stranded; call it yourself if you stop the module manually. On older boards the call is a no-op and returns `false`.

```kotlin
device.stopLogging(sensor)
val flushed = device.flushLogPage()      // true on MMS, false elsewhere
```

### CSV export

Any list of logged or streamed samples can be converted to a `DataTable` and exported as CSV. `DataConvertible` knows the column layout for `CartesianFloat`, `Quaternion`, `EulerAngles`, `CorrectedCartesianFloat`, `Float`, and `Boolean` (convert raw `Long` illuminance to lux `Float` first); quaternion tables gain derived `heading,pitch,roll` columns (`Quaternion.derivedEulerAngles`) matching the firmware's Euler convention.

```kotlin
// From logged samples — columns: epoch,elapsed_ms,x,y,z
val table = DataTable.fromLogged(entries, name = "acceleration")
println(table.csvString)
table.writeCsv(File(context.cacheDir, "accel.csv"))

// From streamed samples — columns: epoch,x,y,z
val streamed = mutableListOf<Timestamped<CartesianFloat>>()
// ... fill from stream ...
val table = DataTable.fromStreamed(streamed, name = "acceleration")
```

### Sensor fusion calibration

```kotlin
// Read calibration while sensor fusion is running (0 = unreliable … 3 = high)
val cal = device.read(SensorFusionCalibrationState()).value
println("Accel: ${cal.accelerometer}  Gyro: ${cal.gyroscope}  Mag: ${cal.magnetometer}")

// Or poll it while coaching the user through the calibration motions
device.poll(SensorFusionCalibrationState(), every = 2.seconds).collect { /* … */ }

// Persist / restore the 10-byte per-sensor calibration blobs
device.send(SensorFusionWriteAccCalibration(data.acc))
device.send(SensorFusionWriteGyroCalibration(data.gyro))
device.send(SensorFusionWriteMagCalibration(data.mag))
```

`SensorFusionCalibrationState` needs sensor fusion revision ≥ 1. `SensorFusionCalibrationData(acc, gyro, mag)` holds the blobs.

### Auto-select accelerometer

```kotlin
// Picks BMI160 or BMI270 from the module table read during connect(), snapping ODR / range
// to the nearest supported value (impl 1 = BMI160, 4 = BMI270).
val impl = device.moduleInfo(Module.ACCELEROMETER)?.implementation ?: -1
val acc = Accelerometer.make(impl, odrHz = 100.0, rangeG = 2f)   // Accelerometer? (null on unknown chip)
if (acc != null) {
    device.startStream(acc)     // or device.startLogging(acc)
}

// Same for the gyroscope (impl 0 = BMI160, 1 = BMI270)
val gyro = Gyroscope.make(device.moduleInfo(Module.GYRO)?.implementation ?: -1, odrHz = 100.0, rangeDps = 2000f)
```

### Log time anchor

During `connect()`, the SDK reads the board's current log tick (`[0x0B, 0x84]`) and converts it to a wall-clock `Instant` stored in `device.logReferenceDate`. Downloaded `LoggedSample` values carry both a `date` (wall clock, derived from that anchor when available) and a `tickMs` (ms since device reset). `RawLogEntry.epochMs` exposes the same tick → ms conversion for raw entries.

### Logger registry across reconnects

Logger subscriptions survive an unexpected BLE disconnect: the board keeps the same logger IDs, and the SDK intentionally preserves `loggerRegistry` on an unexpected drop so a `reconnect()` can download without re-registering. If the *process* restarted (registry lost) but the board is still holding entries, rebuild it from the board's trigger table:

```kotlin
device.connect()
device.recoverLoggers(sensor)                    // matches queryActiveLoggers() by module + register
device.downloadLogs(sensor).collect { /* … */ }
```

`queryActiveLoggers()` / `queryActiveProcessors()` enumerate the board's logger and processor slots (`ActiveLogger`, `ActiveProcessor`) with a 1 s per-probe timeout — the firmware never answers an empty slot, so every enumeration ends with one timed-out probe. Both take a pre-fetched enumeration overload (`recoverLoggers(sensor, active)`) when you're recovering several sensors at once.

### Logging polled readables

Read-only sensors (temperature, humidity, one-shot pressure) can't be streamed, but they can be logged on-board through a **timer → event → logger** chain that the SDK builds for you: the board fires the read every `periodMs` and writes each response to flash with no host involvement — it keeps working across disconnects and app close.

```kotlin
val logger = PolledLogger(readable = Thermometer(channel = 0), periodMs = 1_000)

val handles: PolledLoggerHandles = device.startLogging(logger)   // timerID, eventID, loggerIDs — persist these!
// ... time passes ...
device.stopLogging(logger, handles)                              // stop + remove timer, remove event, stop logging
device.downloadLogs(logger).collect { progress -> /* LoggedSample<Float> */ }
device.clearLog()

// After an app restart with the board still logging:
device.recoverLoggers(logger)                                     // rebuilds the registry from queryActiveLoggers()
```

Anything implementing `PolledLoggable` works: `Thermometer`, `Humidity`, or your own adapter over a one-shot read register (the app's `PolledPressure` does this for barometer pressure).

### Board state capture / restore

After a full `connect()` handshake you can persist the discovered device information, module table, and log time anchor, and pre-populate a fresh `MetaWearDevice` with them before it connects — so an app can render model / firmware / module details for a remembered board while it is still offline.

```kotlin
// After connect(), snapshot current state
val state = device.captureBoardState() ?: return
val json: String = state.encode()                     // JSON text
// …persist `json` to DataStore / SharedPreferences / Room / a file…

// Next session, before connect():
val restored = BoardState.decode(json)
device.restoreBoardState(restored)                    // throws OperationFailed if not disconnected
// device.deviceInfo / device.modules / device.logReferenceDate are now populated offline

device.connect()                                       // refreshes them from the live board
if (!restored.isCompatible(device.deviceInfo!!)) {     // firmware or hardware revision changed
    // invalidate anything you derived from the cached state
}
```

`BoardState` is a value type with `equals`/`hashCode`; its JSON schema is versioned (`BoardState.CURRENT_SCHEMA_VERSION`) so caches written by a newer SDK are rejected safely (`decode` throws `OperationFailed` when the stored schema is newer than the SDK supports). `restoreBoardState` performs no compatibility validation of its own — that's what `isCompatible(liveInfo)` is for.

### Anonymous signals (recover loggers the SDK didn't configure)

If the board still holds active loggers and data processors from a prior session — or from another SDK — call `createAnonymousDataSignals()` to reconstruct `List<AnonymousSignal>` with canonical identifiers and typed decode closures, then download as normal.

```kotlin
device.connect()
val signals = device.createAnonymousDataSignals()
for (sig in signals) {
    println("${sig.identifier} → loggers ${sig.loggerIDs} root ${sig.rootModule}")
}

// Each AnonymousSignal exposes:
//   .identifier   — canonical name (e.g. "acceleration", "angular-velocity:rms?id=0")
//   .rootModule   — underlying Module the chain reads from
//   .chunks       — List<AnonymousSignal.Chunk(id, byteCount)>; .loggerIDs = chunks.map { it.id }
//   .decode(ByteArray) -> List<AnonymousSignal.Output>
//       Output = Cartesian | Scalar | Quaternion | Euler | CorrectedCartesian
```

Wiring downloaded log entries into a signal's decode closure means grouping `RawLogEntry.rawData` bytes by `loggerIDs` and feeding each reassembled payload to `decode`. The app's `AnonymousSignalDecoder` (`data/ForeignLog.kt`) does exactly this to download foreign logs.

Scale factors (accel / gyro range) are read from the live board at call time (`[0x03, 0x83]`, `[0x13, 0x83]`) — if you change range afterward, call `createAnonymousDataSignals()` again.

### Logging a processor handle

The typed `startLogging(loggable)` overload is sensor-shaped — it reads `Loggable.logDataChunks` and parses samples back via `parseLogSample`. For the output of a data-processor chain (throttle, RMS, accumulator, fuser, …) the SDK exposes a key-based overload that takes the `ProcessorHandle` directly and lets you supply your own decoder at download time:

```kotlin
// Throttle 100 Hz Euler fusion down to 1 Hz, log 10 s, then download.
val euler = SensorFusionEuler(mode = SensorFusionMode.NDOF, chip = SensorFusionChip.BMI270)
for (cmd in euler.configureCommands + euler.enableCommands + euler.startCommands) device.send(RawCommand(cmd))

val throttle = device.createProcessor(
    DataProcessor.Time(periodMs = 1000, mode = DataProcessor.Time.Mode.ABSOLUTE),
    source = SensorFusionEulerSignal(),
)
val key = "euler-throttle-1hz"
device.startLogging(throttle, key = key)
delay(10.seconds)
device.stopLogging(key = key)
for (cmd in euler.stopCommands + euler.disableCommands) device.send(RawCommand(cmd))
device.flushLogPage()                                     // MMS only (no-op elsewhere)

device.downloadLogs(key = key) { data ->
    euler.parseLogSample(data)                            // any (ByteArray) -> S decoder
}.collect { progress ->
    println("${progress.percentComplete} ${progress.data.size} samples")
}
```

What's going on:

- `startLogging(handle, key)` splits the handle's full output (`nChannels × channelSize`) into ≤ 4-byte chunks (the firmware's per-entry byte limit), issues one `[0x0B, 0x02, 0x09, 0x03, proc_id, packed]` subscribe per chunk, registers the resulting logger IDs under `key`, and transitions the device to `Logging`. It does not touch any sensor lifecycle — starting and stopping the source that feeds the chain is your job.
- `downloadLogs(key, decode)` reassembles entries by logger-ID order and hands each reconstructed payload to your decoder.

Sensor-fusion outputs are exposed as `Signal` values for use as processor sources: `SensorFusionEulerSignal`, `SensorFusionQuaternionSignal`, `SensorFusionGravitySignal`, `SensorFusionLinearAccelerationSignal`.

---

## Firmware updates

`:metawear-firmware` is a separate module so apps that never flash firmware don't pull in the Nordic DFU library. Every update entry point returns a cold `Flow<DFUProgress>`: drive a progress bar from the same collection that catches failure, and cancel the collector to abort the transfer.

### Check and update a connected board

```kotlin
import com.mbientlab.metawear.firmware.checkForFirmwareUpdate
import com.mbientlab.metawear.firmware.updateFirmwareToLatest

// Is something newer on the MbientLab release catalog?
val build = device.checkForFirmwareUpdate()          // FirmwareBuild? — null when already current
if (build != null) println("Update available: ${build.firmwareRev}")

// Flash the latest release. The flow finishes after FETCHING_CATALOG with no COMPLETED event
// if the board is already current (pass forceReinstall = true to reflash regardless).
device.updateFirmwareToLatest(context).collect { progress ->
    println("${progress.state} ${progress.percentComplete.toInt()}%  part ${progress.currentPart}/${progress.totalParts}")
}

// The flash reboots the board and leaves the cached device state stale:
device.connect()
```

`DFUProgress.State` walks `FETCHING_CATALOG → DOWNLOADING_FIRMWARE → BOOTLOADER_HANDOFF → SCANNING → CONNECTING → STARTING → UPLOADING → VALIDATING → DISCONNECTING → COMPLETED` (or `ABORTED`); `percentComplete` is only populated during `UPLOADING`, and `bytesPerSecond` reports the transfer rate.

`updateFirmware(context, zipUrl)` flashes an explicit firmware file instead of the catalog build — an `https://` URL or a `file://` URI; `.zip` (Nordic DFU distribution package), or raw `.bin` / `.hex`. Either path handles the bootloader handoff itself: it sends `[0xFE, 0x02]` via `sendExpectingDisconnect(Debug.JumpToBootloader())`, waits for the board to drop the link as it reboots into MetaBoot, then runs the Nordic DFU transfer against the same MAC address.

An outdated bootloader is handled automatically on the catalog path: after the handoff, `MetaBootProbe` reads the installed bootloader version from MetaBoot's Device Information service, `BootloaderInterlock` compares it against the build's `required-bootloader`, and the needed bootloader-flavor flash(es) are chained before the application stage. Multi-stage flashes surface through `DFUProgress.currentPart` / `totalParts`; `COMPLETED` is emitted only when the final stage finishes.

`FirmwareServer` is the catalog client (`availableBuilds`, `latestBuild`, `build`, `updateAvailable`, `downloadFirmware`; default catalog `https://mbientlab.com/releases/metawear/info2.json`), `FirmwareBuild` describes one artifact (`hardwareRev`, `modelNumber`, `buildFlavor`, `firmwareRev`, `filename`, `requiredBootloader`, `firmwareUrl`), and `FirmwareException` is the sealed error taxonomy (`BadServerResponse`, `NoAvailableFirmware`, `InvalidFirmwareFile`, `BootloaderUpgradeUnavailable`, `DeviceNotIdle`, `DfuFailed`, `Aborted`, …).

### Android specifics

- The DFU transfer runs inside an Android service. The library declares `MetaWearDfuService` (a `DfuBaseService` subclass) in its manifest, and manifest merging carries the registration into your app — nothing to add unless you subclass it for a foreground notification target, in which case also create Nordic's notification channel via `DfuServiceInitiator.createDfuNotificationChannel(context)` first.
- Firmware updates need the same `BLUETOOTH_CONNECT` runtime permission as the rest of the SDK.
- The catalog JSON is parsed by a minimal hand-rolled parser (Android's `org.json` is stubbed out on the JVM unit-test classpath), keeping all **66 firmware tests** — version compare, catalog selection, server/mock-fetcher, bootloader interlock — plain JVM unit tests. The `DfuSession` / `MetaBootProbe` wrappers are hardware-only and carry no unit-test coverage.

### Boards stuck in MetaBoot

A board whose application flash never completed sits in bootloader mode: it advertises as **"MetaBoot"** with the Nordic DFU service instead of the MetaWear service, so `MetaWearScanner` deliberately does not admit it and the normal connect flow can't talk to it. The SDK's DFU path recovers such a board when it already knows the MAC (the `updateFirmware*` flows scan for the bootloader by address after the handoff), but there is no standalone MetaBoot scan mode or rescue-by-address entry point yet — see [What's not yet implemented](#whats-not-yet-implemented).

---

## Persistence (Room)

`:metawear-persistence` stores downloaded log sessions in a Room database (`com.mbientlab.metawear.persistence`). It depends on `:metawear-protocol` and ships its own JVM test suite (46 tests, against an in-memory fake of the DAO) plus an instrumented suite (13 tests) that re-checks what the fake can only mirror — the generated SQL's sort orders and counts, the foreign-key cascade, and the epoch-millis converter — against a real Room database on-device.

### One database per app, one store per call site

```kotlin
import com.mbientlab.metawear.persistence.*

val db    = PersistenceDatabase.create(context)              // or PersistenceDatabase.createInMemory(context) for tests
val store = PersistenceStore(db.persistenceDao())
```

All `PersistenceStore` methods are `suspend` functions over the Room DAO.

### Save a download session

```kotlin
val samples: List<LoggedSample<CartesianFloat>> = collectAllSamples(device.downloadLogs(sensor))   // your code

val snapshot = store.saveSession(
    deviceID   = device.identifier,
    deviceInfo = device.deviceInfo!!,
    sensorKind = CartesianFloatPersistable.persistenceKind,   // "cartesian"
    samples    = samples,
    persistable = CartesianFloatPersistable,
    label      = "Accelerometer · ±2 g · 50 Hz",              // optional display hint
    deviceName = scanner.advertisedNames.value[device.identifier],
)
println("Saved session ${snapshot.id} with ${snapshot.sampleCount} samples")
```

`SessionSnapshot` is a plain immutable value (`id`, `deviceID`, `sensorKind`, `startDate`, `endDate`, `sampleCount`, `deviceSerial`, `deviceModel`, `deviceFirmware`, `label`, `deviceName`, `groupID`) — safe to hand to Compose. The Room `@Entity` types (`SessionRecord`, `SampleRecord`, with a cascade foreign key so deleting a session deletes its samples) stay inside the store.

### Fetch / reconstruct / export

```kotlin
// All sessions for one device, newest first (or store.fetchAllSessions())
val sessions = store.fetchSessions(deviceID = device.identifier)

// Rehydrate typed samples
val acceleration = store.fetchSamples(sessionID = snapshot.id, persistable = CartesianFloatPersistable)

// One-step CSV — columns epoch,elapsed_ms,x,y,z
val table = store.exportTable(sessionID = snapshot.id, persistable = CartesianFloatPersistable)
table.writeCsv(File(context.cacheDir, "session.csv"))
```

`fetchSamples` and `exportTable` validate that the session's `sensorKind` matches the requested codec and throw `PersistenceException.KindMismatch` otherwise; `saveSession` with zero samples throws `PersistenceException.EmptySampleSet`; unknown IDs throw `PersistenceException.SessionNotFound`.

### Delete

```kotlin
store.deleteSession(id = snapshot.id)
store.deleteAllSessions(deviceID = device.identifier)
store.deleteAll()
```

### Supported sample types

`Persistable<S>` is a small codec interface that pairs a `persistenceKind` discriminator with a flat `(f0, f1, f2, f3, accuracy)` packing (`PersistedValues`). One singleton codec ships per SDK sample type — `FloatPersistable` (`"float"`), `BoolPersistable` (`"bool"`), `CartesianFloatPersistable` (`"cartesian"`), `CorrectedCartesianFloatPersistable` (`"corrected-cartesian"`), `QuaternionPersistable` (`"quaternion"`), `EulerAnglesPersistable` (`"euler"`) — in `PersistableConformances.kt`. `Instant`s persist as epoch milliseconds; sessions are identified by store-assigned UUID strings and devices by their Android MAC identifier. Adding a new sensor type means adding one codec object.

---

## Data modes

### Streaming (live)

BLE delivers data as fast as the connection interval allows (~100 Hz practical aggregate on most phones — the app's `BandwidthAdvisor` warns past that). Packed mode sends 3 samples per BLE packet, tripling effective throughput for IMU sensors.

```
MetaWear → BLE notifications (packed, ~33/sec at 100 Hz)
         → unpack 3 samples per notification
         → Flow<Timestamped<Sample>>
```

### Logging (on-device flash)

Sensors log to flash at up to 800+ Hz independent of BLE. Download when done.

```
MetaWear flash → BLE burst download (1 or 2 entries per notification)
              → parse 9-byte log entries (tick → epoch)
              → Flow<Download<List<LoggedSample<Sample>>>>
```

Log entry format (9 bytes, `PacketParser.parseLogEntry`):
```
Byte 0:    (reset_uid[2:0] << 5) | log_id[4:0]
Bytes 1–4: tick (32-bit LE, ~1.465 ms/tick)
Bytes 5–8: raw sensor data (32-bit LE)
```

A single-entry readout notification is therefore 2 (header) + 9 = 11 bytes; a paired one is 20. Tick math: `PacketParser.MS_PER_TICK = (48.0 / 32768.0) × 1000 ≈ 1.4648 ms/tick`. Signals wider than 4 bytes are split into ≤ 4-byte chunks (`LogChunk(offset, length)`), one logger ID per chunk, and reassembled in logger-ID order on download.

---

## BLE packet format

```
Byte 0:   module_id    (e.g. 0x03 = accelerometer)
Byte 1:   register_id  (| 0x80 for READ requests; response echoes this bit set;
                        | 0x40 = "silent" read used by event-triggered / polled-logger reads)
Byte 2+:  payload      (little-endian)
```

Commands → `command` characteristic (`Uuids.command`, `326A9001-85CB-9195-D9DD-464CFBBAE75A`), write-without-response. Responses/notifications → `notify` characteristic (`Uuids.notify`, `326A9006-…`). The MetaWear service is `326A9000-…`. Macros use write-with-response. Device Information Service reads (`0x180A`: model `0x2A24`, serial `0x2A25`, firmware `0x2A26`, hardware `0x2A27`, manufacturer `0x2A29`) resolve by characteristic UUID.

Module IDs (`com.mbientlab.metawear.protocol.Module`):

| ID | Module | ID | Module |
|---|---|---|---|
| 0x01 | Switch | 0x0D | Serial (I2C / SPI) |
| 0x02 | LED | 0x0F | Macro |
| 0x03 | Accelerometer | 0x11 | Settings |
| 0x04 | Temperature | 0x12 | Barometer |
| 0x05 | GPIO | 0x13 | Gyroscope |
| 0x07 | iBeacon | 0x14 | Ambient light |
| 0x08 | Haptic | 0x15 | Magnetometer |
| 0x09 | Data processor | 0x16 | Humidity |
| 0x0A | Event | 0x19 | Sensor fusion |
| 0x0B | Logging | 0xFE | Debug |
| 0x0C | Timer | | |

`Packet.command(module, register, vararg payload)` and `Packet.read(module, register, …)` build outgoing bytes; `PacketParser` holds the little-endian decoders (`parseInt16LE`, `parseUInt32LE`, `parseFloat32LE`, `parseCartesianFloat`, `parsePackedCartesianFloat`, `parseQuaternion`, `parseEulerAngles`, `parsePressure`, `parseAltitude`, `parseTemperature`, `parseIlluminance`, `parseHumidity`, `parseMacAddress`, `parseBatteryState`, `parseLogEntry`, …).

---

## Testing

### Unit tests (no hardware required)

```bash
./gradlew :metawear-protocol:test
./gradlew :metawear-persistence:testDebugUnitTest
./gradlew :metawear-firmware:testDebugUnitTest
./gradlew :app:testDebugUnitTest
# or target one suite:
./gradlew :metawear-protocol:test --tests '*TimerTest*'
```

Four JVM test source sets ship with the repo — **1275 tests** in total:

- **`:metawear-protocol`** — 1043 tests across 43 files. The full SDK surface, run against `MockBleTransport`, including reference byte vectors from the MetaWear C++ SDK's Python test suite. No hardware required.
- **`:metawear-persistence`** — 46 tests (`PersistableConformanceTest`, `PersistenceStoreTest`, `SessionExportTest`, `AttributionStampTest`) against an in-memory fake DAO — no hardware, no on-disk side effects.
- **`:metawear-firmware`** — 66 tests (`BootloaderInterlockTest`, `DFUProgressTest`, `FirmwareBuildTest`, `FirmwareCatalogTest`, `FirmwareExceptionTest`, `FirmwareServerTest`, `MetaWearVersionTest`).
- **`:app`** — 120 tests: ring buffer / decimation / effective-Hz math, quaternion cube and tare frame, CSV exporters, session grouping, foreign-log decisions, group-capture coordination, and end-to-end walks through the real `MetaWearDevice` against the test-only `DemoBleTransport`.

`:metawear-protocol` coverage by file (ordered roughly by dependency):

| Suite file(s)                                              | What it covers                                                                       |
|------------------------------------------------------------|--------------------------------------------------------------------------------------|
| `protocol/PacketBuilderTest`, `PacketParserTest`, `ProtocolRegressionTest` | Command / read packet construction; raw byte → Kotlin type parsing for all sensors; wire vectors from the C++ SDK test suite |
| `protocol/ProtocolRouterTest`                              | Notification routing, module discovery, concurrent reads, timeouts                   |
| `transport/MockBleTransportTest`, `UuidsTest`              | Mock transport semantics, in-band termination; UUID constants                        |
| `model/BoardModelTest`, `BoardStateTest`                   | Model detection; capture / restore of discovered modules, JSON round-trip, schema versioning |
| `model/DataTableTest`, `AnonymousSignalTest`               | CSV table construction and export, derived Euler; reconstruction of unknown loggers, chunk partitioning, identifier scheme |
| `sensor/AccelerometerCommandTest`, `AccelerometerBoschTest`, `AccelerometerBmi270FeaturesTest` | BMI160 / BMI270 config bytes and packed registers; orientation / any-motion / tap / step; activity, wrist, no-motion, significant motion, downsampling |
| `sensor/GyroscopeCommandTest`, `MagnetometerCommandTest`   | Gyro config bytes, ranges, offsets; magnetometer presets, manual config, suspend, warm-up |
| `sensor/BarometerCommandTest`, `AmbientLightTest`, `HumidityTest`, `TemperatureTest` | BMP280 / BME280 config, altimeter, pressure read; LTR329 config + lux; humidity read + oversampling; channel / silent reads, ext-thermistor config, polled logging |
| `sensor/SensorFusionCommandTest`, `SensorFusionTest`       | Mode / range / chip config bytes, seven outputs, shared engine, calibration          |
| `sensor/LedTest`, `SwitchHapticTest`, `GpioTest`           | LED pattern bytes and presets, `setLed` / `stopLed`; switch stream, haptic pulses; GPIO outputs, one-shot reads, analog parameters, pin-change stream |
| `sensor/TimerTest`, `EventTest`, `MacroTest`               | Timer create/start/stop/remove, tick stream; event sources, ENTRY format, data token; recordMacro (list + body), ADD_PARTIAL, execute, erase |
| `sensor/SerialTest`, `IBeaconTest`                         | I2C / SPI write + read bytes and response parsing; iBeacon UUID / major / minor / power / period bytes |
| `sensor/DataProcessorTest`, `DataProcessorDemuxTest`       | ADD command bytes and config bits for all 17 processor types; per-id demux           |
| `sensor/SettingsTest`, `DebugTest`, `MiscReadablesTest`    | Device name validation, TX power, advertising, connection params, whitelist, reads; debug commands; `LogLength`, `LastResetTime`, `LoggingEnabled`, MAC |
| `MetaWearDeviceTest`, `MetaWearScannerTest`                | State machine, connect/disconnect, streaming guards, fusion / IMU conflicts; admission rule, name / RSSI caches, known-device promotion |
| `GenericReadPollTest`                                      | Generic `device.read` and `device.poll` for `Readable` / `Pollable`                  |
| `LoggingTest`, `PolledLoggingTest`, `LogFinishingTest`     | startLogging commands, `RawLogEntry` parsing, chunk config, download, clearLog; timer → event → logger chain and recovery; log time anchor, registry persistence |
| `DeviceAnonymousSignalsTest`, `FactoryResetTest`           | Live-board scale reads and signal reconstruction; seven-write reset sequence, fallback, post-reset state |
| `ProductionGapTest`                                        | Concurrent reads, multi-sensor streaming, reconnect, device-name validation, edge cases |

### MockBleTransport

Inject notifications and inspect written commands in tests:

```kotlin
val transport = MockBleTransport().apply {
    setReadResponse(Uuids.manufacturerName, "MbientLab".toByteArray())
    setReadResponse(Uuids.modelNumber, "8".toByteArray())
    setReadResponse(Uuids.serialNumber, "A0B1C2".toByteArray())
    setReadResponse(Uuids.firmwareRevision, "1.7.3".toByteArray())
    setReadResponse(Uuids.hardwareRevision, "0.1".toByteArray())
}
val device = MetaWearDevice("AA:BB:CC:DD:EE:FF", transport, backgroundScope)

// Inject a response to a read command
transport.inject(bytes(0x0C, 0x82, 0x01), Uuids.notify)

// Inspect what the device wrote
val cmds: List<ByteArray> = transport.writtenCommands            // or writtenData for (data, characteristic, type)
transport.clearWrites()

// Fault injection
transport.connectError = MetaWearException.BluetoothPoweredOff
transport.simulateDisconnect()                                    // fails open streams / waiters
transport.mockRssi = -70
```

`bytes(…)` is the test helper `internal fun bytes(vararg v: Int): ByteArray` — see [Design notes](#design-notes) for why.

### Hardware integration tests

Hardware tests require a real MetaWear nearby. They live in `:metawear-core`'s `androidTest` source set and run through `connectedAndroidTest` on a phone with USB debugging enabled; the test runner grants the Bluetooth permissions itself (`GrantPermissionRule`).

1. Connect an Android phone with USB debugging enabled (API 31+ recommended).
2. Charge a MetaMotion S and place it within BLE range.
3. Run:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :metawear-core:connectedAndroidTest
```

`HardwareSmokeTest` alone exercises a real MetaMotion S (BMI270) end-to-end: scan → connect (device info + module discovery) → battery read → LED pattern → 2 s accelerometer stream (~1 g at rest) → disconnect. `HardwareSupport` scans for the first advertised `"MetaWear"` name for 10 s and every test **skips itself cleanly** (JUnit `assumeTrue`) when no board is in range, so the task is safe to run on benches without hardware. With several boards in range it picks the lowest MAC for run-to-run stability. Modules that are not present on the connected board (e.g. humidity on a board without a BME280) are skipped within each test. The HTML report lands in `metawear-core/build/reports/androidTests/connected/`.

Hardware suites — 43 tests across 11 files, verified on a Pixel 7 + MetaMotion S:

| Suite                              | What it covers                                                                                                   |
|------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `HardwareSmokeTest`                | Scan finds a MetaWear with MAC identifier + sane RSSI; connect reaches `Idle` with device info + modules; battery read; green LED flash plays and stops; packed accelerometer stream ~1 g at rest; disconnect returns to `Disconnected` |
| `GyroscopeBmi270HardwareTest`      | Unpacked stream then back to `Idle`; packed stream at higher throughput                                          |
| `MagnetometerHardwareTest`         | Low-power stream; high-accuracy preset gives a plausible Earth field; packed stream and suspend when the module revision supports them |
| `SensorFusionHardwareTest`         | Quaternion stream then `Idle`; unit-magnitude quaternion; gravity ~1 g; quaternion + Euler together on the shared engine |
| `SwitchHardwareTest`               | Switch stream starts and stops without error                                                                     |
| `HapticHardwareTest`               | Motor pulse, buzzer pulse, motor at max duty cycle                                                               |
| `GpioHardwareTest`                 | Digital read with pull-up / pull-down, 10-bit ADC, absolute millivolts on the rail, pin-change stream lifecycle   |
| `SettingsHardwareTest`             | Realistic battery, device name set / restore, every TX power level, RSSI while connected, start advertising      |
| `EnvironmentSensorHardwareTest`    | NRF die temperature, barometer pressure, altimeter, humidity (skips when not fitted)                             |
| `ReadHardwareTest`                 | Generic `device.read` round-trip for every temperature channel, battery, last reset time, log length, MAC        |
| `LoggingHardwareTest`              | Accelerometer log → download round trip, log length zero after clear / increasing while logging, `queryActiveLoggers` empty after clear |

The persistence module's instrumented suite (`PersistenceDatabaseTest`, 13 tests) needs only a phone, not a board:

```bash
./gradlew :metawear-persistence:connectedAndroidTest
```

See [`HARDWARE.md`](HARDWARE.md) for the full step-by-step procedure (phone setup, `adb`, the suites, and a manual app pass), plus troubleshooting.

---

## Demo transport (test fixture)

`DemoBleTransport` (`app/src/test/kotlin/com/mbientlab/metawear/app/demo/`) is a protocol-level MetaMotion S emulator behind the same `BleTransport` seam the real transport implements. It lives in the app's **test source set only** — the shipping app has no demo mode and never constructs it. It emulates a connected MetaMotion S on firmware 1.7.3: module discovery, Device Information / battery / MAC / temperature / humidity / pressure / illuminance / log reads, synthetic waveforms on every sensor including the packed registers, all seven fusion outputs, altitude and ambient light, and full logging round trips — streamed sensors, the polled timer / event / logger chain, and logger recovery across a simulated process restart.

- The JVM tests build a `MetaWearDevice` directly over it (`MetaWearDevice(identity.identifier, DemoBleTransport(scope, identity), scope)`) and drive the real device stack — connect, discovery, streaming, logging, download — with no hardware and no Android runtime.
- `DemoBleTransport.Identity.board(index)` mints up to 16 distinguishable identities (MAC, serial, waveform phase offset), so `GroupCaptureCoordinatorTest` walks a simulated three-board fleet end-to-end against the real persistence store.
- The class is pure Kotlin (no Android imports); `DemoBleTransportTest` and `DemoIdentityTest` cover its protocol fidelity and identity minting.

The 3D orientation view is deliberately a Canvas wireframe cube: only the quaternion frame math is load-bearing, and it stays dependency-free.

---

## Design notes

Kotlin / coroutines details the codebase relies on — read these before touching the tests:

- **`ByteArray` equality** is reference-based in Kotlin — tests compare command bytes with `assertArrayEquals`, never `==`; value classes wrapping `ByteArray` (`ScanResult`, `MockBleTransport.Write`) override `equals` with `contentEquals`.
- Bytes are built from `Int` literals via a `bytes(…)` test helper to avoid the `byteArrayOf(0x80)` "does not fit in Byte" compile error (in production code use `0x9F.toByte()` or `Packet.command(...)`, which takes `Int` varargs).
- `MockBleTransport` notification flows deliver termination **in-band** (as sealed events through the channel) rather than via `Channel.close(cause)`: closing a channel does not wake a receiver parked on a coroutines-test `backgroundScope` dispatcher, while `trySend` does. In-band markers also guarantee that packets buffered before a failure are delivered first, then the failure is thrown.
- In tests, prefer `runCurrent()` (or suspending the test body) over `advanceUntilIdle()` when the thing you're waiting on runs in `backgroundScope` — `advanceUntilIdle` only drains foreground tasks.
- The protocol router's read timeout uses `withTimeout` + `invokeOnCancellation` (atomic with respect to resume), so the waiter map is simply pruned — no tombstoning needed to stay consistent under cancellation.
- Expected-failure `async` blocks in tests catch inside the block (`async { runCatching { … } }`) — a failed `async` child cancels the test scope even if the `await` is wrapped.
- Subscription and processor channels use `BufferOverflow.DROP_OLDEST` (256 packets): a stalled collector sheds the oldest samples rather than growing memory without bound.
- `MetaWearDevice` and `MetaWearScanner` take a `CoroutineScope` (default: a process-lifetime `SupervisorJob() + Dispatchers.Default`); pass a narrower scope (e.g. a ViewModel's) to tie SDK work to a lifecycle. Tests pass `runTest`'s `backgroundScope` for virtual time.

---

## What's not yet implemented

| Gap | Blocks | Workaround |
|---|---|---|
| Standalone MetaBoot scan mode / rescue-by-address firmware flash | Reflashing a board that is stranded in bootloader mode when you don't already have a `MetaWearDevice` for it | The `updateFirmware*` flows recover a board whose MAC you know once the handoff has happened; otherwise reflash with the Nordic nRF Connect app |
| First-class `Streamable` wrappers for the accelerometer interrupt registers (orientation, any-motion, tap, activity, wrist, no-motion, step) | Receiving interrupt packets without writing an adapter | Wrap the notification register in a small `Streamable` (see [Bosch motion detectors](#bosch-motion-detectors-accelerometer-interrupts)) — the commands and decoders ship |
| A `prepareSignalSource` / `teardownSignalSource` helper (start a sensor as a processor source without a live stream) | One-liner source setup for processor chains and processor-handle logging | Send the sensor's `configureCommands` / `enableCommands` / `startCommands` through a `Command` wrapper (see [Data processor](#data-processor-module-0x09)) |
| Charging-status as a `Loggable` | Persisting charge transitions to flash | `device.poll(Settings.ReadChargeStatus(), every = …)` covers the live-observation case |
| Maven Central publication | `implementation("com.mbientlab:metawear-…")` from a plain Gradle build | Include the modules from source or use a composite build (see [Add the SDK to your build](#add-the-sdk-to-your-build)) |
| App-side unit tests for `NordicBleTransport` / `DfuSession` / `MetaBootProbe` | JVM coverage of the Android-only wrappers | Covered by the instrumented hardware suites and manual firmware-update passes |

---

## License

See [`LICENSE.md`](LICENSE.md). Copyright MbientLab Inc.
