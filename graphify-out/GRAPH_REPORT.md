# Graph Report - SproControl  (2026-10-20)

## Corpus Check
- Corpus is ~46,785 words - fits in a single context window. You may not need a graph.

## Summary
- 231 nodes · 583 edges · 8 communities (7 shown, 1 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 3 edges (avg confidence: 0.82)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- App activity: link, bridge, lifecycle
- App GATT callbacks and updater
- Widget BLE session (K2Widget.Tap)
- Widget state in preferences
- Protocol encoder and decoder
- APK update install
- Android context plumbing

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 51 edges
2. `Tap` - 29 edges
3. `K2Widget` - 26 edges
4. `K2OneWidget` - 14 edges
5. `ApkProvider` - 9 edges
6. `LinkService` - 9 edges
7. `frame()` - 7 edges
8. `handshake()` - 7 edges
9. `set_time()` - 5 edges
10. `preset_temperature()` - 4 edges

## Surprising Connections (you probably didn't know these)
- `K2OneWidget` --inherits--> `android.appwidget.AppWidgetProvider`  [EXTRACTED]
  android/src/com/sprocontrol/k2/K2OneWidget.java →   _Bridges community 6 → community 0_
- `K2Widget` --inherits--> `android.appwidget.AppWidgetProvider`  [EXTRACTED]
  android/src/com/sprocontrol/k2/K2Widget.java →   _Bridges community 3 → community 0_
- `Tap` --references--> `android.content.Context`  [EXTRACTED]
  android/src/com/sprocontrol/k2/K2Widget.java →   _Bridges community 2 → community 6_
- `Tap` --references--> `org.json.JSONObject`  [EXTRACTED]
  android/src/com/sprocontrol/k2/K2Widget.java →   _Bridges community 2 → community 3_
- `MainActivity` --references--> `android.os.Handler`  [EXTRACTED]
  android/src/com/sprocontrol/k2/MainActivity.java →   _Bridges community 1 → community 0_

## Import Cycles
- None detected.

## Communities (8 total, 1 thin omitted)

### Community 0 - "App activity: link, bridge, lifecycle"
Cohesion: 0.05
Nodes (47): alarmmanager, android.app.PendingIntent, android.app.Service, android.appwidget.AppWidgetManager, android.appwidget.AppWidgetProvider, android.content.Intent, android.os.Handler, android.os.IBinder (+39 more)

### Community 1 - "App GATT callbacks and updater"
Cohesion: 0.08
Nodes (14): android.app.Activity, android.os.Bundle, BluetoothAdapter, BluetoothDevice, BluetoothAdapter, BluetoothDevice, BluetoothGatt, BluetoothGattCallback (+6 more)

### Community 2 - "Widget BLE session (K2Widget.Tap)"
Cohesion: 0.18
Nodes (8): BluetoothGatt, BluetoothGattCharacteristic, Override, SuppressWarnings, Tap, BluetoothGattCallback, BluetoothGattDescriptor, PendingResult

### Community 3 - "Widget state in preferences"
Cohesion: 0.14
Nodes (4): android.content.SharedPreferences, K2Widget, android.webkit.JavascriptInterface, org.json.JSONObject

### Community 4 - "Protocol encoder and decoder"
Cohesion: 0.15
Nodes (21): datetime, c2f(), decode(), f2c(), frame(), handshake(), preset_temperature(), query() (+13 more)

### Community 5 - "APK update install"
Cohesion: 0.24
Nodes (9): android.content.ContentProvider, android.content.ContentValues, android.database.Cursor, android.net.Uri, android.os.ParcelFileDescriptor, ApkProvider, Override, file (+1 more)

### Community 6 - "Android context plumbing"
Cohesion: 0.35
Nodes (3): android.content.Context, Override, K2OneWidget

## Knowledge Gaps
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `App GATT callbacks and updater` to `App activity: link, bridge, lifecycle`, `Widget state in preferences`?**
  _High betweenness centrality (0.227) - this node is a cross-community bridge._
- **Why does `Tap` connect `Widget BLE session (K2Widget.Tap)` to `Widget state in preferences`, `Android context plumbing`?**
  _High betweenness centrality (0.117) - this node is a cross-community bridge._
- **Why does `K2Widget` connect `Widget state in preferences` to `App activity: link, bridge, lifecycle`, `App GATT callbacks and updater`, `Widget BLE session (K2Widget.Tap)`, `Android context plumbing`?**
  _High betweenness centrality (0.075) - this node is a cross-community bridge._
- **Should `App activity: link, bridge, lifecycle` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
- **Should `App GATT callbacks and updater` be split into smaller, more focused modules?**
  _Cohesion score 0.08350951374207188 - nodes in this community are weakly interconnected._
- **Should `Widget state in preferences` be split into smaller, more focused modules?**
  _Cohesion score 0.14204545454545456 - nodes in this community are weakly interconnected._