#include <gtest/gtest.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "CompanionProtocol.h"

using namespace companion;

namespace {

WeatherData sampleWeather() {
  WeatherData w;
  w.condition = WeatherCondition::CLOUDY;
  w.temperatureDeciC = 270;  // 27.0 C
  w.feelsLikeDeciC = 290;
  w.tempMinDeciC = 240;
  w.tempMaxDeciC = 300;
  w.humidity = 76;
  w.timestamp = 1789987200u;
  strcpy(w.location, "Hanoi");
  w.locationLen = 5;
  return w;
}

}  // namespace

TEST(CompanionProtocol, WeatherRoundTripMatchesOriginal) {
  const WeatherData original = sampleWeather();
  std::vector<uint8_t> payload(WEATHER_PAYLOAD_MAX_BYTES);
  const size_t payloadLen = encodeWeather(original, payload.data(), payload.size());
  ASSERT_GT(payloadLen, 0u);

  WeatherData decoded;
  ASSERT_EQ(decodeWeather(payload.data(), payloadLen, decoded), ParseError::NONE);
  EXPECT_EQ(decoded.condition, original.condition);
  EXPECT_EQ(decoded.temperatureDeciC, original.temperatureDeciC);
  EXPECT_EQ(decoded.feelsLikeDeciC, original.feelsLikeDeciC);
  EXPECT_EQ(decoded.tempMinDeciC, original.tempMinDeciC);
  EXPECT_EQ(decoded.tempMaxDeciC, original.tempMaxDeciC);
  EXPECT_EQ(decoded.humidity, original.humidity);
  EXPECT_EQ(decoded.timestamp, original.timestamp);
  EXPECT_EQ(decoded.locationLen, original.locationLen);
  EXPECT_STREQ(decoded.location, "Hanoi");
}

TEST(CompanionProtocol, WeatherFrameSurvivesByteSplitFeeds) {
  const WeatherData original = sampleWeather();
  std::vector<uint8_t> payload(WEATHER_PAYLOAD_MAX_BYTES);
  const size_t payloadLen = encodeWeather(original, payload.data(), payload.size());
  std::vector<uint8_t> frame(MAX_FRAME_BYTES);
  const size_t total = encodeFrame(MessageType::WEATHER, payload.data(), payloadLen, frame.data(), frame.size());
  ASSERT_GT(total, 0u);

  FrameParser parser;
  // Feed one byte at a time: no assumption about BLE write boundaries.
  ParseError lastError = ParseError::NONE;
  for (size_t i = 0; i < total; ++i) {
    lastError = parser.feed(frame.data() + i, 1);
  }
  ASSERT_EQ(lastError, ParseError::NONE);
  ASSERT_TRUE(parser.hasFrame());
  EXPECT_EQ(parser.frameType(), MessageType::WEATHER);
  WeatherData decoded;
  ASSERT_EQ(decodeWeather(parser.framePayload(), parser.framePayloadLen(), decoded), ParseError::NONE);
  EXPECT_STREQ(decoded.location, "Hanoi");
  EXPECT_EQ(decoded.temperatureDeciC, 270);
}

TEST(CompanionProtocol, WrongVersionIsRejected) {
  uint8_t frame[16] = {};
  frame[0] = PROTOCOL_VERSION + 1;
  frame[1] = static_cast<uint8_t>(MessageType::WEATHER);
  frame[2] = 0;
  frame[3] = 4;

  FrameParser parser;
  EXPECT_EQ(parser.feed(frame, sizeof(frame)), ParseError::BAD_VERSION);
  EXPECT_FALSE(parser.hasFrame());
}

TEST(CompanionProtocol, UnknownTypeIsRejectedAndStreamResyncs) {
  uint8_t bad[6] = {};
  bad[0] = PROTOCOL_VERSION;
  bad[1] = 0x7F;  // not a defined type
  bad[2] = 0;
  bad[3] = 2;  // 2 payload bytes follow

  WeatherData original = sampleWeather();
  std::vector<uint8_t> payload(WEATHER_PAYLOAD_MAX_BYTES);
  const size_t payloadLen = encodeWeather(original, payload.data(), payload.size());
  std::vector<uint8_t> good(MAX_FRAME_BYTES);
  const size_t goodLen = encodeFrame(MessageType::WEATHER, payload.data(), payloadLen, good.data(), good.size());
  ASSERT_GT(goodLen, 0u);

  std::vector<uint8_t> stream(bad, bad + sizeof(bad));
  stream.insert(stream.end(), good.begin(), good.begin() + goodLen);

  FrameParser parser;
  EXPECT_EQ(parser.feed(stream.data(), stream.size()), ParseError::UNKNOWN_TYPE);
  // The well-formed frame behind the bad one must still parse.
  ASSERT_TRUE(parser.hasFrame());
  EXPECT_EQ(parser.frameType(), MessageType::WEATHER);
}

TEST(CompanionProtocol, InvalidLengthIsRejected) {
  uint8_t frame[8] = {};
  frame[0] = PROTOCOL_VERSION;
  frame[1] = static_cast<uint8_t>(MessageType::WEATHER);
  frame[2] = 0xFF;  // 65535 > MAX_PAYLOAD_BYTES
  frame[3] = 0xFF;

  FrameParser parser;
  EXPECT_EQ(parser.feed(frame, sizeof(frame)), ParseError::BAD_LENGTH);
  EXPECT_FALSE(parser.hasFrame());
}

