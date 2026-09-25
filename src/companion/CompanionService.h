#pragma once

#include "CompanionProtocol.h"
#include "CompanionState.h"

// BLE lifecycle for the Phone Companion feature. The X4 Pro is a BLE
// peripheral / GATT server advertising as "CrossDiTo-X4"; the phone app is
// central / GATT client. The service only exists while the companion screen
// is open: start() from onEnter(), stop() from onExit().
//
// Wire protocol and UUIDs: docs/companion-protocol.md
namespace companion {

constexpr const char* COMPANION_DEVICE_NAME = "CrossDiTo-X4";

// Test-build version, shown on the companion screen so the running firmware
// can be matched to the released tag (companion-test-<version>). Bump this
// with every companion-test release.
constexpr const char* COMPANION_VERSION = "0.9";

class CompanionService {
 public:
  // Bring up the NimBLE stack and start advertising. Returns false when the
  // stack could not start (logged); state reports the failure via lastError.
  static bool start();

  // Disconnect, stop advertising and tear the stack down so the radio is off
  // while reading.
  static void stop();

  // The local BLE MAC as "AA:BB:CC:DD:EE:FF" (empty before start()). Shown on
  // the companion screen so the phone can match the device by address.
  static const char* address();

  static bool isRunning() { return running_; }

 private:
  static bool running_;
};

}  // namespace companion
