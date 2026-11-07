package com.example.rcsgateway;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.Manifest;

public class MainActivity extends AppCompatActivity {

    private TextView tvPermissionStatus, tvLogs;
    private ScrollView scrollLogs;
    private EditText etLocalUrl, etPublicUrl, etOutboxUrl, etToken;
    private SharedPreferences prefs;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String line = intent.getStringExtra(Forwarder.EXTRA_LOG_LINE);
            if (line != null) appendLog(line);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("RcsGatewayPrefs", Context.MODE_PRIVATE);

        tvPermissionStatus = findViewById(R.id.tv_permission_status);
        tvLogs = findViewById(R.id.tv_logs);
        scrollLogs = findViewById(R.id.scroll_logs);
        etLocalUrl = findViewById(R.id.et_local_url);
        etPublicUrl = findViewById(R.id.et_public_url);
        etOutboxUrl = findViewById(R.id.et_outbox_url);
        etToken = findViewById(R.id.et_token);
        Button btnGrant = findViewById(R.id.btn_grant_permission);
        Button btnSave = findViewById(R.id.btn_save_config);

        etLocalUrl.setText(prefs.getString("local_url", ""));
        etPublicUrl.setText(prefs.getString("public_url", ""));
        etOutboxUrl.setText(prefs.getString("outbox_url", ""));
        etToken.setText(prefs.getString("token", ""));

        requestSmsPermission();
        requestNotificationsPermission();
        GatewayService.start(this);

        btnGrant.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        btnSave.setOnClickListener(v -> {
            prefs.edit()
                    .putString("local_url", etLocalUrl.getText().toString().trim())
                    .putString("public_url", etPublicUrl.getText().toString().trim())
                    .putString("outbox_url", etOutboxUrl.getText().toString().trim())
                    .putString("token", etToken.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "Config saved", Toast.LENGTH_SHORT).show();
            appendLog("Config saved.");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermission();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(logReceiver, new IntentFilter(Forwarder.ACTION_LOG));
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver);
    }

    private void appendLog(String line) {
        tvLogs.append(line + "\n");
        scrollLogs.post(() -> scrollLogs.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void requestSmsPermission() {
        boolean needRecv = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED;
        boolean needSend = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED;
        if (needRecv || needSend) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS}, 1);
        }
    }

    private void requestNotificationsPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    private boolean hasSmsPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermission() {
        ComponentName cn = new ComponentName(this, RcsNotificationListener.class);
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean rcsEnabled = flat != null && flat.contains(cn.flattenToString());
        boolean smsGranted = hasSmsPermission();

        // SMS permission is what captures ALL plain texts (incl. OTP); the
        // notification listener is only needed for RCS.
        String line = "SMS: " + (smsGranted ? "ON" : "OFF")
                + "   |   RCS access: " + (rcsEnabled ? "ON" : "OFF");
        tvPermissionStatus.setText(line);
        tvPermissionStatus.setTextColor(smsGranted ? 0xFF008800 : 0xFFFF0000);
    }
}
