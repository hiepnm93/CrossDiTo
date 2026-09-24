#include "CompanionActivity.h"

#include <GfxRenderer.h>
#include <I18n.h>

#include "MappedInputManager.h"
#include "components/TouchHeaderBackButton.h"
#include "components/UITheme.h"
#include "fontIds.h"

#ifdef SIMULATOR

CompanionActivity::~CompanionActivity() = default;

void CompanionActivity::onEnter() { Activity::onEnter(); }
void CompanionActivity::onExit() { Activity::onExit(); }

void CompanionActivity::loop() {
  if (TouchHeaderBackButton::wasTapped(mappedInput, renderer) ||
      mappedInput.wasPressed(MappedInputManager::Button::Back)) {
    exitViaBack();
  }
}

void CompanionActivity::render(RenderLock&&) {
  const auto& metrics = UITheme::getInstance().getMetrics();
  const auto pageWidth = renderer.getScreenWidth();
  const auto pageHeight = renderer.getScreenHeight();

  renderer.clearScreen();
  const Rect header{0, metrics.topPadding, pageWidth, TouchHeaderBackButton::height(metrics, mappedInput)};
  if (mappedInput.hasTouchHardware()) {
    TouchHeaderBackButton::draw(renderer, header, tr(STR_COMPANION_TITLE), false);
  } else {
    GUI.drawHeader(renderer, header, tr(STR_COMPANION_TITLE));
  }
  renderer.drawCenteredText(UI_10_FONT_ID, pageHeight / 2, tr(STR_COMPANION_UNAVAILABLE), true,
                            EpdFontFamily::BOLD);
  const auto labels = mappedInput.mapLabels(mappedInput.withBackArrow(tr(STR_BACK)), "", "", "");
  GUI.drawButtonHints(renderer, labels.btn1, labels.btn2, labels.btn3, labels.btn4);
  renderer.displayBuffer();
}

void CompanionActivity::exitViaBack() {
  mappedInput.suppressNextBackRelease();
  finish();
}

#else

#include <HalClock.h>

#include <cstdio>

#include "CrossPointSettings.h"
#include "companion/CompanionService.h"
#include "companion/CompanionState.h"
#include "components/themes/BaseTheme.h"

