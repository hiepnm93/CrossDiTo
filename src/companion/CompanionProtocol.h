#pragma once

#include <cstddef>
#include <cstdint>

// CrossDiTo Phone Companion protocol, version 1.
//
// Pure C++ (no Arduino/ESP headers) so the native test suite compiles it
// directly. Wire format is documented in docs/companion-protocol.md; the
// Android encoder must follow the same document.
namespace companion {

constexpr uint8_t PROTOCOL_VERSION = 1;

// Frame header: version u8 | type u8 | length u16 (big-endian) | payload.
constexpr size_t FRAME_HEADER_BYTES = 4;
// Largest payload accepted; also the reassembly buffer size. Weather needs
// about 50 bytes, later message types stay well under this.
constexpr size_t MAX_PAYLOAD_BYTES = 480;
constexpr size_t MAX_FRAME_BYTES = FRAME_HEADER_BYTES + MAX_PAYLOAD_BYTES;

enum class MessageType : uint8_t {
  WEATHER = 0x01,
  CALENDAR = 0x02,       // reserved, not implemented yet
  NOTIFICATION = 0x03,   // reserved, not implemented yet
  PHONE_BATTERY = 0x04,  // reserved, not implemented yet
  CUSTOM_TEXT = 0x05,    // reserved, not implemented yet
};

enum class WeatherCondition : uint8_t {
  CLEAR = 0,
  PARTLY_CLOUDY = 1,
  CLOUDY = 2,
  RAIN = 3,
  HEAVY_RAIN = 4,
  THUNDERSTORM = 5,
  SNOW = 6,
  FOG = 7,
  UNKNOWN = 8,
};
constexpr uint8_t WEATHER_CONDITION_COUNT = 9;

enum class ParseError : uint8_t {
  NONE = 0,
  INCOMPLETE = 1,        // need more bytes to finish the frame
  BAD_VERSION = 2,
  UNKNOWN_TYPE = 3,
  BAD_LENGTH = 4,        // declared length larger than the reassembly buffer
  BAD_PAYLOAD = 5,       // payload failed field validation
  OVERSIZE = 6,          // accumulated bytes exceed the maximum frame size
};

struct WeatherData {
  WeatherCondition condition = WeatherCondition::UNKNOWN;
  // Tenths of a degree Celsius, e.g. 275 == 27.5 C.
  int16_t temperatureDeciC = 0;
  int16_t feelsLikeDeciC = 0;
  int16_t tempMinDeciC = 0;
  int16_t tempMaxDeciC = 0;
  // 0-100 percent.
  uint8_t humidity = 0;
  // Unix epoch seconds of the observation (display uses device-local receipt time).
  uint32_t timestamp = 0;
  // UTF-8 location, not NUL-terminated; use locationLen.
  char location[32] = {};
  uint8_t locationLen = 0;
};

// Weather payload layout (version 1, all multi-byte fields big-endian):
//   0  u8  condition
//   1  i16 temperature (decicelsius)
//   3  i16 feelsLike
//   5  i16 min
//   7  i16 max
//   9  u8  humidity %
//  10  u64 observation epoch seconds
//  18  u8  locationLen (0..WEATHER_LOCATION_MAX)
//  19  ..  UTF-8 location bytes
constexpr size_t WEATHER_PAYLOAD_MIN_BYTES = 19;
constexpr size_t WEATHER_LOCATION_MAX = sizeof(WeatherData::location) - 1;
constexpr size_t WEATHER_PAYLOAD_MAX_BYTES = WEATHER_PAYLOAD_MIN_BYTES + WEATHER_LOCATION_MAX;

// STATUS characteristic value reported back to the phone.
struct StatusValue {
  uint8_t protocolVersion = PROTOCOL_VERSION;
  // 0 = advertising/idle, 1 = phone connected.
  uint8_t connected = 0;
  uint8_t lastError = static_cast<uint8_t>(ParseError::NONE);
};

// Incremental frame parser: BLE writes arrive in arbitrary slices, so bytes
// are fed as they come and complete frames are emitted one at a time. The
// parser owns no heap; state is a fixed buffer plus counters.
class FrameParser {
 public:
  // Feed received bytes. Returns the error of the last consumed frame, or
  // INCOMPLETE while a frame is still being reassembled.
  ParseError feed(const uint8_t* data, size_t length);

  // True when a complete, header-valid frame is available in frameBuffer().
  bool hasFrame() const { return frameReady_; }
  MessageType frameType() const { return frameType_; }
  const uint8_t* framePayload() const { return buffer_ + FRAME_HEADER_BYTES; }
  size_t framePayloadLen() const { return payloadLen_; }

  // Drop any partial frame state (e.g. after an error or disconnect).
  void reset();

 private:
  ParseError parseHeader();

  uint8_t buffer_[MAX_FRAME_BYTES] = {};
  size_t buffered_ = 0;      // bytes currently in buffer_
  size_t expectedLen_ = 0;   // full frame size from the header
  bool headerSeen_ = false;
  bool frameReady_ = false;
  MessageType frameType_ = MessageType::WEATHER;
  size_t payloadLen_ = 0;
};

// Validate a WEATHER payload and fill out. Returns BAD_PAYLOAD for any field
// violation (condition enum, length, humidity range); out is untouched then.
ParseError decodeWeather(const uint8_t* payload, size_t length, WeatherData& out);

// Encode a WEATHER payload (Android mirror of decodeWeather).
// Returns the encoded payload length, or 0 if the data cannot be encoded.
size_t encodeWeather(const WeatherData& in, uint8_t* out, size_t outCap);

// Build a full frame (header + payload) for type. Returns the total length,
// or 0 if payloadLen exceeds MAX_PAYLOAD_BYTES or outCap is too small.
size_t encodeFrame(MessageType type, const uint8_t* payload, size_t payloadLen, uint8_t* out, size_t outCap);

// Encode the STATUS characteristic value (3 bytes).
size_t encodeStatus(const StatusValue& status, uint8_t* out, size_t outCap);

}  // namespace companion
