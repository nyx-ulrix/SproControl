"""HappyGo 1.0.6 (com.timeyaa.flutter_coffeecup2) PCM BLE command encoder / reply decoder.

Ported from the AOT Dart in libapp.so (blutter, Dart 3.11.4) and checked against a live iKape K2:
  package:flutter_coffeecup/model/device/pcm_device_comand_pack.dart  PcmBlePack.sendData / check
  package:flutter_coffeecup/model/device/pcm_device.dart              PcmDevice._onGetDeviceState etc.
  package:flutter_coffeecup/pages/pcm/device_set_page.dart           _DeviceSetPageState._save
  package:flutter_coffeecup/widget/pcm_main_screen.dart              _PcmMainScreenState._setWorkState

Frame (app -> device), written to WRITE_UUID (write without response):
  [0x7F][LEN_HI][LEN_LO][CHK][CMD][DATA...]
  LEN = len(DATA) + 5  (whole frame, big-endian u16)
  CHK = (0x7F + LEN_HI + LEN_LO + CMD + sum(DATA)) & 0xFF
Replies arrive on NOTIFY_UUID with a 0x7E header and the same layout (see PROTOCOL.md §4).
"""

from datetime import datetime

SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"
WRITE_UUID = "0000FFF2-0000-1000-8000-00805F9B34FB"
NOTIFY_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"

# BleCommand opcodes used by PcmBlePack
GET_DEVICE_STATE = 0x00
SET_WORK_STATE = 0x02
SET_TIME = 0x04
GET_CUPS = 0x06
GET_DEVICE_INFO = 0x08
GET_TEMP_SETTING = 0x15
SET_TEMP_SETTING = 0x16
GET_WORK_PARAM = 0x17
SET_WORK_PARAM = 0x18
RESET = 0x20
GET_APPOINTMENT = 0x24

# WorkMode enum index (byte 0 of SET_WORK_STATE)
HOT_WATER = 0       # "Heating" only
HOT_EXTRACTION = 1  # "Heating & Extraction"
EXTRACTION = 2      # direct (cold) extraction

STATES = ["standby", "hotExtractioning", "hotExtractionComplete", "coldExtractioning",
          "coldExtractionComplete", "hotWatering", "hotWaterComplete"]
ERRORS = ["normal", "dryBurning", "batteryOverheating", "heatingShortCircuit", "lowBatteryHot", "lowBattery"]


def frame(cmd: int, data: bytes = b"") -> bytes:
    n = len(data) + 5
    head = bytes((0x7F, n >> 8 & 0xFF, n & 0xFF))
    chk = (sum(head) + cmd + sum(data)) & 0xFF
    return head + bytes((chk, cmd)) + data


def work_state(mode: int, start: bool) -> bytes:
    """Start/stop heating, heating+extraction or extraction. data = [mode, start]."""
    return frame(SET_WORK_STATE, bytes((mode & 0xFF, int(start))))


def c2f(c: float) -> int:
    return round(c * 1.8 + 32)


def f2c(f: float) -> int:
    return round((f - 32) / 1.8)


def preset_temperature(temp_c: int) -> bytes:
    """Preset temperature. The K2 keeps this in °F (its 0x15 reply reports 100..204 °F), so the °C value
    is converted. The machine stores whole °C and reads back floor(°C*1.8+32), e.g. 92 °C -> 198 sent, 197 read."""
    return frame(SET_TEMP_SETTING, bytes((c2f(temp_c) & 0xFF,)))


def work_params(flow_speed: int, soak_s: int, standstill_s: int, extraction_s: int | None = None) -> bytes:
    """Flow speed (ExtractionPressure; 0/1/2 = Low/Medium/High when range is 0..2), pre-soak,
    standstill, extraction time. Extraction byte is only sent when the device's GET_WORK_PARAM
    reply carried an extraction-time range (PcmDevice flag @0x70); pass None otherwise.
    K2 ranges: soak/standstill 0..10, extraction 30..250."""
    vals = (flow_speed, soak_s, standstill_s) + (() if extraction_s is None else (extraction_s,))
    return frame(SET_WORK_PARAM, bytes(v & 0xFF for v in vals))


def query(cmd: int) -> bytes:
    return frame(cmd)


def set_time(now: datetime) -> bytes:
    """Clock sync (local time): [weekday Mon=1..Sun=7, month, day, year-2020, hour, minute, second]."""
    t = (now.isoweekday(), now.month, now.day, now.year - 2020, now.hour, now.minute, now.second)
    return frame(SET_TIME, bytes(v & 0xFF for v in t))


def handshake(now: datetime) -> list[bytes]:
    """PcmDevice.initConnect order, sent after notify-on + MTU 210 + 300 ms; app waits 40 ms after each.
    No auth/pairing exchange exists. getCups data = [isClear] (False on connect)."""
    return [
        set_time(now),
        query(GET_TEMP_SETTING),
        query(GET_WORK_PARAM),
        query(GET_APPOINTMENT),
        query(GET_DEVICE_INFO),
        frame(GET_CUPS, b"\x00"),
        query(GET_DEVICE_STATE),
    ]


