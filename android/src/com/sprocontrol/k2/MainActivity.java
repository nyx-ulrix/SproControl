package com.sprocontrol.k2;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;
import org.json.JSONObject;

/** WebView around assets/k2.html. Android WebView has no Web Bluetooth, so BLE lives here
 *  and talks to the page via window.AndroidBle (JS -> Java) and window.k2Native (Java -> JS).
 *  GATT layout and frame format: PROTOCOL.md §2-4.
 *
 *  Trust boundaries: the machine (frames, device name), the page (bridge arguments) and stored
 *  preferences are all treated as untrusted input and validated here. The link is held while this
 *  activity is on screen and for IDLE_MS after the user leaves it; then it is dropped and the page
 *  is paused, so nothing keeps running in the background. */
public class MainActivity extends Activity {
    static final UUID SVC = uuid("fff0"), RX = uuid("fff1"), TX = uuid("fff2"), CCCD = uuid("2902");
    static UUID uuid(String s) { return UUID.fromString("0000" + s + "-0000-1000-8000-00805f9b34fb"); }
    // The page is served from an app-private origin: every request is answered from assets or denied, so the
    // bundled font loads (file:// pages are cross-origin to their own fonts) and nothing can reach the network.
    static final String HOST = "app.sprocontrol.local", ORIGIN = "https://" + HOST + "/", PAGE = ORIGIN + "k2.html";
    static final int MAX_FRAME = 64; // longest command is 12 bytes; anything bigger is not a PcmBlePack frame
    // ponytail: leaving the screen counts as the user's last action; the link is kept this long after it.
    static final long IDLE_MS = 30_000;

