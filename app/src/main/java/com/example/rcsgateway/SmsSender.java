package com.example.rcsgateway;

import android.content.Context;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.ArrayList;

/**
 * Sends an outgoing SMS off this phone's SIM. Long bodies are split into
 * multipart. Outgoing text is forced to GSM-7-safe characters first
 * (õ/Õ -> ö/Ö), matching how the rest of the CRM sends SMS.
 */
public final class SmsSender {

    private static final String TAG = "RcsGateway";

    private SmsSender() {}

    public static boolean send(Context ctx, String to, String body) {
        if (to == null || to.isEmpty() || body == null) return false;
        String text = gsm7(body);
        try {
            SmsManager sm = getManager(ctx);
            ArrayList<String> parts = sm.divideMessage(text);
            if (parts.size() > 1) {
                sm.sendMultipartTextMessage(to, null, parts, null, null);
            } else {
                sm.sendTextMessage(to, null, text, null, null);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "SMS send failed to " + to + ": " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static SmsManager getManager(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ctx.getSystemService(SmsManager.class);
        }
        return SmsManager.getDefault();
    }

    /** Keep outgoing text in GSM-7 to avoid UCS-2 encoding. */
    static String gsm7(String s) {
        return s.replace('õ', 'ö')  // õ -> ö
                .replace('Õ', 'Ö'); // Õ -> Ö
    }
}
