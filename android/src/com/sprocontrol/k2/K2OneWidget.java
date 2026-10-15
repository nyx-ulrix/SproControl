package com.sprocontrol.k2;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One-cell square widget with a single button that walks a cycle the user composes in Settings: any sequence
 *  of Purge / Heat & Brew / Extract / Heat / Stop (default purge → stop → brew → stop → purge → stop …). While not linked the
 *  button is Connect; while the machine runs it is Stop regardless of the cycle. The only text is what a brew
 *  step will run with: the settings last read from the machine; the other steps are icon-only. */
public class K2OneWidget extends AppWidgetProvider {
    static final String TAP = "com.sprocontrol.k2.ONE_TAP";
    static final List<String> STEPS = Arrays.asList("purge", "brew", "extract", "heat", "stop");
    static final String[] DEFAULT = {"purge", "stop", "brew", "stop", "purge", "stop"};

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { m.updateAppWidget(ids, views(c)); }

    static JSONObject config(Context c) {
        try { String s = K2Widget.prefs(c).getString("one", ""); return s.isEmpty() ? new JSONObject() : new JSONObject(s); }
        catch (Exception e) { return new JSONObject(); }
    }

    static String[] order(Context c) {
        JSONArray a = config(c).optJSONArray("order");
        if (a == null || a.length() == 0) return DEFAULT;
        String[] o = new String[a.length()];
        for (int i = 0; i < a.length(); i++) { o[i] = a.optString(i); if (!STEPS.contains(o[i])) return DEFAULT; }
        return o;
    }

    /** Where the cycle stands: its saved place, unless more than CYCLE_KEEP_MS passed since the last tap (then the start). */
    static int idx(Context c, int n) {
        SharedPreferences p = K2Widget.prefs(c);
        if (System.currentTimeMillis() - p.getLong("lastTap", 0) > K2Widget.CYCLE_KEEP_MS) return 0;
        return Math.floorMod(p.getInt("oneIdx", 0), n);
    }

    static String step(Context c) {
        SharedPreferences p = K2Widget.prefs(c);
        if (!K2Widget.isLinked(p)) return "connect";
        if (K2Widget.runMode(K2Widget.savedState(p)) >= 0) return "stop";
        String[] o = order(c);
        return o[idx(c, o.length)];
    }

    static void advance(Context c, int to) { K2Widget.prefs(c).edit().putInt("oneIdx", to).apply(); }

    /** A Purge step's own seconds from Settings, or 0 for the Purge card's. */
    static int purgeSecs(Context c, int idx) {
        JSONArray a = config(c).optJSONArray("secs");
        return a == null || idx >= a.length() || a.isNull(idx) ? 0 : a.optInt(idx, 0);
    }

    /** The machine went from running to idle without a Stop tap here (its own button, or the app): a Stop step
     *  waiting in the cycle is used up, so the next tap is the next step. */
    static void machineStopped(Context c) {
        String[] o = order(c);
        int idx = idx(c, o.length);
        if (o[idx].equals("stop")) { advance(c, idx + 1); redraw(c); }
    }

    static RemoteViews views(Context c) {
        String step = step(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_one);
        PendingIntent tap = PendingIntent.getBroadcast(c, 10, new Intent(c, K2OneWidget.class).setAction(TAP), PendingIntent.FLAG_IMMUTABLE);
        int icon; String label = "";
        switch (step) {
            case "connect": icon = R.drawable.ic_search; break;
            case "purge":   icon = R.drawable.ic_purge; break;
            case "stop":    icon = R.drawable.ic_stop; break;
            case "heat":    icon = R.drawable.ic_heat; break;
            case "extract": icon = R.drawable.ic_extract; label = brew(c); break;
            default:        icon = R.drawable.ic_both;    label = brew(c);
        }
        boolean searching = System.currentTimeMillis() < K2Widget.prefs(c).getLong("tapBusy", 0); // tap in progress
        int err = K2Widget.prefs(c).getInt("err", 0);
        v.setViewVisibility(R.id.obusy, searching ? View.VISIBLE : View.GONE); // spinner while a tap looks for the machine
        v.setViewVisibility(R.id.obtn, searching ? View.GONE : View.VISIBLE);
        int batt = K2Widget.prefs(c).getInt("batt", -1);
        v.setViewVisibility(R.id.olow, batt >= 0 && batt <= K2Widget.prefs(c).getInt("lowBatt", 30) ? View.VISIBLE : View.GONE); // red dot: battery at the threshold or under
        if (err >= 1 && err <= 3) icon = R.drawable.ic_warn; // the machine reports dry burning / overheating / a short: warn instead of the step
        v.setImageViewResource(R.id.obtn, icon);
        v.setTextViewText(R.id.olabel, label);
        v.setViewVisibility(R.id.olabel, label.isEmpty() ? View.GONE : View.VISIBLE);
        v.setFloat(R.id.oneroot, "setAlpha", step.equals("connect") ? 0.45f : 1f);
        v.setOnClickPendingIntent(R.id.oneroot, tap);
        v.setOnClickPendingIntent(R.id.obtn, tap);
        return v;
    }

    /** What a brew will send: the brew state the app last set and saw confirmed, as "pre-soak • hold • temp". */
    static String brew(Context c) { String d = K2Widget.brewLabel(c); return d == null ? "" : d; }

    static void redraw(Context c) {
        AppWidgetManager.getInstance(c).updateAppWidget(new ComponentName(c, K2OneWidget.class), views(c));
    }

    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (!TAP.equals(i.getAction())) return;
        String step = step(c);
        String[] o = order(c);
        int idx = idx(c, o.length);
        switch (step) {
            case "connect": new K2Widget.Tap(c, -1, -1, goAsync()).run(); break;
            // The cycle only moves on once the machine has confirmed the command (Tap.then).
            case "purge":   new K2Widget.Tap(c, 2, K2Widget.EXTRACTING, goAsync()).purge(purgeSecs(c, idx)).then(() -> advance(c, idx + 1)).run(); break;
            case "brew":    new K2Widget.Tap(c, 1, K2Widget.HOT_EXTRACTING, goAsync()).then(() -> advance(c, idx + 1)).run(); break;
            case "extract": new K2Widget.Tap(c, 2, K2Widget.EXTRACTING, goAsync()).then(() -> advance(c, idx + 1)).run(); break;
            case "heat":    new K2Widget.Tap(c, 0, K2Widget.HOT_WATERING, goAsync()).then(() -> advance(c, idx + 1)).run(); break;
            default: {      // stop: an explicit Stop step is used up; one forced by a running machine leaves the cycle where it is
                Runnable next = o[idx].equals("stop") ? () -> advance(c, idx + 1) : null;
                new K2Widget.Tap(c, 2, K2Widget.EXTRACTING, goAsync()).stopOnly().then(next).run();
            }
        }
    }
}
