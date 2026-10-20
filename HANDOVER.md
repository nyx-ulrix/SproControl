# SproControl — Handover (2026-10-20)

Start a new chat in `C:\Users\malco\Github\SproControl` and say: **"Read HANDOVER.md, then continue."**

SproControl is an open controller for the **iKape K2** portable espresso machine: an Android app
(`SproCtrl`, a single-page WebView UI with a Java BLE bridge, no Gradle), two home-screen widgets,
a Web Bluetooth browser version and a Python encoder/decoder, all built on the machine's recovered
BLE protocol (`PROTOCOL.md`). Public repo `nyx-ulrix/SproControl`; latest release **v1.0.5**; the
owner's portfolio entry is live at liewjiaen.com (repo `nyx-ulrix/liewjiaen.com`, `docs/data.json`).

## 1. Where everything is

| What | Where |
|---|---|
| Overview, features, diagrams, platforms, build, tests | `README.md` (five Mermaid diagrams: architecture, app session, brew gate, widget tap, one-button cycle) |
| Protocol, byte by byte, with live quirks | `PROTOCOL.md` |
| The page (UI + protocol + settings), identical in the APK and the browser | `k2.html` (served in the APK from the private origin `https://app.sprocontrol.local/`) |
| App: BLE link, JS bridge, lifecycle, updater, widget takeover | `android/src/com/sprocontrol/k2/MainActivity.java` |
| Widgets: row widget + the per-tap BLE session (`Tap`), held link, brew/purge frames | `android/src/com/sprocontrol/k2/K2Widget.java` |
| One-cell widget: cycle, steps, per-purge seconds, battery dot, warning | `android/src/com/sprocontrol/k2/K2OneWidget.java` |
| Foreground service that keeps the held link alive | `android/src/com/sprocontrol/k2/LinkService.java` |
| APK install content provider for the updater | `android/src/com/sprocontrol/k2/ApkProvider.java` |
| Manifest (version), layouts, drawables, widget providers | `android/AndroidManifest.xml`, `android/res/` |
| Build script (release / `-Debug` / `-Install`) | `android/build.ps1` |
| Python encoder/decoder + `send()` over bleak, self-check | `pcm_protocol.py` |
| Page self-test (31 checks, captured frames) | `tests/page_test.html` + `tests/page_test.js` |
| Screenshots, brew clip | `docs/img/`, `docs/brew-demo.mp4` |
| Code knowledge graph (graphify, published) | `graphify-out/GRAPH_REPORT.md`, `graph.html`, `graph.json` |
| Release signing key (not in the repo, back it up) | `~/.android/sprocontrol-release.jks` + `.pass` beside it |
| Owner's machine | `HI-T-1292G0_B526`, address `80:F4:16:6D:B5:26`; bonded on the owner's OPPO |

## 2. Owner rules (never break)

- **No AI attribution anywhere**: not in commits (no trailers), not in the README, not in the portfolio.
  Commits are authored `Malcolm Liew Jia En <malcolmliew.je@gmail.com>` via `git -c user.name=… -c user.email=…`.
- **Never commit the HappyGo XAPK/APK** or any APK (`.gitignore` covers it); the release APK goes only
  into the GitHub release as an asset.
- **Never pair/bond the app with the machine** (`createBond` was removed; connect by bonded device if
  one exists, else by the remembered address and address type). Widgets never scan, so never ask for location.
- **Do not change the machine's settings on connect**: the app adopts what the machine holds and only
  names it if a preset matches. Only a confirmed set in the app changes the widgets' brew state.
- **Ship only when told**, and only after the owner has tested on the phone when they said so; "don't
  release yet" means keep the release as a draft (`gh release edit vX --draft`).
- Don't enter the phone's PIN; don't look at or keep screenshots of the owner's other apps/home screen
  beyond the widget crop; the calendar widget seen once was deleted from the scratchpad.
- Portfolio text must tell the real recovery story (Wireshark blocked, nRF Connect, logcat, blutter,
  guess and check) and never say the app was "pulled apart"; the owner edited that wording twice.

## 3. Build, test, install, release

```powershell
powershell -File android/build.ps1            # release APK (release key); prints sha256
powershell -File android/build.ps1 -Debug     # debuggable, debug key, AND the one-cell receiver exported
                                              # (lets `adb shell am broadcast -n com.sprocontrol.k2/.K2OneWidget -a com.sprocontrol.k2.ONE_TAP` stand in for a tap)
adb -s <serial> install -r android/out/k2.apk # debug over debug is fine; release over debug needs an uninstall (different key)
```

- Page tests: serve the repo (`python -m http.server 8765`) and open `tests/page_test.html`, or headless:
  `"/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --disable-gpu --virtual-time-budget=60000 --dump-dom http://localhost:8765/tests/page_test.html` then count `PASS` lines (31/31 on 2026-10-20).
  The desktop app's browser pane was blocked by the login manager late in the last session; headless Chrome is the fallback.
- Protocol self-check: `python pcm_protocol.py`.
- Release: bump `versionCode`/`versionName` in the manifest, build release, `git push`, then
  `gh release create vX.Y.Z <apk> --latest --notes "...\n\nsha256 <hex>"`. The in-app updater reads
  `releases/latest`: tag, `.apk` asset, and that `sha256` line, and checks the signing certificate.
  All releases before 1.0.0 were deleted on the owner's request; history restarts at v1.0.0 (2026-10-14).
- Phone: OPPO on ColorOS, serial `feaaddf7`, Android 14. Its USB link drops every few minutes; retry.
  Emulator `Pixel_7a` (`emulator-5554`, Android 16, no Bluetooth) is good for layout/state screenshots.
