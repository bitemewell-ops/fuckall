package com.example.rcsgateway;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Polls the configured Outbox URL for messages the CRM wants sent, sends each
 * via SmsSender, and acks the result back to the same URL. Runs on a thread
 * owned by GatewayService, so it keeps working while the app is closed.
 *
 * Protocol:
 *   GET  <outbox_url>  -> [{"id":"..","to":"+372..","body":".."}, ...]
 *                         (a bare array, or {"messages":[...]})
 *   POST <outbox_url>  <- {"id":"..","status":"sent"|"failed"}
 *
 * The loop ends when the service stops it (stop()); it is not an unbounded
 * re-poll of the same rows — each row is acked once and the backend is expected
 * to stop returning acked ids.
 */
public class OutboxPoller implements Runnable {

    private final Context app;
    private volatile boolean running = true;

    public OutboxPoller(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            long intervalMs = 10_000L;
            try {
                SharedPreferences p = app.getSharedPreferences("RcsGatewayPrefs", Context.MODE_PRIVATE);
                String url = p.getString("outbox_url", "");
                String token = p.getString("token", "");
                int secs = p.getInt("poll_secs", 10);
                intervalMs = Math.max(3, secs) * 1000L;
                if (!url.isEmpty()) {
                    pollOnce(url, token);
                }
            } catch (Exception e) {
                Forwarder.log(app, "OUTBOX error: " + e.getMessage());
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void pollOnce(String url, String token) throws Exception {
        String body = httpGet(url, token);
        if (body == null || body.trim().isEmpty()) return;

        JSONArray arr;
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            arr = new JSONObject(trimmed).optJSONArray("messages");
        } else {
            arr = new JSONArray(trimmed);
        }
        if (arr == null || arr.length() == 0) return;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            String to = m.optString("to", "");
            String text = m.optString("body", "");
            if (to.isEmpty() || text.isEmpty()) continue;

            boolean ok = SmsSender.send(app, to, text);
            Forwarder.log(app, (ok ? "SMS sent -> " : "SMS send FAIL -> ") + to);
            ackResult(url, id, ok ? "sent" : "failed", token);
        }
    }

    private void ackResult(String url, String id, String status, String token) {
        if (id == null || id.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("status", status);
            httpPost(url, o.toString(), token);
        } catch (Exception e) {
            Forwarder.log(app, "OUTBOX ack error: " + e.getMessage());
        }
    }

    private String httpGet(String url, String token) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            if (token != null && !token.isEmpty()) conn.setRequestProperty("X-Gate-Token", token);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void httpPost(String url, String json, String token) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (token != null && !token.isEmpty()) conn.setRequestProperty("X-Gate-Token", token);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
