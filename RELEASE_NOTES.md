# CrossDiTo Phone Companion — test build 0.8

- **New: auto-send.** Tick "Auto-send weather every minute" and a
  foreground service keeps the reader connected, fetches Open-Meteo for
  the city in the Location field, and pushes the weather once a minute.
  The notification shows the last result. Turn it off any time — the
  reader stops receiving and advertises again for other phones.
- **Fixed: "reader connected but the app says not connected".** When the
  scan window finds nothing (the reader does not advertise while any
  device holds its link — including background connections your phone
  keeps for other apps), the app now connects directly to the last known
  BLE address instead of giving up. Connect once normally and the
  fallback keeps working from then on.
- **Fixed: "not a number" when sending.** Values like "27,5" from
  comma-decimal keyboards (Vietnamese, German, …) are now accepted.
- Firmware unchanged since 0.6 (identical binary, re-shipped so the
  release is self-contained); the reader screen still shows v0.6.
- Battery note: the e-ink screen refreshes only when values actually
  change, but the radio stays linked every minute by design.
