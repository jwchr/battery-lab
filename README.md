# Battery Lab — Samsung phone + Galaxy Watch8 + Galaxy Ring

Experimental, local-first Android battery historian, with phone app, Watch8 companion,
Ring Bluetooth battery probe, discharge estimates, and paired baseline/monitoring tests.

## Status / important limitations

- **New in 0.3:** Battery Lab can host Samsung's own Battery widget, with your approval, and
  record the Ring and Watch percentages it displays. This uses no Bluetooth and causes no
  extra wearable wakeups. Each device is labeled once in the app. Evidence, limits and
  source priority are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

- **Source code prototype, not yet verified on real devices.** The battery calculations and
  documented Ring battery-response decoding have been tested with local Kotlin smoke tests.
  An Android APK build, installation, and actual BLE connectivity **have not yet been tested**.
- Phone: samples native Android battery charge %, charging status, temperature, voltage, and
  optional **remaining charge** (µAh). Remaining charge is *not* true full battery capacity,
  degradation, or battery health.
- Watch: separate Wear OS app reports its own battery over Google's Wear Data Layer,
  approximately every **30 minutes** subject to operating-system scheduling and syncing.
  Both apps use the same application ID and must be signed compatibly.
- Ring: experimental second-client BLE GATT request for Samsung Galaxy Ring. It uses
  Samsung DATA service `0x1B1B`, enables notifications, writes only the 3-byte battery
  sync request `0B 0B 03`, and closes the connection within 20 seconds. **No gesture,
  firmware, health-sensor, or account commands are sent.** Compatibility must be tested on
  the user's actual ring, phone, and firmware. Some devices may not allow concurrent GATT.
- Background jobs use Android JobScheduler and are **not exact-interval timers**. Phone
  samples and optional Ring sampling are approximately every 15 minutes; Android battery
  optimization may defer jobs or Bluetooth may fail. The app records the actual timestamp
  and does not fabricate missing readings.
- Samsung's battery widget is NOT accessed: proprietary widget data is not a documented
  third-party API. There is a **manual widget-reading fallback** if direct BLE fails.
- No INET permission, telemetry, remote account, subscriptions, database sync, or server.
  Data stays in the app's local SQLite database; user-initiated CSV export is supported.

## Getting an APK

The repository includes `.github/workflows/build-apks.yml`. Use the public GitHub
repository `jwchr/battery-lab` and run
**Actions → Build Battery Lab APKs → Run workflow**. Upon successful compilation, download
both artifacts (phone and Wear OS APK). Builds require no paid services other than
GitHub Actions if your account exceeds its included build minutes.

Alternatively, open this folder as a project in Android Studio with Java 17, Android SDK 35,
and Gradle 8.9, and build the `app` and `wear` debug variants. This project intentionally
omits a Gradle wrapper binary; CI provisions Gradle 8.9 directly.

Install the `app` APK on the Samsung phone and the `wear` APK on the paired Galaxy Watch8.
Apps have matching `applicationId` and need matching signing (debug keys from a single
CI runner are used by both APKs). If separate builders sign each APK using
separate debug keystores, cross-device Wear Data Layer discovery may fail; use the same
keystore/signing certificate for both.

The Android app requests `BLUETOOTH_CONNECT` (Nearby devices). On initial launch:
1. Choose the **already paired** Galaxy Ring from the list (verify its displayed name).
2. Tap **Read Ring battery now**. Expect a percentage or a diagnostic error.
3. If unsupported, tap **Enter Ring % manually** to log the reading seen in Samsung's widget.
4. The phone starts logging automatically. Open the Wear OS app once to start watch logging.
5. For the Ring, monitoring defaults to **OFF** until explicitly enabled.

### How the A/B experiment works

- **Baseline A (12h target):** Ring is worn normally; Battery Lab's periodic ring polling
  is OFF. It makes only a start-of-test and end-of-test measurement, or accepts the manually
  entered percentage from Samsung's widget.
- **Monitoring B (12h target):** Same Ring and typical wear, comparable starting charge,
  ambient temperature, phone proximity, and overnight/activity conditions. Battery Lab's
  ring polling is ON, with *requested* 15-minute interval.
- Start with the same battery percentage if possible, preferably from similar charge
  levels. Charging during either test invalidates a direct comparison.
- Press **Finish active test** after the target duration. The app stores the timestamp,
  percentages, actual elapsed time, and computes percentage points per hour.
- Repeat ABBA or BAAB. Compare mean rates, rather than relying on one pair.
- The observed difference is not proof of causation: radio environment, skin temperature,
  overnight sensing, changing state of charge and scheduler delays can affect drain.

### Predictions

The app estimates percent-per-hour from a recent contiguous noncharging discharge
segment of ≥1 hour and ≥4 percentage points lost, then shows remaining runtime and
full-charge equivalent. It does not estimate physical Ring battery capacity in mAh or
provide a validated state-of-health percentage. Short-window estimates may vary.

### CSV export

One file contains rows tagged `reading` and `experiment`. All timestamps are UTC ISO 8601.
Charging may be empty for manually entered Ring percentages or Ring firmware that does
not expose it. Exported values preserve their data sources and actual timestamps.

## Design and security

- Java/Kotlin Android API + Android SQLite, no third-party charting stack.
- Play Services Wearable Data Layer is used only for watch-to-phone sync.
- No health sensor data is accessed.
- Ring device address is saved locally and in the exported device ID to preserve a distinct
  history if the user switches Rings. Review this field before sharing exported CSVs.
- No BLE scanning: enumerates only already-bonded devices, with explicit manual selection.
- On Ring BLE failure, sampling logs the error and does not attempt rapid reconnect loops.

## References

- Independent, reverse-engineered Ring protocol (RFC-SGR-001):
  https://github.com/TheVellichor/SamsungOpenRing/blob/master/docs/RFC-SGR-001-Samsung-Galaxy-Ring-Protocol.md
- Wear OS Data Layer:
  https://developer.android.com/training/wearables/data/overview
- Android battery API:
  https://developer.android.com/reference/android/os/BatteryManager
- Android background scheduling:
  https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work

## Smoke test without Android SDK

```
kotlinc app/src/main/java/com/batterylab/personal/BatteryMath.kt \
  app/src/main/java/com/batterylab/personal/RingBatteryProtocol.kt \
  tools/BatteryMathSmokeTest.kt -include-runtime -d /tmp/battery-lab-tests.jar
java -jar /tmp/battery-lab-tests.jar
```

This validates only the numerical engine and a protocol example. It is **not** an Android
compilation test or a device integration test.
