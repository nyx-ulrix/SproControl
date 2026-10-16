package com.sprocontrol.k2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Keeps the process, and with it the widgets' open Bluetooth link, alive for ten minutes after the last tap
 *  (sliding), as a foreground service; then drops the link and stops. Nothing runs once it has stopped. */
public class LinkService extends Service {
    static final String CH = "link";
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static Context app;
    static boolean afterCycle;    // the one-button cycle completed: the short hold is running and its end starts the cycle over
    static final long CYCLE_DONE_MS = 30_000;
    static final Runnable EXPIRE = () -> {
        Context c = app;
        if (c == null) return;
        if (afterCycle) { afterCycle = false; K2Widget.prefs(c).edit().putInt("oneIdx", 0).putLong("lastTap", 0).apply(); }
        K2Widget.dropHeld(c);
        c.stopService(new Intent(c, LinkService.class));
    };

    /** The cycle's last step was acknowledged: unless another tap comes, the link drops in 30 s and the cycle starts over. */
    static void cycleDone(Context c) {
        app = c.getApplicationContext();
        afterCycle = true;
        MAIN.removeCallbacks(EXPIRE);
        MAIN.postDelayed(EXPIRE, CYCLE_DONE_MS);
    }

    /** A tap happened: (re)start the minute. */
    static void touch(Context c) {
        app = c.getApplicationContext();
        afterCycle = false; // a tap inside the short hold: back to the ten-minute rule
        MAIN.removeCallbacks(EXPIRE);
        MAIN.postDelayed(EXPIRE, K2Widget.LINGER_MS);
        try { app.startForegroundService(new Intent(app, LinkService.class)); } catch (Exception e) { /* not allowed right now: the tap still runs */ }
    }

    /** The app is taking over the machine: drop everything now. */
    static void stopNow(Context c) {
        MAIN.removeCallbacks(EXPIRE);
        K2Widget.dropHeld(c);
        c.stopService(new Intent(c, LinkService.class));
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Machine link", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_both).setContentTitle("SproCtrl is linked to the machine")
                .setContentText("Drops by itself ten minutes after the last widget tap")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true).build();
        startForeground(1, n);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
