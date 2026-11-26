# Battery Lab

## Shared rules
Jeff's standing rules for every chat (permission, reviews, PR limits, keeping the chat small, compaction at 60% instead of handoffs, SSOT) live only in `~/.claude/CLAUDE.md`, which Claude Code loads automatically. Do not copy them here; change them there.
This app's SSOT rows: W098, W099.

## Battery Lab specifics
- **Riskier reviews** (which reviewers they need is in `~/.claude/CLAUDE.md`): safety, security or finance sensitive, or complex changes. In this app that includes Bluetooth, backup and restore, and the database.
- **Merging:** Claude may merge its own PRs when it decides they are ready: reviewed first as the Reviews section of `~/.claude/CLAUDE.md` requires for battery-lab (a session that cannot load that file does not merge at all and leaves the PR for one that can; reason: Neon review, Nov 24, 2026, found the old fallback did not say which reviews count) with findings handled, and the quiet check passing (Jeff, Nov 18: "you have permission to merge on your own when you decide it's ready"). `.github/workflows/pr-ci.yml` (merged Nov 24, 2026, #51) runs the quiet check's tasks on non-draft PRs that touch `app/`, `wear/`, `rf/`, `widgetprobe/`, root `*.gradle.kts`, `gradle.properties` or the workflow itself. It costs nothing because the repo is public and it uses GitHub's standard ubuntu-latest runner (Control Center and Battery Lab checks, Nov 24, 2026; reason it was held back: the old worry about Actions minutes). "Green CI" means that check passes on the PR's head; for a PR where it does not run or shows skipped (draft, other paths, or older than #51), the quiet check below passes on the head in a local or cloud build.
- **Never:** root the phone; modify, decompile or analyze Samsung's apps; use accessibility services; add the "trusted devices only" permission to Claude Code settings.
- **Ring Bluetooth:** connect to the Ring only when the user starts something that needs it (monitoring they turned on, an A/B test they started, a "connect now" button, an RF Lab session they started). Never on a background schedule by default. Listening for the Ring's broadcasts is fine; RF Lab scans only inside a session the user starts.
- **Nothing identifying** in commits, issues or PRs: no serials, Bluetooth addresses, device names or raw readings. Use counts and trends.
- Keep the Ring protocol code and the A/B features.
- **Phone installs** (Jeff does them): `install -r` only, after backing up `databases/battery_lab.db`, `-wal` and `-shm` with `run-as`. Never uninstall, never install a build signed with another key (both wipe the history), never force-stop the app. Mac adb notes: `docs/MAC.md`.

## Build and test (Linux cloud)
- Modules: `app` (phone), `rf` (RF Lab core, plain Kotlin, no Android), `wear` (Watch), `widgetprobe` (widget test tool). compileSdk 37.2 and targetSdk 37 (Android 17), AGP 9.5.2, Kotlin 2.4.21, Java 17 target, Gradle 9.9.2 in CI (no wrapper).
- SDK setup (cloud environment "battery-lab" allows dl.google.com): install the command-line tools into `$HOME/android-sdk`, then `sdkmanager "platforms;android-37.2" "build-tools;37.0.0" "platform-tools"`.
- Quiet check: `gradle --no-daemon -q :rf:test :app:assembleDebug :app:testDebugUnitTest :wear:testDebugUnitTest :wear:assembleDebug :widgetprobe:assembleDebug`. If Maven Central reports a missing dependency, rerun with `--refresh-dependencies`.
- `.github/workflows/build-apks.yml` builds the phone and Watch APKs, manual run only. It costs GitHub Actions minutes, so run it only when Jeff needs APKs.

## Needs Jeff's devices
The cloud has no adb and no Bluetooth. These are hands-on tests for Jeff: phone and Watch installs, Ring Bluetooth reads, hosting Samsung's battery widget, and the button-by-button check.
