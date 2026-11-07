package com.example.rcsgateway;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RcsNotificationListener extends NotificationListenerService {

    private static final String TAG = "RcsGateway";
    private static final String TARGET_PACKAGE = "com.google.android.apps.messaging";

    // Used to push log lines to MainActivity while it is open.
    public static final String ACTION_LOG = "com.example.rcsgateway.LOG";
    public static final String EXTRA_LOG_LINE = "log_line";

    // Failed payloads that both endpoints rejected are spooled here.
    private static final String FAILED_SPOOL = "failed_payloads.log";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        String sender = extras.getString(Notification.EXTRA_TITLE, "Unknown");
        CharSequence diagText = extras.getCharSequence(Notification.EXTRA_TEXT);

        // DIAGNOSTIC: log every notification that carries a title+body, with its
        // package, so we can see which app actually posts your SMS/RCS. Remove
        // once the real package is confirmed.
        if (diagText != null && diagText.length() > 0) {
            emit("DIAG pkg=" + sbn.getPackageName() + " title=" + sender);
        }

        if (!TARGET_PACKAGE.equals(sbn.getPackageName())) {
            return;
        }

        CharSequence textCharSeq = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence bigTextCharSeq = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence subTextCharSeq = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);

        // Prefer BigText (full body, untruncated); fall back to Text, then SubText.
        String message = "";
        if (bigTextCharSeq != null) {
            message = bigTextCharSeq.toString();
        } else if (textCharSeq != null) {
            message = textCharSeq.toString();
        } else if (subTextCharSeq != null) {
            message = subTextCharSeq.toString();
        }

        if (message.isEmpty()) return;

        // Google Messages posts a "typing"/summary notification with no real body;
        // the actual message notifications carry EXTRA_TEXT, so empty is skipped above.
        dispatchMessage(sender, message);
    }

    private void dispatchMessage(final String sender, final String message) {
        SharedPreferences prefs = getSharedPreferences("RcsGatewayPrefs", Context.MODE_PRIVATE);
        final String localUrl = prefs.getString("local_url", "");
        final String publicUrl = prefs.getString("public_url", "");

        new Thread(new Runnable() {
            @Override
            public void run() {
                String jsonString = null;
                try {
                    JSONObject payload = new JSONObject();
                    // Seconds since epoch, matching the {"timestamp": 1700000000} example.
                    payload.put("timestamp", System.currentTimeMillis() / 1000L);
                    payload.put("sender", sender);
                    payload.put("message", message);
                    jsonString = payload.toString();
                } catch (Exception e) {
                    Log.e(TAG, "Error building JSON", e);
                    return;
                }

                boolean success = false;

                if (!localUrl.isEmpty()) {
                    success = executePost(localUrl, jsonString, 3000);
                    emit((success ? "LOCAL ok   " : "LOCAL fail ") + preview(sender, message));
                }

                if (!success && !publicUrl.isEmpty()) {
                    success = executePost(publicUrl, jsonString, 5000);
                    emit((success ? "PUBLIC ok  " : "PUBLIC fail") + preview(sender, message));
                }

                if (!success) {
                    spoolFailed(jsonString);
                    emit("SPOOLED (both endpoints failed) " + preview(sender, message));
                }
            }
        }).start();
    }

    private boolean executePost(String targetUrl, String jsonPayload, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "POST " + targetUrl + " -> " + responseCode);
            return responseCode >= 200 && responseCode < 300;
        } catch (Exception e) {
            Log.w(TAG, "Connection failed to " + targetUrl + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void spoolFailed(String jsonPayload) {
        if (jsonPayload == null) return;
        try (FileOutputStream fos = openFileOutput(FAILED_SPOOL, Context.MODE_APPEND)) {
            fos.write((jsonPayload + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "Failed to spool payload", e);
        }
    }

    private void emit(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + line;
        Log.d(TAG, stamped);
        Intent i = new Intent(ACTION_LOG);
        i.putExtra(EXTRA_LOG_LINE, stamped);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);
    }

    private String preview(String sender, String message) {
        String m = message.length() > 40 ? message.substring(0, 40) + "…" : message;
        return "[" + sender + "] " + m;
    }
}
