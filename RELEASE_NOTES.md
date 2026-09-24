# CrossDiTo Phone Companion — test build 0.3

Fixes over 0.2 (pair with firmware 0.3 — the two sides were built to
match):

- **Firmware: the reader now advertises its name.** NimBLE keeps scan
  response off by default and the 128-bit UUID filled the advertising
  packet, so phones saw an unnamed BLE device. "CrossDiTo-X4" is now
  properly visible.
- **App: scanning rewritten.** No more UUID ScanFilter; the app matches
  name/UUID itself and gives actionable errors (location off on
  Android 11 or older, Bluetooth off, scan failure reasons).

Still not found? Checklist:
1. Reader must sit on the Phone Companion screen ("Waiting for phone…").
2. Android 11 or older: location must be ON.
3. Update BOTH the firmware and the APK to 0.3.
4. Serial check: `pio device monitor -e x4-pro` must log
   "advertising as CrossDiTo-X4" after opening the screen.
