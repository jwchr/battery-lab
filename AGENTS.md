# Battery Lab

## Review guidelines
When you review a plan, branch or pull request here, check it first against the rules in `CLAUDE.md` and cite the rule: Ring Bluetooth connects only when the user starts something that needs it (never on a background schedule; listening for broadcasts is fine), nothing identifying in commits, issues or PRs (no serials, Bluetooth addresses, device names or raw readings), no accessibility services, and nothing that touches Samsung's apps. Then check what loses or corrupts the battery history: a database migration or write interrupted partway, a backup that misses a table or the `-wal` file, a restore over newer data, a monitored or A/B test left open across a reboot or app kill, a Bluetooth link not closed on every path, and anything that would need an uninstall or a differently signed build. Rank findings P1 to P3 with file:line and a concrete trigger. Skip style.


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