def decode(reply: bytes) -> dict:
    """Decode one device -> app frame (0x7E header). Raises ValueError on a bad length or checksum.
    Known payloads (PROTOCOL.md §4.3): 0x00 state, 0x15 temp setting, 0x17 work params."""
    if len(reply) < 5 or reply[0] != 0x7E:
        raise ValueError("not a 0x7E frame")
    n = (reply[1] & 0x3F) << 8 | reply[2]
    if n != len(reply):
        raise ValueError(f"length byte {n} != {len(reply)}")
    if (sum(reply) - reply[3]) & 0xFF != reply[3]:
        raise ValueError("checksum mismatch")
    cmd, d = reply[4], reply[5:]
    out = {"cmd": cmd, "fragment": reply[1] >> 6, "data": d}
    if cmd == GET_DEVICE_STATE and len(d) >= 6:
        out.update(battery=d[1] & 0x7F, charging=bool(d[2] & 0x80), charged=bool(d[2] & 0x40),
                   temp_c=d[3], state=STATES[d[4]] if d[4] < len(STATES) else d[4],
                   error=ERRORS[d[5]] if d[5] < len(ERRORS) else d[5])
    elif cmd == GET_TEMP_SETTING and len(d) >= 3:
        out.update(min_c=f2c(d[0]), max_c=f2c(d[1]), preset_c=f2c(d[2]))
    elif cmd == GET_WORK_PARAM and len(d) >= 7:
        out.update(flow=d[0], soak=(d[1], d[2], d[3]), standstill=(d[4], d[5], d[6]))
        if len(d) > 9:
            out["extraction"] = (d[7], d[8], d[9])
    return out


async def send(address: str, *packets: bytes) -> None:
    """Connect like the app does, then write `packets`. Needs `pip install bleak`."""
    import asyncio

    from bleak import BleakClient

    def rx(_, data):
        try:
            print("rx", data.hex(" "), decode(bytes(data)))
        except ValueError as e:
            print("rx", data.hex(" "), e)

    async with BleakClient(address) as c:  # connect + service discovery
        await c.start_notify(NOTIFY_UUID, rx)
        # MTU: Android requests 210 here; bleak negotiates it itself (c.mtu_size), no call needed.
        await asyncio.sleep(0.3)
        for p in handshake(datetime.now()) + list(packets):
            print("tx", p.hex(" "))
            await c.write_gatt_char(WRITE_UUID, p, response=False)  # FFF2 is WRITE NO RESPONSE on the K2
            # ponytail: fixed 40 ms gap; the app instead awaits each reply (or timeout) then 40 ms
            await asyncio.sleep(0.04)


if __name__ == "__main__":
    assert query(GET_DEVICE_STATE) == bytes.fromhex("7F 00 05 84 00")
    assert work_state(HOT_EXTRACTION, True) == bytes.fromhex("7F 00 07 8A 02 01 01")
    assert work_state(HOT_WATER, False) == bytes.fromhex("7F 00 07 88 02 00 00")
    assert preset_temperature(92) == bytes.fromhex("7F 00 06 61 16 C6")  # 92 °C -> 198 °F
    assert work_params(1, 5, 3, 30) == bytes.fromhex("7F 00 09 C7 18 01 05 03 1E")
    assert work_params(1, 5, 3) == bytes.fromhex("7F 00 08 A8 18 01 05 03")
    # 2026-10-13 14:30:05 is a Tuesday (2)
    assert set_time(datetime(2026, 10, 13, 14, 30, 5)) == frame(SET_TIME, bytes((2, 10, 13, 6, 14, 30, 5)))
    assert len(handshake(datetime.now())) == 7
    # replies captured from a live K2 (adb logcat of the app's rx log)
    s = decode(bytes.fromhex("7E 00 0B 02 00 01 45 02 31 00 00"))
    assert (s["battery"], s["temp_c"], s["state"], s["error"]) == (69, 49, "standby", "normal")
    t = decode(bytes.fromhex("7E 00 08 96 15 64 CC CB"))
    assert (t["min_c"], t["max_c"], t["preset_c"]) == (38, 96, 95)
    w = decode(bytes.fromhex("7E 00 0F DA 17 02 07 00 0A 07 00 0A FA 1E FA"))
    assert (w["flow"], w["soak"], w["standstill"], w["extraction"]) == (2, (7, 0, 10), (7, 0, 10), (250, 30, 250))
    try:
        decode(bytes.fromhex("7E 00 0B 03 00 01 45 02 31 00 00"))
        raise AssertionError("bad checksum accepted")
    except ValueError:
        pass
    print("ok")
