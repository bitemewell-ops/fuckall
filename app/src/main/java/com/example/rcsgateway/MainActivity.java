package com.example.rcsgateway;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MainActivity extends AppCompatActivity {

    private TextView tvPermissionStatus, tvLogs;
    private ScrollView scrollLogs;
    private EditText etLocalUrl, etPublicUrl;
    private SharedPreferences prefs;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String line = intent.getStringExtra(RcsNotificationListener.EXTRA_LOG_LINE);
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
        Button btnGrant = findViewById(R.id.btn_grant_permission);
        Button btnSave = findViewById(R.id.btn_save_config);

        etLocalUrl.setText(prefs.getString("local_url", ""));
        etPublicUrl.setText(prefs.getString("public_url", ""));

        btnGrant.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        btnSave.setOnClickListener(v -> {
            prefs.edit()
                    .putString("local_url", etLocalUrl.getText().toString().trim())
                    .putString("public_url", etPublicUrl.getText().toString().trim())
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
                .registerReceiver(logReceiver, new IntentFilter(RcsNotificationListener.ACTION_LOG));
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

    private void checkPermission() {
        ComponentName cn = new ComponentName(this, RcsNotificationListener.class);
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean enabled = flat != null && flat.contains(cn.flattenToString());

        if (enabled) {
            tvPermissionStatus.setText("Permission: GRANTED");
            tvPermissionStatus.setTextColor(0xFF008800);
        } else {
            tvPermissionStatus.setText("Permission: NOT GRANTED");
            tvPermissionStatus.setTextColor(0xFFFF0000);
        }
    }
}
