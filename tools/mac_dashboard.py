#!/usr/bin/env python3
"""Battery Lab dashboard for the Mac.

Pulls the app's database from the phone over adb (debug build: uses run-as) and serves a
read-only summary page at http://127.0.0.1:8765. Standard library only. Nothing leaves this
Mac: the server listens on localhost, and the page loads no outside scripts or fonts.

    python3 tools/mac_dashboard.py              # pull from the phone, then serve
    python3 tools/mac_dashboard.py path/to.db   # serve an existing copy (no phone needed)

The page is refreshed from the phone each time you press "Pull again". It shows counts, recent
levels, forecasts, journal and capability status. From dumpsys it shows only a fixed list of
battery keys and the batterystats summary lines (never the per-app list).
"""
import contextlib
import html
import http.server
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.parse

PKG = "com.batterylab.personal"
HOST, PORT = "127.0.0.1", 8765
ADB = os.environ.get("ADB") or shutil.which("adb") or "/opt/homebrew/bin/adb"
WORK = tempfile.mkdtemp(prefix="battery-lab-dash-")
FIXED_DB = sys.argv[1] if len(sys.argv) > 1 else None

BATTERY_KEYS = re.compile(r"^\s*(level|scale|voltage|temperature|status|Charge counter|"
                          r"mSavedBatteryAsoc|mSavedBatteryUsage):")
STATS_KEYS = re.compile(r"^\s*(Time on battery|Time on battery screen off|Total run time|"
                        r"Discharge|Screen off discharge|Screen on discharge|Capacity:|Computed drain|"
                        r"Start clock time)")


def adb(*args, binary=False):
    out = subprocess.run([ADB, *args], capture_output=True, timeout=60).stdout
    return out if binary else out.decode("utf-8", "replace")


def pull():
    """Copies battery_lab.db (+ -wal/-shm) into the temp folder; returns its path."""
    if FIXED_DB:
        return FIXED_DB
    for suffix in ("", "-wal", "-shm"):
        data = adb("exec-out", "run-as", PKG, "cat", f"databases/battery_lab.db{suffix}", binary=True)
        path = os.path.join(WORK, f"battery_lab.db{suffix}")
        if data and not data.startswith(b"run-as:") and not data.startswith(b"cat:"):
            with open(path, "wb") as f:
                f.write(data)
        elif os.path.exists(path):
            os.remove(path)
    return os.path.join(WORK, "battery_lab.db")


def q(db, sql, args=()):
    try:
        return db.execute(sql, args).fetchall()
    except sqlite3.Error:
        return []


def table(headers, rows):
    head = "".join(f"<th>{html.escape(str(h))}</th>" for h in headers)
    body = "".join("<tr>" + "".join(f"<td>{html.escape('' if c is None else str(c))}</td>" for c in r) + "</tr>"
                   for r in rows)
    return f"<table><tr>{head}</tr>{body or '<tr><td colspan=99>none</td></tr>'}</table>"


def sparkline(points, color):
    """Inline SVG of percent over the last 48 hours."""
    if not points:
        return "<p class=quiet>no readings</p>"
    end = time.time() * 1000
    start = end - 48 * 3600_000
    w, h = 640, 120
    pts = " ".join(f"{(t - start) / (end - start) * w:.1f},{h - p / 100 * h:.1f}" for t, p in points if t >= start)
    return (f'<svg viewBox="0 0 {w} {h}" class=spark><polyline fill=none stroke="{color}" '
            f'stroke-width=2 points="{pts}"/></svg>')


