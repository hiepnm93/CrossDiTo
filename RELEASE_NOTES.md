# CrossDiTo Phone Companion — test build 0.5

**Verify what is on the reader:** the Phone Companion screen must show

```
Waiting for phone…
CrossDiTo-X4 v0.5
AA:BB:CC:DD:EE:FF   <- BLE MAC
```

If you do NOT see "v0.5" and the MAC line, the new bin has not actually
been applied to the reader (an earlier OTA did not take) — re-flash via
Settings > System > SD Card Firmware Update with this bin file.

Fixes over 0.4:
- Secondary lines (device name, BLE MAC, updated time, connection
  status) were drawn as WHITE ink — invisible on the white e-ink panel
  and unreliable under dark-mode polarity flip. Everything now draws as
  black ink and is visible in both light and dark modes.
- The companion version is displayed on-screen and bumps together with
  the release tag, so firmware and release can never drift apart.

The APK is unchanged from 0.3 but re-attached for convenience.
