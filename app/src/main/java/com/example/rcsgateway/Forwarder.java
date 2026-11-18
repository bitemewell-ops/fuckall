package com.example.rcsgateway;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Activity log shared by the outgoing sender and the UI. The app forwards no incoming messages:
 * all SMS evidence reaches the CRM through the Google Messages Firefox extension.
 */
public final class Forwarder {

    private static final String TAG = "RcsGateway";
    public static final String ACTION_LOG = "com.example.rcsgateway.LOG";
    public static final String EXTRA_LOG_LINE = "log_line";

    private Forwarder() {}

    public static void log(Context app, String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date()) + "  " + line;
        Log.d(TAG, stamped);
        Intent i = new Intent(ACTION_LOG);
        i.putExtra(EXTRA_LOG_LINE, stamped);
        LocalBroadcastManager.getInstance(app).sendBroadcast(i);
    }
}