- Driving the page from the shell: `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`
  then `node <scratch>/cdp.js "<expr>"` (needs `{"type":"module"}` beside it). `window.__k2` exposes
  `frame`, `feed`, `onFrame`, `apply` (= applyPreset) for tests; the page's other internals are not global.
- Staging widget states on a device: kill the process as the app's own user
  (`run-as com.sprocontrol.k2 kill -9 <pid>`; `am force-stop` puts the app in the stopped state and
  broadcasts stop arriving), write `shared_prefs/k2.xml` with `run-as`, then start the activity and
  call `AndroidBle.setOffset(0)` over DevTools to redraw. Shell broadcasts to a dead process start it on
  the OPPO but not on the Android 16 emulator.
- Talking to the machine from the laptop: `bleak` is installed; scratch scripts `k2set.py` (set
  temp/soak/hold), `k2read.py`, `k2purgetest.py` pattern in the session scratchpad; the K2 accepts one
  central, so kill the phone app first.

## 4. How it behaves (state on 2026-10-20, v1.0.5)

**App**
- Searches for the machine by itself on open and on every return to the front; the name bar still
  reconnects on a tap. Link dropped 30 s after leaving the app; nothing runs in the background.
- Every set is read back (`0x15` / `0x17`) and compared in °C; a lost echo is re-asked (4 tries); a
  mismatch resends. Heat / Heat & Brew / Extract are greyed until the handshake is done, no set is
  pending or rejected, no machine error, and the shown five values equal the machine's last read-back.
- Temperature offset: shown = machine + offset; sent = shown − offset (the brew state too).
- Default preset 92 °C / flow high / 7 s / 7 s / 250 s (an untouched factory Default from older installs is migrated).
- Purge: flow and seconds from Settings (1–250 s; the K2 stores 0–255 although it reports 30–250),
  never touches the temperature, puts the app's own settings back when it ends or is stopped (toast
  shows `pre-soak • hold • temp`). The K2 often reports idle for a short purge and ignores settings
  written while the pump runs.
- Timers: pre-soak/hold countdown and the brew timer start with the pump: at once for Extract; for
  Heat & Extract when the water reaches the setpoint or first falls 2° from its peak.
- Battery: readout flashes red at the Settings threshold (default 30 %) and under.
- Updater: Settings → App update, manual only.

**Widgets** (row widget + one-cell button; both share prefs file `k2`)
- A tap is the only thing that opens a link; it stays held for **ten minutes after the last tap**
  (foreground service `LinkService`, notification "SproCtrl is linked"); ten quiet minutes drop it.
  Once the one-cell cycle's **last step** is acknowledged the hold shrinks to **30 s**, after which the
  link drops and the cycle restarts at step one. A tap inside those 30 s restores the ten-minute rule.
- Connect: clock `0x04` first (the K2 is deaf for ~400 ms after notify-on and only starts its status
  push after the clock), ack → read `0x17`, then a status frame → first step shown (~4 s cold).
- Taps on the held link send in ~7 ms; the next button appears on the machine's `0x02` acknowledgement
  (70–230 ms). A command has 500 ms from its first frame to be acknowledged; otherwise "No response"
  and nothing more is sent. Taps during a busy tap queue (one slot). Writes stop once a tap is over.
- If the app holds the link, a widget tap makes the app drop it and close, then runs.
- **Two states only**: the *brew state* (`brew` = the five values the app last set and saw confirmed;
  pushed by `pushBrew()` in the page via `AndroidBle.setBrewState`) and the *purge state* (`purge`,
  plus per-step seconds in the one-cell config `secs`). A brew sends the brew state, reads it back,
  starts only when all five match; a purge sends the purge state and starts. No restore logic, no
  settings read from the machine.
- One-cell widget: default cycle purge → stop → brew → stop → purge → stop; the cycle advances only on
  acknowledgement; a Stop pressed on the machine consumes a Stop step; the cycle keeps its place for
  ten minutes after the last tap (`lastTap`), then starts over. Label = brew state as
  `pre-soak • hold • temp` on brew/extract steps only; Stop face = square; red dot top-right under the
  battery threshold; warning triangle replaces the icon on machine faults 1–3. Linked only while
  status frames keep coming (9 s), re-checked by an alarm.
- Address: remembered with its type after the app's first link (`mac`, `macType`); the K2 may use a
  random address, which a plain lookup cannot reach.

## 5. Gotchas learned the hard way

- Replies arrive two to a notification or split across two; both the app and the widget reassemble frames.
- The phone's stack answers 201 (busy) to a write queued right behind another: resend, capped.
- `aapt2 -A` on Windows writes backslash asset paths: assets are added with `jar`.
- PowerShell 5.1: `.ps1` without BOM reads as ANSI; write non-ASCII via `[char]` codes or Python.
- Bash heredocs holding Python with `'''` and backslashes broke twice: write edit scripts with the
  Write tool and run `python -I script.py "C:\path"`; keep Python strings raw.
- `python -I` ignores user site-packages (bleak): run laptop BLE scripts without `-I`.
- Two GATT clients from one process share notifications; an in-flight widget tap keeps its own link
  for up to 8 s after the app takes over.

## 6. Where the last session stopped

- v1.0.5 published (pump-based timers); README has the five diagrams, the platforms table and the code
  map; `graphify-out` is rebuilt (231 nodes, code only; the document cache had no hits) and pushed.
- Not verified on the phone since 1.0.3: the 30 s post-cycle drop, the 92 °C default migration, the
  pump-timer rule (needs a real Heat & Extract with `adb logcat -s K2:I` to tune `pumpOn()`).
- Ideas the owner has not asked for (do not build unless asked): a pump bit in status byte `d[0]`
  (seen `01` only) is still unidentified; the purge seconds above 250 are accepted by the machine but
  capped in the UI.
