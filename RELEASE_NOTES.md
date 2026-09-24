# CrossDiTo Phone Companion — test build 0.7

- **Fixed: the app could not find the reader while another Bluetooth
  central was connected.** The X4 stops advertising while connected, so
  nRF Connect left running in the background (or a PC connection) hides
  it from the app's scan. Close nRF Connect from recents — switching
  apps is not enough — and confirm the reader shows "Waiting for phone…".
- **Fixed: scanning is now low-latency.** Some phone stacks deliver
  unfiltered scan results slowly or unreliably in balanced mode; the app
  now scans the same way nRF Connect does. ("Not bonded" in nRF is
  normal — the companion protocol does not use pairing.)
- **Fixed: on Android 15 the app title was drawn under the status bar**,
  with signal/battery icons on top of it.
- Firmware is unchanged since 0.6 (identical binary, re-shipped so the
  release is self-contained).
- Update the app to 0.7; the reader keeps the 0.6 firmware, so its
  screen still shows v0.6 — expected.

Expected on the reader screen:

```
Waiting for phone…
CrossDiTo-X4 v0.6
AA:BB:CC:DD:EE:FF
```
