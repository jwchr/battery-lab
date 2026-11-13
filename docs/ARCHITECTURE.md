# Battery data architecture (Nov 2026)

Test hardware: Galaxy S26 Ultra (Android 17 / SDK 37, One UI 9.0), Galaxy Watch8, Galaxy Ring.

## Recommendation

| Priority | Source | Radio cost | Status |
|---|---|---|---|
| 1 | **Samsung Battery widget, hosted by Battery Lab** (`SamsungWidgetSource`) | None: reads what Samsung already displays | Verified readable on device; background reading needs a soak test |
| 2 | Android `BatteryManager` for the phone itself | None | Existing, unchanged |
| 3 | Manual entry from Samsung's widget | None | Existing, unchanged |
| 4 | Direct Ring BLE battery request (SamsungOpenRing RFC-SGR-001) | Wakes the Ring once per request | Existing `RingReader`, **off by default**; fallback and the "monitoring" arm of A/B tests only |
| 5 | Wear OS companion over the Data Layer | Watch app runs about every 30 min | Existing; `wear` module currently fails to compile (pre-existing, see below) |

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
- **Background reads are unverified.** They use `AppWidgetHost.createView`, which returns
  the latest views Android cached for our widget id, inflated offscreen. Whether Samsung keeps
  updating a widget whose host is not listening still needs an on-device soak test.
- **Charging is inferred** from the presence of a second icon in the slot. This was validated
  for the phone slot only.
- **Samsung can change the widget layout in an update.** Parsing keys on the
  `arc_progress` / `list_progress_fg` ids and numeric text. If those disappear, the source
  logs a probe event and records nothing; it does not record guesses.

## A/B Ring-overhead experiment
Start and finish readings now come from the widget first, then a manual entry less than
2 minutes old, and only then a BLE request. So the **baseline** arm causes zero extra Ring
wakeups, and the widget gives continuous baseline samples between the endpoints. The
**monitoring** arm still turns on the 15-minute BLE request, and requires choosing the paired
Ring. Comparing the two arms gives a measurable estimate of the BLE fallback's battery cost.
A sensible fallback cadence is decided from that data, not assumed.

## Pre-existing issue
`wear/src/main/java/com/batterylab/personal/WatchActivity.kt:29` reassigns a `val`, so the
`wear` module does not compile on `main`. It is unrelated to this change and not fixed here.
