# Battery Lab

## Shared rules
Jeff's standing rules for every chat (permission, reviews, PR limits, keeping the chat small, compaction at 60% instead of handoffs, SSOT) live only in `~/.claude/CLAUDE.md`, which Claude Code loads automatically. Do not copy them here; change them there.
This app's SSOT rows: W098, W099.

## Battery Lab specifics
- **Riskier reviews** (Codex): safety, security or finance sensitive, or complex changes. In this app that includes Bluetooth, backup and restore, and the database.
- **Merging:** Claude may merge its own PRs when it decides they are ready: reviewed first (CodeRabbit for regular changes, Codex for the riskier ones above; the full rule is in `~/.claude/CLAUDE.md`, and a session that cannot load it still must not merge unreviewed) with findings handled, and the quiet check passing (Jeff, Nov 18: "you have permission to merge on your own when you decide it's ready"). This repo has no automatic PR workflow (to save Actions minutes), so "green CI" means the quiet check below passes on the PR's head in a local or cloud build.
- **Never:** root the phone; modify, decompile or analyze Samsung's apps; use accessibility services; add the "trusted devices only" permission to Claude Code settings.
- **Ring Bluetooth:** connect to the Ring only when the user starts something that needs it (monitoring they turned on, an A/B test they started, a "connect now" button, an RF Lab session they started). Never on a background schedule by default. Listening for the Ring's broadcasts is fine; RF Lab scans only inside a session the user starts.
- **Nothing identifying** in commits, issues or PRs: no serials, Bluetooth addresses, device names or raw readings. Use counts and trends.
- Keep the Ring protocol code and the A/B features.
- **Phone installs** (Jeff does them): `install -r` only, after backing up `databases/battery_lab.db`, `-wal` and `-shm` with `run-as`. Never uninstall, never install a build signed with another key (both wipe the history), never force-stop the app. Mac adb notes: `docs/MAC.md`.

## Build and test (Linux cloud)
- Modules: `app` (phone), `rf` (RF Lab core, plain Kotlin, no Android), `wear` (Watch), `widgetprobe` (widget test tool). compileSdk 35, Java 17 target, Gradle 8.9 in CI.
- SDK setup (cloud environment "battery-lab" allows dl.google.com): install the command-line tools into `$HOME/android-sdk`, then `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`.
- Quiet check: `gradle --no-daemon -q :rf:test :app:assembleDebug :app:testDebugUnitTest :wear:assembleDebug :widgetprobe:assembleDebug`. If Maven Central reports a missing dependency, rerun with `--refresh-dependencies`.
- `.github/workflows/build-apks.yml` builds the phone and Watch APKs, manual run only. It costs GitHub Actions minutes, so run it only when Jeff needs APKs.

## Needs Jeff's devices
The cloud has no adb and no Bluetooth. These are hands-on tests for Jeff: phone and Watch installs, Ring Bluetooth reads, hosting Samsung's battery widget, and the button-by-button check.


<!-- BEGIN PROJECT ENGINEERING -->
Read `.engineering/POLICY.md` before material work. Pinned policy SHA256:
`7adf68dd0645913d8be76ef7508639a2b69c81b9d4bd326637226b46946ad5a9`.
This snapshot is a required distribution of Engineering Standards, not a second
maintained policy. Preserve all existing owner/reviewer/release and spending rules.
Record observable acceptance, failure analysis and exact-source evidence; run the
existing project suites plus `python3 .engineering/check_policy.py`. A policy check
only verifies distribution integrity, not tests, independent inspection, device
truth or release approval. Never merge or deploy merely because it passes.
<!-- END PROJECT ENGINEERING -->
