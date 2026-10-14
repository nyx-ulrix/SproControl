package com.sprocontrol.k2;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.bluetooth.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Home-screen widget. Not linked: only a Search button. Linked (last contact worked, or the app is connected):
 *  Extract and Heat & Extract, merged into one Stop plus a running timer while brewing; after the brew the
 *  final time shows for 10 s (tap it to dismiss). The status line leads with the last-used profile, and a
 *  widget-started brew sends that profile's settings first. Purge reads the machine's settings, keeps them
 *  in prefs ("restore"), runs extraction alone for 30 s with no pre-soak or hold; the next tap (or the app's
 *  next connect) sends the kept settings back before anything else. Not linked: the whole widget is dimmed.
 *  Every tap is one short BLE session to the bonded K2: read one status frame, send, disconnect.
 *  Nothing connects on its own: no polling, no alarms except the single one that clears the final time. */
public class K2Widget extends AppWidgetProvider {
    static final String EXTRACT = "com.sprocontrol.k2.EXTRACT", BOTH = "com.sprocontrol.k2.BOTH",
            SEARCH = "com.sprocontrol.k2.SEARCH", CLEAR = "com.sprocontrol.k2.CLEAR", PURGE = "com.sprocontrol.k2.PURGE";
    static final int EXTRACTING = 3, HOT_EXTRACTING = 1, HOT_WATERING = 5; // PcmDeviceState values (status byte 9)
    static final long DONE_MS = 10_000;         // how long the final time stays after a brew
    static final long BREW_MAX_MS = 20 * 60_000; // ponytail: no brew runs this long; an older "running" state is stale
    static final long TAP_TIMEOUT_MS = 8_000;    // under the 10 s a broadcast receiver is allowed to stay alive
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { m.updateAppWidget(ids, views(c)); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("k2", 0); }

    /** Running PcmDeviceState -> the WorkMode that stops it (§5.2), or -1 if idle/complete. */
    static int runMode(int state) {
        return state == HOT_EXTRACTING ? 1 : state == EXTRACTING ? 2 : state == 5 ? 0 : -1;
    }

    /** The saved machine state, unless it says "running" from longer ago than any brew can last. */
    static int savedState(SharedPreferences p) {
        int s = p.getInt("state", 0);
        if (runMode(s) >= 0 && System.currentTimeMillis() - p.getLong("brewWall", 0) > BREW_MAX_MS) return 0;
        return s;
    }

    /** The address type the stack reports for a reached device (public unless it says random); falls back to public. */
    static int addressType(BluetoothDevice d) {
        try { Object t = d.getClass().getMethod("getAddressType").invoke(d); if (t instanceof Integer && (Integer) t == 1) return 1; }
        catch (Exception e) { /* not available: public */ }
        return 0;
    }

    /** The K2 reached before, without a scan: the bonded one, else the remembered address (with its type). */
    @SuppressWarnings("MissingPermission")
    static BluetoothDevice knownDevice(Context c, BluetoothAdapter ad) {
        if (ad == null) return null;
        try { for (BluetoothDevice d : ad.getBondedDevices()) if (MainActivity.isK2(d.getName())) return d; } catch (SecurityException e) { return null; }
        String mac = prefs(c).getString("mac", "");
        if (mac.isEmpty()) return null;
        try {
            if (Build.VERSION.SDK_INT >= 33) return ad.getRemoteLeDevice(mac, prefs(c).getInt("macType", 0) == 1 ? BluetoothDevice.ADDRESS_TYPE_RANDOM : BluetoothDevice.ADDRESS_TYPE_PUBLIC);
            return ad.getRemoteDevice(mac);
        } catch (IllegalArgumentException e) { return null; }
    }

    static JSONObject profile(Context c) {
        try { String s = prefs(c).getString("profile", ""); return s.isEmpty() ? null : new JSONObject(s); }
        catch (Exception e) { return null; }
    }

