#!/usr/bin/env bash
# Soak-test readout for Battery Lab (debug build only: uses `run-as`).
#
# Prints counts, timing gaps and percent CHANGES only. It never prints device
# labels, serials or raw battery percentages, so the output is safe to paste
# into an issue.
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
  "$ADB" exec-out run-as "$PKG" cat databases/battery_lab.db >"$DB"
fi

# App foreground times (epoch ms) from usagestats, if the phone is attached.
: >"$WORK/fg.txt"
if $have_phone; then
  "$ADB" shell dumpsys usagestats 2>/dev/null \
    | grep "package=$PKG" | grep 'type=ACTIVITY_RESUMED' \
    | grep -oE 'time="[^"]+"' | cut -d'"' -f2 | sort -u \
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
