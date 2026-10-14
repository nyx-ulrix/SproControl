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
 *  button is Connect; while the machine runs it is Stop regardless of the cycle. The only text is the profile a
 *  brew step will send; the other steps are icon-only. Each tap is one short BLE session. */
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

    /** Profile for Heat & Brew: the one chosen in Settings, else the app's last-used one (which Extract/Heat use). */
    static JSONObject brewProfile(Context c) {
        JSONObject p = config(c).optJSONObject("profile");
        return p != null ? p : K2Widget.profile(c);
    }

    static String step(Context c) {
        SharedPreferences p = K2Widget.prefs(c);
        if (!p.getBoolean("linked", false)) return "connect";
        if (K2Widget.runMode(K2Widget.savedState(p)) >= 0) return "stop";
        String[] o = order(c);
        return o[Math.floorMod(p.getInt("oneIdx", 0), o.length)];
    }

    static void advance(Context c, int to) { K2Widget.prefs(c).edit().putInt("oneIdx", to).apply(); }

    static RemoteViews views(Context c) {
        String step = step(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_one);
        int icon; String label = "";
        switch (step) {
            case "connect": icon = R.drawable.ic_search; break;
            case "purge":   icon = R.drawable.ic_purge; break;
            case "stop":    icon = R.drawable.ic_stop; break;
            case "heat":    icon = R.drawable.ic_heat; break;
            case "extract": icon = R.drawable.ic_extract; label = name(K2Widget.profile(c), ""); break;
            default:        icon = R.drawable.ic_both;    label = name(brewProfile(c), "");
        }
        boolean searching = System.currentTimeMillis() < K2Widget.prefs(c).getLong("tapBusy", 0); // tap in progress
        v.setViewVisibility(R.id.obusy, searching ? View.VISIBLE : View.GONE); // spinner while a tap looks for the machine
        v.setViewVisibility(R.id.obtn, searching ? View.GONE : View.VISIBLE);
        int err = K2Widget.prefs(c).getInt("err", 0);
        if (err >= 1 && err <= 3) icon = R.drawable.ic_warn; // the machine reports dry burning / overheating / a short: warn instead of the step
        v.setImageViewResource(R.id.obtn, icon);
        v.setTextViewText(R.id.olabel, label);
        v.setViewVisibility(R.id.olabel, label.isEmpty() ? View.GONE : View.VISIBLE);
        v.setFloat(R.id.obtn, "setAlpha", step.equals("connect") ? 0.45f : 1f); // dim the icon, not the box
        PendingIntent tap = PendingIntent.getBroadcast(c, 10, new Intent(c, K2OneWidget.class).setAction(TAP), PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.oneroot, tap);
        v.setOnClickPendingIntent(R.id.obtn, tap);
        return v;
    }

    static String name(JSONObject p, String fallback) { return p == null ? fallback : p.optString("n", fallback); }

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
            case "brew":    new K2Widget.Tap(c, 1, K2Widget.HOT_EXTRACTING, goAsync()).withProfile(brewProfile(c)).then(() -> advance(c, idx + 1)).run(); break;
            case "extract": new K2Widget.Tap(c, 2, K2Widget.EXTRACTING, goAsync()).then(() -> advance(c, idx + 1)).run(); break;
            case "heat":    new K2Widget.Tap(c, 0, K2Widget.HOT_WATERING, goAsync()).then(() -> advance(c, idx + 1)).run(); break;
            default: {      // stop: an explicit Stop step is used up; one forced by a running machine leaves the cycle where it is
                Runnable next = o[idx].equals("stop") ? () -> advance(c, idx + 1) : null;
                new K2Widget.Tap(c, 2, K2Widget.EXTRACTING, goAsync()).stopOnly().then(next).run();
            }
        }
    }
}
