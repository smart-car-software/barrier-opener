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
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.materialswitch.MaterialSwitch;

/** The app-wide settings in {@link AppSettings}: driver's-screen popup, wheel keys, log server. */
public class SettingsActivity extends AppCompatActivity {

    private RootStream check;
    private TextView status;
    private EditText logUrl;
    private EditText logUser;
    private EditText logPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        MaterialSwitch cluster = findViewById(R.id.switchClusterPopup);
        cluster.setChecked(AppSettings.clusterPopup(this));
        cluster.setOnCheckedChangeListener((b, on) -> AppSettings.setClusterPopup(this, on));

        MaterialSwitch wheel = findViewById(R.id.switchWheelKeys);
        MaterialSwitch mute = findViewById(R.id.switchMuteMedia);
        wheel.setChecked(AppSettings.wheelKeys(this));
        mute.setChecked(AppSettings.muteMedia(this));
        mute.setEnabled(wheel.isChecked());
        status = findViewById(R.id.textWheelStatus);
        check = new RootStream(this);
        wheel.setOnCheckedChangeListener((b, on) -> {
            AppSettings.setWheelKeys(this, on);
            mute.setEnabled(on);
            if (on) {
                checkRoot();
            } else {
                check.stop();
                status.setText("");
            }
        });
        mute.setOnCheckedChangeListener((b, on) -> AppSettings.setMuteMedia(this, on));
        if (wheel.isChecked()) {
            checkRoot();
        }

        logUrl = findViewById(R.id.editLogUrl);
        logUser = findViewById(R.id.editLogUser);
        logPassword = findViewById(R.id.editLogPassword);
        logUrl.setText(AppSettings.logUrl(this));
        logUser.setText(AppSettings.logUser(this));
        logPassword.setText(AppSettings.logPassword(this));
    }

    /** Saved on leaving the screen, whatever way it is left. */
    @Override
    protected void onPause() {
        AppSettings.setLogServer(this, text(logUrl), text(logUser), text(logPassword));
        super.onPause();
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }

    /**
     * Checked by itself whenever the wheel keys are on: the first connection is also when the
     * unit asks to allow debugging for this app, which is better asked while the driver is here,
     * at the settings, than the first time a gate comes into range.
     */
    private void checkRoot() {
        status.setText(R.string.settings_check_root_running);
        StringBuilder out = new StringBuilder();
        check.start("id -un", null, line -> {
            if (!AppSettings.wheelKeys(this)) {
                return; // Switched off meanwhile: the stopped check's ending says nothing.
            }
            if (line == null) {
                boolean root = out.toString().contains("\nroot") || out.toString().startsWith("root");
                status.setText(root ? getString(R.string.settings_check_root_ok)
                        : getString(R.string.settings_check_root_failed, out.toString().trim()));
            } else {
                out.append(line).append('\n');
            }
        });
    }

    @Override
    protected void onDestroy() {
        check.stop();
        super.onDestroy();
    }
}
