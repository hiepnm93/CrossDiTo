# CrossDiTo Companion Protocol (version 1)

The Phone Companion link lets an Android phone push small pieces of
information (weather today; calendar, notifications, battery, custom text
later) to the X4 Pro's e-ink screen over Bluetooth Low Energy. The X4 Pro
never needs Internet access for this feature.

- Firmware implementation: `src/companion/` (`CompanionService`,
  `CompanionProtocol`, `CompanionState`), UI in
  `src/activities/network/CompanionActivity.cpp`
- Android implementation: `android-companion/`
  (`BleClient`, `CompanionProtocolEncoder`)

Both sides MUST follow this document.

## Roles

| Side | BLE role | GATT role |
| --- | --- | --- |
| X4 Pro (CrossDiTo) | Peripheral | Server |
| Android phone | Central | Client |

The X4 Pro only advertises while the **Phone Companion** screen is open
(Home → Phone Companion). Leaving the screen stops BLE entirely.

## GATT layout

Device name advertised: `CrossDiTo-X4`

| Item | UUID | Properties |
| --- | --- | --- |
| Companion Service | `c0de0001-5b1e-4c7d-8a9f-000000000001` | — |
| DATA characteristic | `c0de0001-5b1e-4c7d-8a9f-000000000002` | Write, Write Without Response |
| STATUS characteristic | `c0de0001-5b1e-4c7d-8a9f-000000000003` | Read, Notify |

UUIDs are custom 128-bit values and are stable across releases. Do not
create per-data-type characteristics or services; message types live inside
the payload framing.

### STATUS value (3 bytes)

| Offset | Field | Values |
| --- | --- | --- |
| 0 | protocol version | `1` |
| 1 | connection | `0` advertising/idle, `1` phone connected |
| 2 | last error | `ParseError` table below |

The X4 Pro notifies STATUS when the connection state changes and whenever it
rejects a frame. Clients should read STATUS once after connecting.

### Error codes (last error byte)

| Value | Meaning |
| --- | --- |
| 0 | no error |
| 1 | incomplete frame (internal; not reported as an error) |
| 2 | unsupported protocol version |
| 3 | unknown message type |
| 4 | declared payload length exceeds the maximum |
| 5 | payload failed validation (bad enum, bad length, invalid UTF-8) |
| 6 | accumulated bytes exceeded the maximum frame size |

## Transport framing

BLE writes are a byte stream: writes do NOT have to match frame boundaries
and a frame may be split across any number of writes. The receiver
reassembles frames; the sender may send a frame in chunks to stay below the
negotiated ATT MTU without checking it.

```
+---------+--------+-----------+------------------+
| version | type   | length    | payload          |
| uint8   | uint8  | uint16 BE | length bytes     |
+---------+--------+-----------+------------------+
```

- `version`: `1`
- `length`: payload byte count, **big-endian**
- Maximum payload: **480 bytes**; maximum frame: 484 bytes
- After a bad header the receiver skips exactly `length` payload bytes and
  resynchronizes on the next frame, so one malformed frame never breaks the
  stream.

## Message types

| Type | Name | Status |
| --- | --- | --- |
| `0x01` | `WEATHER` | implemented (v1) |
| `0x02` | `CALENDAR` | reserved |
| `0x03` | `NOTIFICATION` | reserved |
| `0x04` | `PHONE_BATTERY` | reserved |
| `0x05` | `CUSTOM_TEXT` | reserved |

Reserved types are skipped by the receiver and reported as error `3` on the
STATUS characteristic. Future protocol versions must keep type numbering
stable.

## WEATHER payload (type `0x01`)

All multi-byte fields are big-endian, two's complement for signed values.

```
offset  size  field
0       1     condition      uint8, see table below
1       2     temperature    int16, degrees Celsius x 10 (27.5 C -> 275)
3       2     feelsLike      int16, degrees Celsius x 10
5       2     tempMin        int16, degrees Celsius x 10
7       2     tempMax        int16, degrees Celsius x 10
9       1     humidity       uint8, 0..100 percent
10      8     timestamp      uint64, observation time as Unix epoch seconds
18      1     locationLen    uint8, 0..31
19      N     location       UTF-8 bytes, exactly locationLen bytes
19+N    0..1  wind           uint8, km/h at 10 m; senders SHOULD append it,
              receivers accept both lengths (0 means unknown)
```

Total payload length must be exactly `19 + locationLen` bytes (legacy, wind
unknown) or `20 + locationLen` bytes (with wind); the maximum payload is
51 bytes. Senders built after the wind addition always append the byte.

### Conditions

| Value | Name |
| --- | --- |
| 0 | `CLEAR` |
| 1 | `PARTLY_CLOUDY` |
| 2 | `CLOUDY` |
| 3 | `RAIN` |
| 4 | `HEAVY_RAIN` |
| 5 | `THUNDERSTORM` |
| 6 | `SNOW` |
| 7 | `FOG` |
| 8 | `UNKNOWN` |

### Validation rules (receiver side)

The receiver never trusts the payload:

- `condition` above `8` → error `5`, frame dropped
- declared length larger than 480 → error `4`
- payload length not equal to `19 + locationLen` or `20 + locationLen` → error `5`
- location longer than 31 bytes → truncated at the nearest UTF-8 character
  boundary, then accepted
- location that is not valid UTF-8 (truncated sequences, bad continuation
  bytes, embedded NUL) → error `5`
- humidity above 100 → clamped to 100

### Timestamp handling

`timestamp` carries the observation time from the phone. The X4 Pro displays
the device-local receipt time (from its RTC) on the "Updated" line and does
not interpret timezones; the field exists so future widgets can reason about
freshness.

## Example

Weather for Hanoi, 27.0 °C (feels 29.0), min 24.0, max 30.0, 76 % humidity,
12 km/h wind, condition `CLOUDY`, observation epoch `1789986000`:

```
frame:   01 01 00 19 02 01 0E 01 22 00 F0 01 2C 4C
         00 00 00 00 6A B1 04 D0 05 48 61 6E 6F 69 0C
```

Walkthrough: header `01 01 00 19` = version 1, type `0x01` (WEATHER),
payload length `0x0019` = 25. Payload starts `02` (`CLOUDY`), temperature
`01 0E` = 270 = 27.0 °C, humidity `4C` = 76, timestamp `6A B1 04 D0` =
`1789986000`, `locationLen` `05`, followed by `Hanoi`, then wind `0C` =
12 km/h. Round-trip vectors
live in `test/companion_protocol/CompanionProtocolTest.cpp` and
`android-companion/app/src/test/.../CompanionProtocolEncoderTest.kt`, and
both suites must keep passing against this document.

## Implementation notes

- The X4 Pro runs the GATT server only while the companion screen is open.
- Weather survives a temporary BLE disconnect for the rest of the screen
  session; leaving the screen discards it.
- The Android side must not require any location permission for manual
  weather entry; automatic location is optional and separate.