    WebView web;
    BluetoothAdapter adapter;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic tx;
    ScanCallback scanCb;
    String lastStatus;
    int gen;                 // connection generation: stale timeouts/callbacks compare against it
    boolean resumeLink;      // reconnect when the activity comes back
    final Handler main = new Handler(Looper.getMainLooper());
    final Runnable idleDrop = this::sleepLink;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable); // chrome://inspect only on debug builds
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);      // localStorage: nickname, presets, offset
        ws.setAllowFileAccess(false);       // assets still load; the rest of the filesystem does not
        ws.setAllowContentAccess(false);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setGeolocationEnabled(false);
        // The bridge is only ever exposed to the bundled page: refuse every navigation away from it, and serve
        // the page and its font straight from assets under ORIGIN.
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !PAGE.equals(r.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                android.net.Uri u = r.getUrl();
                String path = u.getPath() == null ? "" : u.getPath().substring(1);
                if (!"https".equals(u.getScheme()) || !HOST.equals(u.getHost()) || path.isEmpty() || path.contains("..")) return deny();
                try {
                    String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".ttf") ? "font/ttf" : path.endsWith(".js") ? "text/javascript" : "application/octet-stream";
                    return new WebResourceResponse(mime, path.endsWith(".html") ? "utf-8" : null, getAssets().open(path));
                } catch (IOException e) { return deny(); }
            }
        });
        web.addJavascriptInterface(this, "AndroidBle");
        web.loadUrl(PAGE);
        setContentView(web);
    }

    static WebResourceResponse deny() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    /** Back on screen: cancel the idle drop; if the link was already dropped, pick it up again. */
    @Override protected void onStart() {
        super.onStart();
        main.removeCallbacks(idleDrop);
        web.onResume();
        web.resumeTimers();
        if (resumeLink) { resumeLink = false; startScan(); }
    }

    /** Off screen: the link (and the widget's live status) stays for IDLE_MS, then sleepLink() runs. */
    @Override protected void onStop() {
        main.removeCallbacks(idleDrop);
        main.postDelayed(idleDrop, IDLE_MS);
        super.onStop();
    }

    /** Drop the link and freeze the page. No BLE traffic, timers or widget updates until we return. */
    void sleepLink() {
        resumeLink = gatt != null;
        dropLink();
        js("gone", null);
        web.onPause();
        web.pauseTimers();
    }

    @Override protected void onDestroy() { main.removeCallbacks(idleDrop); dropLink(); web.destroy(); super.onDestroy(); }

    /** Call into the page. The argument is JSON-quoted, so a hostile device name cannot become code. */
    void js(String fn, String arg) {
        String code = "k2Native." + fn + "(" + (arg == null ? "" : JSONObject.quote(arg)) + ")";
        if (Looper.myLooper() == Looper.getMainLooper()) web.evaluateJavascript(code, null);
        else main.post(() -> web.evaluateJavascript(code, null));
    }

    /** Least privilege: scanning (and the location permission ColorOS wants for it) is only asked for
     *  when there is no bonded K2 to connect to directly. */
    boolean hasPerms(boolean scan) {
        String[] need = scan
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION}
                : new String[]{Manifest.permission.BLUETOOTH_CONNECT};
        for (String x : need) if (checkSelfPermission(x) != PackageManager.PERMISSION_GRANTED) { requestPermissions(need, scan ? 2 : 1); return false; }
        return true;
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (r.length == 0) { js("fail", "Bluetooth permission denied"); return; } // dialog dismissed
        for (int x : r) if (x != PackageManager.PERMISSION_GRANTED) { js("fail", "Bluetooth permission denied"); return; }
        startScan();
    }

    /** JS bridge calls arrive on a binder thread; everything that touches state runs on the main thread. */
    @JavascriptInterface public void connect() { main.post(this::startScan); }

    @SuppressWarnings("MissingPermission")
    void startScan() {
        if (!hasPerms(false)) return;
        stopScan();
        if (adapter == null || !adapter.isEnabled()) { js("fail", "Turn Bluetooth on · tap to retry"); return; }
        // Already bonded (e.g. via HappyGo / nRF)? Connect straight away, no scan needed.
        for (BluetoothDevice d : adapter.getBondedDevices()) if (isK2(d.getName())) { open(d); return; }
        if (!hasPerms(true)) return;
        BluetoothLeScanner s = adapter.getBluetoothLeScanner();
        if (s == null) { js("fail", "Turn Bluetooth on · tap to retry"); return; }
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                String n = r.getScanRecord() != null && r.getScanRecord().getDeviceName() != null
                        ? r.getScanRecord().getDeviceName() : r.getDevice().getName();
                if (!isK2(n)) return;
                stopScan();
                open(r.getDevice());
            }
        };
        s.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        main.postDelayed(() -> { if (scanCb != null) { stopScan(); js("fail", "Not found · tap to retry"); } }, 10000);
    }

    /** Advertised as HI-T-1292G0_B526, bonded as Hi-t-1292G0_B526; the app calls it PCM03_B526. */
    static boolean isK2(String n) {
        if (n == null) return false;
        n = n.toUpperCase();
        return n.startsWith("HI-T") || n.startsWith("PCM");
    }

    @SuppressWarnings("MissingPermission")
    void open(BluetoothDevice d) {
        dropLink(); // synchronously: a posted teardown would run after connectGatt() and kill the new link
        final int my = ++gen;
        Log.i("K2", "connecting " + d.getName() + " " + d.getAddress());
        js("fail", "Connecting…");
        main.postDelayed(() -> { if (my == gen && gatt != null && tx == null) { Log.i("K2", "connect timeout"); disconnect(); js("fail", "Connect timed out · tap to retry"); } }, 15000);
        gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressWarnings("MissingPermission")
    void stopScan() {
        if (scanCb == null) return;
        BluetoothLeScanner s = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (s != null) s.stopScan(scanCb);
        scanCb = null;
    }

    @JavascriptInterface public void disconnect() { main.post(this::dropLink); }

    @SuppressWarnings("MissingPermission")
    void dropLink() {
        stopScan();
        gen++;
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
        tx = null;
        K2Widget.prefs(this).edit().putLong("appHold", 0).apply(); // widget taps may use the machine again
    }

    /** Nickname from the page; the widget shows it as its title. */
    @JavascriptInterface public void setName(String n) {
        if (n == null) return;
        n = n.trim();
        if (n.isEmpty() || n.length() > 24) return;
        K2Widget.prefs(this).edit().putString("name", n).apply();
        K2Widget.redraw(this);
    }

    /** Last-used profile from the page as JSON {n,t,f,s,h,e} ("" clears it); the widget shows it and brews with it.
     *  Stored only if it parses and every field is a number in range, so the widget never has to trust it. */
    @JavascriptInterface public void setProfile(String json) {
        String clean = "";
        if (json != null && !json.isEmpty()) {
            try {
                JSONObject j = new JSONObject(json), o = new JSONObject();
                String n = j.getString("n").trim();
                if (n.isEmpty() || n.length() > 20) return;
                o.put("n", n).put("t", need(j, "t", 0, 130)).put("f", need(j, "f", 0, 9))
                 .put("s", need(j, "s", 0, 255)).put("h", need(j, "h", 0, 255));
                o.put("e", j.isNull("e") ? JSONObject.NULL : need(j, "e", 0, 255));
                clean = o.toString();
            } catch (Exception e) { return; }
        }
        K2Widget.prefs(this).edit().putString("profile", clean).apply();
        K2Widget.redraw(this);
    }

    static int need(JSONObject j, String k, int lo, int hi) throws Exception {
        int v = j.getInt(k);
        if (v < lo || v > hi) throw new IllegalArgumentException(k);
        return v;
    }

    /** Settings a purge replaced, as JSON {f,s,h,e} ("" clears). Kept until something puts them back:
     *  the page on its next connect, or the widget on its next tap. */
    @JavascriptInterface public void setRestore(String json) {
        String clean = "";
        if (json != null && !json.isEmpty()) {
            try {
                JSONObject j = new JSONObject(json), o = new JSONObject();
                o.put("f", need(j, "f", 0, 9)).put("s", need(j, "s", 0, 255)).put("h", need(j, "h", 0, 255));
                o.put("e", j.isNull("e") ? JSONObject.NULL : need(j, "e", 0, 255));
                clean = o.toString();
            } catch (Exception e) { return; }
        }
        K2Widget.prefs(this).edit().putString("restore", clean).apply();
    }

    @JavascriptInterface public String pendingRestore() { return K2Widget.prefs(this).getString("restore", ""); }

    /** Water temp offset from Settings (shown = machine + offset); the widget applies it too. */
    @JavascriptInterface public void setOffset(int o) {
        K2Widget.prefs(this).edit().putInt("offset", Math.max(-10, Math.min(10, o))).apply();
        K2Widget.redraw(this);
    }

    /** One complete frame as hex ("7f 00 05 84 00"; spaces ignored). The page spaces writes 40 ms apart. */
    @JavascriptInterface public void write(String hex) {
        if (hex == null) return;
        final String h = hex.replace(" ", "").toLowerCase();
        if (h.isEmpty() || h.length() % 2 != 0 || h.length() > MAX_FRAME * 2 || !h.matches("[0-9a-f]+")) return;
        main.post(() -> send(h));
    }

    @SuppressWarnings({"MissingPermission", "deprecation"})
    void send(String h) {
        if (gatt == null || tx == null) return;
        byte[] v = new byte[h.length() / 2];
        for (int i = 0; i < v.length; i++) v[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        int type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; // FFF2 is WRITE NO RESPONSE on the K2
        int rc;
        if (Build.VERSION.SDK_INT >= 33) rc = gatt.writeCharacteristic(tx, v, type);
        else { tx.setWriteType(type); tx.setValue(v); rc = gatt.writeCharacteristic(tx) ? 0 : -1; }
        Log.i("K2", "tx " + h + " rc=" + rc); // 0 = queued; 201 = busy
    }

    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            Log.i("K2", "state " + state + " status " + status);
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) g.discoverServices();
            else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                g.close();
                main.post(() -> { if (g == gatt) { gatt = null; tx = null; js("gone", null); K2Widget.setLinked(MainActivity.this, false); } }); // lost the machine
            }
        }

        @Override @SuppressWarnings({"MissingPermission", "deprecation"})
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = status == BluetoothGatt.GATT_SUCCESS ? g.getService(SVC) : null;
            BluetoothGattCharacteristic rx = s == null ? null : s.getCharacteristic(RX), t = s == null ? null : s.getCharacteristic(TX);
            BluetoothGattDescriptor d = rx == null ? null : rx.getDescriptor(CCCD);
            Log.i("K2", "services " + status + " fff0=" + (s != null));
            if (t == null || d == null) { js("fail", "Not a K2 · tap to retry"); g.disconnect(); return; }
            tx = t;
            g.setCharacteristicNotification(rx, true);
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
        }

        @Override @SuppressWarnings("MissingPermission")
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.i("K2", "cccd written " + status);
            if (status != BluetoothGatt.GATT_SUCCESS) { js("fail", "Notify refused · tap to retry"); g.disconnect(); return; }
            // PcmDevice.initConnect waits 300 ms after notify-on before its first command
            String name = g.getDevice().getName();
            main.postDelayed(() -> { if (g == gatt) js("ready", name); }, 300);
            K2Widget.setLinked(MainActivity.this, true); // widget shows the brew buttons
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) { rx(v); }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) rx(c.getValue());
        }

        void rx(byte[] v) {
            if (v == null || v.length == 0 || v.length > 4096) return;
            StringBuilder sb = new StringBuilder();
            for (byte x : v) sb.append(String.format("%02x", x & 0xFF));
            Log.i("K2", "rx " + sb);
            String st = K2Widget.status(MainActivity.this, v); // keep the home-screen widget's battery/temp current
            if (st != null) {
                int state = v[9] & 0xFF;
                String key = st + state;
                // While we hold the link, widget taps must not open a second one (the K2 takes one central).
                K2Widget.prefs(MainActivity.this).edit().putLong("appHold", System.currentTimeMillis() + 5000).apply();
                if (!key.equals(lastStatus)) { lastStatus = key; main.post(() -> K2Widget.show(MainActivity.this, st, state)); }
            }
            js("data", sb.toString());
        }
    };
}