TEST(CompanionProtocol, TruncatedPacketStaysIncomplete) {
  const WeatherData original = sampleWeather();
  std::vector<uint8_t> payload(WEATHER_PAYLOAD_MAX_BYTES);
  const size_t payloadLen = encodeWeather(original, payload.data(), payload.size());
  std::vector<uint8_t> frame(MAX_FRAME_BYTES);
  const size_t frameLen = encodeFrame(MessageType::WEATHER, payload.data(), payloadLen, frame.data(), frame.size());
  ASSERT_GT(frameLen, 0u);

  FrameParser parser;
  EXPECT_EQ(parser.feed(frame.data(), frameLen - 1), ParseError::INCOMPLETE);
  EXPECT_FALSE(parser.hasFrame());
  // Completing the frame afterwards succeeds.
  EXPECT_EQ(parser.feed(frame.data() + frameLen - 1, 1), ParseError::NONE);
  EXPECT_TRUE(parser.hasFrame());
}

TEST(CompanionProtocol, PayloadLengthMismatchIsBadPayload) {
  const WeatherData original = sampleWeather();
  std::vector<uint8_t> payload(WEATHER_PAYLOAD_MAX_BYTES);
  const size_t payloadLen = encodeWeather(original, payload.data(), payload.size());
  // Declare a longer payload than the location field accounts for.
  std::vector<uint8_t> frame(MAX_FRAME_BYTES);
  const size_t frameLen = encodeFrame(MessageType::WEATHER, payload.data(), payloadLen + 1, frame.data(),
                                      frame.size());
  ASSERT_GT(frameLen, 0u);

  WeatherData decoded;
  EXPECT_EQ(decodeWeather(frame.data() + FRAME_HEADER_BYTES, frameLen - FRAME_HEADER_BYTES, decoded),
            ParseError::BAD_PAYLOAD);
}

TEST(CompanionProtocol, InvalidConditionEnumIsRejected) {
  uint8_t payload[WEATHER_PAYLOAD_MIN_BYTES + 5] = {};
  payload[0] = WEATHER_CONDITION_COUNT;  // one past the last valid enum
  payload[18] = 5;
  memcpy(payload + WEATHER_PAYLOAD_MIN_BYTES, "Hanoi", 5);

  WeatherData decoded;
  EXPECT_EQ(decodeWeather(payload, sizeof(payload), decoded), ParseError::BAD_PAYLOAD);
}

TEST(CompanionProtocol, Utf8LocationRoundTrips) {
  WeatherData original = sampleWeather();
  const char* utf8 = "Hà Nội — 河内";  // 2-byte and 3-byte sequences, punctuation
  strcpy(original.location, utf8);
  original.locationLen = static_cast<uint8_t>(strlen(utf8));

  std::vector<uint8_t> payload(WEATHER_PAYLOAD_MAX_BYTES);
  const size_t payloadLen = encodeWeather(original, payload.data(), payload.size());
  ASSERT_GT(payloadLen, 0u);

  WeatherData decoded;
  ASSERT_EQ(decodeWeather(payload.data(), payloadLen, decoded), ParseError::NONE);
  EXPECT_EQ(decoded.locationLen, original.locationLen);
  EXPECT_STREQ(decoded.location, utf8);
}

TEST(CompanionProtocol, InvalidUtf8LocationIsRejected) {
  uint8_t payload[WEATHER_PAYLOAD_MIN_BYTES + 4] = {};
  payload[0] = static_cast<uint8_t>(WeatherCondition::CLEAR);
  payload[18] = 4;
  payload[19] = 0xC3;  // truncated 2-byte sequence
  payload[20] = 'x';
  payload[21] = 0x00;  // embedded NUL
  payload[22] = 'y';

  WeatherData decoded;
  EXPECT_EQ(decodeWeather(payload, sizeof(payload), decoded), ParseError::BAD_PAYLOAD);
}

TEST(CompanionProtocol, OversizeLocationIsTruncatedOnCharacterBoundary) {
  WeatherData original = sampleWeather();
  memset(original.location, 'a', WEATHER_LOCATION_MAX - 2);
  original.location[WEATHER_LOCATION_MAX - 2] = static_cast<char>(0xC3);
  original.location[WEATHER_LOCATION_MAX - 1] = static_cast<char>(0xA9);  // é split by the limit
  original.location[WEATHER_LOCATION_MAX] = static_cast<char>(0xA9);
  original.locationLen = WEATHER_LOCATION_MAX + 1;

  std::vector<uint8_t> payload(WEATHER_PAYLOAD_MAX_BYTES + 4);
  const size_t payloadLen = encodeWeather(original, payload.data(), payload.size());
  ASSERT_GT(payloadLen, 0u);

  WeatherData decoded;
  ASSERT_EQ(decodeWeather(payload.data(), payloadLen, decoded), ParseError::NONE);
  EXPECT_LE(decoded.locationLen, WEATHER_LOCATION_MAX);
  EXPECT_STREQ(decoded.location + decoded.locationLen - 2, "aa");
}

TEST(CompanionProtocol, StatusEncodesThreeBytes) {
  StatusValue status;
  status.connected = 1;
  status.lastError = static_cast<uint8_t>(ParseError::BAD_VERSION);
  uint8_t out[3] = {};
  ASSERT_EQ(encodeStatus(status, out, sizeof(out)), 3u);
  EXPECT_EQ(out[0], PROTOCOL_VERSION);
  EXPECT_EQ(out[1], 1);
  EXPECT_EQ(out[2], static_cast<uint8_t>(ParseError::BAD_VERSION));
}

TEST(CompanionProtocol, OversizePayloadFrameIsRefused) {
  std::vector<uint8_t> payload(MAX_PAYLOAD_BYTES + 1, 0x41);
  std::vector<uint8_t> frame(MAX_FRAME_BYTES + 16);
  EXPECT_EQ(encodeFrame(MessageType::WEATHER, payload.data(), payload.size(), frame.data(), frame.size()), 0u);
}
