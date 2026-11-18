# Working with the phone and watch from the Mac

- **adb:** the phone is held by the Android SDK's adb server on **port 5040**, not the Homebrew adb on 5037:

  ```bash
  ADB_SERVER_SOCKET=tcp:127.0.0.1:5040 /opt/homebrew/share/android-commandlinetools/platform-tools/adb devices
  ```

- **Watch:** pair over Wireless debugging from **Terminal.app** with a separate helper (`adb -P 5041 pair <ip>:<pairing pop-up port>`, then `connect` to the main screen's port). A Claude-launched adb helper is blocked from the local network by macOS.
- **Before any `adb shell input`:** Battery Lab must be the resumed activity and the screen awake.
- **Timing logs:** `ADB_SERVER_SOCKET=tcp:127.0.0.1:5040 /opt/homebrew/share/android-commandlinetools/platform-tools/adb logcat -s BatteryLabStats BatteryLabMain`.
- **Soak readout:** `ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb ADB_SERVER_SOCKET=tcp:127.0.0.1:5040 tools/soak_report.sh`. It prints timestamps, percent changes and counts, but its last section (fuel gauge) prints the phone's current level, voltage and temperature: remove that section before sharing the output in a commit, issue or PR.
- **Mac build:**

  ```bash
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools /opt/homebrew/opt/gradle@8/bin/gradle --no-daemon -q :app:assembleDebug :app:testDebugUnitTest :wear:assembleDebug
  ```
