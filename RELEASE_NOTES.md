# CrossDiTo Phone Companion — test build 0.6

- **Fixed: advertised name was still being dropped.** setName() must run
  AFTER enabling the scan response (the ADV payload is already full with
  flags + 128-bit UUID). nRF Connect shows the name now, and the app's
  name matching works.
- Android shows versions both ways: app version in the title bar,
  reader protocol version in the connected status.
- Update BOTH sides to 0.6. Remove any pairing you created with nRF
  Connect ("Forget device") before testing the companion app.

Expected on the reader screen:

```
Waiting for phone…
CrossDiTo-X4 v0.6
AA:BB:CC:DD:EE:FF
```
