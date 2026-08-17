# Hardware verification guide

Everything in this repo is verified by 1,275 JVM tests that run without
hardware. Three things remain that can only be proven against a real board
and phone. This guide is the exact procedure.

## What you need

- **Android phone**, Android 12+ (API 31+), with **Bluetooth on**.
- **MetaMotion S** (or R/RL), charged, within ~1 m of the phone.
- USB cable to this Mac.

## One-time phone setup

1. Enable Developer options: **Settings → About phone → tap "Build number" 7×**.
2. Enable **Settings → Developer options → USB debugging**.
3. Plug the phone into the Mac. Accept the "Allow USB debugging?" prompt on the phone.
4. Verify the connection:

```bash
adb devices
# expect one line like: RF8N... device   (not "unauthorized")
# (adb lives in <Android SDK>/platform-tools — add it to PATH, or use the
#  Terminal inside Android Studio, which already has it)
```

All gradle commands below are run from the repo root with:

```bash
# JAVA_HOME must point at a JDK 21 — Android Studio's bundled JBR works:
#   macOS: export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd <path-to>/MetaWear-API-Kotlin
```

## 1. BLE smoke + hardware suites (43 tests, needs the board)

```bash
./gradlew :metawear-core:connectedAndroidTest
```

- Exercises scan → connect → device info/module discovery → battery → LED →
  live accelerometer/gyro/mag/fusion streams → switch/haptic/GPIO → a full
  log→download round trip → disconnect, against the real board.
- Bluetooth permissions are granted automatically by the test runner
  (`GrantPermissionRule`) — no taps needed.
- **Self-skips cleanly** (reported as "skipped", not failed) if no device
  advertising a `MetaWear` name is found within 10 s. If everything skips:
  confirm the board is charged/advertising and not currently connected to
  another app or phone (a connected board stops advertising).
- HTML report:
  `metawear-core/build/reports/androidTests/connected/` (open `index.html`).

## 2. Persistence instrumented tests (13 tests, phone only — no board)

```bash
./gradlew :metawear-persistence:connectedAndroidTest
```

Runs the real-database tests (SQL sorting/counting, cascade deletes,
converters, export) that the JVM suite covers with fakes. Report in
`metawear-persistence/build/reports/androidTests/connected/`.

## 3. The app on the phone

```bash
./gradlew :app:installDebug
```

Then on the phone:

1. Open **MetaWear**. Grant the Bluetooth permission prompts on first scan.
2. **Scan** — the board should appear with name + RSSI. Tap to connect.
3. Suggested manual pass, mirroring what the automated suites cover:
   - Device screen shows model, firmware, battery.
   - **Live stream**: pick Accelerometer 100 Hz — chart moves when you shake
     the board; magnitude sits near 1 g at rest. Try a fusion output — the
     3D cube should track the board's orientation.
   - **Controls**: LED green + play (board LED lights), haptic buzz
     (board vibrates).
   - **Logging**: start a log, wait ~30 s, stop, download — progress bar
     completes, session appears in history, CSV export shares a file.
   - **Settings**: rename the device; rescan to see the new name.
4. No board handy? The app itself needs hardware, but the JVM suite drives
   the same device stack against `DemoBleTransport` (a test-only emulator):
   `./gradlew :app:testDebugUnitTest`.

## Troubleshooting

- `adb devices` empty → different cable/port; re-accept the phone prompt.
- Everything skips in step 1 → board asleep, out of range, or connected
  elsewhere; power-cycle the board and rerun.
- Connect fails intermittently → normal Android BLE; the transport retries
  transient status-133 errors automatically, just rerun the suite.
- Firmware update testing: use the app's firmware screen only when battery
  > 50% and the board is close to the phone; the update takes ~1–2 min and
  the board reboots twice.
