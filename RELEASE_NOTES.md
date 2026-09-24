# CrossDiTo Phone Companion — test build 0.4

- Reader screen now shows its **BLE MAC address** under `CrossDiTo-X4`
  while waiting, so you can match the exact reader on the phone
  (apps that cannot read the advertised name show "N/A" — match by
  this address instead).
- Android app echoes the found address when connecting
  ("Found CrossDiTo-X4 (AA:BB:…)").

Match rule on the phone: same name OR same address = your reader.
Still need BOTH sides on 0.4 for a clean test.
