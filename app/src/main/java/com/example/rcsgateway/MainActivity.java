package com.example.rcsgateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

/** Outgoing-only SMS gateway: polls the outbox URL, sends each text and acks it. Incoming messages are not forwarded. */
public class MainActivity extends AppCompatActivity {

    private TextView tvPermissionStatus, tvLogs;
    private ScrollView scrollLogs;
    private EditText etOutboxUrl, etToken;
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
        etOutboxUrl = findViewById(R.id.et_outbox_url);
        etToken = findViewById(R.id.et_token);
        Button btnSave = findViewById(R.id.btn_save_config);

        etOutboxUrl.setText(prefs.getString("outbox_url", ""));
        etToken.setText(prefs.getString("token", ""));

        requestSendPermission();
        requestNotificationsPermission();
        GatewayService.start(this);

        btnSave.setOnClickListener(v -> {
            prefs.edit()
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

    private void requestSendPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS}, 1);
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

    private void checkPermission() {
        boolean sendGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
        tvPermissionStatus.setText("Send SMS: " + (sendGranted ? "ON" : "OFF") + "   |   Incoming: not forwarded");
        tvPermissionStatus.setTextColor(sendGranted ? 0xFF008800 : 0xFFFF0000);
    }
}
