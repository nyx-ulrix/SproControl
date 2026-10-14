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
        boolean linked = isLinked(p), busy = runMode(savedState(p)) >= 0,
                done = !busy && doneMs >= 0 && wall < p.getLong("doneUntil", 0);
        JSONObject prof = profile(c);
        String status = p.getString("status", "--");
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.wname, p.getString("name", "Ikape K2"));
        int err = p.getInt("err", 0);
        boolean warn = err >= 1 && err <= 3; // dry burning, battery overheating, heating short circuit
        v.setTextViewText(R.id.wstat, (warn ? ERRORS[err] + " · " : "") + (prof == null ? status : status + " · " + prof.optString("n"))); // profile next to the temperature
        v.setViewVisibility(R.id.werr, warn ? android.view.View.VISIBLE : android.view.View.GONE);
        int batt = p.getInt("batt", -1);
        v.setViewVisibility(R.id.wlow, batt >= 0 && batt < 30 ? android.view.View.VISIBLE : android.view.View.GONE); // red dot: battery under 30 %
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
            // Frames stopping (app force-stopped, link gone) must show: one inexact alarm re-checks after FRESH_MS.
            c.getSystemService(AlarmManager.class).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + FRESH_MS + 200, action(c, STALE, 6));
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
    static final String STALE = "com.sprocontrol.k2.STALE";
    static final long FRESH_MS = 9_000; // a link is only "linked" while status frames keep coming (one a second)

    /** Linked only while the machine is actually heard from; a stale link (app force-stopped, machine off) shows Connect / Search. */
    static boolean isLinked(SharedPreferences p) {
        return p.getBoolean("linked", false) && System.currentTimeMillis() - p.getLong("statusAt", 0) < FRESH_MS;
    }

    static String status(Context c, byte[] v) {
        if (!valid(v) || v.length < 11 || v[4] != 0) return null;
        int err = v.length > 10 ? v[10] & 0xFF : 0;
        SharedPreferences.Editor e = prefs(c).edit().putInt("batt", v[6] & 0x7F);
        if (err != prefs(c).getInt("err", 0)) e.putInt("err", err > 5 ? 0 : err); // the widgets warn on 1..3
        e.apply();
        return (v[6] & 0x7F) + "% · " + ((v[8] & 0xFF) + prefs(c).getInt("offset", 0)) + "°C";
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

    /** The brew state: the five values the app last set on the machine and saw confirmed (prefs "brew"). Only the
     *  app changes it (a preset, or an adjusted value); a widget brew sends exactly this, nothing else. */
    static JSONObject brewState(Context c) {
        try { String s = prefs(c).getString("brew", ""); return s.isEmpty() ? null : new JSONObject(s); }
        catch (Exception e) { return null; }
    }

    /** "7 • 7 • 95°C" (pre-soak • hold • temperature as shown in the app), or null when nothing has been set yet. */
    static String brewLabel(Context c) {
        JSONObject j = brewState(c);
        if (j == null) return null;
        return j.optInt("s") + " • " + j.optInt("h") + " • " + j.optInt("t") + "°C";
    }

    /** SET_TEMP (°F on the K2) + SET_WORK_PARAM for the brew state, or none when nothing has been set yet. */
    static List<byte[]> brewFrames(Context c) {
        List<byte[]> out = new ArrayList<>();
        JSONObject j = brewState(c);
        if (j == null) return out;
        try {
            // the state keeps shown (offset) °C; the machine wants its own scale, in °F
            out.add(frame(0x16, Math.round((j.getInt("t") - prefs(c).getInt("offset", 0)) * 1.8f + 32)));
            out.add(j.isNull("e") ? frame(0x18, j.getInt("f"), j.getInt("s"), j.getInt("h"))
                                  : frame(0x18, j.getInt("f"), j.getInt("s"), j.getInt("h"), j.getInt("e")));
        } catch (Exception e) { out.clear(); }
        return out;
    }

    /** The purge state: flow and seconds from Settings (else the brew's flow, or medium, and 30 s), pre-soak 0, hold 0. */
    static byte[] purgeFrame(Context c) {
        int f = 1, e = 30;
        JSONObject b = brewState(c);
        if (b != null) f = b.optInt("f", 1);
        try { JSONObject j = new JSONObject(prefs(c).getString("purge", "{}")); if (!j.isNull("f")) f = j.getInt("f"); e = j.optInt("e", 30); }
        catch (Exception x) { /* defaults */ }
        return frame(0x18, f, 0, 0, e);
    }

    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        String a = i.getAction();
        if (EXTRACT.equals(a)) new Tap(c, 2, EXTRACTING, goAsync()).run();        // mode extraction
        else if (BOTH.equals(a)) new Tap(c, 1, HOT_EXTRACTING, goAsync()).run();  // mode hotExtraction, with the brew state
        else if (PURGE.equals(a)) new Tap(c, 2, EXTRACTING, goAsync()).purge().run();  // extraction with 0/0/30 s
        else if (SEARCH.equals(a)) new Tap(c, -1, -1, goAsync()).run();           // just reach it and read the state
        else if (CLEAR.equals(a)) { prefs(c).edit().putLong("doneUntil", 0).apply(); redraw(c); }
        else if (STALE.equals(a)) { if (!isLinked(prefs(c)) && prefs(c).getBoolean("linked", false)) { Log.i("K2", "widget: no frames for " + FRESH_MS + " ms, unlinked"); setLinked(c, false); } else redraw(c); }
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
        BluetoothGatt gatt; BluetoothGattCharacteristic tx; boolean handled, finished, isPurge, stopOnly;
        JSONObject want; boolean needTemp, needParam, resent; // a start waits until the brew state is read back from the machine
        int lastState;
        Boolean confirmRunning;                 // after the command: wait for the machine's acknowledgement (running / idle)
        Runnable onDone;                        // runs once the machine has confirmed the command (the cycle advances here)
        byte[] buf = new byte[0];               // notifications carry parts of frames, or several at once
        Tap delegate;                           // a later tap riding this session's link; frames go to it while it runs
        Tap queued;                             // a tap that arrived while the delegate was still busy: runs next
        Tap link;                               // the session whose link this tap rides (null: its own)
        boolean awake, probed, gotStatus;       // warm-up on a fresh link: clock acknowledged, a read answered, a status frame seen
        boolean tookOver;                       // this tap already asked the app to let go of the machine
        long t0 = System.currentTimeMillis();
        String status;

        Tap(Context c, int mode, int runningState, PendingResult done) {
            this.c = c.getApplicationContext(); this.mode = mode; this.runningState = runningState; this.done = done;
        }

        Tap purge() { isPurge = true; return this; }
        Tap stopOnly() { stopOnly = true; return this; }                       // never starts anything
        Tap then(Runnable r) { onDone = r; return this; }

        String name() { return mode < 0 ? "search" : isPurge ? "purge" : stopOnly ? "stop" : mode == 1 ? "both" : mode == 2 ? "extract" : "heat"; }

        /** Never scans, so location is never needed: it connects to the bonded K2 or to the address the app last reached. */
        @SuppressWarnings("MissingPermission")
        void run() {
            prefs(c).edit().putLong("tapBusy", System.currentTimeMillis() + TAP_TIMEOUT_MS + 7000).apply(); // spinner until finish()
            // The app holds the only link the K2 accepts: the widget wins. The app drops its link and closes, and
            // this tap runs on its own link a moment later.
            if (!tookOver && System.currentTimeMillis() < prefs(c).getLong("appHold", 0)) {
                tookOver = true;
                Log.i("K2", "widget " + name() + ": taking the machine over from the app");
                MainActivity.releaseLink();
                prefs(c).edit().putLong("appHold", 0).apply();
                MAIN.postDelayed(this::run, 700);
                return;
            }
            LinkService.touch(c); // keeps the process (and the link below) alive for a minute after this tap
            Tap h = held;
            if (h != null && h.gatt != null && h.tx != null && h.delegate != null) {
                // The link is busy with the previous tap: this one runs the moment it finishes (a second connection would run late).
                Log.i("K2", "widget " + name() + ": queued behind " + h.delegate.name());
                if (h.queued != null) h.queued.finish("Replaced", -1);
                h.queued = this;
                return;
            }
            if (h != null && h.gatt != null && h.tx != null) {
                // A link is still open from a recent tap: use it, with its newest status frame, right now.
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
            if (link != null) {
                if (link.delegate == this) link.delegate = null;
                Tap q = link.queued; link.queued = null;
                if (q != null && held == link) MAIN.post(q::run);
            }
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

        /** Notify-on done. The K2 drops writes for a few hundred ms and only starts its status push once it has the
         *  clock, so the clock goes first; its acknowledgement proves the link is awake and only then does the command
         *  (or, for Connect, a warm-up read) go out. */
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.i("K2", "widget: cccd written " + status + " at " + (System.currentTimeMillis() - t0) + " ms");
            if (status != BluetoothGatt.GATT_SUCCESS) { MAIN.post(() -> finish("Not a K2?", -1)); return; }
            MAIN.postDelayed(() -> write(g, timeFrame()), 300);
            MAIN.postDelayed(() -> { if (!awake && !finished) write(g, timeFrame()); }, 1300);
            MAIN.postDelayed(() -> { if (!awake && !finished) { awake = true; afterWake(g); } }, 2300); // no ack at all: carry on regardless
            for (int i = 0; i < 5; i++) MAIN.postDelayed(() -> { if (lastStatus == null && !finished) write(g, frame(0x00)); }, 1500 + 1000L * i);
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
                if (t.finished) { // lingering: keep the widget's readout fresh; a stop on the machine itself moves the cycle on
                    String st = status(c, f);
                    if (st != null) {
                        int state = f[9] & 0xFF;
                        boolean was = runMode(savedState(prefs(c))) >= 0;
                        show(c, st, state);
                        if (was && runMode(state) < 0) K2OneWidget.machineStopped(c);
                    }
                }
                else t.rx(g, f);
            }
        }

        static byte[] timeFrame() {
            java.util.Calendar t = java.util.Calendar.getInstance();
            int wd = t.get(java.util.Calendar.DAY_OF_WEEK) - 1; if (wd == 0) wd = 7;
            return frame(0x04, wd, t.get(java.util.Calendar.MONTH) + 1, t.get(java.util.Calendar.DAY_OF_MONTH), t.get(java.util.Calendar.YEAR) - 2020,
                    t.get(java.util.Calendar.HOUR_OF_DAY), t.get(java.util.Calendar.MINUTE), t.get(java.util.Calendar.SECOND));
        }

        void write(BluetoothGatt g, byte[] f) { write(g, f, 0); }

        @SuppressWarnings({"MissingPermission", "deprecation"})
        void write(BluetoothGatt g, byte[] f, int attempt) {
            Tap owner = link != null ? link : this;
            if (g == null || tx == null || owner.gatt != g || finished) return; // this tap is over, or the link is gone
            int type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, rc;
            try {
                if (Build.VERSION.SDK_INT >= 33) rc = g.writeCharacteristic(tx, f, type);
                else { tx.setWriteType(type); tx.setValue(f); rc = g.writeCharacteristic(tx) ? 0 : -1; }
            } catch (Exception e) { rc = -1; }
            Log.i("K2", "widget tx " + hex(f) + " rc=" + rc + " at " + (System.currentTimeMillis() - t0) + " ms");
            if (rc == 201 && attempt < 3) MAIN.postDelayed(() -> write(g, f, attempt + 1), 60); // the stack was busy: a few more tries, never forever
        }

        static String hex(byte[] v) { StringBuilder sb = new StringBuilder(); for (byte x : v) sb.append(String.format("%02x", x & 0xFF)); return sb.toString(); }

        /** The link is awake: a command tap acts on the saved state (while it is under two minutes old); Connect probes a read. */
        void afterWake(BluetoothGatt g) {
            boolean fresh = System.currentTimeMillis() - prefs(c).getLong("statusAt", 0) < 120_000;
            if (mode >= 0) { if (fresh && !handled) { handled = true; decide(g, savedState(prefs(c))); } }
            else { write(g, frame(0x17)); MAIN.postDelayed(() -> { if (!probed && !finished) write(g, frame(0x17)); }, 1200); }
        }

        /** Connect finishes only when the link has proven itself three ways, so the next tap is instant. */
        void maybeConnected() {
            if (mode >= 0 || finished || !(awake && probed && gotStatus)) return;
            finish(status, lastState);
        }

        void rx(BluetoothGatt g, byte[] v) {
            if (finished) return;
            if (v[4] == 0x04 && !awake) { awake = true; Log.i("K2", "widget: awake at " + (System.currentTimeMillis() - t0) + " ms"); MAIN.postDelayed(() -> { if (!finished) afterWake(g); }, 150); return; } // a breath after the ack: a query right behind it goes unanswered
            if (mode < 0 && v[4] == 0x17) { probed = true; maybeConnected(); return; }
            // Brew, step 2: the machine reads back the brew state just sent. All five must match, or nothing starts.
            if (want != null) {
                if (v[4] == 0x15 && v.length >= 8 && needTemp) {
                    int got = Math.round(((v[7] & 0xFF) - 32) / 1.8f), t = want.optInt("t") - prefs(c).getInt("offset", 0);
                    if (got == t) needTemp = false; else { Log.i("K2", "widget: temp read back " + got + " not " + t); mismatch("Temp not accepted"); return; }
                } else if (v[4] == 0x17 && v.length >= 12 && needParam) {
                    boolean ok = (v[5] & 0xFF) == want.optInt("f") && (v[6] & 0xFF) == want.optInt("s") && (v[9] & 0xFF) == want.optInt("h")
                            && (want.isNull("e") || (v.length > 12 && (v[12] & 0xFF) == want.optInt("e")));
                    if (ok) needParam = false; else { Log.i("K2", "widget: settings read back differ from the brew state"); mismatch("Settings not accepted"); return; }
                }
                if (!needTemp && !needParam) { Log.i("K2", "widget: brew state confirmed by the machine at " + (System.currentTimeMillis() - t0) + " ms"); want = null; go(g, frame(0x02, mode, 1), null, true); return; }
                if (v[4] != 0) return;
            }
            // The K2 acknowledges a start/stop (0x02 reply, 01) within ~70 ms: that is the confirmation the next button waits for.
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
            if (confirmRunning != null) { if (confirmRunning == (runMode(now) >= 0)) { if (onDone != null) onDone.run(); finish(st, now); } else show(c, st, -1); return; }
            if (mode < 0) { handled = gotStatus = true; MAIN.postDelayed(() -> { if (!finished) { probed = awake = true; maybeConnected(); } }, 2500); maybeConnected(); return; }
            if (handled) return;
            handled = true;
            decide(g, now);
        }

        /** A read-back that differs: the K2 drops the odd write, so the state goes out once more; a second miss ends the tap. */
        void mismatch(String why) {
            if (resent) { finish(why, lastState); return; }
            resent = true; needTemp = needParam = true;
            List<byte[]> again = brewFrames(c); again.add(frame(0x15)); again.add(frame(0x17));
            MAIN.postDelayed(() -> { if (!finished && want != null) sendOnly(gatt, again); }, 120);
        }

        /** The command, from the machine's state (a fresh frame, or the saved one right after connecting). */
        void decide(BluetoothGatt g, int now) {
            // Stop if the machine is running anything, or if the widget is showing Stop
            // (so a Stop tap can never start a brew, even if the saved state is stale).
            int running = runMode(now) >= 0 ? runMode(now) : runMode(savedState(prefs(c)));
            boolean stop = running >= 0;
            if (stop) { go(g, null, frame(0x02, running, 0), false); return; }
            if (stopOnly) { if (onDone != null) onDone.run(); finish(status, now); return; } // nothing running: a Stop tap does nothing
            if (lastStatus != null && lastStatus.length > 10 && (lastStatus[10] & 0xFF) != 0) { finish("Machine error " + (lastStatus[10] & 0xFF), now); return; }
            if (isPurge) { go(g, purgeFrame(c), frame(0x02, 2, 1), true); return; } // the purge state, then start
            // Brew: the brew state goes out, is read back from the machine, and only then does the start follow.
            want = brewState(c);
            if (want == null) { finish("Set a brew in the app first", now); return; }
            needTemp = needParam = true;
            List<byte[]> frames = brewFrames(c); frames.add(frame(0x15)); frames.add(frame(0x17));
            Log.i("K2", "widget mode " + mode + ": brew state " + brewLabel(c) + " out, then read-back (machine state " + now + ")");
            sendOnly(g, frames);
            MAIN.postDelayed(() -> { if (!finished && want != null) finish("Settings unconfirmed", lastState); }, 1500);
        }

        /** The command frames, then half a second for the machine's acknowledgement; after that nothing more is sent. */
        void go(BluetoothGatt g, byte[] a, byte[] b, boolean running) {
            List<byte[]> frames = new ArrayList<>();
            if (a != null) frames.add(a);
            if (b != null) frames.add(b);
            Log.i("K2", "widget sends " + frames.size() + " frame(s), expecting " + (running ? "running" : "idle") + " at " + (System.currentTimeMillis() - t0) + " ms");
            sendOnly(g, frames);
            confirmRunning = running;
            MAIN.postDelayed(() -> { if (!finished) { Log.i("K2", "widget: no acknowledgement within 500 ms, giving up"); finish("No response", lastState); } }, 500 + 80L * frames.size());
        }

        void sendOnly(BluetoothGatt g, List<byte[]> frames) {
            for (int i = 0; i < frames.size(); i++) { byte[] f = frames.get(i); MAIN.postDelayed(() -> write(g, f), 80L * i); }
        }
    }
}
