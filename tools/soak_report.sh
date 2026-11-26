#!/usr/bin/env bash
# Soak-test readout for Battery Lab (debug build only: uses `run-as`).
#
# Prints counts, timing gaps and percent CHANGES only. It never prints device
# labels, serials or raw battery percentages, so the output is safe to paste
# into an issue. The last section (fuel gauge) prints the phone's current level,
# voltage and temperature from a fixed list of dumpsys keys; trim it before
# pasting if you'd rather not share those.
#
# Usage:
#   tools/soak_report.sh            # pull the database from the phone over adb
#   tools/soak_report.sh path.db    # report on an existing copy (no phone needed)
#
# Each widget reading is tagged "fg" when it falls within 90 s after the app
# came to the foreground (from usagestats), otherwise "bg" (background job).
set -euo pipefail

PKG=com.batterylab.personal
ADB=${ADB:-$(command -v adb || echo /opt/homebrew/bin/adb)}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

have_phone=false
if "$ADB" get-state >/dev/null 2>&1; then have_phone=true; fi

if [[ $# -ge 1 ]]; then
  DB=$1
else
  $have_phone || { echo "No phone over adb and no database path given." >&2; exit 1; }
  DB=$WORK/battery_lab.db
  # Recent readings may sit in the -wal file. Pull only that: a -shm pulled a moment later could
  # describe frames this -wal doesn't have, so sqlite3 rebuilds the index from the -wal instead.
  # Keep the -wal only if it starts with SQLite's WAL magic, so an error message is never used.
  # A checkpoint between the two copies would pair an old database with a newer -wal and silently
  # drop readings, so the database is copied again afterwards and the pair is used only if it
  # did not change. A -wal the phone has but that didn't copy is retried, never quietly skipped.
  wal_on_phone() {
    "$ADB" shell "run-as $PKG sh -c 'test -s databases/battery_lab.db-wal && echo present || echo absent'" \
      2>/dev/null | tr -d '\r'
  }
  pulled=false
  for attempt in 1 2 3; do
    "$ADB" exec-out run-as "$PKG" cat databases/battery_lab.db >"$DB" \
      || { echo "Couldn't copy the database from the phone." >&2; exit 1; }
    rm -f "$DB-wal" "$DB-shm"
    wal_ok=true
    if ! "$ADB" exec-out run-as "$PKG" cat databases/battery_lab.db-wal >"$DB-wal" \
      || ! head -c 4 "$DB-wal" | od -An -tx1 | tr -d ' \n' | grep -qE '^377f068[23]$'; then
      rm -f "$DB-wal"
      [[ $(wal_on_phone) == absent ]] || wal_ok=false
    fi
    "$ADB" exec-out run-as "$PKG" cat databases/battery_lab.db >"$WORK/recheck.db" || wal_ok=false
    if $wal_ok && cmp -s "$DB" "$WORK/recheck.db"; then pulled=true; break; fi
    sleep 1
  done
  rm -f "$WORK/recheck.db"
  $pulled || { echo "Couldn't get a matching copy of the database and its -wal file after 3 tries" \
    "(the app may be saving a lot right now). Try again in a minute." >&2; exit 1; }
fi

# App foreground times (epoch ms) from usagestats, if the phone is attached.
: >"$WORK/fg.txt"
if $have_phone; then
  "$ADB" shell dumpsys usagestats 2>/dev/null \
    | { grep "package=$PKG" || true; } | { grep 'type=ACTIVITY_RESUMED' || true; } \
    | { grep -oE 'time="[^"]+"' || true; } | cut -d'"' -f2 | sort -u \
    | while read -r t; do
        # Phone and Mac are assumed to share a timezone.
        echo $(( $(date -j -f '%Y-%m-%d %H:%M:%S' "$t" +%s) * 1000 ))
      done >"$WORK/fg.txt"
fi

sqlite3 "$DB" <<SQL
CREATE TEMP TABLE fg(t INTEGER);
.mode csv
.import '$WORK/fg.txt' fg
.mode column
.headers on
CREATE TEMP VIEW d AS
  SELECT device,
    CASE WHEN lower(device) LIKE '%ring%'  THEN 'ring'
         WHEN lower(device) LIKE '%watch%' THEN 'watch'
         WHEN lower(device) LIKE '%phone%' THEN 'phone'
         ELSE 'dev' || ROW_NUMBER() OVER (ORDER BY MIN(id)) END AS kind
  FROM readings GROUP BY device;
CREATE TEMP VIEW r AS
  SELECT d.kind, x.*,
    CASE WHEN EXISTS (SELECT 1 FROM fg WHERE x.time_ms BETWEEN fg.t - 5000 AND fg.t + 90000)
         THEN 'fg' ELSE 'bg' END AS how
  FROM readings x JOIN d USING (device);

.print '== Readings by device and source =='
SELECT kind, source, COUNT(*) AS n,
  SUM(how = 'bg') AS bg_n,
  datetime(MIN(time_ms)/1000, 'unixepoch', 'localtime') AS first,
  datetime(MAX(time_ms)/1000, 'unixepoch', 'localtime') AS last,
  MAX(percent) - MIN(percent) AS pct_range
FROM r GROUP BY kind, source ORDER BY kind, source;

.print ''
.print '== Samsung widget timeline (change vs first reading) =='
SELECT kind, strftime('%H:%M:%S', time_ms/1000, 'unixepoch', 'localtime') AS t, how,
  percent - FIRST_VALUE(percent) OVER (PARTITION BY kind ORDER BY time_ms) AS d_pct,
  percent - LAG(percent) OVER (PARTITION BY kind ORDER BY time_ms) AS d_prev,
  charging,
  ROUND((time_ms - LAG(time_ms) OVER (PARTITION BY kind ORDER BY time_ms)) / 60000.0, 1) AS gap_min
FROM r WHERE source = 'samsung_widget' ORDER BY kind, time_ms;

.print ''
.print '== Background widget reads: did the value move? =='
WITH w AS (
  SELECT kind, how, percent - LAG(percent) OVER (PARTITION BY kind ORDER BY time_ms) AS d_prev
  FROM r WHERE source = 'samsung_widget')
SELECT kind, COUNT(*) AS bg_reads, SUM(d_prev <> 0) AS changed_vs_prev
FROM w WHERE how = 'bg' GROUP BY kind;

.print ''
.print '== Wearable charging flag vs percent movement (audit #4 A2) =='
.print 'Pairs of consecutive widget readings at most 40 min apart, grouped by the charging'
.print 'flag of the earlier reading. If the bolt means charging, flagged pairs should rise or'
.print 'hold at 100, and unflagged pairs should almost never rise.'
WITH p AS (
  SELECT kind, charging AS flag, percent,
    LEAD(percent) OVER (PARTITION BY kind ORDER BY time_ms) - percent AS d,
    LEAD(time_ms) OVER (PARTITION BY kind ORDER BY time_ms) - time_ms AS gap
  FROM r WHERE source = 'samsung_widget' AND kind IN ('ring', 'watch'))
SELECT kind, CASE flag WHEN 1 THEN 'charging' WHEN 0 THEN 'not' ELSE 'unknown' END AS flagged,
  COUNT(*) AS pairs, SUM(d > 0) AS rose, SUM(d = 0 AND percent = 100) AS held_100,
  SUM(d = 0 AND percent < 100) AS flat, SUM(d < 0) AS fell
FROM p WHERE d IS NOT NULL AND gap <= 40 * 60000
GROUP BY kind, flag ORDER BY kind, flag;

.print ''
.print '== probe_events (errors) =='
SELECT COUNT(*) AS n,
  datetime(MIN(time_ms)/1000, 'unixepoch', 'localtime') AS first,
  datetime(MAX(time_ms)/1000, 'unixepoch', 'localtime') AS last
FROM probe_events;
SELECT COUNT(*) AS experiments FROM experiments;
SQL

if $have_phone; then
  echo
  echo "== Job scheduler =="
  "$ADB" shell dumpsys jobscheduler "$PKG" 2>/dev/null \
    | awk -v pkg="$PKG/" '/JOB #/ { p = index($0, pkg) > 0 } p' \
    | grep -E 'JOB #|PERIODIC:|Last successful run|Ready:' | sed 's/^ *//' || true
  echo "App foreground starts logged: $(wc -l <"$WORK/fg.txt" | tr -d ' ')"
fi

if $have_phone; then
  echo
  echo "== Samsung kernel fuel gauge (dumpsys battery, selected keys only) =="
  # Only these keys are printed, so nothing identifying (serials, addresses) can appear.
  "$ADB" shell dumpsys battery 2>/dev/null \
    | grep -E '^ *(level|scale|voltage|temperature|status|Charge counter|mSavedBatteryAsoc|mSavedBatteryUsage):' \
    | sed 's/^ *//' || true
fi
