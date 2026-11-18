package com.example.rcsgateway;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class InboxImporter {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final String[] COLUMNS = {Telephony.Sms._ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT};

    private static final class SmsRow {
        final long id;
        final String sender;
        final String message;
        final long date;
        final long sentAt;

        SmsRow(Cursor row) {
            id = row.getLong(0);
            sender = row.getString(1);
            message = row.getString(2);
            date = row.getLong(3);
            sentAt = row.getLong(4);
        }
    }

    private InboxImporter() {}

    static boolean start(Context context) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                scan(app);
            } catch (Exception error) {
                Log.w("RcsGateway", "Could not read SMS inbox", error);
                Forwarder.log(app, "SMS import stopped. Check inbox permission and try again.");
            } finally {
                RUNNING.set(false);
            }
        }, "sms-inbox-import").start();
        return true;
    }

    private static void scan(Context app) throws Exception {
        ContentResolver resolver = app.getContentResolver();
        List<SmsRow> messages = new ArrayList<>();
        try (Cursor rows = resolver.query(Telephony.Sms.Inbox.CONTENT_URI, COLUMNS,
                null, null,
                Telephony.Sms.DATE + " ASC, " + Telephony.Sms._ID + " ASC")) {
            if (rows == null) throw new IllegalStateException("Inbox query failed");
            while (rows.moveToNext()) messages.add(new SmsRow(rows));
        }

        int imported = 0;
        int deleted = 0;
        int eligible = 0;
        int failed = 0;
        for (SmsRow sms : messages) {
            String sender = sms.sender == null || sms.sender.trim().isEmpty() ? "Unknown" : sms.sender;
            String body = sms.message == null ? "" : sms.message;
            Forwarder.InboxReply reply;
            try {
                reply = SmsUpload.upload(app, sender, body, sms.date, sms.sentAt, sms.id);
            } catch (Exception error) {
                Log.w("RcsGateway", "Could not upload inbox SMS " + sms.id, error);
                failed++;
                continue;
            }
            if (reply == Forwarder.InboxReply.FAILED) {
                failed++;
                continue;
            }
            imported++;
            if (reply == Forwarder.InboxReply.DELETE) {
                eligible++;
                if (deleteIfDefault(app, sms)) deleted++;
            }
        }
        Forwarder.log(app, "SMS inbox: " + imported + " saved by gateway, " + deleted + " deleted, "
                + (imported - deleted) + " kept on phone.");
        if (eligible > deleted && !isDefaultSmsApp(app)) {
            Forwarder.log(app, "Only the default SMS app can delete inbox messages.");
        }
        if (failed > 0) Forwarder.log(app, failed + " SMS not confirmed. Tap Import missed SMS again to retry them.");
    }

    private static boolean deleteIfDefault(Context app, SmsRow sms) {
        if (!isDefaultSmsApp(app)) return false;
        try {
            return app.getContentResolver().delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, sms.id),
                    "type = ? AND address = ? AND body = ? AND date = ?",
                    new String[]{Integer.toString(Telephony.Sms.MESSAGE_TYPE_INBOX), sms.sender, sms.message,
                            Long.toString(sms.date)}) == 1;
        } catch (RuntimeException error) {
            Log.w("RcsGateway", "Could not delete inbox SMS " + sms.id, error);
            return false;
        }
    }

    private static boolean isDefaultSmsApp(Context app) {
        return app.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(app));
    }

}
