# Working rules for Claude in this repo

The owner is Jeff. He is not a developer: write to him in short, plain American English, no em dashes, times in Eastern.

## Permission (Jeff, 2026-11-18, all projects, permanent)

Don't ask Jeff for permission. Ask only when an action is safety, security or financially sensitive; for anything else, decide and act. Jeff: "Make this rule global and persistent, forever."

## Reviews (Jeff's standing rule, restated 2026-11-18)

1. **CodeRabbit** gets every review, unless the change is safety, security or finance sensitive, or complex.
2. Those go to **Codex** (comment `@codex review` on the PR).
3. If Codex is unavailable, **Argon (Gemini) via API**.
4. If the Argon API is unavailable, Jeff gives it to Argon **manually**: hand him a `Review-<PR>-for-Gemini.txt` file and keep working.

As of 2026-11-18 the Argon API is out of usage, so step 3 falls through to step 4.

## Merging and devices

- Claude may merge its own PRs when it decides they are ready: reviewed per the rule above with findings handled, and build and unit tests passing (Jeff, 2026-11-18: "you have permission to merge on your own when you decide it's ready").
- Up to 3 open PRs at a time.
- Anything that needs Jeff's phone, Watch or Ring is a hands-on test for him: ask in one plain line and keep working.
- Phone, install and Bluetooth rules, and build commands: see `docs/HANDOFF.md`. The newest handoff note is the latest open "Handoff note" issue.

## Where to report

GitHub (PRs and the handoff issue) and the SSOT Tables spreadsheet: Battery Lab's rows in Active Work, plus one Change Log line per change.
