# HappyGo PCM BLE Protocol

How the HappyGo Android app (v1.0.6, `com.timeyaa.flutter_coffeecup2`) talks to the PCM portable coffee machine over Bluetooth LE: connecting, the handshake, heating and extraction control, and brew parameters.

The Python port is in [pcm_protocol.py](pcm_protocol.py). Run `python pcm_protocol.py` to execute its self-check.

> **Status:** recovered from the app binary, then verified against a live iKape K2 (`HI-T-1292G0_B526`) on 2026-10-13: every handshake frame, the status/temperature/work-parameter replies and all brew commands were confirmed byte-for-byte with the machine's own replies. Sections marked *live* carry what the machine actually did.

---

## 1. Source and method

| Item | Value |
|---|---|
| Input | `HappyGo_1.0.6_APKPure.xapk` (base + `config.arm64_v8a` split) |
| Framework | Flutter. App logic is AOT-compiled Dart in `lib/arm64-v8a/libapp.so` |
| Dart VM | 3.11.4, snapshot `78da37fed6bf1489361a312568249f3f`, android arm64, compressed pointers |
| Tool | [blutter](https://github.com/worawit/blutter), built in an `ubuntu:24.04` Docker container |
| BLE stack | `flutter_blue_plus` |

Java decompilation (jadx) is not useful here: the Java layer is only the Flutter embedding.

### Relevant Dart sources (from blutter output)

| File | What it holds |
|---|---|
| `package:flutter_coffeecup/model/device/pcm_device_comand_pack.dart` | `PcmBlePack`: frame builder, checksum, one factory per command |
| `package:flutter_coffeecup/model/device/pcm_device.dart` | `PcmDevice`: connect sequence, send/receive, reply handlers |
| `package:flutter_coffeecup/model/device/ble_frame_decoder.dart` | Reassembles incoming notify frames |
| `package:flutter_coffeecup/pages/pcm/device_set_page.dart` | `_DeviceSetPageState._save`: settings page → temperature and work-param commands |
| `package:flutter_coffeecup/widget/pcm_main_screen.dart` | `_PcmMainScreenState._setWorkState`: start/stop buttons |
| `package:ec_kit/model/device/base_device.dart` | GATT UUIDs, service discovery, notify subscription, write |
| `package:ec_kit/model/device/base_ble_command.dart` | `BaseBleCommand.sendData`: 1-byte opcode |
| `package:ec_kit/util/number_util.dart` | `setUint16toList`: length field encoding |

There are two device families in the app: the "cup" (`BlePack`, `device_comand_pack.dart`) and the PCM machine (`PcmBlePack`). Heating, extraction, pre-soak, standstill and flow speed all live on the **PCM** side, which this document covers. The cup uses the same frame layout with its own opcodes.

---

## 2. GATT layout

Built by `BleUuidUtil.from(short)` as `"0000" + short + "-0000-1000-8000-00805F9B34FB"`.

| Role | Short | Full UUID |
|---|---|---|
| Service | `FFF0` | `0000FFF0-0000-1000-8000-00805F9B34FB` |
| Write (app → device) | `FFF2` | `0000FFF2-0000-1000-8000-00805F9B34FB` |
| Notify (device → app) | `FFF1` | `0000FFF1-0000-1000-8000-00805F9B34FB` |
| CCCD | `2902` | `00002902-0000-1000-8000-00805f9b34fb` |

These `FFFx` IDs are the "0xFFF…" values seen when sniffing. They are not part of the packet payload.

The app calls `BluetoothCharacteristic.write(value)` with default arguments (write with response), but the K2's `FFF2` only advertises **WRITE NO RESPONSE**, so clients should write without response (§4.3).

---

## 3. Connection handshake

There is **no authentication, pairing, key exchange or encryption** on the link. The app's only AES code (`AesUtil.aesDecrypt`) decrypts firmware-update files downloaded over HTTP (`BleUtil.getUpdateFile`). It never touches BLE traffic.

The "handshake" is a fixed setup sequence in `PcmDevice.initConnect` (0x86b25c), which runs after the BLE link is up:

| # | Step | Detail |
|---|---|---|
| 1 | Connect | `BluetoothDevice.connect()` |
| 2 | Discover services | `BaseDevice.initConnect` → `discoverServices()` |
| 3 | Enable notify on `FFF1` | `startListenNormalNotify` → `setNotifyValue(true)` |
| 4 | Request MTU **210** | `requestMtu(210)` (Android-side; frames are ≤ 12 bytes, so it isn't needed for these commands) |
| 5 | Wait **300 ms** | `Future.delayed(300 ms)` |
| 6 | Load preferences | Battery placeholder; temperature unit from local pref `TemperatureUnitIsF` (app-side only, nothing sent) |
| 7 | `SET_TIME` `0x04` | Clock sync with phone local time (see §5.6) |
| 8 | `GET_TEMP_SETTING` `0x15` | Preset temperature |
| 9 | `GET_WORK_PARAM` `0x17` | Flow speed / pre-soak / standstill / extraction ranges and values |
| 10 | `GET_APPOINTMENT` `0x24` | Scheduled brew |
| 11 | `GET_DEVICE_INFO` `0x08` | Device info |
| 12 | `GET_CUPS` `0x06` | Cup history, data `[0x00]` (`isClear = false`) |
| 13 | `GET_DEVICE_STATE` `0x00` | Starts real-time state (`readRealTimeData`, app-side timeout 3) |

Each command in steps 7–12 is **awaited** (the app waits for the reply or its timeout), then the app waits **40 ms** before sending the next. The Python port uses a fixed 40 ms gap instead.

I found no periodic `GET_DEVICE_STATE` poll after step 13. `readRealTimeData` is only called from `initConnect`, which suggests the machine pushes state updates on `FFF1` by itself. **That last point is an inference.**

### Example handshake bytes (2026-10-13 14:30:05, a Tuesday)

```
7F 00 0C DF 04 02 0A 0D 06 0E 1E 05   SET_TIME
7F 00 05 99 15                        GET_TEMP_SETTING
7F 00 05 9B 17                        GET_WORK_PARAM
7F 00 05 A8 24                        GET_APPOINTMENT
7F 00 05 8C 08                        GET_DEVICE_INFO
7F 00 06 8B 06 00                     GET_CUPS (isClear=false)
7F 00 05 84 00                        GET_DEVICE_STATE
```

---

## 4. Frame format

### 4.1 App → device (`PcmBlePack.sendData`)

```
+------+--------+--------+-----+-----+-----------+
| 0x7F | LEN_HI | LEN_LO | CHK | CMD | DATA ...  |
+------+--------+--------+-----+-----+-----------+
```

| Field | Size | Value |
|---|---|---|
| Header | 1 | `0x7F` |
| LEN | 2 | `len(DATA) + 5` = total frame length, **big-endian** u16 |
| CHK | 1 | `(0x7F + LEN_HI + LEN_LO + CMD + sum(DATA)) & 0xFF` |
| CMD | 1 | Opcode (`BaseBleCommand.sendData` → 1 byte) |
| DATA | n | Command payload |

The checksum (`PcmBlePack.check`) is the byte-sum of `DATA`, plus the byte-sum of the 3-byte header, plus the opcode, truncated to 8 bits.

**Note:** CHK comes *before* CMD in the frame, but CMD is still included in the sum.

### 4.2 Device → app (`BleFrameDecoder`)

- Header byte is **`0x7E`**. The decoder scans its buffer for `0x7E` and drops anything before it.
- Length is `((b[1] & 0x3F) << 8) | b[2]`. A frame is accepted when 5 ≤ length < 4096.
- After the header and length, the layout matches §4.1 (CHK, CMD, DATA).

The reply's CMD (byte 4) echoes the request opcode; `d` below is the DATA after CMD (frame byte 5 onward).

### 4.3 Reply payloads

From `PcmDevice._onGetDeviceState`, `_onGetTempSetting`, `_onGetDeviceWorkParam`.

**`0x00` device state** (also pushed unprompted while notify is on):

| `d[i]` | Meaning |
|---|---|
| 0 | unknown (seen `01`) |
| 1 | battery % = `d[1] & 0x7F` (UI level: 0, 1–24, 25–50, 51–75, >75) |
| 2 | flags: `0x80` charging, `0x40` charging full, `0x20` battery level 0 |
| 3 | current temperature, °C |
| 4 | `PcmDeviceState`: 0 standby, 1 hotExtractioning, 2 hotExtractionComplete, 3 coldExtractioning, 4 coldExtractionComplete, 5 hotWatering, 6 hotWaterComplete |
| 5 | `PcmWorkErrorCode`: 0 normal, 1 dryBurning, 2 batteryOverheating, 3 heatingShortCircuit, 4 lowBatteryHot, 5 lowBattery |

Verified on a live K2 (nRF Connect): `7E 00 0B 02 00 01 45 02 31 00 00` = 69 %, not charging, 49 °C, standby, no error. Its checksum matches §4.1's rule (sum of all bytes except CHK).

**`0x15` temp setting:** `d[0]` min, `d[1]` max, `d[2]` preset, all in **°F** on the K2. Live reply `7E 00 08 96 15 64 CC CB` = 100–204 °F (38–96 °C), preset 203 °F (95 °C). So `0x16` must also be sent in °F; a raw °C value like 61 is below the minimum and is ignored. The machine keeps the preset as whole °C and reports `floor(°C × 1.8 + 32)`. Sending 198 °F (92.2 °C) reads back as 197 °F (92 °C), and 205 °F reads back as 204 °F (96 °C). Compare read-backs in °C. The `0x00` state temperature is °C.

**`0x17` work params:** `d[0]` flow speed; soak `d[1]` value, `d[2]` min, `d[3]` max; standstill `d[4]` value, `d[5]` min, `d[6]` max; extraction `d[7]` value, `d[8]` min, `d[9]` max (present only if `len(d) > 9`; this sets the "supports extraction time" flag, §5.3); flow range `d[10]` min, `d[11]` max (only if `len(d) > 11`, else 0..2).

**Live K2 behaviour (Android, 2026-10-13):** the `0x08` device-info reply arrives unprompted right after connect. The `0x00` status frame is pushed about once a second. Long replies such as `0x06` cups are split across notifications: `b[1] >> 6` is the fragment status (`0` single, `1` first, `2` middle, `3` last), and each fragment carries its own LEN and CHK. Example `0x17` reply: `7E 00 0F DA 17 02 07 00 0A 07 00 0A FA 1E FA` = flow 2, soak 7 (0–10), standstill 7 (0–10), extraction 250 (30–250).

**Write type:** the K2's `FFF2` advertises **WRITE NO RESPONSE** only (seen in nRF Connect), so use write-without-response.

---

## 5. Commands

### 5.1 Opcode table (PCM)

| Opcode | Name (`PcmBlePack`) | Direction | DATA |
|---|---|---|---|
| `0x00` | `getDeviceState` | query | – |
| `0x02` | `setWorkState` | set | `[mode, start]` |
| `0x04` | `setTime` | set | 7 bytes (§5.6) |
| `0x06` | `getCups` | query | `[isClear]` |
| `0x08` | `getDeviceInfo` | query | – |
| `0x15` | `getTempSetting` | query | – |
| `0x16` | `setTempSetting` | set | `[temp]` |
| `0x17` | `getDeviceWorkParam` | query | – |
| `0x18` | `setDeviceWorkParam` | set | `[flow, soak, standstill(, extraction)]` |
| `0x19` | `getTodayCups` | query | – |
| `0x20` | `reset` | set | – |
| `0x23` | `setAppointment` | set | 6 bytes (not decoded) |
| `0x24` | `getAppointment` | query | – |

Opcode values come from the `BleCommand` constant objects (`Obj!BleCommand@…`, field `off_8`) referenced by each factory.

### 5.2 Heating / extraction: `SET_WORK_STATE` `0x02`

DATA = `[mode, start]`

| `mode` | `WorkMode` enum | UI label | Device state while running |
|---|---|---|---|
| `0` | `hotWater` | Heating | `hotWatering` |
| `1` | `hotExtraction` | Heating & Extraction | `hotExtractioning` |
| `2` | `extraction` | Extraction (direct / cold) | `coldExtractioning` |

| `start` | Meaning |
|---|---|
| `1` | start |
| `0` | stop (app sets local state to `standby`) |

**Call flow:** `_PcmMainScreenState._setWorkState(start, mode)` → `PcmDevice.setWorkState` → `PcmBlePack.setWorkState` (builds `[mode.index, start ? 1 : 0]`).

### 5.3 Brew parameters: `SET_WORK_PARAM` `0x18`

DATA = `[flow_speed, pre_soak, standstill, extraction_time]`

| Byte | Field | How the app computes it | Default range |
|---|---|---|---|
| 0 | Flow speed (`_extractionPressure`) | Selected value | `0..2` → Low / Medium / High |
| 1 | Pre-soak time | `soakPicker.index + SoakingTime.min` | 0–10 |
| 2 | Standstill time | `standstillPicker.index + StandstillTime.min` | 0–10 |
| 3 | Extraction time | `extractionPicker.selectedItem × 10 + ExtractionTime.min` | 30–180, step 10 |

- **Byte 3 is conditional.** It's only sent when `PcmDevice` flag `@0x70` is true. That flag is set in `_onGetDeviceWorkParam` when the `0x17` reply includes the extraction-time fields. Otherwise the payload is 3 bytes (`LEN = 8`).
- **Ranges are device-reported.** The defaults above come from the `PcmDevice` constructor. Each is replaced by the min/max the machine returns for `0x17`.
- **Flow speed labels.** "Low / Medium / High" is shown only when the device-reported pressure range is 0..2. Otherwise the raw number is shown.
- **Units.** The app doesn't label pre-soak and standstill units. Seconds is assumed.
- All values are truncated with `& 0xFF`.

**Call flow:** `_DeviceSetPageState._save` → `PcmDevice.setDeviceWorkParam(pressure, soak, standstill, extraction)` → `PcmBlePack.setDeviceWorkParam(..., supportsExtractionTime)`.

### 5.4 Preset temperature: `SET_TEMP_SETTING` `0x16`

DATA = `[temp]`

- `temp = tempPicker.index + round(maxMinValue.min)`, where `maxMinValue` is whatever the machine reported for `0x15`.
- **Live (K2):** the K2 reports its range as `100..204` and keeps the preset in **°F**, so this byte is °F regardless of the app's display unit (a raw °C byte such as 61 is below the minimum and is ignored). The machine stores whole °C: 198 °F reads back as 197 °F (both 92 °C). Compare read-backs in °C.
- In the app's own code, if the unit pref is °F (`TemperatureUnit` index 1, `TemperatureUnitIsF`) it additionally sends `round(temp × 1.8 + 32)`; how that interacts with a °F-reporting machine was not exercised.
- **Skipped while busy.** `_save` doesn't send this while the machine is `hotWatering` or `hotExtractioning`.
- **Ordering.** `_save` sends `0x16` first, waits for it, checks that the device's reported preset matches, then sends `0x18`.

### 5.5 Queries

All have empty DATA: `0x00`, `0x08`, `0x15`, `0x17`, `0x19`, `0x24`. `0x06` (`getCups`) takes `[isClear]`, where `0x01` clears history on read.

### 5.6 Clock: `SET_TIME` `0x04`

DATA (7 bytes, phone local time) = `[weekday, month, day, year − 2020, hour, minute, second]`

- `weekday`: Monday = 1 … Sunday = 7 (Dart `DateTime.weekday`, same as Python `isoweekday()`).
- `year − 2020`: the code subtracts `0x7E4`.

---

## 6. Example frames

| Action | Bytes |
|---|---|
| Start heating only | `7F 00 07 89 02 00 01` |
| Stop heating only | `7F 00 07 88 02 00 00` |
| Start heating + extraction | `7F 00 07 8A 02 01 01` |
| Stop heating + extraction | `7F 00 07 89 02 01 00` |
| Start extraction only | `7F 00 07 8B 02 02 01` |
| Stop extraction only | `7F 00 07 8A 02 02 00` |
| Preset temp 92 °C (K2: sent as 198 °F, see §5.4) | `7F 00 06 61 16 C6` |
| Preset 92 sent as a raw °C byte (ignored by the K2: below its 100 °F minimum) | `7F 00 06 F7 16 5C` |
| Flow Medium, soak 5, standstill 3, extraction 30 | `7F 00 09 C7 18 01 05 03 1E` |
| Same, device without extraction-time support | `7F 00 08 A8 18 01 05 03` |
| Get device state | `7F 00 05 84 00` |
| Get device info | `7F 00 05 8C 08` |
| Get temp setting | `7F 00 05 99 15` |
| Get work params | `7F 00 05 9B 17` |

Worked checksum, *start heating + extraction*:
`0x7F + 0x00 + 0x07 + 0x02 (CMD) + 0x01 + 0x01 = 0x8A`

---

## 7. Python usage

```python
import asyncio
from pcm_protocol import *

frames = [
    preset_temperature(92),            # sent as 198 °F, see §5.4
    work_params(flow_speed=1, soak_s=5, standstill_s=3, extraction_s=30),
    work_state(HOT_EXTRACTION, start=True),
]
asyncio.run(send("80:F4:16:6D:B5:26", *frames))  # runs the §3 handshake first, decodes every reply

print(decode(bytes.fromhex("7E 00 0B 02 00 01 45 02 31 00 00")))
# {'cmd': 0, ..., 'battery': 69, 'charging': False, 'temp_c': 49, 'state': 'standby', 'error': 'normal'}
```

`send()` needs `pip install bleak`. The frame builders and `decode()` use only the standard library; `python pcm_protocol.py` runs the self-check, which includes frames captured from the live machine.

---

## 8. Reverse-engineering notes

These matter if you re-check any of the above against the blutter output:

- **Tagged constants.** Constants stored into Dart lists are Smi-tagged (value × 2). `r16 = 254` written into the frame list is the byte **`0x7F`**, and the decoder's `cmp w5, #0xfc` matches **`0x7E`**.
- **Unboxed values.** Ints passed to `_slowSetRange` / `AllocateUint8Array` counts, and unboxed `int` fields such as `BleCommand.off_8` and `Duration`, are raw (untagged).
- **Booleans.** `true` / `false` are `NULL + 0x20` / `NULL + 0x30`, so bit 4 clear means true. `tst x, #0x10; cset eq` converts a bool to 1/0.
- **Endianness.** `Endian` object `@953191` has `_littleEndian = false`, i.e. `Endian.big`, which is used for LEN.
- **Durations.** These are stored in microseconds: `0x493E0` = 300 ms, `0x9C40` = 40 ms.
- **Field offsets.** Instance-field offsets in the disassembly (`field_X`) are the declared offset minus 1. For example, page `field_1b` is `_standstillTimeController @0x1c`, and device `field_63` is `StandstillTime @0x64`.

## 9. Not covered yet

- Decoding of the cups, device-info and appointment replies (the K2 answers `0x08` with two fragments carrying the strings `0.0.2`, `0.0.1` and `30178`, presumably firmware/hardware versions and a serial).
- `setAppointment` (`0x23`) field meanings.
- Firmware update (`UpdatePack` / `update_comand_pack.dart`).
- The cup-device (`BlePack`) opcode set.
