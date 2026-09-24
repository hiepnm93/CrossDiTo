#pragma once

#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

#include <atomic>
#include <cstdint>

#include "CompanionProtocol.h"

// Shared state between the BLE service (NimBLE host task) and the companion
// activity (main loop task). Fixed-size storage only; the weather snapshot is
// copied under the mutex so neither side ever touches the other's buffers.
namespace companion {

class CompanionState {
 public:
  static CompanionState& getInstance() {
    static CompanionState instance;
    return instance;
  }

  // Monotonic change counter; the activity polls it in loop() and re-renders
  // only when it moved, so BLE traffic never causes extra e-ink refreshes.
  uint32_t revision() const { return revision_.load(); }

  void setConnected(bool connected) {
    if (mutex_ == nullptr) return;
    if (xSemaphoreTake(mutex_, portMAX_DELAY) == pdTRUE) {
      if (connected_ != connected) {
        connected_ = connected;
        revision_.fetch_add(1);
      }
      xSemaphoreGive(mutex_);
    }
  }

  bool isConnected() const {
    if (mutex_ == nullptr) return false;
    bool connected = false;
    if (xSemaphoreTake(mutex_, 0) == pdTRUE) {
      connected = connected_;
      xSemaphoreGive(mutex_);
    }
    return connected;
  }

  void setWeather(const WeatherData& weather) {
    if (mutex_ == nullptr) return;
    if (xSemaphoreTake(mutex_, portMAX_DELAY) == pdTRUE) {
      weather_ = weather;
      hasWeather_ = true;
      revision_.fetch_add(1);
      xSemaphoreGive(mutex_);
    }
  }

  // Copies the latest weather into out. Returns false when nothing was
  // received yet in this session.
  bool getWeather(WeatherData& out) const {
    if (mutex_ == nullptr) return false;
    bool ok = false;
    if (xSemaphoreTake(mutex_, 0) == pdTRUE) {
      if (hasWeather_) {
        out = weather_;
        ok = true;
      }
      xSemaphoreGive(mutex_);
    }
    return ok;
  }

  void setLastError(ParseError error) {
    if (mutex_ == nullptr) return;
    if (xSemaphoreTake(mutex_, portMAX_DELAY) == pdTRUE) {
      if (lastError_ != error) {
        lastError_ = error;
        revision_.fetch_add(1);
      }
      xSemaphoreGive(mutex_);
    }
  }

  ParseError getLastError() const {
    if (mutex_ == nullptr) return ParseError::NONE;
    ParseError error = ParseError::NONE;
    if (xSemaphoreTake(mutex_, 0) == pdTRUE) {
      error = lastError_;
      xSemaphoreGive(mutex_);
    }
    return error;
  }

 private:
  CompanionState() { mutex_ = xSemaphoreCreateMutex(); }
  // Static singleton; never destroyed.
  ~CompanionState() = default;

  SemaphoreHandle_t mutex_ = nullptr;
  std::atomic<uint32_t> revision_{0};
  bool connected_ = false;
  bool hasWeather_ = false;
  ParseError lastError_ = ParseError::NONE;
  WeatherData weather_{};
};

}  // namespace companion