    static RemoteViews views(Context c) {
        SharedPreferences p = prefs(c);
        long wall = System.currentTimeMillis(), up = SystemClock.elapsedRealtime();
        long brewWall = p.getLong("brewWall", 0), doneMs = p.getLong("doneMs", -1);
        boolean linked = p.getBoolean("linked", false), busy = runMode(savedState(p)) >= 0,
                done = !busy && doneMs >= 0 && wall < p.getLong("doneUntil", 0);
        JSONObject prof = profile(c);
        String status = p.getString("status", "--");
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.wname, p.getString("name", "Ikape K2"));
        int err = p.getInt("err", 0);
        boolean warn = err >= 1 && err <= 3; // dry burning, battery overheating, heating short circuit
        v.setTextViewText(R.id.wstat, (warn ? ERRORS[err] + " · " : "") + (prof == null ? status : status + " · " + prof.optString("n"))); // profile next to the temperature
        v.setViewVisibility(R.id.werr, warn ? android.view.View.VISIBLE : android.view.View.GONE);
        v.setFloat(R.id.wroot, "setAlpha", linked ? 1f : 0.45f);
        int GONE = android.view.View.GONE, SHOWN = android.view.View.VISIBLE;
        // Timer: counts up while brewing; frozen at the final time for DONE_MS after.
        // Timestamps are wall-clock in storage and converted to the chronometer's uptime base here, so they survive a reboot.
        if (busy && brewWall > 0) { v.setViewVisibility(R.id.wtimer, SHOWN); v.setChronometer(R.id.wtimer, up - (wall - brewWall), null, true); }
        else if (done) { v.setViewVisibility(R.id.wtimer, SHOWN); v.setChronometer(R.id.wtimer, up - doneMs, null, false); }
        else v.setViewVisibility(R.id.wtimer, GONE);
        // Not linked: Search only. Linked: brew buttons; while brewing they merge into one Stop
        // (the Extract slot; it stops whatever runs). The final time replaces them until it clears.
        boolean searching = wall < p.getLong("tapBusy", 0); // a tap is in progress: spinner until the machine confirms
        v.setViewVisibility(R.id.wbusy, searching ? SHOWN : GONE); // spinner while a tap looks for the machine
        v.setViewVisibility(R.id.wopen, linked || searching ? GONE : SHOWN);
        v.setViewVisibility(R.id.wextract, linked && !done && !searching ? SHOWN : GONE);
        v.setViewVisibility(R.id.wboth, linked && !busy && !done && !searching ? SHOWN : GONE);
        v.setViewVisibility(R.id.wpurge, linked && !busy && !done && !searching ? SHOWN : GONE);
        v.setImageViewResource(R.id.wextract, busy ? R.drawable.ic_stop : R.drawable.ic_extract);
        v.setOnClickPendingIntent(R.id.wextract, action(c, EXTRACT, 1));
        v.setOnClickPendingIntent(R.id.wboth, action(c, BOTH, 2));
        v.setOnClickPendingIntent(R.id.wpurge, action(c, PURGE, 5));
        v.setOnClickPendingIntent(R.id.wopen, action(c, SEARCH, 3));
        v.setOnClickPendingIntent(R.id.wtimer, action(c, CLEAR, 4));
        v.setOnClickPendingIntent(R.id.wtext, PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        return v;
    }

