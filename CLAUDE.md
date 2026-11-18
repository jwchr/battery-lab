# Battery Lab

## Working rules (same in every app; Control Center, Nov 18, 2026)
- Owner: Jeff, not a developer. Short plain American English, no em dashes, Eastern time. Verify, don't infer.
- Permission (Jeff, Nov 17-18): decide and act; ask Jeff only for safety, security or money. Hands-on tests on his devices and product facts only he knows are questions, not approvals.
- Reviews (Jeff, Nov 17, quoted exactly): "CodeRabbit gets the regular reviews and Codex gets the riskier ones. If Codex is not available, then it goes to Argon. If the API Argon is not available, then tell me and I will do it manually. That's how it's always been." When the Argon API fails, give Jeff a Review-<PR>-for-Gemini.txt file and keep working. The author never reviews its own PR. CodeRabbit after Nov 22: wait for Jeff's answer (asked Nov 21).
- Up to 3 open PRs, oldest finished first.
- Keep the chat small: quiet tests (failures and final count only), grep instead of reading whole files, subagents for big reads, never paste whole files or logs.
- Current state lives only in the newest GitHub issue titled "Handoff note: <app> chat, <date>" (find it with an issue search on that title). Rules live here. Do not keep a second status file.
- SSOT Tables spreadsheet 1G9kZJhYwc5nkw2xkvUDPHOwgPdZXDitjLc8gaCFEuv0, tab Active Work, this app's rows only (W098, W099); one Change Log line per change.
- Report on GitHub and in SSOT; never send_message. The Control Center hands you off at 40% full: when asked, finish the current step, push, write the handoff note (done, in progress with branch and head, next, waiting on Jeff, must not do; under 400 words; no rules, no history) and stop.

## Battery Lab specifics
- **Riskier reviews** (Codex): safety, security or finance sensitive, or complex changes. In this app that includes Bluetooth, backup and restore, and the database.
- **Merging:** Claude may merge its own PRs on green CI and a clean qualifying review.
- **Argon API:** out of usage (monthly spending cap) as of Nov 18, so Argon reviews go to Jeff by hand.
- **Never:** root the phone; modify, decompile or analyze Samsung's apps; use accessibility services; add the "trusted devices only" permission to Claude Code settings.
- **Ring Bluetooth:** connect to the Ring only when the user starts something that needs it (monitoring they turned on, an A/B test they started, a "connect now" button). Never on a background schedule by default. Listening for the Ring's broadcasts is fine.
- **Nothing identifying** in commits, issues or PRs: no serials, Bluetooth addresses, device names or raw readings. Use counts and trends.
- Keep the Ring protocol code and the A/B features.
- **Phone installs** (Jeff does them): `install -r` only, after backing up `databases/battery_lab.db`, `-wal` and `-shm` with `run-as`. Never uninstall, never install a build signed with another key (both wipe the history), never force-stop the app. Mac adb notes: `docs/MAC.md`.

## Build and test (Linux cloud)
- Modules: `app` (phone), `wear` (Watch), `widgetprobe` (widget test tool). compileSdk 35, Java 17 target, Gradle 8.9 in CI.
- SDK setup (cloud environment "battery-lab" allows dl.google.com): install the command-line tools into `$HOME/android-sdk`, then `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`.
- Quiet check: `gradle --no-daemon -q :app:assembleDebug :app:testDebugUnitTest :wear:assembleDebug` (130 unit tests pass as of Nov 18). If Maven Central reports a missing dependency, rerun with `--refresh-dependencies`.
- `.github/workflows/build-apks.yml` builds the phone and Watch APKs, manual run only. It costs GitHub Actions minutes, so run it only when Jeff needs APKs.

## Needs Jeff's devices
The cloud has no adb and no Bluetooth. These are hands-on tests for Jeff: phone and Watch installs, Ring Bluetooth reads, hosting Samsung's battery widget, and the button-by-button check.
