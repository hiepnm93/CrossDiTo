# CrossDiTo Phone Companion — test build 0.9

- **New: weather screen redesign.** The reader screen is retitled
  "Thời tiết hiện tại" (Current weather) and shows: condition icon beside a
  large temperature, **RealFeel** in smaller text underneath, then a sunshine
  summary (Nhiều nắng / Ít nắng / Không có nắng), followed by location,
  humidity, wind, high/low, and the update time. Full Vietnamese strings for
  every companion screen (they were falling back to English before).
- **New: wind speed.** Open-Meteo fetch includes wind and it rides the WEATHER
  payload as an optional trailing byte. Readers keep accepting payloads from
  0.1–0.8 senders, and this firmware still accepts old apps, so you can
  upgrade either side first. See `docs/companion-protocol.md`.
- **New: app tabs + sync log.** The app splits into Reader (connection,
  pairing, sync log) and Weather tabs; the sync log records every auto-send
  result. Auto-send now holds a wakelock so the once-a-minute push survives
  Doze.
- Firmware and app are both at version 0.9 this time; the reader status line
  now matches the app.

Assets: `apk/CrossDiTo-Companion-debug.apk` (install over the old one),
`firmware/CrossDiTo-x4-pro.bin` (flash / OTA).

Source: `feat/phone-companion` branch. Test procedure: see release 0.1.
