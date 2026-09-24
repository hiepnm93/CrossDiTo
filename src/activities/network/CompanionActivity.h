#pragma once

#include "activities/Activity.h"
#include "companion/CompanionProtocol.h"

// Phone Companion screen: advertises BLE while open, receives companion
// packets from the phone app and renders the latest weather snapshot.
// BLE is fully stopped when the screen closes.
class CompanionActivity final : public Activity {
 public:
  CompanionActivity(GfxRenderer& renderer, MappedInputManager& mappedInput)
      : Activity("Companion", renderer, mappedInput) {}
  ~CompanionActivity() override;

  void onEnter() override;
  void onExit() override;
  void loop() override;
  void render(RenderLock&&) override;
  bool preventAutoSleep() override { return true; }
  bool skipLoopDelay() override { return true; }

 private:
  void exitViaBack();

  uint32_t lastRenderedRevision_ = 0;
  bool bleStarted_ = false;
  // Receipt time of the currently displayed snapshot ("Updated HH:MM").
  uint8_t updatedHour_ = 0;
  uint8_t updatedMinute_ = 0;
  bool hasUpdatedTime_ = false;
  companion::WeatherCondition lastRenderedCondition_ = companion::WeatherCondition::UNKNOWN;
  int16_t lastRenderedTempDeciC_ = 0;
};
