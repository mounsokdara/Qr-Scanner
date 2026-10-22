# QR & Barcode Scanner

Android Studio project for the scanner shown in the recording. It is a full app, not a UI mock: camera scan, gallery decode, history, favorites, QR and barcode creation, My QR, settings, export, and share.

## Build an APK

1. Install Android Studio with Android SDK 35.
2. Open this folder (the one that contains `settings.gradle.kts`).
3. Wait for Gradle sync to finish.
4. Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. The installable file is `app/build/outputs/apk/debug/app-debug.apk`. Copy it to a phone and allow install from that source, or press Run with a phone or emulator connected.

Minimum Android version is 8.0 (API 26). A camera is optional. **Scan image** still decodes a picture from the gallery.

## What is included

- CameraX live preview, torch, zoom, front/back switch, touch focus, batch scan
- ZXing decode of QR, EAN-8, EAN-13, UPC-A, UPC-E, Code 39/93/128, ITF, Codabar, Data Matrix, PDF417, Aztec
- Create the same formats, plus URL, text, contact (vCard), email, SMS, location, phone, calendar, and Wi-Fi
- History and favorites, rename, delete with undo, CSV/TXT/PNG export and CSV import
- My QR contact card, in-app browser, search engines, custom action URL
- Smart actions on scan: connect to Wi-Fi (Android 11+), add contact, add calendar event, call, email, SMS, show on map
- Link safety warnings for http links, raw IP addresses, look-alike (punycode/unicode) hosts, shorteners and `user@host` tricks. Risky links are never auto-opened
- History search, pinch-to-zoom, beep/vibrate toggles (silent mode respected)
- QR style options when creating codes: colour and error-correction level
- Permissions: camera, vibrate, internet, flashlight, and photos (read images)

Package id: `com.mounsokdara.qr_scanner` (namespace `com.localscanner.qr`). Version 1.0.0 (in development). See [CHANGELOG.md](CHANGELOG.md).

CI (`.github/workflows/ci.yml`) runs the unit tests and a debug build on every branch and pull request. Release builds: GitHub Actions (`.github/workflows/build-apk.yml`) builds the unsigned release, then `scripts/sign-apk.sh` zip-aligns and signs it v1+v2+v3+v4. The signing key is not in this repo. Actions secrets required: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. The keystore lives in the private vault.

