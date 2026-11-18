package com.example.rcsgateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives EVERY incoming plain SMS directly from the system, regardless of
 * which app is the default messaging app. Multipart (long) messages arrive as
 * several parts with the same originating address; we stitch them back together
 * per sender before forwarding. OTP / shortcode messages included.
 *
 * RCS is not an SMS and never reaches this receiver — that path stays in
 * RcsNotificationListener.
 */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }

        SmsMessage[] parts = getMessages(intent);
        if (parts == null || parts.length == 0) return;

        // Concatenate parts by sender, preserving order.
        Map<String, StringBuilder> bySender = new LinkedHashMap<>();
        Map<String, Long> sentTimes = new LinkedHashMap<>();
        for (SmsMessage part : parts) {
            if (part == null) continue;
            String from = part.getOriginatingAddress();
            if (from == null) from = "Unknown";
            String body = part.getMessageBody();
            StringBuilder sb = bySender.get(from);
            if (sb == null) {
                sb = new StringBuilder();
                bySender.put(from, sb);
                sentTimes.put(from, part.getTimestampMillis());
            }
            if (body != null) sb.append(body);
        }

        Context app = context.getApplicationContext();
        long receivedAt = System.currentTimeMillis();
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                for (Map.Entry<String, StringBuilder> entry : bySender.entrySet()) {
                    if (entry.getValue().length() == 0) continue;
                    String sender = entry.getKey();
                    try {
                        Forwarder.InboxReply reply = SmsUpload.upload(app, sender, entry.getValue().toString(),
                                receivedAt, sentTimes.get(sender), -1L);
                        Forwarder.log(app, reply == Forwarder.InboxReply.FAILED
                                ? "SMS upload unconfirmed. Use Import missed SMS to retry."
                                : "SMS saved by gateway [" + sender + "]");
                    } catch (Exception error) {
                        Log.w("RcsGateway", "Could not record SMS upload", error);
                    }
                }
            } finally {
                pending.finish();
            }
        }, "sms-upload").start();
    }

    private SmsMessage[] getMessages(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return Telephony.Sms.Intents.getMessagesFromIntent(intent);
        }
        return null; // minSdk is 26, so the modern path always applies.
    }
}
