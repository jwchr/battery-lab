# Battery data architecture (Nov 2026)

Test hardware: Galaxy S26 Ultra (Android 17 / SDK 37, One UI 9.0), Galaxy Watch8, Galaxy Ring.

## Recommendation

| Priority | Source | Radio cost | Status |
|---|---|---|---|
| 1 | **Samsung Battery widget, hosted by Battery Lab** (`SamsungWidgetSource`) | None: reads what Samsung already displays | Verified on device, including background reads (soak test below); needs battery usage set to Unrestricted |
| 2 | Android `BatteryManager` for the phone itself | None | Existing, unchanged |
| 3 | Manual entry from Samsung's widget | None | Existing, unchanged |
| 4 | Direct Ring BLE battery request (SamsungOpenRing RFC-SGR-001) | Wakes the Ring once per request | Existing `RingReader`, **off by default**; fallback and the "monitoring" arm of A/B tests only |
| 5 | Wear OS companion over the Data Layer | Watch app runs about every 30 min | Existing; compiles again (the `val` reassignment in `WatchActivity` is fixed) |

The background job (`BatteryPollService`, about every 15 minutes, opportunistic) records the
phone battery, then reads the hosted widget offscreen. It only does a Ring BLE request if the
user has explicitly turned Ring monitoring on.

## Evidence

### Standard Bluetooth broadcasts: negative
The v0.2 passive diagnostic, installed on the S26 Ultra with Nearby Devices granted, recorded
**zero** `BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED` events. That held across Ring and Watch
discharge, recharge and reconnect, while Samsung's widget showed both percentages.
Background delivery of that receiver was never independently validated, but even the
foreground receiver saw nothing.

### Samsung's widget and providers: established from Android's own package reports
Source: `dumpsys appwidget` and `dumpsys package` on the phone (read-only).

- The Battery widget is `com.android.settings.intelligence`: `GridTypeBatteryWidget` and
  `ListTypeBatteryWidget`. It is a Jetpack Glance widget (`glance_view_id_*` views).
- Settings Intelligence receives updates through its own `BatteryWidgetReceiver`
  (`…intelligence.ACTION_BATTERY_INFO_ADDED/CHANGED/REMOVED`).
- The Watch plugin (`com.samsung.wearable.watchuniteplugin`) has providers named
  `BatteryInfoProvider`, `STFBatteryDBProvider` and `WearableInfoProvider`. The Ring plugin
  (`com.samsung.android.ringplugin`) has `RingContentProvider`.
- The battery-related permissions these plugins declare are all `signature|privileged`:
  `companionservice.BATTERY_INFO_PROVIDER`, `WEARABLE_INFO_PROVIDER` and
  `stfinder.READ_BATTERY/WRITE_BATTERY`. A third-party app cannot hold them. The plugins
  also hold Samsung's `SEM_BATTERY_INFO(_PROVIDER)`.

### Not established
- **Which permission guards each provider, and whether each is exported.** It was not
  verified. The `signature|privileged` declarations make third-party access unlikely, but
  that is an inference.
- **Whether `ACTION_BATTERY_INFO_*` broadcasts reach other apps.** They may be explicit,
  or permission-protected. A passive runtime receiver could test this with no wearable
  traffic.
- **Public Samsung SDKs** that might report wearable battery: not researched.
- Decompiling Samsung's APKs was stopped during this investigation and was not pursued.
  None of the conclusions here rely on it.

### Widget hosting: positive (probe `widgetprobe`, 2026-11-13)
The probe hosted both widgets with the public `AppWidgetHost` API, after the user approved
Android's `ACTION_APPWIDGET_BIND` dialog.

- No configure activity was required, and the widget was not restricted to the Samsung launcher.
- The Grid widget showed four slots: `ProgressBar#arc_progress` plus a `TextView` with the
  plain number (`94`, `31`, `79`, and one empty slot).
- The List widget showed the same values as `TextView` `"94%"` beside `ProgressBar#list_progress_fg`.
- Slot 1 tracked the phone: 94 → 95 while Android reported 96% and charging. It was the only
  slot with an extra icon (the charging bolt).
- Samsung pushed a new view to the probe when a wearable changed (79 → 78) about 30 seconds
  later, with no request from Battery Lab.
- **Device names are not exposed.** The name `TextView`s are empty and the icons have no
  content description. Slots are therefore identified by a one-time user label, keyed to an
  icon-shape fingerprint, so a reordered widget still maps correctly.

## Known limits of the widget source
- **Freshness is Samsung's.** The stored timestamp is when Battery Lab observed the widget,
  not when the wearable reported. When a wearable disconnects, the widget may keep showing
  its last value.
- **Background reads need the app exempt from battery optimization.** See the soak test below.
  The app shows a warning with a shortcut to App info when it is not exempt.
- **Charging is inferred** from the presence of a second icon in the slot. This was validated
  for the phone slot only.
- **Samsung can change the widget layout in an update.** Parsing keys on the
  `arc_progress` / `list_progress_fg` ids and numeric text. If those disappear, the source
  logs a probe event and records nothing; it does not record guesses.

## Soak test: background reads (2026-11-13 to 14)
Background reads use `AppWidgetHost.createView`, which inflates offscreen the latest views
Android cached for our widget id. Samsung keeps that cache current even when no host is
listening, so reads made while Battery Lab is closed return fresh values.
Results are summarized with `tools/soak_report.sh`, which reports counts and changes only.

- **Default battery setting:** reads worked for about 2.5 hours with the app closed.
  - Adaptive Battery then moved the app to the FREQUENT standby bucket, and later to RARE.
  - In RARE, JobScheduler allows 3 sessions per 24 hours. The poll stopped for about 5 hours
    with only the quota constraint unsatisfied.
  - Each run takes under a second, so the limiting factor is the number of runs, not the
    runtime.
- **Battery usage set to Unrestricted** (standby bucket 5, exempted), over about 14 hours:
  - The poll ran 56 times. The gaps averaged 15.5 minutes, and the longest was 37 minutes.
  - The Ring and Watch were read on almost every run.
  - One Ring dropout lasted a single run and was logged. No read failed.
  - Most background reads showed a new value, so they are fresh, not cached.
- **Diagnostics** (written to `probe_events`):
  - Standby bucket changes.
  - A labeled Ring or Watch that is missing from the widget.
  - Changes in which slots are unlabeled.

## A/B Ring-overhead experiment
Start and finish readings now come from the widget first, then a manual entry less than
2 minutes old, and only then a BLE request. So the **baseline** arm causes zero extra Ring
wakeups, and the widget gives continuous baseline samples between the endpoints. The
**monitoring** arm still turns on the 15-minute BLE request, and requires choosing the paired
Ring. Comparing the two arms gives a measurable estimate of the BLE fallback's battery cost.
A sensible fallback cadence is decided from that data, not assumed.
