#include "CompanionService.h"

#include <Logging.h>

#ifdef SIMULATOR

// The desktop simulator has no BLE radio. The companion screen renders an
// "unavailable" notice in this configuration.
namespace companion {
bool CompanionService::running_ = false;
bool CompanionService::start() { return false; }
void CompanionService::stop() {}
const char* CompanionService::address() { return ""; }
}  // namespace companion

#else

#include <NimBLEDevice.h>

#include <cstdio>

namespace companion {

namespace {

constexpr const char* LOG_TAG = "COMPANION";

// Stable custom UUIDs (docs/companion-protocol.md).
constexpr const char* SERVICE_UUID = "c0de0001-5b1e-4c7d-8a9f-000000000001";
constexpr const char* DATA_CHAR_UUID = "c0de0001-5b1e-4c7d-8a9f-000000000002";
constexpr const char* STATUS_CHAR_UUID = "c0de0001-5b1e-4c7d-8a9f-000000000003";

NimBLECharacteristic* dataChar = nullptr;
NimBLECharacteristic* statusChar = nullptr;
FrameParser parser;

void publishStatus() {
  if (statusChar == nullptr) return;
  StatusValue status;
  status.connected = CompanionState::getInstance().isConnected() ? 1 : 0;
  status.lastError = static_cast<uint8_t>(CompanionState::getInstance().getLastError());
  uint8_t encoded[3];
  encodeStatus(status, encoded, sizeof(encoded));
  statusChar->setValue(encoded, sizeof(encoded));
  statusChar->notify();
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
    LOG_INF(LOG_TAG, "phone connected, conn %u", connInfo.getConnHandle());
    CompanionState::getInstance().setConnected(true);
    parser.reset();
    publishStatus();
  }

  void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
    LOG_INF(LOG_TAG, "phone disconnected (reason %d)", reason);
    CompanionState::getInstance().setConnected(false);
    parser.reset();
    publishStatus();
    // Re-advertise so the phone can reconnect without re-entering the screen.
    pServer->startAdvertising();
  }
};

class DataCharCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
    // Runs in the NimBLE host task: parse and publish into shared state only,
    // never touch the renderer from here.
    const std::string value = pCharacteristic->getValue();
    if (value.empty()) return;

    CompanionState& state = CompanionState::getInstance();
    const ParseError result = parser.feed(reinterpret_cast<const uint8_t*>(value.data()), value.size());
    state.setLastError(result == ParseError::INCOMPLETE ? ParseError::NONE : result);
    if (result != ParseError::NONE || !parser.hasFrame()) {
      if (result != ParseError::NONE && result != ParseError::INCOMPLETE) {
        LOG_ERR(LOG_TAG, "frame rejected: %d", static_cast<int>(result));
        publishStatus();
      }
      return;
    }

    WeatherData weather;
    if (parser.frameType() == MessageType::WEATHER) {
      const ParseError decodeResult = decodeWeather(parser.framePayload(), parser.framePayloadLen(), weather);
      if (decodeResult == ParseError::NONE) {
        state.setWeather(weather);
        LOG_INF(LOG_TAG, "weather received: %.*s", static_cast<int>(weather.locationLen), weather.location);
      } else {
        state.setLastError(decodeResult);
        LOG_ERR(LOG_TAG, "weather payload rejected: %d", static_cast<int>(decodeResult));
      }
    } else {
      // Reserved message types are acknowledged with an error code so the
      // phone can tell them apart from silent drops.
      state.setLastError(ParseError::UNKNOWN_TYPE);
      LOG_DBG(LOG_TAG, "unimplemented type 0x%02X (%zu bytes)", static_cast<uint8_t>(parser.frameType()),
              parser.framePayloadLen());
    }
    publishStatus();
  }
};

ServerCallbacks serverCallbacks;
DataCharCallbacks dataCharCallbacks;

}  // namespace

bool CompanionService::running_ = false;

namespace {
// Backing store for address(); filled once per start().
char g_address[18] = {};
}  // namespace

const char* CompanionService::address() { return g_address; }

bool CompanionService::start() {
  if (running_) return true;

  if (!NimBLEDevice::init(COMPANION_DEVICE_NAME)) {
    LOG_ERR(LOG_TAG, "NimBLE init failed");
    return false;
  }
  snprintf(g_address, sizeof(g_address), "%s", NimBLEDevice::getAddress().toString().c_str());
  LOG_INF(LOG_TAG, "BLE address %s", g_address);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(&serverCallbacks, false);

  NimBLEService* service = server->createService(SERVICE_UUID);

  dataChar = service->createCharacteristic(DATA_CHAR_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  dataChar->setCallbacks(&dataCharCallbacks);

  statusChar =
      service->createCharacteristic(STATUS_CHAR_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  publishStatus();

  if (!service->start()) {
    LOG_ERR(LOG_TAG, "GATT service start failed");
    NimBLEDevice::deinit();
    return false;
  }

  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  // Order matters: scan response must be enabled BEFORE setName(). The
  // 128-bit UUID (18 bytes) plus flags fill most of the 31-byte ADV payload,
  // and setName() only routes into the scan response when it is already
  // enabled — otherwise it writes into the full ADV payload, fails, and the
  // name is silently dropped.
  advertising->enableScanResponse(true);
  advertising->setName(COMPANION_DEVICE_NAME);
  if (!advertising->start()) {
    LOG_ERR(LOG_TAG, "advertising failed to start");
    NimBLEDevice::deinit();
    return false;
  }

  running_ = true;
  LOG_INF(LOG_TAG, "advertising as %s", COMPANION_DEVICE_NAME);
  return true;
}

void CompanionService::stop() {
  if (!running_) return;
  running_ = false;
  CompanionState::getInstance().setConnected(false);
  dataChar = nullptr;
  statusChar = nullptr;
  parser.reset();
  NimBLEDevice::deinit(true);
  LOG_INF(LOG_TAG, "BLE stopped");
}

}  // namespace companion

#endif  // SIMULATOR
