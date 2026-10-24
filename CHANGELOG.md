# Changelog

## Unreleased (app stays at 1.0.0 while in development)

### New
- Camera screen restyled after Google Lens: corner-bracket scan box (can be turned off in Settings), round gallery button next to a grey-ringed search shutter, title in the top bar.
- The glow around every detected code is now a rainbow gradient. Risky links keep a ⚠ and an amber label.
- **Google Lens-style scanner**: every code in view gets a glowing gradient border and a short label chip (for example "Website · example.com"). Tap a code to see its details. A single code is opened automatically after a short hold.
- **Multiple codes at once**: all QR codes in the frame are detected live; barcodes, Data Matrix, Aztec and PDF417 are found too when you freeze the frame or import an image.
- **Freeze button** (shutter) that takes a picture of the current frame without saving anything to storage, then marks every code in it. Tap the ✕ or press Back to return to the live camera.
- **Import image** now shows the picture with a glowing box around every code, instead of opening only the first one.
- Risky links get an amber/red glow and a warning marker on their label.
- Result sheet header with a type icon and short title, centred and capped in width on tablets.
- Tablet layouts: history and the create list become multi-column grids, forms and settings keep a readable width.
- Settings: turn each feature on or off: scan multiple codes, auto scan, glowing boxes, code labels, freeze button, link safety warnings, pinch to zoom.
- Zoom and pan frozen or imported pictures with pinch and drag, or double-tap to zoom. The boxes follow the picture and labels stay readable.
- Smart result actions by content type: **Connect** to Wi-Fi (Android 11+), **Add contact**, **Add event**, **Call**, **Send email**, **Send SMS**, **Show map**.
- Link safety banner on scanned URLs (http, raw IP, punycode / look-alike characters, URL shorteners, `user@host` tricks). Suspicious links are no longer auto-opened.
- Search box in History and Favorites.
- Pinch-to-zoom on the camera preview (the zoom slider follows).
- Settings: beep on scan, vibrate on scan. The beep stays quiet in silent/vibrate mode.
- Create: pick a QR colour and error-correction level (L/M/Q/H).
- In-app browser: reload, open in external browser, copy and share link; host is always shown and unencrypted pages are labelled.
- Unit tests and a CI workflow that builds on every branch and pull request.

### Cleanup
- Removed all code comments, the unused Our apps screen, and unused icons, colours, strings and imports.

### Fixed
- Glowing boxes and labels appeared in the wrong place on the live camera (offset from the real code). The camera frame was never rotated upright before its coordinates were mapped to the screen. Frames are now rotated properly, which also lets vertical barcodes decode in portrait.
- Freeze sometimes reported "No code found" for a code the live view could see; it now falls back to the sharper raw camera frame.
- The focus ring no longer lingers on a frozen picture, and the hint says "Hold steady…" while a single code is about to open.
- Tap-to-focus: now meters focus and exposure at the tap point, no longer fights CameraX over the AF mode, and shows a focus ring (green on success, red on failure).
- Scanning a contact (vCard) or calendar event QR offered a web **Search** of the whole card; it now opens the Contacts / Calendar editor instead.
- In-app browser drew under the status bar on Android 15 (edge-to-edge is now handled).
- "Export PNG" from the result sheet failed on Android 8-9 because the storage permission was never requested.
- CSV export could run spreadsheet formulas (`=`, `+`, `-`, `@` cells). Cells are now neutralised and restored on re-import.
- Generated contact QR codes now use a proper structured name, and website URLs no longer get stray backslashes.
- Beep and vibration ignored the user's preferences.

### Security / privacy
- Removed the unused photo-library permissions (the picker needs none).
- Enabled WebView Safe Browsing, blocked mixed content and content-provider access in the in-app browser.

### Performance
- The inverted-colour decode pass now runs on every third camera frame instead of every frame.
