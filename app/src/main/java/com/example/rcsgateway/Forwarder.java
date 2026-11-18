package com.example.rcsgateway;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Shared delivery logic used by both SmsReceiver (plain SMS) and
 * RcsNotificationListener (RCS). Builds the JSON, POSTs local-first then
 * public-fallback, spools on total failure, and mirrors status to the UI log.
 */
public final class Forwarder {

    private static final String TAG = "RcsGateway";
    public static final String ACTION_LOG = "com.example.rcsgateway.LOG";
    public static final String EXTRA_LOG_LINE = "log_line";
    private static final String FAILED_SPOOL = "failed_payloads.log";

    // Cross-path de-dupe: a plain SMS is seen both by SmsReceiver and (because
    // Google Messages also notifies) by RcsNotificationListener. Suppress a
    // repeat of the same body within a short window so the gate gets it once.
    private static final long DEDUP_MS = 20_000;
    private static final java.util.HashMap<String, Long> RECENT = new java.util.HashMap<>();

    private Forwarder() {}

    private static synchronized boolean isDuplicate(String message) {
        long now = System.currentTimeMillis();
        String key = message == null ? "" : message.trim();
        java.util.Iterator<java.util.Map.Entry<String, Long>> it = RECENT.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > DEDUP_MS) it.remove();
        }
        Long prev = RECENT.get(key);
        if (prev != null && now - prev <= DEDUP_MS) return true;
        RECENT.put(key, now);
        return false;
    }

    public static void send(final Context context, final String sender, final String message) {
        if (message == null || message.isEmpty()) return;
        if (isDuplicate(message)) {
            log(context.getApplicationContext(), "dup skipped [" + sender + "]");
            return;
        }

        SharedPreferences prefs =
                context.getSharedPreferences("RcsGatewayPrefs", Context.MODE_PRIVATE);
        final String localUrl = prefs.getString("local_url", "");
        final String publicUrl = prefs.getString("public_url", "");
        final String token = prefs.getString("token", "");
        final Context app = context.getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String jsonString;
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("timestamp", System.currentTimeMillis() / 1000L);
                    payload.put("sender", sender == null ? "Unknown" : sender);
                    payload.put("message", message);
                    jsonString = payload.toString();
                } catch (Exception e) {
                    Log.e(TAG, "Error building JSON", e);
                    return;
                }

                boolean success = false;
                if (!localUrl.isEmpty()) {
                    success = executePost(localUrl, jsonString, 3000, token);
                    log(app, (success ? "LOCAL ok   " : "LOCAL fail ") + preview(sender, message));
                }
                if (!success && !publicUrl.isEmpty()) {
                    success = executePost(publicUrl, jsonString, 5000, token);
                    log(app, (success ? "PUBLIC ok  " : "PUBLIC fail") + preview(sender, message));
                }
                if (!success) {
                    spoolFailed(app, jsonString);
                    log(app, "SPOOLED (no endpoint delivered) " + preview(sender, message));
                }
            }
        }).start();
    }

    enum InboxReply { DELETE, KEEP, FAILED }

    static InboxReply importInbox(Context context, JSONObject payload, String key) {
        SharedPreferences prefs = context.getSharedPreferences("RcsGatewayPrefs", Context.MODE_PRIVATE);
        String token = prefs.getString("token", "");
        InboxReply local = postInbox(prefs.getString("local_url", ""), payload, key, 3000, token);
        return local != InboxReply.FAILED ? local
                : postInbox(prefs.getString("public_url", ""), payload, key, 5000, token);
    }

    private static InboxReply postInbox(String base, JSONObject payload, String key, int timeout, String token) {
        if (base.isEmpty()) return InboxReply.FAILED;
        try {
            URL url = new URL(base);
            String path = url.getPath().replaceFirst("/+$", "") + "/inbox";
            String query = url.getQuery() == null ? "" : "?" + url.getQuery();
            URL inboxUrl = new URL(url.getProtocol(), url.getHost(), url.getPort(), path + query);
            HttpURLConnection conn = openPost(inboxUrl, payload.toString(), timeout, token, false);
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) return InboxReply.FAILED;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        conn.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] body = new char[1024];
                    StringBuilder response = new StringBuilder();
                    int count;
                    while ((count = reader.read(body)) != -1) {
                        response.append(body, 0, count);
                        if (response.length() > 4096) return InboxReply.FAILED;
                    }
                    JSONObject result = new JSONObject(response.toString());
                    if (!key.equals(result.optString("key"))) return InboxReply.FAILED;
                    Object doDelete = result.opt("do_delete");
                    if (Boolean.TRUE.equals(doDelete)) return InboxReply.DELETE;
                    if (Boolean.FALSE.equals(doDelete)) return InboxReply.KEEP;
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Inbox import request failed", e);
        }
        return InboxReply.FAILED;
    }

    private static boolean executePost(String targetUrl, String jsonPayload, int timeoutMs, String token) {
        HttpURLConnection conn = null;
        try {
            conn = openPost(new URL(targetUrl), jsonPayload, timeoutMs, token, true);
            int code = conn.getResponseCode();
            Log.d(TAG, "POST " + targetUrl + " -> " + code);
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.w(TAG, "POST failed to " + targetUrl + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection openPost(URL url, String jsonPayload, int timeoutMs, String token,
                                              boolean followRedirects)
            throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        boolean written = false;
        try {
            conn.setInstanceFollowRedirects(followRedirects);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (token != null && !token.isEmpty()) conn.setRequestProperty("X-Gate-Token", token);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }
            written = true;
            return conn;
        } finally {
            if (!written) conn.disconnect();
        }
    }

    private static void spoolFailed(Context app, String jsonPayload) {
        try (FileOutputStream fos = app.openFileOutput(FAILED_SPOOL, Context.MODE_APPEND)) {
            fos.write((jsonPayload + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "spool failed", e);
        }
    }

    public static void log(Context app, String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date()) + "  " + line;
        Log.d(TAG, stamped);
        Intent i = new Intent(ACTION_LOG);
        i.putExtra(EXTRA_LOG_LINE, stamped);
        LocalBroadcastManager.getInstance(app).sendBroadcast(i);
    }

    private static String preview(String sender, String message) {
        String m = message.length() > 40 ? message.substring(0, 40) + "…" : message;
        return "[" + sender + "] " + m;
    }
}
