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
    static final int EXTRACTING = 3, HOT_EXTRACTING = 1; // PcmDeviceState values (status byte 9)
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
        v.setTextViewText(R.id.wstat, prof == null ? status : status + " · " + prof.optString("n")); // profile next to the temperature
        v.setFloat(R.id.wroot, "setAlpha", linked ? 1f : 0.45f);
        int GONE = android.view.View.GONE, SHOWN = android.view.View.VISIBLE;
        // Timer: counts up while brewing; frozen at the final time for DONE_MS after.
        // Timestamps are wall-clock in storage and converted to the chronometer's uptime base here, so they survive a reboot.
        if (busy && brewWall > 0) { v.setViewVisibility(R.id.wtimer, SHOWN); v.setChronometer(R.id.wtimer, up - (wall - brewWall), null, true); }
        else if (done) { v.setViewVisibility(R.id.wtimer, SHOWN); v.setChronometer(R.id.wtimer, up - doneMs, null, false); }
        else v.setViewVisibility(R.id.wtimer, GONE);
        // Not linked: Search only. Linked: brew buttons; while brewing they merge into one Stop
        // (the Extract slot; it stops whatever runs). The final time replaces them until it clears.
        v.setViewVisibility(R.id.wopen, linked ? GONE : SHOWN);
        v.setViewVisibility(R.id.wextract, linked && !done ? SHOWN : GONE);
        v.setViewVisibility(R.id.wboth, linked && !busy && !done ? SHOWN : GONE);
        v.setViewVisibility(R.id.wpurge, linked && !busy && !done ? SHOWN : GONE);
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
    static void setLinked(Context c, boolean linked) {
        prefs(c).edit().putBoolean("linked", linked).apply();
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
    static String status(Context c, byte[] v) {
        if (!valid(v) || v.length < 11 || v[4] != 0) return null;
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
    static List<byte[]> profileFrames(Context c) {
        List<byte[]> out = new ArrayList<>();
        JSONObject j = profile(c);
        if (j == null) return out;
        try {
            // profile temps are shown (offset) °C; the machine wants its own scale, in °F
            out.add(frame(0x16, Math.round((j.getInt("t") - prefs(c).getInt("offset", 0)) * 1.8f + 32)));
            out.add(j.isNull("e") ? frame(0x18, j.getInt("f"), j.getInt("s"), j.getInt("h"))
                                  : frame(0x18, j.getInt("f"), j.getInt("s"), j.getInt("h"), j.getInt("e")));
        } catch (Exception e) { out.clear(); }
        return out;
    }

    /** Purge settings: the profile's flow (medium if none), pre-soak 0, hold 0, extraction 30 s. */
    static byte[] purgeFrame(Context c) {
        JSONObject j = profile(c);
        int flow = 1;
        try { if (j != null) flow = j.getInt("f"); } catch (Exception e) { /* keep medium */ }
        return j != null && j.isNull("e") ? frame(0x18, flow, 0, 0) : frame(0x18, flow, 0, 0, 30);
    }

    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        String a = i.getAction();
        if (EXTRACT.equals(a)) new Tap(c, 2, EXTRACTING, goAsync()).run();        // mode extraction
        else if (BOTH.equals(a)) new Tap(c, 1, HOT_EXTRACTING, goAsync()).run();  // mode hotExtraction
        else if (PURGE.equals(a)) new Tap(c, 2, EXTRACTING, goAsync()).purge().run();  // extraction with 0/0/30 s
        else if (SEARCH.equals(a)) new Tap(c, -1, -1, goAsync()).run();           // just reach it and read the state
        else if (CLEAR.equals(a)) { prefs(c).edit().putLong("doneUntil", 0).apply(); redraw(c); }
    }

    /** One widget tap = one short BLE session. mode < 0 (Search) only reads. All state is touched on the main thread. */
    static class Tap extends BluetoothGattCallback {
        final Context c; final int mode, runningState; final PendingResult done;
        BluetoothGatt gatt; BluetoothGattCharacteristic tx; boolean handled, finished, isPurge, wantParams;
        String status;

        Tap(Context c, int mode, int runningState, PendingResult done) {
            this.c = c.getApplicationContext(); this.mode = mode; this.runningState = runningState; this.done = done;
        }

        Tap purge() { isPurge = true; return this; }

        @SuppressWarnings("MissingPermission")
        void run() {
            // The app holds the only link the K2 accepts; a second one would just fail and unlink the widget.
            if (System.currentTimeMillis() < prefs(c).getLong("appHold", 0)) { handled = true; finish("Use the app", -1); return; }
            BluetoothAdapter ad = ((BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
            BluetoothDevice dev = null;
            try {
                if (ad != null && ad.isEnabled()) for (BluetoothDevice d : ad.getBondedDevices()) if (MainActivity.isK2(d.getName())) dev = d;
            } catch (SecurityException e) { finish("Open app once", -1); return; }
            if (dev == null) { finish(ad != null && ad.isEnabled() ? "Pair in app first" : "Bluetooth off", -1); return; }
            show(c, mode >= 0 ? "Connecting…" : "Searching…", -1);
            MAIN.postDelayed(() -> finish(mode >= 0 ? "Not reachable" : "Not found", -1), TAP_TIMEOUT_MS);
            gatt = dev.connectGatt(c, false, this, BluetoothDevice.TRANSPORT_LE);
        }

        @SuppressWarnings("MissingPermission")
        void finish(String msg, int state) {
            if (finished) return;
            finished = true;
            prefs(c).edit().putBoolean("linked", handled).apply(); // reached it -> brew buttons; else -> Search
            show(c, msg, state);
            if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
            done.finish();
        }

        @Override @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) g.discoverServices();
            else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS)
                MAIN.post(() -> finish(handled ? null : mode < 0 ? "Not found" : "Not reachable", -1));
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

        // The K2 pushes a status frame about once a second; the first one decides start vs stop.
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] v) { MAIN.post(() -> rx(g, v)); }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (Build.VERSION.SDK_INT < 33) { byte[] v = ch.getValue(); MAIN.post(() -> rx(g, v)); }
        }

        @SuppressWarnings({"MissingPermission", "deprecation"})
        void write(BluetoothGatt g, byte[] f) {
            if (finished || tx == null) return;
            int type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(tx, f, type);
            else { tx.setWriteType(type); tx.setValue(f); g.writeCharacteristic(tx); }
        }

        void rx(BluetoothGatt g, byte[] v) {
            if (finished) return;
            // Purge, step 2: the machine's current settings arrive; keep them, then purge with its own flow speed.
            if (wantParams && valid(v) && v.length >= 12 && v[4] == 0x17) {
                wantParams = false;
                int f = v[5] & 0xFF, s = v[6] & 0xFF, h = v[9] & 0xFF;
                boolean ext = v.length > 14;
                try {
                    JSONObject j = new JSONObject().put("f", f).put("s", s).put("h", h);
                    j.put("e", ext ? v[12] & 0xFF : JSONObject.NULL);
                    prefs(c).edit().putString("restore", j.toString()).apply();
                } catch (Exception e) { /* nothing kept; the profile still restores on the next brew */ }
                go(g, ext ? frame(0x18, f, 0, 0, 30) : frame(0x18, f, 0, 0), frame(0x02, 2, 1), runningState);
                return;
            }
            String st = status(c, v);
            if (st == null || handled) return;
            handled = true;
            status = st;
            int now = v[9] & 0xFF;
            // Stop if the machine is running anything, or if the widget is showing Stop
            // (so a Stop tap can never start a brew, even if the saved state is stale).
            int running = runMode(now) >= 0 ? runMode(now) : runMode(savedState(prefs(c)));
            boolean stop = running >= 0;
            byte[] restore = stop ? null : restoreFrame(c);
            if (mode < 0) {
                // Search: just reach it; if a purge left settings behind and the machine is idle, put them back now.
                if (restore != null) { prefs(c).edit().remove("restore").apply(); go(g, restore, null, now); } else finish(st, now);
                return;
            }
            if (stop) { go(g, null, frame(0x02, running, 0), 0); return; }
            if (isPurge) {
                // Purge, step 1: ask for the current settings so they can be kept (step 2 above); fall back after 1.5 s.
                wantParams = true;
                write(g, frame(0x17));
                MAIN.postDelayed(() -> { if (wantParams && !finished) { wantParams = false; go(g, purgeFrame(c), frame(0x02, 2, 1), runningState); } }, 1500);
                return;
            }
            // Start: settings a purge replaced go back first, then the last-used profile, then the start.
            List<byte[]> frames = new ArrayList<>();
            if (restore != null) { frames.add(restore); prefs(c).edit().remove("restore").apply(); }
            frames.addAll(profileFrames(c));
            frames.add(frame(0x02, mode, 1));
            Log.i("K2", "widget start mode " + mode + " with " + (frames.size() - 1) + " settings frames (machine state " + now + ")");
            sendAll(g, frames, runningState);
        }

        /** One or two writes, then finish showing `state`. */
        void go(BluetoothGatt g, byte[] a, byte[] b, int state) {
            List<byte[]> frames = new ArrayList<>();
            if (a != null) frames.add(a);
            if (b != null) frames.add(b);
            Log.i("K2", "widget sends " + frames.size() + " frame(s) -> state " + state);
            sendAll(g, frames, state);
        }

        /** Writes 80 ms apart; the result shows right away and the next tap (or the app) corrects it if the machine disagrees. */
        void sendAll(BluetoothGatt g, List<byte[]> frames, int state) {
            for (int i = 0; i < frames.size(); i++) { byte[] f = frames.get(i); MAIN.postDelayed(() -> write(g, f), 80L * i); }
            MAIN.postDelayed(() -> finish(status, state), 80L * frames.size() + 600);
        }
    }
}