namespace {

Rect screenSafeArea(const GfxRenderer& renderer) {
  return UITheme::getInstance().getScreenSafeArea(renderer, false, false);
}

// --- Procedural weather glyphs (no bitmap assets needed) --------------------
// Each glyph fills a square box; stroke width scales with the box size.

// A filled circle via a fully-rounded rect (GfxRenderer keeps fillArc private).
void fillCircle(GfxRenderer& renderer, int cx, int cy, int r) {
  renderer.fillRoundedRect(cx - r, cy - r, r * 2, r * 2, r, Color::Black);
}

void drawSunGlyph(GfxRenderer& renderer, int cx, int cy, int r) {
  fillCircle(renderer, cx, cy, r);
  // Eight rays at 45-degree steps; diagonals are shortened by ~3/4.
  static const int8_t dirs[8][2] = {{1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}, {0, 1}, {1, 1}};
  const int rayStart = r + r / 3;
  const int rayEnd = r * 2;
  for (const auto& dir : dirs) {
    const bool diagonal = dir[0] != 0 && dir[1] != 0;
    const int scale = diagonal ? 3 : 4;  // ~0.75 vs 1.0
    const int sx = cx + dir[0] * rayStart * scale / 4;
    const int sy = cy + dir[1] * rayStart * scale / 4;
    const int ex = cx + dir[0] * rayEnd * scale / 4;
    const int ey = cy + dir[1] * rayEnd * scale / 4;
    renderer.drawLine(sx, sy, ex, ey, true);
  }
}

void drawCloudShape(GfxRenderer& renderer, int cx, int cy, int r) {
  // Two filled circles + a base rectangle approximate a cloud.
  fillCircle(renderer, cx - r, cy, r);
  fillCircle(renderer, cx + r, cy + r / 3, r);
  renderer.fillRect(cx - r * 2, cy, r * 4, r + r / 3, true);
}

void drawRainUnderCloud(GfxRenderer& renderer, int cx, int cy, int r, int drops) {
  const int top = cy + r + r / 3;
  const int bottom = top + r;
  const int spread = r * 3 / 2;
  for (int i = 0; i < drops; ++i) {
    const int x = cx - spread + (2 * spread) * i / (drops > 1 ? drops - 1 : 1);
    renderer.drawLine(x, top, x - r / 4, bottom, true);
  }
}

void drawWeatherGlyph(GfxRenderer& renderer, const Rect& box, companion::WeatherCondition condition) {
  const int r = box.width / 6;
  const int cx = box.x + box.width / 2;
  const int cy = box.y + box.height / 2;

  switch (condition) {
    case companion::WeatherCondition::CLEAR:
      drawSunGlyph(renderer, cx, cy, r);
      break;
    case companion::WeatherCondition::PARTLY_CLOUDY:
      drawSunGlyph(renderer, cx - r, cy - r / 2, r * 2 / 3);
      drawCloudShape(renderer, cx + r / 2, cy + r / 3, r);
      break;
    case companion::WeatherCondition::CLOUDY:
      drawCloudShape(renderer, cx, cy, r);
      break;
    case companion::WeatherCondition::RAIN:
      drawCloudShape(renderer, cx, cy - r / 2, r);
      drawRainUnderCloud(renderer, cx, cy - r / 2, r, 3);
      break;
    case companion::WeatherCondition::HEAVY_RAIN:
      drawCloudShape(renderer, cx, cy - r / 2, r);
      drawRainUnderCloud(renderer, cx, cy - r / 2, r, 5);
      break;
    case companion::WeatherCondition::THUNDERSTORM: {
      drawCloudShape(renderer, cx, cy - r / 2, r);
      const int top = cy + r / 2;
      renderer.drawLine(cx, top, cx - r / 2, top + r, true);
      renderer.drawLine(cx - r / 2, top + r, cx + r / 4, top + r, true);
      renderer.drawLine(cx + r / 4, top + r, cx - r / 4, top + 2 * r, true);
      break;
    }
    case companion::WeatherCondition::SNOW:
      drawCloudShape(renderer, cx, cy - r / 2, r);
      for (int i = 0; i < 3; ++i) {
        const int y = cy + r + i * r / 2;
        const int x = cx - r + i * r;
        renderer.drawLine(x - r / 4, y, x + r / 4, y, true);
        renderer.drawLine(x, y - r / 4, x, y + r / 4, true);
      }
      break;
    case companion::WeatherCondition::FOG:
      drawCloudShape(renderer, cx, cy - r, r * 2 / 3);
      for (int i = 0; i < 3; ++i) {
        const int y = cy + r / 2 + i * r / 2;
        renderer.drawLine(cx - r * 2, y, cx + r * 2, y, true);
      }
      break;
    case companion::WeatherCondition::UNKNOWN:
    default:
      renderer.drawCenteredText(UI_12_FONT_ID, cy, "?", true, EpdFontFamily::BOLD);
      break;
  }
}

const char* conditionString(companion::WeatherCondition condition) {
  switch (condition) {
    case companion::WeatherCondition::CLEAR:
      return tr(STR_WEATHER_CLEAR);
    case companion::WeatherCondition::PARTLY_CLOUDY:
      return tr(STR_WEATHER_PARTLY_CLOUDY);
    case companion::WeatherCondition::CLOUDY:
      return tr(STR_WEATHER_CLOUDY);
    case companion::WeatherCondition::RAIN:
      return tr(STR_WEATHER_RAIN);
    case companion::WeatherCondition::HEAVY_RAIN:
      return tr(STR_WEATHER_HEAVY_RAIN);
    case companion::WeatherCondition::THUNDERSTORM:
      return tr(STR_WEATHER_THUNDERSTORM);
    case companion::WeatherCondition::SNOW:
      return tr(STR_WEATHER_SNOW);
    case companion::WeatherCondition::FOG:
      return tr(STR_WEATHER_FOG);
    case companion::WeatherCondition::UNKNOWN:
    default:
      return tr(STR_WEATHER_UNKNOWN);
  }
}

const char* errorString(companion::ParseError error) {
  switch (error) {
    case companion::ParseError::BAD_VERSION:
      return tr(STR_COMPANION_ERR_VERSION);
    case companion::ParseError::UNKNOWN_TYPE:
      return tr(STR_COMPANION_ERR_TYPE);
    case companion::ParseError::BAD_LENGTH:
    case companion::ParseError::OVERSIZE:
      return tr(STR_COMPANION_ERR_LENGTH);
    case companion::ParseError::BAD_PAYLOAD:
      return tr(STR_COMPANION_ERR_PAYLOAD);
    case companion::ParseError::NONE:
    case companion::ParseError::INCOMPLETE:
    default:
      return "";
  }
}

void formatDeciC(char* buf, size_t bufSize, int16_t deciC) {
  const int16_t abs = deciC < 0 ? static_cast<int16_t>(-deciC) : deciC;
  snprintf(buf, bufSize, "%s%d.%d", deciC < 0 ? "-" : "", abs / 10, abs % 10);
}

}  // namespace

