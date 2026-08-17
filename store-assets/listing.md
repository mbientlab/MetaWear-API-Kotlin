# Google Play listing — MetaWear

Package: `com.mbientlab.metawear.app` · versionName 5.0.0 · versionCode 100

## App name (max 30 chars)
MetaWear

## Short description (max 80 chars)
Stream, log, and download data from MbientLab MetaWear motion sensors.

## Full description (max 4000 chars)
MetaWear connects your Android phone to MbientLab MetaWear and MetaMotion Bluetooth sensor boards to stream, record, and export motion and environmental data — no coding required.

Connect over Bluetooth Low Energy and:

• Live stream — accelerometer, gyroscope, magnetometer, and every sensor-fusion output (quaternion, Euler angles, gravity, linear acceleration, and calibrated fields) with real-time charts, a 3D orientation view, and a fusion calibration guide. Environmental sensors too: temperature, humidity, barometric pressure, and ambient light.

• Log to the board's flash — start recording, disconnect and go about your day, then reconnect and download the whole session with live progress. Sessions are saved on your phone.

• Session history — browse, re-plot, replay 3D orientation with a scrub timeline, and export any session to CSV to share or open in a spreadsheet.

• Controls — drive the on-board LED and haptic motor.

• Device settings — rename the board, tune advertising and transmit power, inspect and clear on-board loggers and flash memory, and update firmware over the air.

Supported boards: MetaMotion S and MetaMotion R / RL. Requires a MetaWear/MetaMotion sensor and a phone with Bluetooth. Android 8.0 and later; Android 12+ recommended.

MetaWear boards are made by MbientLab (mbientlab.com).

## Category
Tools  (alternative: Productivity)

## Contact
Email: hello@mbientlab.com
Website: https://mbientlab.com
Privacy Policy: https://mbientlab.com/privacy  (VERIFY this URL resolves before submitting)

## Content rating
Everyone. No user accounts, no ads, no in-app purchases.

## Data safety form answers
- Data collected: NONE. The app talks only to the sensor over Bluetooth and stores
  recorded sessions locally on the device.
- Data shared with third parties: NONE.
- Network use: HTTPS to MbientLab's server ONLY to check for and download board
  firmware updates. No analytics, no tracking, no personal data leaves the phone.
- CSV export is user-initiated through the Android share sheet (the user chooses
  the destination).

## Permissions justification (for the review notes)
- BLUETOOTH_SCAN / BLUETOOTH_CONNECT (and legacy BLUETOOTH + ACCESS_FINE_LOCATION
  on Android ≤ 11): discover and connect to the sensor board. neverForLocation is
  set on the scan permission — the app does not use or derive location.
- INTERNET: fetch the firmware catalog and firmware images for OTA updates.
- FOREGROUND_SERVICE + POST_NOTIFICATIONS: the Nordic DFU firmware-update service
  runs in the foreground with a progress notification.