def page():
    path = pull()
    if not os.path.exists(path):
        return "<p>No database. Is the phone connected with USB debugging on and a debug build installed?</p>"
    with contextlib.closing(sqlite3.connect(
            f"file:{urllib.parse.quote(os.path.abspath(path))}?mode=ro", uri=True)) as db:
        now = time.time() * 1000
        parts = []
        version = q(db, "PRAGMA user_version")
        parts.append(f"<p class=quiet>Schema v{version[0][0] if version else '?'} · pulled {time.strftime('%a %H:%M:%S')}</p>")

        devices = q(db, """SELECT device, source, COUNT(*), MAX(time_ms) FROM readings
                           GROUP BY device, source ORDER BY device, source""")
        parts.append("<h2>Readings</h2>" + table(["device", "source", "rows", "latest"],
            [(d, s, n, time.strftime("%a %H:%M", time.localtime(t / 1000))) for d, s, n, t in devices]))

        lines = [("Phone", "device = 'phone'", "#5b8fd6"),
                 ("Watch", "device LIKE 'watch:%'", "#74a658"),
                 ("Ring", "device LIKE 'ring:%'", "#e9a23b")]
        parts.append("<h2>Last 48 hours</h2>")
        for key, where, color in lines:
            rows = q(db, f"""SELECT time_ms, percent FROM readings WHERE {where} AND time_ms >= ?
                             ORDER BY time_ms""", (now - 48 * 3600_000,))
            parts.append(f"<h3>{key}</h3>" + sparkline(rows, color))

        parts.append("<h2>Prediction report card</h2>" + table(
            ["status", "forecasts", "mean |lab error| 4h", "mean |Android error| 4h"],
            q(db, """SELECT status, COUNT(*),
                       ROUND(AVG(ABS(MAX(0.0, start_pct - lab_rate_pp_per_hr * 4) - actual_4h_pct)), 1),
                       ROUND(AVG(CASE WHEN android_hours_to_empty > 0 THEN ABS(MAX(0.0, start_pct - start_pct / android_hours_to_empty * 4) - actual_4h_pct) END), 1)
                     FROM forecasts GROUP BY status""")))

        parts.append("<h2>Journal</h2>" + table(["when", "category", "title"],
            [(time.strftime("%a %d %b %H:%M", time.localtime(t / 1000)), c, ti)
             for t, c, ti in q(db, "SELECT time_ms, category, title FROM context_journal ORDER BY time_ms DESC LIMIT 30")]))

        parts.append("<h2>Capabilities</h2>" + table(["device", "field", "status", "units", "step"],
            q(db, "SELECT device_kind, field_key, status, units, observed_min_step FROM capability_probes ORDER BY device_kind, field_key")))

        parts.append("<h2>Experiments</h2>" + table(["mode", "start", "start %", "end %", "hours"],
            [(m, time.strftime("%a %d %b %H:%M", time.localtime(s / 1000)), sp, ep,
              None if e is None else round((e - s) / 3600_000, 1))
             for m, s, sp, e, ep in q(db, "SELECT mode, start_ms, start_pct, end_ms, end_pct FROM experiments ORDER BY start_ms DESC LIMIT 20")]))

    if not FIXED_DB:
        battery = [l.strip() for l in adb("shell", "dumpsys", "battery").splitlines() if BATTERY_KEYS.match(l)]
        stats = [l.strip() for l in adb("shell", "dumpsys", "batterystats", "--charged").splitlines() if STATS_KEYS.match(l)]
        parts.append("<h2>Fuel gauge (dumpsys battery)</h2><pre>" + html.escape("\n".join(battery) or "unavailable") + "</pre>")
        parts.append("<h2>Since last full charge (batterystats summary)</h2><pre>" +
                     html.escape("\n".join(dict.fromkeys(stats)) or "unavailable") + "</pre>")
    return "\n".join(parts)


STYLE = """body{font:14px -apple-system,system-ui,sans-serif;max-width:760px;margin:24px auto;padding:0 16px;
color:#1e3550;background:#fbf8f2}h1{font-size:26px}h2{font-size:16px;margin-top:28px}h3{font-size:13px;margin:10px 0 2px}
table{border-collapse:collapse;width:100%;font-size:12.5px}td,th{border-bottom:1px solid #eae4da;padding:4px 6px;text-align:left}
.quiet{color:#7c8592}.spark{width:100%;height:90px;background:#fff;border:1px solid #ece5d8;border-radius:8px}
pre{background:#fff;border:1px solid #ece5d8;border-radius:8px;padding:10px;white-space:pre-wrap}
a.btn{display:inline-block;padding:6px 12px;border-radius:14px;background:#24456b;color:#fff;text-decoration:none}"""


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/", "/index.html"):
            self.send_error(404)
            return
        try:
            content = page()
        except Exception as e:  # keep serving if adb or the copy fails
            content = f"<p>Couldn't read the phone: {html.escape(str(e))}</p>"
        body = (f"<!doctype html><meta charset=utf-8><title>Battery Lab</title><style>{STYLE}</style>"
                f"<h1>Battery Lab</h1><a class=btn href='/'>Pull again</a>{content}").encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"Battery Lab dashboard on http://{HOST}:{PORT}  (Ctrl-C to stop)")
    try:
        http.server.HTTPServer((HOST, PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        shutil.rmtree(WORK, ignore_errors=True)
