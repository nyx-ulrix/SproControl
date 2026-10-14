package com.sprocontrol.k2;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
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
    // Updates: GitHub Releases of this repository. Checked only when the user taps Check in Settings.
    static final String REPO = "nyx-ulrix/SproControl";
    static final long MAX_APK = 20L << 20;

    WebView web;
    BluetoothAdapter adapter;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic tx;
    ScanCallback scanCb;
    boolean viaMac;   // this connect is a direct one to the remembered address (no scan, no location); scan if it fails
    String lastStatus;
    int gen;                 // connection generation: stale timeouts/callbacks compare against it
    boolean resumeLink;      // reconnect when the activity comes back
    final Handler main = new Handler(Looper.getMainLooper());
    static MainActivity shown; // the activity on screen, if any: widget taps hand their command to it while it holds the link

    static MainActivity instance; // the activity, while it exists (the link lives with it)

    /** A widget tap while the app holds the link: the app lets go and closes, so the widget can take the machine. */
    static boolean releaseLink() {
        MainActivity a = instance;
        if (a == null) return false;
        a.main.post(() -> { Log.i("K2", "widget takes over: app link dropped, app closed"); a.dropLink(); a.finishAndRemoveTask(); });
        return true;
    }
    final Runnable idleDrop = this::sleepLink;

    @Override protected void onCreate(Bundle b) {
        instance = this;
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
        shown = this;
        LinkService.stopNow(this); // the app takes the machine over from the widgets
        super.onStart();
        main.removeCallbacks(idleDrop);
        web.onResume();
        web.resumeTimers();
        if (resumeLink) { resumeLink = false; startScan(); }
        else if (gatt == null) main.postDelayed(() -> js("auto", null), 600); // opening the app looks for the machine by itself
    }

    /** Off screen: the link (and the widget's live status) stays for IDLE_MS, then sleepLink() runs. */
    @Override protected void onStop() {
        if (shown == this) shown = null;
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

    @Override protected void onDestroy() { if (instance == this) instance = null; main.removeCallbacks(idleDrop); dropLink(); web.destroy(); super.onDestroy(); }

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
        // Reached before? Connect to that address directly: no scan, so no location prompt. Falls back to a scan.
        BluetoothDevice known = viaMac ? null : K2Widget.knownDevice(this, adapter);
        if (known != null) { viaMac = true; open(known); return; }
        viaMac = false;
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
        main.postDelayed(() -> { if (my == gen && gatt != null && tx == null) {
            Log.i("K2", "connect timeout" + (viaMac ? ", scanning instead" : ""));
            if (viaMac) { startScan(); return; }
            disconnect(); js("fail", "Connect timed out · tap to retry"); } }, viaMac ? 8000 : 15000);
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
        K2Widget.setLinked(this, false); // nothing is connected now: the widgets show Search / Connect until a tap reaches the machine
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
            try { clean = cleanProfile(new JSONObject(json)).toString(); } catch (Exception e) { return; }
        }
        K2Widget.prefs(this).edit().putString("profile", clean).apply();
        K2Widget.redraw(this);
    }

    /** A profile with only the fields the widgets use, each checked; throws on anything else. */
    static JSONObject cleanProfile(JSONObject j) throws Exception {
        String n = j.getString("n").trim();
        if (n.isEmpty() || n.length() > 20) throw new IllegalArgumentException("name");
        JSONObject o = new JSONObject().put("n", n).put("t", need(j, "t", 0, 130)).put("f", need(j, "f", 0, 9))
                .put("s", need(j, "s", 0, 255)).put("h", need(j, "h", 0, 255));
        o.put("e", j.isNull("e") ? JSONObject.NULL : need(j, "e", 0, 255));
        return o;
    }

    /** One-button widget: {order:[purge|brew|extract|heat|stop...], profile:{...}|null}. */
    @JavascriptInterface public void setOneWidget(String json) {
        try {
            JSONObject j = new JSONObject(json), o = new JSONObject();
            JSONArray in = j.getJSONArray("order"), order = new JSONArray();
            if (in.length() < 1 || in.length() > 8) return;
            for (int i = 0; i < in.length(); i++) { String s = in.getString(i); if (!K2OneWidget.STEPS.contains(s)) return; order.put(s); }
            o.put("order", order);
            JSONArray secs = new JSONArray(); // one entry per step: a Purge step's own seconds, or null
            JSONArray sin = j.optJSONArray("secs");
            for (int i = 0; i < in.length(); i++) {
                Object v = sin != null && i < sin.length() ? sin.opt(i) : null;
                secs.put(v instanceof Integer && (Integer) v >= 1 && (Integer) v <= 250 ? v : JSONObject.NULL);
            }
            o.put("secs", secs);
            o.put("profile", JSONObject.NULL); // brews use the machine's own settings; nothing to choose here
            K2Widget.prefs(this).edit().putString("one", o.toString()).putInt("oneIdx", 0).apply();
            K2Widget.redraw(this);
        } catch (Exception e) { /* ignored: the page only sends what it built */ }
    }

    @JavascriptInterface public String getOneWidget() { return K2Widget.prefs(this).getString("one", ""); }

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
                if (j.has("t") && !j.isNull("t")) o.put("t", need(j, "t", 0, 130)); // machine-scale °C
                clean = o.toString();
            } catch (Exception e) { return; }
        }
        K2Widget.prefs(this).edit().putString("restore", clean).apply();
    }

    @JavascriptInterface public String pendingRestore() { return K2Widget.prefs(this).getString("restore", ""); }

    /** Low-battery threshold (percent) from Settings; the widgets show their dot below it. */
    @JavascriptInterface public void setLowBatt(int n) {
        if (n < 5 || n > 90) return;
        K2Widget.prefs(this).edit().putInt("lowBatt", n).apply();
        K2Widget.redraw(this);
    }

    /** The brew state: the five values this app just set on the machine and saw read back. Widgets brew with exactly these. */
    @JavascriptInterface public void setBrewState(String json) {
        try {
            JSONObject j = new JSONObject(json), o = new JSONObject();
            o.put("t", need(j, "t", 0, 130)).put("f", need(j, "f", 0, 9)).put("s", need(j, "s", 0, 255)).put("h", need(j, "h", 0, 255));
            o.put("e", j.isNull("e") ? JSONObject.NULL : need(j, "e", 0, 255));
            K2Widget.prefs(this).edit().putString("brew", o.toString()).apply();
            K2Widget.redraw(this);
        } catch (Exception e) { /* ignored: the page only sends what it built */ }
    }

    /** Purge settings from the page: {e: seconds 30..250, f: flow 0..9 or null for the brew's own}. */
    @JavascriptInterface public void setPurge(String json) {
        try {
            JSONObject j = new JSONObject(json), o = new JSONObject().put("e", need(j, "e", 1, 250)); // the K2 stores any 0-255; 1-250 here
            o.put("f", j.isNull("f") ? JSONObject.NULL : need(j, "f", 0, 9));
            K2Widget.prefs(this).edit().putString("purge", o.toString()).apply();
        } catch (Exception e) { /* ignored: the page only sends what it built */ }
    }

    /* ---------- updates (GitHub Releases), started only from Settings ---------- */

    @JavascriptInterface public String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    @JavascriptInterface public void checkUpdate() { new Thread(this::doCheck, "k2-update-check").start(); }

    /** Only https and only GitHub, after redirects too. */
    static HttpsURLConnection open(String url) throws IOException {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol()) || !githubHost(u.getHost())) throw new IOException("Refused: " + u.getHost());
        HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "SproControl");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.connect();
        if (!githubHost(c.getURL().getHost())) throw new IOException("Refused redirect to " + c.getURL().getHost());
        return c;
    }

    static boolean githubHost(String h) {
        return h != null && (h.equals("github.com") || h.equals("api.github.com") || h.endsWith(".github.com") || h.endsWith(".githubusercontent.com"));
    }

    static byte[] readAll(InputStream in, long max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n; long total = 0;
        while ((n = in.read(buf)) > 0) { total += n; if (total > max) throw new IOException("Too large"); out.write(buf, 0, n); }
        return out.toByteArray();
    }

    void doCheck() {
        try {
            HttpsURLConnection c = open("https://api.github.com/repos/" + REPO + "/releases/latest");
            int code = c.getResponseCode();
            if (code == 404) { js("update", "{\"none\":true}"); return; }
            if (code != 200) throw new IOException("GitHub answered " + code);
            JSONObject rel = new JSONObject(new String(readAll(c.getInputStream(), 1 << 20), "UTF-8"));
            String tag = rel.getString("tag_name").replaceFirst("^[vV]", "");
            String url = null; long size = 0;
            JSONArray assets = rel.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size"); break; }
            }
            Matcher m = Pattern.compile("(?i)sha-?256[:\\s]+([0-9a-f]{64})").matcher(rel.optString("body", ""));
            JSONObject out = new JSONObject().put("version", tag).put("newer", newer(tag, version()))
                    .put("url", url == null ? JSONObject.NULL : url).put("size", size)
                    .put("sha", m.find() ? m.group(1).toLowerCase() : JSONObject.NULL)
                    .put("notes", rel.optString("body", "").trim());
            js("update", out.toString());
        } catch (Exception e) {
            js("update", "{\"error\":" + JSONObject.quote(e.getMessage() == null ? e.toString() : e.getMessage()) + "}");
        }
    }

    /** "1.2.0" > "1.0.0"; non-numeric parts count as 0. */
    static boolean newer(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (Exception e) { return 0; } }

    /** Download the release APK, verify its SHA-256 (from the release notes) and its signing certificate,
     *  then hand it to Android's installer. The user confirms there as well. */
    @JavascriptInterface public void installUpdate(String url, String sha) {
        new Thread(() -> doInstall(url, sha == null ? "" : sha.trim().toLowerCase()), "k2-update-download").start();
    }

    void doInstall(String url, String sha) {
        File apk = new File(getCacheDir(), ApkProvider.NAME);
        try {
            HttpsURLConnection c = open(url);
            if (c.getResponseCode() != 200) throw new IOException("Download failed: " + c.getResponseCode());
            long len = c.getContentLengthLong();
            if (len > MAX_APK) throw new IOException("Too large");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[16384]; int n; long total = 0, last = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n; if (total > MAX_APK) throw new IOException("Too large");
                    md.update(buf, 0, n); out.write(buf, 0, n);
                    if (len > 0 && total - last > len / 20) { last = total; js("update", "{\"progress\":" + (int) (100 * total / len) + "}"); }
                }
            }
            String got = hex(md.digest());
            if (!sha.isEmpty() && !sha.equals(got)) throw new IOException("Checksum mismatch");
            // Same signing certificate as the installed app, or Android would refuse it anyway; say so up front.
            PackageManager pm = getPackageManager();
            PackageInfo mine = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (theirs == null || theirs.signingInfo == null) throw new IOException("Not a valid APK");
            Signature[] a = mine.signingInfo.getApkContentsSigners(), b = theirs.signingInfo.getApkContentsSigners();
            if (a.length == 0 || b.length == 0 || !Arrays.equals(a[0].toByteArray(), b[0].toByteArray())) throw new IOException("Signed with a different key");
            if (!getPackageName().equals(theirs.packageName)) throw new IOException("Different package");
            js("update", "{\"installing\":true}");
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + ApkProvider.NAME), "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            main.post(() -> startActivity(i));
        } catch (Exception e) {
            apk.delete();
            js("update", "{\"error\":" + JSONObject.quote(e.getMessage() == null ? e.toString() : e.getMessage()) + "}");
        }
    }

    static String hex(byte[] d) { StringBuilder sb = new StringBuilder(); for (byte x : d) sb.append(String.format("%02x", x & 0xFF)); return sb.toString(); }

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
                main.post(() -> { if (g == gatt) { gatt = null; boolean fresh = tx == null && viaMac; tx = null;
                    if (fresh) { startScan(); return; } // the remembered address did not answer: look for any K2
                    js("gone", null); K2Widget.setLinked(MainActivity.this, false); } }); // lost the machine
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
            // Remember the address (and its type: the K2 may use a random one, which a plain address lookup
            // cannot reach): later connects, app and widgets, go straight to it without a scan.
            K2Widget.prefs(MainActivity.this).edit().putString("mac", g.getDevice().getAddress()).putInt("macType", K2Widget.addressType(g.getDevice())).apply();
            viaMac = false;
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
                android.content.SharedPreferences p = K2Widget.prefs(MainActivity.this);
                p.edit().putLong("appHold", System.currentTimeMillis() + 5000).apply();
                boolean first = !p.getBoolean("linked", false); // the machine has answered: now the widgets may show their buttons
                if (first || !key.equals(lastStatus)) { lastStatus = key; main.post(() -> {
                    if (first) K2Widget.setLinked(MainActivity.this, true);
                    boolean was = K2Widget.runMode(K2Widget.savedState(K2Widget.prefs(MainActivity.this))) >= 0;
                    K2Widget.show(MainActivity.this, st, state);
                    if (was && K2Widget.runMode(state) < 0) K2OneWidget.machineStopped(MainActivity.this); // stopped on the machine or in the app
                }); }
            }
            js("data", sb.toString());
        }
    };
}
