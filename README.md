# Mahindra Mumbai Beacon

A premium responsive web and Android lighting-control experience for the Mahindra Mumbai BeaconHill project. The dashboard, zone imagery, and project views use the original architectural renders supplied in the building-views PDF. Architectural lighting overlays are clearly labelled simulations; they are configurable approximations, not measured luminaire locations.

## What is included

- Seven linked screens: Overview, eleven lighting zones, animation studio, project-photo gallery, Bluetooth connections, scene management, and system settings.
- The project gallery uses the supplied PDF renders, with category filters, keyboard navigation, and zoom/pan controls for every image.
- Overview features Mahindra Lifespaces' official Exclusive tower film from its project site; the video is streamed, not bundled, with the supplied tower render shown if it is unavailable.
- Responsive navigation, an explicit back button, URL/history back support, device reduced-motion preferences, and local saved scenes.
- A persistent dark/light theme switch is available on Overview.
- A local-only simulator that never reports real hardware acknowledgement.
- Android Bluetooth Classic SPP through a native Capacitor plugin. Pair the HC-05 once in Android settings; on Android the app scans for the paired module and reconnects automatically.
- Arduino Mega 2560 firmware and the zone-to-relay pin map are in [hardware/arduino/MahindraBeaconMega](./hardware/arduino/MahindraBeaconMega/).
- Emergency/fire/exit circuits are never routed through this general-purpose relay firmware.

## Run on the web

Requires Node.js 20.19+ or 22.12+ and npm.

```powershell
npm.cmd install
npm.cmd run dev
```

Open the local Vite URL (normally `http://localhost:5173`). Without a verified site controller, the app visibly remains in **simulation mode**. HC-05 Bluetooth Classic SPP is available in the Android app; browsers cannot connect to Classic SPP.

The checked-in UI bundle and static assets are currently served from `android/app/src/main/assets/public`. The editable React `src/` tree is missing from this checkout, so UI feature changes require restoring that source tree.

## Build and install the Android app

Requires Android Studio, Android SDK 35, and JDK 21 (select JDK 21 as the Gradle JDK in Android Studio's settings).

Open the `android` folder in Android Studio, or build from PowerShell:

```powershell
npm.cmd run build
npx.cmd cap sync android
Set-Location android
.\gradlew.bat assembleDebug
```

The installable debug APK is `android\app\build\outputs\apk\debug\app-debug.apk`.

Install on a connected, USB-debugging-enabled Android tablet:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r ".\android\app\build\outputs\apk\debug\app-debug.apk"
```

Then open **Mahindra Beacon** from the tablet's launcher. The app presents the same branded interface on web and Android.

## Arduino Mega 2560 and HC-05

The app sends newline-delimited JSON over Bluetooth Classic SPP. Individual and batched master commands carry a command ID and expect a matching controller response within two seconds. A missing command response marks only that command unconfirmed; it no longer closes an otherwise-connected HC-05 socket. A real socket loss still returns lighting controls to simulation. The standalone sketch is `hardware/arduino/MahindraBeaconMega/MahindraBeaconMega.ino`.

Ten non-safety zones use Mega D2–D9 and D12–D13; D14–D19 remain inactive. The 11th app zone, `Refuse / Emergency Exit`, is intentionally not relay-controlled and stays on its independent life-safety circuit. ALL ON/OFF batches the ten controllable zones in one Bluetooth round trip. HC-05 uses `SoftwareSerial` RX D10/TX D11 at 9600 baud. The relay is configured active-LOW as previously reported. Install ArduinoJson using Arduino IDE Library Manager.

The sketch's acknowledgment confirms the Arduino set a digital output, not that a relay contact or lamp circuit operated. Do not connect mains loads or life-safety circuits without a qualified electrician and independent certified safety controls.

## Life-safety commissioning

The emergency-exit zone is excluded from master commands and scenes, and the general-purpose Bluetooth relay firmware rejects it outright. The restricted maintenance PIN exists **only in app memory**; it is not device authentication. Do not route a fire/emergency circuit through a general-purpose wireless relay. Have a qualified site-controls/electrical engineer commission and independently test any real system.

## Verify

```powershell
npm.cmd test
npm.cmd run build
```

For Android, run `.\gradlew.bat assembleDebug` from the `android` folder. Hardware command acknowledgments require a connected Mega/HC-05 with the matching firmware; simulator activity is explicitly identified as simulated.

## Project imagery and branding

The dashboard, lighting visualization, and gallery use the supplied project renders in `public/assets/`, including the tower, evening view, clubhouse/pool, entrance, garden, retail, balcony, and facade. The supplied Mahindra Lifespaces wordmark is included in the app branding. The project and sales information are available from the [official Mahindra Lifespaces project page](https://www.mahindralifespaces.com/residential/mumbai-properties/mahindra-beaconhill/).
