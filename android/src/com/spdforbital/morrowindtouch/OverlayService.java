package com.spdforbital.morrowindtouch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;

/** Keeps the control overlay on screen above Steam Link and runs the network link to the PC. */
public class OverlayService extends Service {
    private WindowManager wm;
    private OverlayView view;
    private Net net;

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();

        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        net = new Net(p.getString("host", ""), p.getString("password", ""));
        view = new OverlayView(this, net, (p.getInt("sens", 12) + 2) / 10f);
        net.setListener(view);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        view.attach(wm, lp);
        net.start();
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("mwt", "Morrowind Touch",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent hide = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "mwt")
                .setContentTitle("Morrowind Touch")
                .setContentText("Controls show while Morrowind runs. Tap for settings.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Hide / show", hide).build())
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    static final String ACTION_TOGGLE = "com.spdforbital.morrowindtouch.TOGGLE",
            ACTION_STOP = "com.spdforbital.morrowindtouch.STOP";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) {
            stopSelf();
        } else if (ACTION_TOGGLE.equals(a) && view != null) {
            view.toggleUserHidden();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (net != null) net.stop();
        if (view != null) view.detach();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
