#include <ArduinoJson.h>
#include <SoftwareSerial.h>

// HC-05 TXD -> Mega D10; Mega D11 -> HC-05 RXD through a logic-level divider.
SoftwareSerial BT(10, 11);

// Verify the relay module's input trigger polarity before changing this setting.
#define MAHINDRA_RELAY_ACTIVE_LOW 1

constexpr uint8_t RELAY_ON_LEVEL = MAHINDRA_RELAY_ACTIVE_LOW ? LOW : HIGH;
constexpr uint8_t RELAY_OFF_LEVEL = MAHINDRA_RELAY_ACTIVE_LOW ? HIGH : LOW;
constexpr size_t MAX_COMMAND_LENGTH = 256;
constexpr uint8_t ADDITIONAL_RELAY_PIN = 13;
constexpr uint32_t RELAY_WATCHDOG_TIMEOUT_MS = 3000;

const uint8_t RELAY_PINS[] = {2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 14, 15, 16, 17, 18, 19};

struct ZoneRelay {
  const char* zoneId;
  uint8_t pin;
};

const ZoneRelay ZONE_RELAYS[] = {
  {"res-3bhk", 2},
  {"res-4bhk", 3},
  {"clubhouse", 4},
  {"service", 5},
  {"cafe", 6},
  {"podium", 7},
  {"balcony", 8},
  {"landscape", 9},
  {"facade", 12},
  {"additional", 13},
};

char commandLine[MAX_COMMAND_LENGTH + 1];
size_t commandLength = 0;
bool commandOverflow = false;
uint32_t lastControllerActivityAt = 0;
bool controllerWatchdogArmed = false;

void markControllerActivity() {
  lastControllerActivityAt = millis();
  controllerWatchdogArmed = true;
}

void sendWatchdogProbeReply(uint32_t commandId) {
  StaticJsonDocument<192> reply;
  reply["commandId"] = commandId;
  reply["acknowledged"] = true;
  reply["watchdog"] = true;
  reply["timeoutMs"] = RELAY_WATCHDOG_TIMEOUT_MS;
  serializeJson(reply, BT);
  BT.write('\n');
  Serial.print(F("TX watchdog ready: "));
  serializeJson(reply, Serial);
  Serial.println();
}

void sendReply(uint32_t commandId, bool acknowledged, const char* zoneId, const char* state, const char* error = nullptr) {
  StaticJsonDocument<192> reply;
  reply["commandId"] = commandId;
  reply["acknowledged"] = acknowledged;
  reply["zoneId"] = zoneId;
  reply["state"] = state;
  if (error != nullptr) {
    reply["error"] = error;
  }
  serializeJson(reply, BT);
  BT.write('\n');
  Serial.print(F("TX: "));
  serializeJson(reply, Serial);
  Serial.println();
}

void handleMasterCommand(JsonDocument& command, uint32_t commandId, const char* state) {
  JsonArrayConst zoneIds = command["zoneIds"].as<JsonArrayConst>();
  if ((strcmp(state, "ON") != 0 && strcmp(state, "OFF") != 0) || zoneIds.isNull() || zoneIds.size() == 0) {
    sendReply(commandId, false, "", state, "invalid_master_command");
    return;
  }

  StaticJsonDocument<1024> reply;
  JsonArray results = reply.createNestedArray("results");
  bool allAcknowledged = true;

  for (JsonVariantConst zoneValue : zoneIds) {
    const char* zoneId = zoneValue.as<const char*>();
    JsonObject result = results.createNestedObject();
    result["zoneId"] = zoneId == nullptr ? "" : zoneId;
    result["state"] = state;
    bool acknowledged = false;
    const char* error = nullptr;

    if (zoneId == nullptr || zoneId[0] == '\0') {
      error = "invalid_zone";
    } else if (strcmp(zoneId, "emergency") == 0) {
      error = "life_safety_not_on_general_relay";
    } else {
      for (const ZoneRelay& zoneRelay : ZONE_RELAYS) {
        if (strcmp(zoneId, zoneRelay.zoneId) == 0) {
          digitalWrite(zoneRelay.pin, strcmp(state, "ON") == 0 ? RELAY_ON_LEVEL : RELAY_OFF_LEVEL);
          acknowledged = true;
          break;
        }
      }
      if (!acknowledged) {
        error = "unknown_zone";
      }
    }

    result["acknowledged"] = acknowledged;
    if (error != nullptr) {
      result["error"] = error;
      allAcknowledged = false;
    }
  }

  reply["commandId"] = commandId;
  reply["acknowledged"] = allAcknowledged;
  reply["state"] = state;
  serializeJson(reply, BT);
  BT.write('\n');
  Serial.print(F("TX: "));
  serializeJson(reply, Serial);
  Serial.println();
}

