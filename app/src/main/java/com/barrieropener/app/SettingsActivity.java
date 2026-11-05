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

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.materialswitch.MaterialSwitch;

/** The app-wide switches in {@link AppSettings}: the driver's-screen popup and the wheel keys. */
public class SettingsActivity extends AppCompatActivity {

    private RootStream check;

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
        wheel.setOnCheckedChangeListener((b, on) -> {
            AppSettings.setWheelKeys(this, on);
            mute.setEnabled(on);
        });
        mute.setOnCheckedChangeListener((b, on) -> AppSettings.setMuteMedia(this, on));

        // The real popup with a made-up barrier, so the switches above can be tried without
        // driving up to a gate. EXTRA_TEST: nothing is triggered, whatever is answered.
        findViewById(R.id.btnTestPopup).setOnClickListener(v -> startActivity(
                new Intent(this, BarrierPopupActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("barrier_name", getString(R.string.car_diag_test_barrier))
                        .putExtra("barrier_id", -1L)
                        .putExtra(BarrierPopupActivity.EXTRA_TEST, true)));

        TextView probe = findViewById(R.id.textProbeMute);
        findViewById(R.id.btnProbeMute).setOnClickListener(v -> {
            probe.setText(R.string.settings_check_root_running);
            StringBuilder out = new StringBuilder();
            check.start(MonjaroWheel.PROBE, null, line -> {
                if (line == null) {
                    probe.setText(out.toString().trim());
                } else {
                    out.append(line).append('\n');
                }
            });
        });

        TextView status = findViewById(R.id.textWheelStatus);
        check = new RootStream(this);
        findViewById(R.id.btnCheckWheel).setOnClickListener(v -> {
            status.setText(R.string.settings_check_root_running);
            StringBuilder out = new StringBuilder();
            check.start("id -un", null, line -> {
                if (line == null) {
                    boolean root = out.toString().contains("\nroot") || out.toString().startsWith("root");
                    status.setText(root ? getString(R.string.settings_check_root_ok)
                            : getString(R.string.settings_check_root_failed, out.toString().trim()));
                } else {
                    out.append(line).append('\n');
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        check.stop();
        super.onDestroy();
    }
}
