package com.example.rcsgateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class SmsUpload {
    private SmsUpload() {}

    // Live broadcasts and the inbox importer share this receipt store.
    static synchronized Forwarder.InboxReply upload(Context app, String sender, String message,
                                                   long receivedAt, long sentAt, long inboxId) throws Exception {
        String key = messageKey(app, sender, message, receivedAt, sentAt, inboxId);
        SharedPreferences receipts = app.getSharedPreferences("SmsUploadReceipts", Context.MODE_PRIVATE);
        if (receipts.contains(key)) {
            return receipts.getBoolean(key, false) ? Forwarder.InboxReply.DELETE : Forwarder.InboxReply.KEEP;
        }

        JSONObject payload = new JSONObject();
        payload.put("key", key);
        payload.put("sender", sender);
        payload.put("message", message);
        payload.put("timestamp", Math.max(0L, receivedAt / 1000L));
        Forwarder.InboxReply reply = Forwarder.importInbox(app, payload, key);
        if (reply != Forwarder.InboxReply.FAILED
                && !receipts.edit().putBoolean(key, reply == Forwarder.InboxReply.DELETE).commit()) {
            return Forwarder.InboxReply.FAILED;
        }
        return reply;
    }

    private static String messageKey(Context app, String sender, String message, long receivedAt,
                                     long sentAt, long inboxId) throws Exception {
        String device = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (device == null || device.isEmpty()) throw new IllegalStateException("Missing Android ID");
        // DATE_SENT is the SMS timestamp available to both the receiver and the inbox.
        JSONArray fields = sentAt > 0
                ? new JSONArray().put(device).put("sms").put(sender).put(message).put(sentAt)
                : new JSONArray().put(device).put(inboxId).put(sender).put(message).put(receivedAt);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(fields.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }
}
