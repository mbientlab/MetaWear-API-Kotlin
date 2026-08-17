# Releasing to Google Play

The store artifact is an Android App Bundle (.aab) signed with the MbientLab
**upload key**. Google re-signs with the app-signing key it holds (Play App
Signing), so this repo only ever needs the upload key — never the final signing
key. No key or password is committed.

## One-time: point the build at the upload key

Create `app/keystore.properties` (gitignored — never commit it):

    storeFile=/absolute/path/to/mbientlab_android_apps.jks
    storePassword=YOUR_STORE_PASSWORD
    keyAlias=YOUR_KEY_ALIAS
    keyPassword=YOUR_KEY_PASSWORD

`mbientlab_android_apps.jks` lives in the MbientLab-files folder, outside this
repo. If unsure of the alias:

    keytool -list -keystore /path/to/mbientlab_android_apps.jks

The password is required to list it. This must be the SAME upload key the current
Play listing uses; if Play App Signing was enrolled with `private_key.pepk`, the
upload key is the one to sign with here.

## Build the signed bundle

    JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
      ./gradlew :app:bundleRelease

Output: `app/build/outputs/bundle/release/app-release.aab`

Confirm it's signed with the expected key:

    jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab | head

## Bump the version each release

In `app/build.gradle.kts`, raise `versionCode` (integer, must exceed the last
uploaded value — currently 100) and set a human `versionName`.

## Upload

Play Console → MetaWear app → Production (or Internal testing first) → Create
release → upload the .aab. Assets for the listing are in `store-assets/`:
`listing.md` (all text + Data safety answers), `screenshots/` (phone,
1080×2400), `icon-512.png` (512×512 hi-res icon). A feature graphic
(1024×500) still needs to be produced.

## Pre-submit checklist

- [ ] `versionCode` bumped above the live release
- [ ] Signed with the upload key (`jarsigner -verify` shows the MbientLab cert)
- [ ] Data safety form filled from `listing.md` (declares: no data collected/shared)
- [ ] Privacy policy URL resolves
- [ ] Screenshots + 512² icon + feature graphic uploaded
- [ ] Tested the exact .aab via Play Internal testing on a real device + board
