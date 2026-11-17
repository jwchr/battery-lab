# Battery Lab handoff

Last updated 2026-11-17. This replaces the Nov 13 note in issue #2. For the architecture and its evidence, see [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Where things stand
- **PR #3** (Samsung widget source and the scheduling fix): merged into `main` on 2026-11-17.
- **PR #6** (the redesign, the Stats "lab engine", the audit #4 fixes, cloud backup and two review passes): merged into `main` on 2026-11-17, after PR #3. Both branches are deleted.
- **New work goes on a branch off `main`.** Every merge still needs the owner's OK.
- **Phone:** has the newest debug build (the code now on `main`), installed over the old app with history kept. The background job runs every 15 minutes, and the app is exempt from battery optimization (Unrestricted).
- **Watch app:** never installed on the watch (no watch-app readings have ever arrived). It is optional: a cross-check on Samsung's widget, every 2 hours. Installing it needs wireless debugging on the watch.

## Audit #4 status
For the full table, see the triage comment on issue #4. That comment predates A1, the A3 glitch/week fixes, A6 and B4.

| Item | State |
|---|---|
| A1 widget freshness | Cards show "same since <time>" after an unchanged hour |
| A2 wearable charging icon | Checked against history (Ring 9/9, Watch 23/23); inference kept |
| A3 runtime labels and math | Relabeled as a projection; lone glitch readings ignored; week-crossing stretches split; 8 boundary tests |
| A4 capacity proxy | Already labeled as an estimate |
| A5 baseline waking the Ring | Fixed: only the monitoring arm may use BLE |
| A6 main-thread work | Stats loads in the background (main thread 152 → 52 ms); the main screen builds once per open (appears in 612 → 494 ms) |
| B1 CSV false success | Already fixed |
| B2 widget-settings cancel | Fixed |
| B3 Ring picker | Likely Rings listed first |
| B4 watch percent | Refreshes on return and on send |
| B5 cloud backup | On (owner chose cloud): Android backs up a gzipped CSV of the history only; settings stay on the phone |
| B6 address in exports | Already fixed (stable Ring ID) |

**Decided: leave the main screen's history loading on the main thread.** Every main-screen query has a fixed window (last 1,000 readings, 48 h, 26 weeks), so its cost stays flat as history grows. On a fresh open it is about 250 ms, and about 77 ms once the app is running. Revisit only if the `BatteryLabMain` log shows growth.

## Open items
1. **Button-by-button check on the phone** (owner's time). Cover:
   - connecting the widget, and cancelling its settings screen
   - labels
   - Read widget now
   - manual entry
   - the Ring picker
   - monitoring
   - starting and finishing each A/B test
   - CSV export

   The owner has said some buttons and numbers looked wrong. Which ones is not known yet.
2. **B5 restore check:** a real restore onto a new phone is untested; the backup itself ran on 2026-11-16.
3. **A/B Ring-overhead experiment** (widget-only vs BLE monitoring, ABBA order). Not run yet.
4. **Issue #5 (Ring Radar):** a proposal only, not approved for coding.
5. **Old branches kept:** `feature/widget-host-probe` (3 commits) and `feature/passive-battery-diagnostic` (2 commits, PR #1 closed) still hold work that is not in `main`.

## Working with the phone
- **adb:** the phone is held by the Android SDK's adb server on **port 5040**, not the Homebrew adb on 5037. Before blaming phone settings, run commands this way:

  ```bash
  ADB_SERVER_SOCKET=tcp:127.0.0.1:5040 /opt/homebrew/share/android-commandlinetools/platform-tools/adb devices
  ```

- **Install:** use `install -r` only. Never uninstall, and never install a build signed with another key: either deletes the history. Back up `databases/battery_lab.db`, `-wal` and `-shm` with `run-as` first.
- **Don't force-stop the app.** That cancels its background job.
- **Before any `adb shell input`:** Battery Lab must be the resumed activity and the screen awake.
- **Timing logs:** `adb logcat -s BatteryLabStats BatteryLabMain`.
- **Soak readout:** `ADB=<sdk adb> ADB_SERVER_SOCKET=tcp:127.0.0.1:5040 tools/soak_report.sh`. It prints counts only.

## Build and test (on the Mac, no GitHub Actions)
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools /opt/homebrew/opt/gradle@8/bin/gradle --no-daemon :app:assembleDebug :app:testDebugUnitTest :wear:assembleDebug
```
88 unit tests pass as of this note. The repo's only workflow is manual (`workflow_dispatch`).

## Rules that still apply
- **Not allowed:**
  - rooting
  - modifying, decompiling or analysing Samsung's apps
  - accessibility services
  - unnecessary Ring BLE wakeups
- **Nothing identifying in commits, issues or PRs:** no serials, Bluetooth addresses, device names or raw readings. Use counts and trends.
- **Keep the Ring protocol code and the A/B features.**
