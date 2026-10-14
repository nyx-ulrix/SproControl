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

    static String step(Context c) {
        SharedPreferences p = K2Widget.prefs(c);
        if (!K2Widget.isLinked(p)) return "connect";
        if (K2Widget.runMode(K2Widget.savedState(p)) >= 0) return "stop";
        String[] o = order(c);
        return o[Math.floorMod(p.getInt("oneIdx", 0), o.length)];
    }

    static void advance(Context c, int to) { K2Widget.prefs(c).edit().putInt("oneIdx", to).apply(); }

    /** The machine went from running to idle without a Stop tap here (its own button, or the app): a Stop step
     *  waiting in the cycle is used up, so the next tap is the next step. */
    static void machineStopped(Context c) {
        String[] o = order(c);
        int idx = Math.floorMod(K2Widget.prefs(c).getInt("oneIdx", 0), o.length);
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
        boolean battery = step.equals("stop") && !searching && err < 1; // the Stop face: a battery with the charge inside
        v.setViewVisibility(R.id.obtn, searching || battery ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.ostop, battery ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.obatt, K2Widget.prefs(c).getInt("batt", -1) < 0 ? "" : K2Widget.prefs(c).getInt("batt", 0) + "%");
        if (err >= 1 && err <= 3) icon = R.drawable.ic_warn; // the machine reports dry burning / overheating / a short: warn instead of the step
        v.setImageViewResource(R.id.obtn, icon);
        v.setOnClickPendingIntent(R.id.ostop, tap);
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
        int idx = Math.floorMod(K2Widget.prefs(c).getInt("oneIdx", 0), o.length);
        switch (step) {
            case "connect": new K2Widget.Tap(c, -1, -1, goAsync()).run(); break;
            // The cycle only moves on once the machine has confirmed the command (Tap.then).
            case "purge":   new K2Widget.Tap(c, 2, K2Widget.EXTRACTING, goAsync()).purge().then(() -> advance(c, idx + 1)).run(); break;
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