    /** Explicit, immutable intents to our own (non-exported) receiver: nothing else can send them. */
    static PendingIntent action(Context c, String a, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, K2Widget.class).setAction(a), PendingIntent.FLAG_IMMUTABLE);
    }

    static void redraw(Context c) {
        AppWidgetManager.getInstance(c).updateAppWidget(new ComponentName(c, K2Widget.class), views(c));
        K2OneWidget.redraw(c);
    }

    /** Save what the machine is doing and redraw. state < 0 keeps the saved state.
     *  A change into a running state starts the timer; a change out of it freezes the final time for DONE_MS. */
    static void show(Context c, String status, int state) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        if (status != null) e.putString("status", status);
        if (state >= 0) {
            boolean was = runMode(savedState(p)) >= 0, is = runMode(state) >= 0;
            long wall = System.currentTimeMillis();
            e.putLong("statusAt", wall); // how fresh the saved state is
            if (is && !was) e.putLong("brewWall", wall).putLong("doneMs", -1);
            if (!is && was) {
                long st = p.getLong("brewWall", 0);
                if (st > 0) {
                    e.putLong("doneMs", wall - st).putLong("doneUntil", wall + DONE_MS);
                    // Clear the final time even if this process is gone by then: one inexact alarm, no wakeup.
                    c.getSystemService(AlarmManager.class).set(AlarmManager.ELAPSED_REALTIME,
                            SystemClock.elapsedRealtime() + DONE_MS + 50, action(c, CLEAR, 4));
                }
                e.putLong("brewWall", 0);
            }
            e.putInt("state", state);
        }
        e.apply();
        redraw(c);
    }

    /** Whether the widget shows the brew buttons (true) or only Search (false). */
    /** Unlinked (app link dropped, tap failed): the one-button widget's cycle starts over at Connect → first step. */
    static void setLinked(Context c, boolean linked) {
        SharedPreferences.Editor e = prefs(c).edit().putBoolean("linked", linked);
        if (!linked) e.putInt("oneIdx", 0);
        e.apply();
        redraw(c);
    }

    /** A complete 0x7E frame with a good checksum (sum of every other byte). */
    static boolean valid(byte[] v) {
        if (v == null || v.length < 5 || (v[0] & 0xFF) != 0x7E || (((v[1] & 0x3F) << 8) | (v[2] & 0xFF)) != v.length) return false;
        int sum = 0;
        for (int i = 0; i < v.length; i++) if (i != 3) sum += v[i] & 0xFF;
        return (sum & 0xFF) == (v[3] & 0xFF);
    }

    /** Status frame (cmd 0x00, PROTOCOL.md §4.3) -> "61% · 60°C" (temp + the app's water offset), or null. */
    static final String[] ERRORS = {"", "Dry burning", "Battery overheating", "Heating short circuit", "Low battery (hot)", "Low battery"};

    static String status(Context c, byte[] v) {
        if (!valid(v) || v.length < 11 || v[4] != 0) return null;
        int err = v.length > 10 ? v[10] & 0xFF : 0;
        if (err != prefs(c).getInt("err", 0)) prefs(c).edit().putInt("err", err > 5 ? 0 : err).apply(); // the widgets warn on 1..3
        return (v[6] & 0x7F) + "% · " + ((v[8] & 0xFF) + prefs(c).getInt("offset", 0)) + "°C";
    }

    /** SET_WORK_PARAM carrying the settings a purge replaced, or null if nothing is pending. */
    static byte[] restoreFrame(Context c) {
        try {
            String s = prefs(c).getString("restore", "");
            if (s.isEmpty()) return null;
            JSONObject j = new JSONObject(s);
            return j.isNull("e") ? frame(0x18, j.getInt("f"), j.getInt("s"), j.getInt("h"))
                                 : frame(0x18, j.getInt("f"), j.getInt("s"), j.getInt("h"), j.getInt("e"));
        } catch (Exception e) { return null; }
    }

    static byte[] frame(int cmd, int... data) {
        byte[] f = new byte[data.length + 5];
        f[0] = 0x7F; f[1] = (byte) (f.length >> 8); f[2] = (byte) f.length; f[4] = (byte) cmd;
        for (int i = 0; i < data.length; i++) f[5 + i] = (byte) Math.max(0, Math.min(255, data[i]));
        int sum = 0;
        for (byte b : f) sum += b & 0xFF;
        f[3] = (byte) sum;
        return f;
    }

    /** The last-used profile as SET_TEMP (°F on the K2) + SET_WORK_PARAM frames, or none.
     *  MainActivity.setProfile only stores validated values; frame() clamps to a byte regardless. */
    static List<byte[]> profileFrames(Context c) { return profileFrames(c, profile(c)); }

    static List<byte[]> profileFrames(Context c, JSONObject j) {
        List<byte[]> out = new ArrayList<>();
        if (j == null) return out;
        try {
            // profile temps are shown (offset) °C; the machine wants its own scale, in °F
            out.add(frame(0x16, Math.round((j.getInt("t") - prefs(c).getInt("offset", 0)) * 1.8f + 32)));
            out.add(j.isNull("e") ? frame(0x18, j.getInt("f"), j.getInt("s"), j.getInt("h"))
                                  : frame(0x18, j.getInt("f"), j.getInt("s"), j.getInt("h"), j.getInt("e")));
        } catch (Exception e) { out.clear(); }
        return out;
    }

    /** Purge settings: the flow and seconds chosen in the app's Settings (else `flow` and 30 s), pre-soak 0, hold 0. */
    static byte[] purgeFrame(Context c, int flow, boolean ext) {
        int f = flow, e = 30;
        try { JSONObject j = new JSONObject(prefs(c).getString("purge", "{}")); if (!j.isNull("f")) f = j.getInt("f"); e = j.optInt("e", 30); }
        catch (Exception x) { /* defaults */ }
        return ext ? frame(0x18, f, 0, 0, e) : frame(0x18, f, 0, 0);
    }

    static byte[] purgeFrame(Context c) {
        JSONObject j = profile(c);
        int flow = 1;
        try { if (j != null) flow = j.getInt("f"); } catch (Exception e) { /* medium */ }
        return purgeFrame(c, flow, j == null || !j.isNull("e"));
    }

    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        String a = i.getAction();
        if (EXTRACT.equals(a)) new Tap(c, 2, EXTRACTING, goAsync()).run();        // mode extraction
        else if (BOTH.equals(a)) new Tap(c, 1, HOT_EXTRACTING, goAsync()).withProfile(K2OneWidget.brewProfile(c)).run();  // mode hotExtraction, chosen profile
        else if (PURGE.equals(a)) new Tap(c, 2, EXTRACTING, goAsync()).purge().run();  // extraction with 0/0/30 s
        else if (SEARCH.equals(a)) new Tap(c, -1, -1, goAsync()).run();           // just reach it and read the state
        else if (CLEAR.equals(a)) { prefs(c).edit().putLong("doneUntil", 0).apply(); redraw(c); }
    }

    /** One widget tap = one short BLE session. mode < 0 (Search) only reads. All state is touched on the main thread. */
    // ---- the held link: after a tap the connection stays open for LINGER_MS (sliding), kept alive by LinkService ----
    static final long LINGER_MS = 60_000;
    static Tap held;                 // the session that owns the open GATT link, if any
    static byte[] lastStatus;        // the newest status frame on that link (under a second old while it streams)

    /** Drops the held link (the app is about to connect, or a minute passed with no tap). */
    @SuppressWarnings("MissingPermission")
    static void dropHeld(Context c) {
        Tap h = held;
        if (h == null) return;
        held = null; lastStatus = null;
        Log.i("K2", "widget: link dropped");
        if (h.gatt != null) { h.gatt.disconnect(); h.gatt.close(); h.gatt = null; }
        h.tx = null;
        if (h.delegate != null) h.delegate.finish(null, -1);
        setLinked(c, false);
    }

    static class Tap extends BluetoothGattCallback {
        final Context c; final int mode, runningState; final PendingResult done;
        BluetoothGatt gatt; BluetoothGattCharacteristic tx; boolean handled, finished, isPurge, wantParams, stopOnly;
        JSONObject profileOverride, want;       // want: the profile a start must see read back before it goes out
        boolean needTemp, needParam; int lastState, tries; List<byte[]> setFrames;
        Boolean confirmRunning;                 // after the command: wait for a status frame saying running (true) / idle (false)
        Runnable onDone;                        // runs once the machine has confirmed the command (the cycle advances here)
        byte[] buf = new byte[0];               // notifications carry parts of frames, or several at once
        Tap delegate;                           // a later tap riding this session's link; frames go to it while it runs
        Tap link;                               // the session whose link this tap rides (null: its own)
        long t0 = System.currentTimeMillis();
        String status;

        Tap(Context c, int mode, int runningState, PendingResult done) {
            this.c = c.getApplicationContext(); this.mode = mode; this.runningState = runningState; this.done = done;
        }

        Tap purge() { isPurge = true; return this; }
        Tap stopOnly() { stopOnly = true; return this; }                       // never starts anything
        Tap withProfile(JSONObject p) { profileOverride = p; return this; }    // settings sent before the start
        Tap then(Runnable r) { onDone = r; return this; }

        String name() { return mode < 0 ? "search" : isPurge ? "purge" : stopOnly ? "stop" : mode == 1 ? "both" : mode == 2 ? "extract" : "heat"; }

        /** Never scans, so location is never needed: it connects to the bonded K2 or to the address the app last reached. */
        @SuppressWarnings("MissingPermission")
        void run() {
            prefs(c).edit().putLong("tapBusy", System.currentTimeMillis() + TAP_TIMEOUT_MS + 7000).apply(); // spinner until finish()
            // The app holds the only link the K2 accepts: hand the command to it instead of opening a second one.
            if (System.currentTimeMillis() < prefs(c).getLong("appHold", 0)) {
                handled = true;
                if (mode >= 0 && MainActivity.fromWidget(name())) { if (onDone != null) onDone.run(); finish("Sent via app", -1); }
                else finish("Use the app", -1);
                return;
            }
            LinkService.touch(c); // keeps the process (and the link below) alive for a minute after this tap
            // A link is still open from a recent tap: use it, with its newest status frame, right now.
            Tap h = held;
            if (h != null && h.gatt != null && h.tx != null && h.delegate == null) {
                link = h; gatt = h.gatt; tx = h.tx; h.delegate = this;
                Log.i("K2", "widget " + name() + ": on the held link");
                if (lastStatus != null) rx(gatt, lastStatus);
                else MAIN.postDelayed(() -> { if (!handled && !finished) { handled = true; decide(gatt, savedState(prefs(c))); } }, 150);
                return;
            }
            BluetoothAdapter ad = ((BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
            if (ad == null || !ad.isEnabled()) { finish("Bluetooth off", -1); return; }
            BluetoothDevice dev = knownDevice(c, ad);
            if (dev == null) { finish("Connect in app first", -1); return; }
            Log.i("K2", "widget " + name() + ": connecting " + dev.getAddress() + (dev.getBondState() == BluetoothDevice.BOND_BONDED ? " (bonded)" : ""));
            show(c, mode >= 0 ? "Connecting…" : "Searching…", -1);
            MAIN.postDelayed(() -> { if (!handled) finish(mode >= 0 ? "Not reachable" : "Not found", -1); }, TAP_TIMEOUT_MS);
            gatt = dev.connectGatt(c, false, this, BluetoothDevice.TRANSPORT_LE);
        }

        /** Done with this tap. The link stays open (held) for the next one; LinkService drops it after a quiet minute. */
        void finish(String msg, int state) {
            if (finished) return;
            finished = true;
            Log.i("K2", "widget " + name() + ": " + (msg == null ? "done" : msg) + " after " + (System.currentTimeMillis() - t0) + " ms");
            SharedPreferences.Editor e = prefs(c).edit().putBoolean("linked", handled).remove("tapBusy"); // reached it -> brew buttons; else -> Search
            if (!handled) e.putInt("oneIdx", 0); // and the one-button cycle starts over
            e.apply();
            show(c, msg, state);
            if (link != null) { if (link.delegate == this) link.delegate = null; }
            else if (handled && gatt != null && tx != null) { held = this; }
            else if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
            done.finish();
        }

        @Override @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) g.discoverServices();
            else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS)
                MAIN.post(() -> {
                    if (held == this) { held = null; lastStatus = null; setLinked(c, false); Log.i("K2", "widget: link lost"); }
                    g.close(); gatt = null; tx = null;
                    if (delegate != null) delegate.finish(delegate.handled ? "Link lost" : "Not reachable", -1);
                    finish(handled ? null : mode < 0 ? "Not found" : "Not reachable", -1);
                });
        }

        @Override @SuppressWarnings({"MissingPermission", "deprecation"})
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = status == BluetoothGatt.GATT_SUCCESS ? g.getService(MainActivity.SVC) : null;
            BluetoothGattCharacteristic rx = s == null ? null : s.getCharacteristic(MainActivity.RX), t = s == null ? null : s.getCharacteristic(MainActivity.TX);
            BluetoothGattDescriptor d = rx == null ? null : rx.getDescriptor(MainActivity.CCCD);
            if (t == null || d == null) { MAIN.post(() -> finish("Not a K2?", -1)); return; }
            tx = t;
            g.setCharacteristicNotification(rx, true);
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
        }

        /** Notify-on done: act at once on the last known state (the K2's own status push only starts a few seconds
         *  after it has been given the clock), then send the clock and ask for the state until it answers. */
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.i("K2", "widget: cccd written " + status + " at " + (System.currentTimeMillis() - t0) + " ms");
            if (status != BluetoothGatt.GATT_SUCCESS) { MAIN.post(() -> finish("Not a K2?", -1)); return; }
            // The K2 drops writes for about 400 ms after notify-on (HappyGo waits 300 ms before its first command).
            // ...acting on the saved state only while it is fresh (two minutes); after a longer idle, wait for a real status frame.
            boolean fresh = System.currentTimeMillis() - prefs(c).getLong("statusAt", 0) < 120_000;
            if (mode >= 0 && fresh) MAIN.postDelayed(() -> { if (!handled && !finished) { handled = true; decide(g, savedState(prefs(c))); } }, 350);
            MAIN.postDelayed(() -> write(g, timeFrame()), 1200);
            for (int i = 0; i < 5; i++) MAIN.postDelayed(() -> { if (lastStatus == null && !finished) write(g, frame(0x00)); }, 900 + 1000L * i);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] v) { MAIN.post(() -> feed(g, v)); }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (Build.VERSION.SDK_INT < 33) { byte[] v = ch.getValue(); MAIN.post(() -> feed(g, v)); }
        }

        /** Reassembles frames: a notification may hold two replies, or half of a long one (the app's log shows both). */
        void feed(BluetoothGatt g, byte[] v) {
            if (v == null) return;
            Log.i("K2", "widget rx " + hex(v));
            byte[] n = new byte[buf.length + v.length];
            System.arraycopy(buf, 0, n, 0, buf.length); System.arraycopy(v, 0, n, buf.length, v.length);
            buf = n;
            while (true) {
                int start = 0;
                while (start < buf.length && (buf[start] & 0xFF) != 0x7E) start++;
                if (start > 0) buf = java.util.Arrays.copyOfRange(buf, start, buf.length);
                if (buf.length < 3) return;
                int len = ((buf[1] & 0x3F) << 8) | (buf[2] & 0xFF);
                if (len < 5) { buf = java.util.Arrays.copyOfRange(buf, 1, buf.length); continue; }
                if (buf.length < len) return;
                byte[] f = java.util.Arrays.copyOfRange(buf, 0, len);
                buf = java.util.Arrays.copyOfRange(buf, len, buf.length);
                if (!valid(f)) { Log.i("K2", "widget: bad checksum, dropped"); continue; }
                if (f[4] == 0 && f.length >= 11) lastStatus = f;
                Tap t = delegate != null && !delegate.finished ? delegate : this;
                if (t.finished) { String st = status(c, f); if (st != null) show(c, st, f[9] & 0xFF); } // lingering: keep the widget's readout fresh
                else t.rx(g, f);
            }
        }

        static byte[] timeFrame() {
            java.util.Calendar t = java.util.Calendar.getInstance();
            int wd = t.get(java.util.Calendar.DAY_OF_WEEK) - 1; if (wd == 0) wd = 7;
            return frame(0x04, wd, t.get(java.util.Calendar.MONTH) + 1, t.get(java.util.Calendar.DAY_OF_MONTH), t.get(java.util.Calendar.YEAR) - 2020,
                    t.get(java.util.Calendar.HOUR_OF_DAY), t.get(java.util.Calendar.MINUTE), t.get(java.util.Calendar.SECOND));
        }

        @SuppressWarnings({"MissingPermission", "deprecation"})
        void write(BluetoothGatt g, byte[] f) {
            if (g == null || tx == null) return;
            int type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, rc;
            try {
                if (Build.VERSION.SDK_INT >= 33) rc = g.writeCharacteristic(tx, f, type);
                else { tx.setWriteType(type); tx.setValue(f); rc = g.writeCharacteristic(tx) ? 0 : -1; }
            } catch (Exception e) { rc = -1; }
            Log.i("K2", "widget tx " + hex(f) + " rc=" + rc + " at " + (System.currentTimeMillis() - t0) + " ms");
            if (rc != 0 && rc != -1) MAIN.postDelayed(() -> write(g, f), 60); // 201 = the stack was busy: send it again
        }

        static String hex(byte[] v) { StringBuilder sb = new StringBuilder(); for (byte x : v) sb.append(String.format("%02x", x & 0xFF)); return sb.toString(); }

        void rx(BluetoothGatt g, byte[] v) {
            if (finished) return;
            // Start, step 2: the read-backs of the settings just sent. All five must match or nothing starts.
            if (want != null) {
                if (v[4] == 0x15 && v.length >= 8 && needTemp) {
                    needTemp = false;
                    int got = Math.round(((v[7] & 0xFF) - 32) / 1.8f), t = want.optInt("t") - prefs(c).getInt("offset", 0);
                    if (got != t) { Log.i("K2", "widget: temp read back " + got + " not " + t); retry("Temp not accepted"); return; }
                } else if (v[4] == 0x17 && v.length >= 12 && needParam) {
                    needParam = false;
                    boolean ok = (v[5] & 0xFF) == want.optInt("f") && (v[6] & 0xFF) == want.optInt("s") && (v[9] & 0xFF) == want.optInt("h")
                            && (want.isNull("e") || (v.length > 12 && (v[12] & 0xFF) == want.optInt("e")));
                    if (!ok) { Log.i("K2", "widget: settings read back differ from the profile"); retry("Settings not accepted"); return; }
                }
                if (!needTemp && !needParam) { want = null; go(g, frame(0x02, mode, 1), null, true); }
                else if (v[4] != 0) return;
            }
            // Purge, step 2: the machine's current settings arrive; keep them, then purge with its own flow speed.
            if (wantParams && v.length >= 12 && v[4] == 0x17) {
                wantParams = false;
                int f = v[5] & 0xFF, s = v[6] & 0xFF, h = v[9] & 0xFF;
                boolean ext = v.length > 14;
                byte[] purge = purgeFrame(c, f, ext);
                // Already on the purge settings: nothing to swap now or put back later.
                if (s == 0 && h == 0 && (purge[5] & 0xFF) == f && (!ext || (v[12] & 0xFF) == (purge[8] & 0xFF))) { go(g, null, frame(0x02, 2, 1), true); return; }
                try {
                    JSONObject j = new JSONObject().put("f", f).put("s", s).put("h", h);
                    j.put("e", ext ? v[12] & 0xFF : JSONObject.NULL);
                    prefs(c).edit().putString("restore", j.toString()).apply();
                } catch (Exception e) { /* nothing kept; the profile still restores on the next brew */ }
                go(g, purge, frame(0x02, 2, 1), true);
                return;
            }
            // The K2 acknowledges a start/stop (0x02 reply, 01) within ~70 ms: that is the confirmation the next
            // button waits for; the once-a-second state push would add up to a second.
            if (confirmRunning != null && v[4] == 0x02 && v.length >= 6 && (v[5] & 0xFF) == 1) {
                int st8 = confirmRunning ? runningState : 0;
                if (lastStatus != null && lastStatus.length > 9) lastStatus[9] = (byte) st8; // the next tap must not see the old state
                Log.i("K2", "widget: acknowledged at " + (System.currentTimeMillis() - t0) + " ms");
                if (onDone != null) onDone.run();
                finish(status, st8);
                return;
            }
            String st = status(c, v);
            if (st == null) return;
            status = st;
            int now = v[9] & 0xFF;
            lastState = now;
            // Waiting for the machine to confirm the command: the spinner stays until its state says so.
            if (confirmRunning != null) {
                if (confirmRunning == (runMode(now) >= 0)) { Log.i("K2", "widget: confirmed, state " + now); if (onDone != null) onDone.run(); finish(st, now); }
                else show(c, st, -1);
                return;
            }
            if (handled) return;
            handled = true;
            decide(g, now);
        }

        /** The command, from the machine's state (a fresh frame, or the saved one right after connecting). */
        void decide(BluetoothGatt g, int now) {
            // Stop if the machine is running anything, or if the widget is showing Stop
            // (so a Stop tap can never start a brew, even if the saved state is stale).
            int running = runMode(now) >= 0 ? runMode(now) : runMode(savedState(prefs(c)));
            boolean stop = running >= 0;
            byte[] restore = stop ? null : restoreFrame(c);
            if (mode < 0) {
                // Search: just reach it; if a purge left settings behind and the machine is idle, put them back now.
                if (restore != null) { prefs(c).edit().remove("restore").apply(); sendOnly(g, java.util.Collections.singletonList(restore)); }
                finish(status, now);
                return;
            }
            if (stop) { go(g, null, frame(0x02, running, 0), false); return; }
            if (stopOnly) { if (onDone != null) onDone.run(); finish(status, now); return; } // nothing running: a Stop tap does nothing
            if (lastStatus != null && lastStatus.length > 10 && (lastStatus[10] & 0xFF) != 0) { finish("Machine error " + (lastStatus[10] & 0xFF), now); return; }
            if (isPurge) {
                // Purge, step 1: ask for the current settings so they can be kept (step 2 above); fall back after 1.5 s.
                wantParams = true;
                write(g, frame(0x17));
                MAIN.postDelayed(() -> { if (wantParams && !finished) { wantParams = false; go(g, purgeFrame(c), frame(0x02, 2, 1), true); } }, 1500);
                return;
            }
            // Start, step 1: settings a purge replaced go back first, then the profile, then a read of both
            // (step 2 above sends the start once they match). No profile: start with whatever the machine holds.
            List<byte[]> frames = new ArrayList<>();
            if (restore != null) { frames.add(restore); prefs(c).edit().remove("restore").apply(); }
            JSONObject p = profileOverride != null ? profileOverride : profile(c);
            frames.addAll(profileFrames(c, p));
            if (p == null) { frames.add(frame(0x02, mode, 1)); sendOnly(g, frames); await(true); return; }
            want = p; needTemp = needParam = true; setFrames = frames;
            frames.add(frame(0x15)); frames.add(frame(0x17));
            Log.i("K2", "widget mode " + mode + ": " + (frames.size() - 2) + " settings frames, then read-back (machine state " + now + ")");
            sendOnly(g, frames);
            // The K2 drops the odd write: ask once more, then give up without starting.
            MAIN.postDelayed(() -> { if (!finished && want != null && tries == 0) retry("Settings unconfirmed"); }, 1500);
            MAIN.postDelayed(() -> { if (!finished && want != null) finish("Settings unconfirmed", lastState); }, 4500);
        }

        /** A read-back that differs or never came: the sets were probably dropped, so send them (and the reads) again, twice at most. */
        void retry(String why) {
            if (++tries > 2 || setFrames == null) { finish(why, lastState); return; }
            Log.i("K2", "widget: " + why + ", sending the settings again (" + tries + ")");
            needTemp = needParam = true;
            MAIN.postDelayed(() -> { if (!finished && want != null) sendOnly(gatt, setFrames); }, 150);
        }

        /** One or two writes, then wait for the machine's state to confirm them. */
        void go(BluetoothGatt g, byte[] a, byte[] b, boolean running) {
            List<byte[]> frames = new ArrayList<>();
            if (a != null) frames.add(a);
            if (b != null) frames.add(b);
            Log.i("K2", "widget sends " + frames.size() + " frame(s), expecting " + (running ? "running" : "idle") + " at " + (System.currentTimeMillis() - t0) + " ms");
            sendOnly(g, frames);
            await(running);
        }

        /** Spinner until a status frame shows the machine running (or idle, for a stop); a few seconds, then give up. */
        void await(boolean running) {
            confirmRunning = running;
            MAIN.postDelayed(() -> { if (!finished) { Log.i("K2", "widget: no confirmation"); finish("Not confirmed", lastState); } }, 6000);
        }

        void sendOnly(BluetoothGatt g, List<byte[]> frames) {
            for (int i = 0; i < frames.size(); i++) { byte[] f = frames.get(i); MAIN.postDelayed(() -> write(g, f), 80L * i); }
        }
    }
}
