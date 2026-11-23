# Mahindra Beacon Mega 2560 relay firmware

This sketch implements the app's newline-delimited, command-ID JSON protocol over HC-05 Bluetooth Classic. Master ON/OFF applies the requested non-safety zones in one controller operation and returns per-zone results.

## Before uploading

The provided `16_CHANNEL_RELAY.ino` uses `SoftwareSerial` on D10/D11 and relay outputs on D2–D9 and D12–D19. This app-compatible adaptation maps ten zones and holds the other six channels inactive. The sketch currently selects **active-LOW** based on an earlier report, but the observed lamp response is now inverted. Do not treat the trigger polarity or contact wiring as verified: have a qualified person check the relay input trigger and the lamp's COM/NO/NC connection using only isolated low-voltage test lamps before changing this setting or connecting other loads.

Arduino IDE also needs the **ArduinoJson** library (install it from Library Manager).

## Mega 2560 pin assignment

| Mega pin | App lighting zone |
| --- | --- |
| D2 | 3 BHK |
| D3 | 4 BHK |
| D4 | Clubhouse |
| D5 | Service Floor |
| D6 | Cafe |
| D7 | Podium |
| D8 | Swimming Pool |
| D9 | Landscape |
| D12 | Animation |
| D13 | Additional Area |
| D14–D19 | Spare relay channels; held inactive |

`Refuse / Emergency Exit` is deliberately not assigned to a general-purpose Arduino relay. Keep emergency, fire-alarm, and exit-sign circuits on their independently certified life-safety controls.

## HC-05 serial connection

The sketch uses `SoftwareSerial BT(10, 11)` at 9600 baud: HC-05 TXD to Mega D10 (software RX), and Mega D11 (software TX) to HC-05 RXD through a suitable logic-level divider. Connect grounds together and power the module from a stable regulated supply. Pair the HC-05 once in Android settings; the app then scans for and reconnects to the paired HC-05 automatically.

Use the relay module's documented input wiring and a qualified electrician for any mains-connected loads. A JSON acknowledgement confirms that the Arduino set a requested output; it does **not** sense relay contacts or verify that a lamp actually lit.

The controller starts with all mapped outputs inactive. Emergency lighting is never controlled by this sketch, including when a command includes a maintenance flag.

The Android app currently compensates for the observed inverse lamp response by mapping a requested light ON to a relay OFF command, and vice versa. This is a temporary logical inversion, not a sensor-confirmed state. Keep it limited to the isolated low-voltage test lamps until a qualified person verifies relay trigger polarity and COM/NO/NC wiring.
