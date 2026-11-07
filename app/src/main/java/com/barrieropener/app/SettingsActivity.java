/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.barrieropener.app;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * The app-wide settings in {@link AppSettings}: where popups go (Car Notification Service, when
 * installed) and the log server with its "Send logs".
 */
public class SettingsActivity extends AppCompatActivity {

    private TextView notifyStatus;
    private EditText logUrl;
    private EditText logUser;
    private EditText logPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        notifyStatus = findViewById(R.id.textNotifyStatus);

        logUrl = findViewById(R.id.editLogUrl);
        logUser = findViewById(R.id.editLogUser);
        logPassword = findViewById(R.id.editLogPassword);
        logUrl.setText(AppSettings.logUrl(this));
        logUser.setText(AppSettings.logUser(this));
        logPassword.setText(AppSettings.logPassword(this));

        Button send = findViewById(R.id.btnSendLogs);
        send.setOnClickListener(v -> {
            // What was just typed is what the driver expects to be used.
            saveLogServer();
            send.setEnabled(false);
            Toast.makeText(this, R.string.logs_sending, Toast.LENGTH_SHORT).show();
            LogSender.send(this, null, message -> {
                send.setEnabled(true);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        });
    }

    /** Re-read on every return: the service may have been installed or removed meanwhile. */
    @Override
    protected void onResume() {
        super.onResume();
        notifyStatus.setText(NotifyClient.installed(this)
                ? R.string.settings_notify_installed : R.string.settings_notify_missing);
    }

    /** Saved on leaving the screen, whatever way it is left. */
    @Override
    protected void onPause() {
        saveLogServer();
        super.onPause();
    }

    private void saveLogServer() {
        AppSettings.setLogServer(this, text(logUrl), text(logUser), text(logPassword));
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }
}
