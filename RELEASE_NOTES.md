# CrossDiTo Phone Companion — test build 0.1

Experimental BLE Phone Companion for the Xteink X4 Pro. BLE is only active
while the Phone Companion screen is open; normal reading is untouched.

## Contents

- `firmware/CrossDiTo-x4-pro.bin` — X4 Pro firmware with the companion feature
- `apk/CrossDiTo-Companion-debug.apk` — Android companion app (debug, arm64+arm32)

## Flash the firmware

Option A — SD card: copy `firmware/CrossDiTo-x4-pro.bin` to the SD card root,
then on the reader choose **Settings > System > SD Card Firmware Update**.

Option B — USB: with the reader connected, run

```sh
pio run -e x4-pro --target upload
```

or use esptool with the repo's partition table (app at 0x10000).

## Install the app

Copy `apk/CrossDiTo-Companion-debug.apk` to the phone and install it
(allow "install unknown apps" for the file manager you use). Android 8+.

## Manual test procedure

1. On the X4 Pro: **Home → Phone Companion**. The screen shows
   "Waiting for phone… / CrossDiTo-X4".
2. On the phone: open **CrossDiTo Companion**, grant the Bluetooth
   permission prompts, make sure Bluetooth is on, tap **Connect**.
3. Wait for **Connected to X4** (the reader shows "Phone connected").
4. The form is pre-filled with test values (Hanoi, 27°, cloudy…).
   Tap **Send to X4**.
5. The reader screen now shows the weather: icon, temperature, location,
   condition, humidity, high/low and the receipt time.
6. Optional: edit **Location** and tap **Fetch weather** to pull real data
   from Open-Meteo, then **Send to X4** again.
7. Press **Back** on the reader to leave Phone Companion — BLE turns off.
8. Exit and re-open the screen: the weather snapshot is discarded with the
   session (by design).

## Known limitations

- Weather is the only implemented data type; calendar, notifications,
  battery and custom text are reserved protocol types.
- Weather lives only while the companion screen stays open.
- The screen keeps the device awake and advertising until you leave it.
- Not tested: iOS, GATT pairing/bonding (open connection), devices renamed
  in the firmware.

Protocol details: `docs/companion-protocol.md` on the `feat/phone-companion`
branch (source for this build).
