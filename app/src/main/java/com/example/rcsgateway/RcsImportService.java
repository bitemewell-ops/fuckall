package com.example.rcsgateway;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RcsImportService extends AccessibilityService {
    static final String MESSAGES = "com.google.android.apps.messaging";
    private static final long CAPTURE_WINDOW_MS = 120_000L;
    private static final int MAX_NODES = 500;

    private static RcsImportService connected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshDetails;
    private long armedUntil;
    private boolean showingDetails;
    private String selectedText;
    private RcsDetails shown;
    private Button importButton;

    // The activity and accessibility service both run on the main thread in this process.
    static boolean arm() {
        if (connected == null) return false;
        connected.reset();
        connected.armedUntil = SystemClock.uptimeMillis() + CAPTURE_WINDOW_MS;
        connected.main.post(connected.refresh);
        return true;
    }

    @Override
    protected void onServiceConnected() {
        connected = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (armedUntil == 0L || !isMessagesPackage(event.getPackageName())) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            selectedText = selectedMessage(event.getSource());
            showingDetails = false;
        }
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 120L);
    }

    @Override
    public void onInterrupt() {
        reset();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (connected == this) connected = null;
        reset();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (connected == this) connected = null;
        reset();
        super.onDestroy();
    }

    private void refreshDetails() {
        if (SystemClock.uptimeMillis() >= armedUntil) {
            reset();
            return;
        }
        List<String> text = messagesScreen();
        if (!RcsDetails.isDetailsScreen(text)) {
            if (showingDetails) selectedText = null;
            showingDetails = false;
            removeButton();
        } else {
            showingDetails = true;
            RcsDetails details = RcsDetails.read(text, selectedText, Locale.getDefault());
            if (details != null) {
                showButton(details);
            } else if (importButton == null || shown != null) {
                showButton(null);
            }
        }
        if (armedUntil != 0L) main.postDelayed(refresh, 500L);
    }

    private List<String> messagesScreen() {
        List<String> text = new ArrayList<>();
        AccessibilityNodeInfo root = messagesRoot();
        if (root == null) return text;
        try {
            readText(root, text, new int[]{MAX_NODES});
            return text;
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo messagesRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            if (isMessagesPackage(root.getPackageName())) return root;
            root.recycle();
        }
        // Touching the overlay can make it the active window while Messages still has focus.
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused()) continue;
            root = window.getRoot();
            if (root == null) continue;
            if (isMessagesPackage(root.getPackageName())) return root;
            root.recycle();
        }
        return null;
    }

    private static boolean isMessagesPackage(CharSequence name) {
        return name != null && MESSAGES.contentEquals(name);
    }

    private static String selectedMessage(AccessibilityNodeInfo source) {
        if (source == null) return null;
        try {
            if (!source.isLongClickable()) return null;
            if (source.getText() != null) return source.getText().toString();
            List<String> text = new ArrayList<>();
            readText(source, text, new int[]{40});
            return text.size() == 1 ? text.get(0) : null;
        } finally {
            source.recycle();
        }
    }

    private static void readText(AccessibilityNodeInfo node, List<String> text, int[] remaining) {
        if (!node.isVisibleToUser() || remaining[0]-- <= 0) return;
        CharSequence value = node.getText();
        if (value != null && !value.toString().trim().isEmpty()) text.add(value.toString().trim());
        for (int i = 0; i < node.getChildCount() && remaining[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                readText(child, text, remaining);
            } finally {
                child.recycle();
            }
        }
    }

    private void showButton(RcsDetails details) {
        if (details != null && details.sameMessage(shown) && importButton != null) return;
        if (importButton == null) {
            importButton = new Button(this);
            WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            layout.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            layout.y = (int) (12 * getResources().getDisplayMetrics().density);
            try {
                getSystemService(WindowManager.class).addView(importButton, layout);
            } catch (RuntimeException error) {
                importButton = null;
                reset();
                report("Could not show the RCS import button.");
                return;
            }
        }
        shown = details;
        if (details != null) {
            importButton.setText("Import reply from " + details.sender);
            importButton.setOnClickListener(v -> uploadSelected(details));
        } else {
            importButton.setText("RCS details unavailable");
            importButton.setOnClickListener(v -> report("Import needs the RCS text, sender number and Received date and time in View details."));
        }
    }

    private void uploadSelected(RcsDetails expected) {
        if (SystemClock.uptimeMillis() >= armedUntil
                || !expected.sameMessage(RcsDetails.read(messagesScreen(), selectedText, Locale.getDefault()))) {
            report("The selected RCS reply changed. Open View details again.");
            return;
        }
        reset();
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                Forwarder.InboxReply reply = SmsUpload.uploadRcs(app, expected.sender, expected.body, expected.receivedAt);
                main.post(() -> report(reply == Forwarder.InboxReply.FAILED
                        ? "RCS upload unconfirmed. Start RCS import again to retry."
                        : "RCS reply saved by gateway."));
            } catch (Exception error) {
                Log.w("RcsGateway", "RCS upload failed", error);
                main.post(() -> report("RCS upload failed. Start RCS import again to retry."));
            }
        }, "rcs-upload").start();
    }

    private void report(String text) {
        Forwarder.log(getApplicationContext(), text);
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void reset() {
        armedUntil = 0L;
        showingDetails = false;
        selectedText = null;
        main.removeCallbacks(refresh);
        removeButton();
    }

    private void removeButton() {
        shown = null;
        if (importButton == null) return;
        getSystemService(WindowManager.class).removeView(importButton);
        importButton = null;
    }
}