CompanionActivity::~CompanionActivity() = default;

void CompanionActivity::onEnter() {
  Activity::onEnter();
  bleStarted_ = companion::CompanionService::start();
  lastRenderedRevision_ = companion::CompanionState::getInstance().revision();
  hasUpdatedTime_ = false;
  requestUpdate();
}

void CompanionActivity::onExit() {
  companion::CompanionService::stop();
  bleStarted_ = false;
  Activity::onExit();
}

void CompanionActivity::exitViaBack() {
  mappedInput.suppressNextBackRelease();
  finish();
}

void CompanionActivity::loop() {
  if (TouchHeaderBackButton::wasTapped(mappedInput, renderer) ||
      mappedInput.wasPressed(MappedInputManager::Button::Back)) {
    exitViaBack();
    return;
  }

  const uint32_t revision = companion::CompanionState::getInstance().revision();
  if (revision != lastRenderedRevision_) {
    requestUpdate();
  }
}

void CompanionActivity::render(RenderLock&&) {
  lastRenderedRevision_ = companion::CompanionState::getInstance().revision();
  companion::WeatherData weather;
  const bool hasWeather = companion::CompanionState::getInstance().getWeather(weather);
  const bool connected = companion::CompanionState::getInstance().isConnected();
  const auto error = companion::CompanionState::getInstance().getLastError();

  // Fresh snapshot (condition or temperature moved)? Record receipt time for
  // the "Updated HH:MM" line so reconnects do not rewrite it silently.
  if (hasWeather &&
      (!hasUpdatedTime_ || weather.condition != lastRenderedCondition_ ||
       weather.temperatureDeciC != lastRenderedTempDeciC_)) {
    uint16_t year;
    uint8_t month, day;
    if (halClock.getDateTime(year, month, day, updatedHour_, updatedMinute_)) {
      hasUpdatedTime_ = true;
    }
    lastRenderedCondition_ = weather.condition;
    lastRenderedTempDeciC_ = weather.temperatureDeciC;
  }

  const auto& metrics = UITheme::getInstance().getMetrics();
  const Rect screen = screenSafeArea(renderer);
  renderer.clearScreen();

  const Rect header{0, metrics.topPadding, renderer.getScreenWidth(),
                    TouchHeaderBackButton::height(metrics, mappedInput)};
  if (mappedInput.hasTouchHardware()) {
    TouchHeaderBackButton::draw(renderer, header, tr(STR_COMPANION_TITLE), false);
  } else {
    GUI.drawHeader(renderer, header, tr(STR_COMPANION_TITLE));
  }

  int y = screen.y + header.height + metrics.verticalSpacing;

  if (!bleStarted_) {
    renderer.drawCenteredText(UI_12_FONT_ID, screen.y + screen.height / 2, tr(STR_COMPANION_UNAVAILABLE), true,
                              EpdFontFamily::BOLD);
  } else if (!hasWeather) {
    // Waiting state: show device name and connection status in the middle.
    if (error != companion::ParseError::NONE) {
      renderer.drawCenteredText(UI_12_FONT_ID, y + metrics.verticalSpacing, errorString(error), true,
                                EpdFontFamily::BOLD);
    }
    renderer.drawCenteredText(UI_12_FONT_ID, screen.y + screen.height / 3, tr(STR_COMPANION_ADVERTISING), true);
    char nameBuf[40];
    snprintf(nameBuf, sizeof(nameBuf), "%s v%s", companion::COMPANION_DEVICE_NAME, companion::COMPANION_VERSION);
    renderer.drawCenteredText(UI_10_FONT_ID, screen.y + screen.height / 3 + 30, nameBuf, true);
    // Shown so the phone side can match this exact reader by BLE address.
    renderer.drawCenteredText(SMALL_FONT_ID, screen.y + screen.height / 3 + 58, companion::CompanionService::address(),
                              true);
  } else {
    // --- Weather layout -------------------------------------------------
    const int contentTop = y;
    const int centerX = screen.x + screen.width / 2;

    // Icon left of the big temperature.
    Rect glyphBox{screen.x + screen.width / 8, contentTop + metrics.verticalSpacing, screen.width / 4,
                  screen.height / 4};
    drawWeatherGlyph(renderer, glyphBox, weather.condition);

    char tempBuf[12];
    formatDeciC(tempBuf, sizeof(tempBuf), weather.temperatureDeciC);
    renderer.drawCenteredText(BITTER_16_FONT_ID, glyphBox.y + glyphBox.height / 2, tempBuf, true,
                              EpdFontFamily::BOLD);

    renderer.drawCenteredText(UI_12_FONT_ID, glyphBox.y + glyphBox.height + metrics.verticalSpacing,
                              conditionString(weather.condition), true, EpdFontFamily::BOLD);

    char locationBuf[sizeof(weather.location) + 8];
    snprintf(locationBuf, sizeof(locationBuf), "%.*s", static_cast<int>(weather.locationLen), weather.location);
    renderer.drawCenteredText(UI_12_FONT_ID, glyphBox.y + glyphBox.height + metrics.verticalSpacing + 28,
                              locationBuf, true);

    // Humidity and high/low rows, label left / value right.
    int rowY = glyphBox.y + glyphBox.height + metrics.verticalSpacing * 3 + 24;
    const int labelX = screen.x + metrics.contentSidePadding;
    const int valueX = screen.x + screen.width - metrics.contentSidePadding;
    const int rowStep = 30;

    char valueBuf[32];

    snprintf(valueBuf, sizeof(valueBuf), "%u%%", weather.humidity);
    renderer.drawText(UI_12_FONT_ID, labelX, rowY, tr(STR_WEATHER_HUMIDITY), true);
    renderer.drawText(UI_12_FONT_ID, valueX - renderer.getTextWidth(UI_12_FONT_ID, valueBuf), rowY, valueBuf, true);

    rowY += rowStep;
    char minBuf[12];
    char maxBuf[12];
    formatDeciC(minBuf, sizeof(minBuf), weather.tempMinDeciC);
    formatDeciC(maxBuf, sizeof(maxBuf), weather.tempMaxDeciC);
    snprintf(valueBuf, sizeof(valueBuf), "%s / %s C", maxBuf, minBuf);
    renderer.drawText(UI_12_FONT_ID, labelX, rowY, tr(STR_WEATHER_HIGH_LOW), true);
    renderer.drawText(UI_12_FONT_ID, valueX - renderer.getTextWidth(UI_12_FONT_ID, valueBuf), rowY, valueBuf, true);

    rowY += rowStep;
    if (hasUpdatedTime_) {
      char timeBuf[10];
      if (halClock.formatTime(timeBuf, sizeof(timeBuf), SETTINGS.clockUtcOffsetQ, SETTINGS.clockFormat == 1)) {
        snprintf(valueBuf, sizeof(valueBuf), "%s %s", tr(STR_WEATHER_UPDATED), timeBuf);
        renderer.drawCenteredText(UI_10_FONT_ID, rowY, valueBuf, true);
      }
    }
    rowY += rowStep;
    char statusBuf[48];
    snprintf(statusBuf, sizeof(statusBuf), "%s v%s",
             connected ? tr(STR_COMPANION_CONNECTED) : tr(STR_COMPANION_DISCONNECTED),
             companion::COMPANION_VERSION);
    renderer.drawCenteredText(UI_10_FONT_ID, rowY + metrics.verticalSpacing, statusBuf, true);
  }

  const auto labels = mappedInput.mapLabels(mappedInput.withBackArrow(tr(STR_BACK)), "", "", "");
  GUI.drawButtonHints(renderer, labels.btn1, labels.btn2, labels.btn3, labels.btn4);
  renderer.displayBuffer();
}

#endif  // SIMULATOR
