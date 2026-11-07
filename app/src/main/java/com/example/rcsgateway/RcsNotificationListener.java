package com.example.rcsgateway;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Handles RCS, which is NOT an SMS and so never reaches SmsReceiver — it only
 * surfaces as a Google Messages notification. Plain SMS is handled directly by
 * SmsReceiver, so this listener stays scoped to Google Messages to avoid
 * double-forwarding the same text.
 */
public class RcsNotificationListener extends NotificationListenerService {

    private static final String TARGET_PACKAGE = "com.google.android.apps.messaging";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !TARGET_PACKAGE.equals(sbn.getPackageName())) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;
        Bundle extras = notification.extras;
        if (extras == null) return;

        String sender = extras.getString(Notification.EXTRA_TITLE, "Unknown");

        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);

        String message = "";
        if (bigText != null) message = bigText.toString();
        else if (text != null) message = text.toString();
        else if (subText != null) message = subText.toString();

        if (message.isEmpty()) return;

        Forwarder.log(getApplicationContext(), "RCS in [" + sender + "]");
        Forwarder.send(this, sender, message);
    }
}