void handleCommand(const char* line) {
  StaticJsonDocument<512> command;
  const DeserializationError parseError = deserializeJson(command, line);
  if (parseError) {
    sendReply(0, false, "", "", "invalid_json");
    return;
  }

  const uint32_t commandId = command["commandId"] | 0;
  const char* commandType = command["command"] | "zone";
  if (strcmp(commandType, "heartbeat") == 0) {
    markControllerActivity();
    return;
  }
  if (strcmp(commandType, "watchdogProbe") == 0) {
    markControllerActivity();
    sendWatchdogProbeReply(commandId);
    return;
  }
  markControllerActivity();
  if (strcmp(commandType, "master") == 0) {
    handleMasterCommand(command, commandId, command["state"] | "");
    return;
  }

  const char* zoneId = command["zoneId"] | "";
  const char* state = command["state"] | "";
  if (zoneId[0] == '\0' || (strcmp(state, "ON") != 0 && strcmp(state, "OFF") != 0)) {
    sendReply(commandId, false, zoneId, state, "invalid_command");
    return;
  }

  if (strcmp(zoneId, "emergency") == 0) {
    sendReply(commandId, false, zoneId, state, "life_safety_not_on_general_relay");
    return;
  }

  for (const ZoneRelay& zoneRelay : ZONE_RELAYS) {
    if (strcmp(zoneId, zoneRelay.zoneId) == 0) {
      digitalWrite(zoneRelay.pin, strcmp(state, "ON") == 0 ? RELAY_ON_LEVEL : RELAY_OFF_LEVEL);
      sendReply(commandId, true, zoneId, state);
      return;
    }
  }

  sendReply(commandId, false, zoneId, state, "unknown_zone");
}

void setup() {
  Serial.begin(115200);
  for (const uint8_t pin : RELAY_PINS) {
    digitalWrite(pin, RELAY_OFF_LEVEL);
    pinMode(pin, OUTPUT);
  }
  BT.begin(9600);
  lastControllerActivityAt = millis();
  controllerWatchdogArmed = false;
  Serial.println(F("Mahindra Beacon HC-05 controller ready; relays initialized OFF."));
}

void loop() {
  while (BT.available() > 0) {
    const char incoming = static_cast<char>(BT.read());
    if (incoming == '\n') {
      if (commandOverflow) {
        sendReply(0, false, "", "", "command_too_long");
      } else if (commandLength > 0) {
        commandLine[commandLength] = '\0';
        Serial.print(F("RX: "));
        Serial.println(commandLine);
        handleCommand(commandLine);
      }
      commandLength = 0;
      commandOverflow = false;
    } else if (incoming != '\r') {
      if (commandLength < MAX_COMMAND_LENGTH) {
        commandLine[commandLength++] = incoming;
      } else {
        commandOverflow = true;
      }
    }
  }

  if (
    controllerWatchdogArmed
    && static_cast<uint32_t>(millis() - lastControllerActivityAt) >= RELAY_WATCHDOG_TIMEOUT_MS
  ) {
    digitalWrite(ADDITIONAL_RELAY_PIN, RELAY_OFF_LEVEL);
    controllerWatchdogArmed = false;
    Serial.println(F("Relay watchdog expired; Additional Area relay switched OFF."));
  }
}
