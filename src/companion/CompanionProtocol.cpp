#include "CompanionProtocol.h"

#include <cstring>

namespace companion {
namespace {

// Multi-byte fields are big-endian; shift instead of casting to avoid
// unaligned/endianness assumptions (AGENTS.md gotcha list).
void putU16(uint8_t* out, uint16_t v) {
  out[0] = static_cast<uint8_t>(v >> 8);
  out[1] = static_cast<uint8_t>(v);
}

uint16_t getU16(const uint8_t* in) {
  return static_cast<uint16_t>((static_cast<uint16_t>(in[0]) << 8) | in[1]);
}

void putU32(uint8_t* out, uint32_t v) {
  out[0] = static_cast<uint8_t>(v >> 24);
  out[1] = static_cast<uint8_t>(v >> 16);
  out[2] = static_cast<uint8_t>(v >> 8);
  out[3] = static_cast<uint8_t>(v);
}

uint32_t getU32(const uint8_t* in) {
  return (static_cast<uint32_t>(in[0]) << 24) | (static_cast<uint32_t>(in[1]) << 16) |
         (static_cast<uint32_t>(in[2]) << 8) | in[3];
}

void putU64(uint8_t* out, uint64_t v) {
  for (int i = 0; i < 8; ++i) {
    out[i] = static_cast<uint8_t>(v >> (56 - i * 8));
  }
}

uint64_t getU64(const uint8_t* in) {
  uint64_t v = 0;
  for (int i = 0; i < 8; ++i) {
    v = (v << 8) | in[i];
  }
  return v;
}

// Minimal UTF-8 structure check: reject truncated sequences, bad continuations,
// overlong encodings, and NUL bytes. Not a full Unicode validator; the goal is
// never rendering control bytes or garbage onto the e-ink screen.
bool isValidUtf8(const char* s, size_t len) {
  size_t i = 0;
  while (i < len) {
    const uint8_t c = static_cast<uint8_t>(s[i]);
    if (c == 0x00) return false;
    size_t seqLen = 0;
    uint32_t codeMin = 0;
    if (c < 0x80) {
      i += 1;
      continue;
    } else if ((c & 0xE0) == 0xC0) {
      seqLen = 2;
      codeMin = 0x80;
    } else if ((c & 0xF0) == 0xE0) {
      seqLen = 3;
      codeMin = 0x800;
    } else if ((c & 0xF8) == 0xF0) {
      seqLen = 4;
      codeMin = 0x10000;
    } else {
      return false;
    }
    if (i + seqLen > len) return false;
    uint32_t cp = c & (0xFF >> (seqLen + 1));
    for (size_t j = 1; j < seqLen; ++j) {
      const uint8_t cc = static_cast<uint8_t>(s[i + j]);
      if ((cc & 0xC0) != 0x80 || cc == 0x00) return false;
      cp = (cp << 6) | (cc & 0x3F);
    }
    if (cp < codeMin || cp > 0x10FFFF) return false;
    i += seqLen;
  }
  return true;
}

}  // namespace

void FrameParser::reset() {
  buffered_ = 0;
  expectedLen_ = 0;
  headerSeen_ = false;
  frameReady_ = false;
  payloadLen_ = 0;
}

ParseError FrameParser::parseHeader() {
  if (buffer_[0] != PROTOCOL_VERSION) {
    return ParseError::BAD_VERSION;
  }
  const uint8_t typeByte = buffer_[1];
  if (typeByte < static_cast<uint8_t>(MessageType::WEATHER) ||
      typeByte > static_cast<uint8_t>(MessageType::CUSTOM_TEXT)) {
    return ParseError::UNKNOWN_TYPE;
  }
  const uint16_t payloadLen = getU16(buffer_ + 2);
  if (payloadLen > MAX_PAYLOAD_BYTES) {
    return ParseError::BAD_LENGTH;
  }
  expectedLen_ = FRAME_HEADER_BYTES + payloadLen;
  headerSeen_ = true;
  return ParseError::INCOMPLETE;
}

ParseError FrameParser::feed(const uint8_t* data, size_t length) {
  if (data == nullptr && length > 0) {
    return ParseError::BAD_LENGTH;
  }
  // A previous frame that was never read is treated as consumed; only the
  // newest frame is retained.
  frameReady_ = false;

  // The first hard error wins: a malformed frame must still surface even if
  // well-formed frames follow it in the same write.
  ParseError firstError = ParseError::INCOMPLETE;
  bool frameCompleted = false;
  size_t offset = 0;
  while (offset < length) {
    if (!headerSeen_) {
      // Fill up to a full header.
      const size_t want = FRAME_HEADER_BYTES - buffered_;
      const size_t take = (length - offset) < want ? (length - offset) : want;
      memcpy(buffer_ + buffered_, data + offset, take);
      buffered_ += take;
      offset += take;
      if (buffered_ < FRAME_HEADER_BYTES) {
        return firstError;
      }
      const ParseError headerResult = parseHeader();
      if (headerResult != ParseError::INCOMPLETE) {
        // Bad header: drop what is buffered, then skip the rest of the frame
        // using its declared length so the stream can resync.
        if (firstError == ParseError::INCOMPLETE) {
          firstError = headerResult;
        }
        const size_t declaredFrameLen = FRAME_HEADER_BYTES + getU16(buffer_ + 2);
        reset();
        const size_t skip = (length - offset) < (declaredFrameLen - FRAME_HEADER_BYTES)
                                ? (length - offset)
                                : (declaredFrameLen - FRAME_HEADER_BYTES);
        offset += skip;
        continue;
      }
    }

    const size_t want = expectedLen_ - buffered_;
    const size_t take = (length - offset) < want ? (length - offset) : want;
    if (take > 0) {
      if (buffered_ + take > sizeof(buffer_)) {
        reset();
        return ParseError::OVERSIZE;
      }
      memcpy(buffer_ + buffered_, data + offset, take);
      buffered_ += take;
      offset += take;
    }
    if (buffered_ < expectedLen_) {
      return firstError;
    }

    // Complete frame.
    frameReady_ = true;
    frameType_ = static_cast<MessageType>(buffer_[1]);
    payloadLen_ = expectedLen_ - FRAME_HEADER_BYTES;
    frameCompleted = true;

    // Shift any pipelined bytes to the front for the next frame.
    const size_t extra = buffered_ - expectedLen_;
    if (extra > 0) {
      memmove(buffer_, buffer_ + expectedLen_, extra);
    }
    buffered_ = extra;
    expectedLen_ = 0;
    headerSeen_ = false;
    // Loop again: more complete frames may be queued behind this one.
  }
  if (firstError != ParseError::INCOMPLETE) {
    return firstError;
  }
  return frameCompleted ? ParseError::NONE : ParseError::INCOMPLETE;
}

ParseError decodeWeather(const uint8_t* payload, size_t length, WeatherData& out) {
  if (payload == nullptr || length < WEATHER_PAYLOAD_MIN_BYTES) {
    return ParseError::BAD_PAYLOAD;
  }
  const uint8_t condition = payload[0];
  if (condition >= WEATHER_CONDITION_COUNT) {
    return ParseError::BAD_PAYLOAD;
  }
  const uint8_t locationLen = payload[18];
  // Newer senders append a wind byte after the location; the legacy 19+N form
  // (companion-test-0.1) is still accepted with windKph = 0.
  const bool hasWind = length == WEATHER_PAYLOAD_MIN_BYTES + locationLen + 1;
  if (!hasWind && length != WEATHER_PAYLOAD_MIN_BYTES + locationLen) {
    return ParseError::BAD_PAYLOAD;
  }
  const char* location = reinterpret_cast<const char*>(payload + WEATHER_PAYLOAD_MIN_BYTES);

  // Oversized locations are truncated on a UTF-8 character boundary rather
  // than rejected, so a long city name still renders.
  uint8_t useLen = locationLen;
  if (useLen > WEATHER_LOCATION_MAX) {
    useLen = WEATHER_LOCATION_MAX;
    while (useLen > 0 && (static_cast<uint8_t>(location[useLen]) & 0xC0) == 0x80) {
      --useLen;
    }
  }
  if (!isValidUtf8(location, useLen)) {
    return ParseError::BAD_PAYLOAD;
  }

  WeatherData data;
  data.condition = static_cast<WeatherCondition>(condition);
  data.temperatureDeciC = static_cast<int16_t>(getU16(payload + 1));
  data.feelsLikeDeciC = static_cast<int16_t>(getU16(payload + 3));
  data.tempMinDeciC = static_cast<int16_t>(getU16(payload + 5));
  data.tempMaxDeciC = static_cast<int16_t>(getU16(payload + 7));
  data.humidity = payload[9] > 100 ? 100 : payload[9];
  data.timestamp = getU32(payload + 10);
  if (hasWind) {
    data.windKph = payload[WEATHER_PAYLOAD_MIN_BYTES + locationLen];
  }
  memcpy(data.location, location, useLen);
  data.location[useLen] = '\0';
  data.locationLen = useLen;
  out = data;
  return ParseError::NONE;
}

size_t encodeWeather(const WeatherData& in, uint8_t* out, size_t outCap) {
  if (out == nullptr || outCap < WEATHER_PAYLOAD_MAX_BYTES) {
    return 0;
  }
  uint8_t locationLen = in.locationLen;
  if (locationLen > WEATHER_LOCATION_MAX) {
    locationLen = WEATHER_LOCATION_MAX;
    while (locationLen > 0 && (static_cast<uint8_t>(in.location[locationLen]) & 0xC0) == 0x80) {
      --locationLen;
    }
  }
  if (!isValidUtf8(in.location, locationLen)) {
    return 0;
  }

  putU16(out + 1, static_cast<uint16_t>(in.temperatureDeciC));
  putU16(out + 3, static_cast<uint16_t>(in.feelsLikeDeciC));
  putU16(out + 5, static_cast<uint16_t>(in.tempMinDeciC));
  putU16(out + 7, static_cast<uint16_t>(in.tempMaxDeciC));
  out[0] = static_cast<uint8_t>(in.condition);
  out[9] = in.humidity > 100 ? 100 : in.humidity;
  putU32(out + 10, in.timestamp);
  out[18] = locationLen;
  memcpy(out + WEATHER_PAYLOAD_MIN_BYTES, in.location, locationLen);
  out[WEATHER_PAYLOAD_MIN_BYTES + locationLen] = in.windKph;
  return WEATHER_PAYLOAD_MIN_BYTES + 1 + locationLen;
}

size_t encodeFrame(MessageType type, const uint8_t* payload, size_t payloadLen, uint8_t* out, size_t outCap) {
  if (payloadLen > MAX_PAYLOAD_BYTES || outCap < FRAME_HEADER_BYTES + payloadLen) {
    return 0;
  }
  out[0] = PROTOCOL_VERSION;
  out[1] = static_cast<uint8_t>(type);
  putU16(out + 2, static_cast<uint16_t>(payloadLen));
  if (payloadLen > 0) {
    memcpy(out + FRAME_HEADER_BYTES, payload, payloadLen);
  }
  return FRAME_HEADER_BYTES + payloadLen;
}

size_t encodeStatus(const StatusValue& status, uint8_t* out, size_t outCap) {
  if (out == nullptr || outCap < 3) {
    return 0;
  }
  out[0] = status.protocolVersion;
  out[1] = status.connected;
  out[2] = status.lastError;
  return 3;
}

}  // namespace companion
