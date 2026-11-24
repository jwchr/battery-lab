"""Synthetic fixtures for the Mac report tools. No phone is needed."""
import contextlib
import importlib.util
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


TOOLS = Path(__file__).resolve().parent


class DashboardRegressionTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("dashboard_test", TOOLS / "mac_dashboard.py")
        self.dashboard = importlib.util.module_from_spec(spec)
        with mock.patch.object(sys, "argv", ["dashboard"]):
            spec.loader.exec_module(self.dashboard)
        self.addCleanup(shutil.rmtree, self.dashboard.WORK)
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        path = Path(self.folder.name) / "fixture ?#%.db"
        with contextlib.closing(sqlite3.connect(path)) as db:
            db.execute("CREATE TABLE forecasts(status, start_pct, lab_rate_pp_per_hr, "
                       "actual_4h_pct, android_hours_to_empty)")
            db.executemany("INSERT INTO forecasts VALUES (?, ?, ?, ?, ?)", [
                ("depleted", 20, 10.0, 0, 2.0),
                ("positive", 80, 10.0, 35, 8.0),
                ("no_android", 20, 10.0, 0, 0),
            ])
            db.commit()
        self.dashboard.FIXED_DB = str(path)

    def test_clamped_errors_and_special_path(self):
        page = self.dashboard.page()
        for status, lab, android in [("depleted", "0.0", "0.0"),
                                      ("positive", "5.0", "5.0"),
                                      ("no_android", "0.0", "")]:
            self.assertIn(f"<td>{status}</td><td>1</td><td>{lab}</td><td>{android}</td>", page)

    def test_connection_closes_on_success_and_exception(self):
        connect = sqlite3.connect
        connections = []

        def capture(*args, **kwargs):
            db = connect(*args, **kwargs)
            connections.append(db)
            return db

        with mock.patch.object(self.dashboard.sqlite3, "connect", side_effect=capture):
            self.dashboard.page()
            with mock.patch.object(self.dashboard, "q", side_effect=RuntimeError("fixture failure")):
                with self.assertRaisesRegex(RuntimeError, "fixture failure"):
                    self.dashboard.page()
        self.assertEqual(2, len(connections))
        for db in connections:
            with self.assertRaises(sqlite3.ProgrammingError):
                db.execute("SELECT 1")


class SoakReportRegressionTest(unittest.TestCase):
    def test_wal_and_empty_foreground_history(self):
        sqlite_cli = shutil.which("sqlite3")
        self.assertIsNotNone(sqlite_cli, "sqlite3 CLI is required for this check")
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = root / "fixture.db"
            db = sqlite3.connect(path)
            self.addCleanup(db.close)
            db.executescript("""
                CREATE TABLE readings(id INTEGER, device TEXT, time_ms INTEGER,
                                      percent INTEGER, charging INTEGER, source TEXT);
                CREATE TABLE probe_events(time_ms INTEGER);
                CREATE TABLE experiments(id INTEGER);
            """)
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("PRAGMA wal_autocheckpoint=0")
            db.execute("INSERT INTO readings VALUES (1, 'test', 1000, 20, 0, 'test_source')")
            db.commit()
            self.assertGreater(Path(str(path) + "-wal").stat().st_size, 0)
            adb = root / "adb"
            adb.write_text("#!" + sys.executable + "\n" + '''
import os
from pathlib import Path
import sys
args = sys.argv[1:]
if args == ["get-state"]:
    sys.exit(0)
if args[0] == "exec-out":
    suffix = args[-1].split("battery_lab.db", 1)[1]
    mode = os.environ["SIDECAR_MODE"]
    if suffix and mode != "valid":
        if mode == "error":
            print("cat: unavailable")
        if mode == "partial":
            sys.stdout.buffer.write(b"partial")
        if mode == "other_error":
            print("/system/bin/sh: denied")
        sys.exit(1 if mode in ("missing", "partial") else 0)
    sys.stdout.buffer.write(Path(os.environ["FIXTURE_DB"] + suffix).read_bytes())
elif args[1:3] == ["dumpsys", "usagestats"]:
    print(os.environ["USAGE_TEXT"])
''')
            adb.chmod(0o755)
            sqlite_wrapper = root / "sqlite3"
            sqlite_wrapper.write_text("#!" + sys.executable + "\n" + '''
import os
from pathlib import Path
import sys
path = sys.argv[1]
if os.environ["SIDECAR_MODE"] != "valid":
    assert not Path(path + "-wal").exists()
# Never a pulled -shm: sqlite3 rebuilds it from the -wal that was pulled.
assert not Path(path + "-shm").exists()
os.execv(os.environ["REAL_SQLITE"], [os.environ["REAL_SQLITE"], *sys.argv[1:]])
''')
            sqlite_wrapper.chmod(0o755)
            for mode in ("valid", "missing", "empty", "error", "other_error", "partial"):
                for usage in ("", "package=com.batterylab.personal type=ACTIVITY_PAUSED",
                              "package=com.batterylab.personal type=ACTIVITY_RESUMED"):
                    with self.subTest(sidecars=mode, usage=usage):
                        env = dict(os.environ, ADB=str(adb), FIXTURE_DB=str(path),
                                   SIDECAR_MODE=mode, USAGE_TEXT=usage, REAL_SQLITE=sqlite_cli,
                                   PATH=str(root) + os.pathsep + os.environ["PATH"])
                        result = subprocess.run(["bash", str(TOOLS / "soak_report.sh")],
                                                env=env, capture_output=True, text=True, timeout=20)
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertIn("App foreground starts logged: 0", result.stdout)
                        if mode == "valid":
                            self.assertRegex(result.stdout, re.compile(r"dev1\s+test_source\s+1\s+1"))


if __name__ == "__main__":
    unittest.main()
