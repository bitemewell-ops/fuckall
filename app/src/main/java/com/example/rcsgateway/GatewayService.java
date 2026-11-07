package com.example.rcsgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps the app's process alive with an ongoing notification so the SMS
 * receiver keeps firing after the app is closed/swiped away. MIUI force-stops
 * ordinary apps on swipe (which silences manifest receivers); it leaves a
 * foreground service running. START_STICKY asks the system to restart us if we
 * are ever killed.
 */
public class GatewayService extends Service {

    private static final String CHANNEL_ID = "gateway";
    private static final int NOTIF_ID = 1;

    public static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, GatewayService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Gateway", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RCS Gateway running")
                .setContentText("Forwarding incoming SMS")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}
