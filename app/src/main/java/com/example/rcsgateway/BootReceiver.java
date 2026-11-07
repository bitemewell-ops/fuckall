package com.example.rcsgateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the foreground service after a reboot so the gateway comes back up
 * without the app being opened. (On MIUI this still benefits from Autostart,
 * but the foreground service is the primary keep-alive.)
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            GatewayService.start(context);
        }
    }
}
