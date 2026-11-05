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

import android.graphics.Point;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

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
        RadioGroup displays = findViewById(R.id.groupClusterDisplay);
        TextView noDisplay = findViewById(R.id.textClusterNoDisplay);
        cluster.setChecked(AppSettings.clusterPopup(this));
        buildDisplayChoice(displays, noDisplay);
        displays.setVisibility(cluster.isChecked() ? View.VISIBLE : View.GONE);
        cluster.setOnCheckedChangeListener((b, on) -> {
            AppSettings.setClusterPopup(this, on);
            displays.setVisibility(on ? View.VISIBLE : View.GONE);
        });

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

    /**
     * Lists every display besides the head unit's own; on a Monjaro they all call themselves
     * "HDMI screen", so each shows its id and size — the cluster is the 1920×1080 one.
     */
    private void buildDisplayChoice(RadioGroup group, TextView noDisplay) {
        List<Display> list = ClusterDisplay.secondaryDisplays(this);
        noDisplay.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        int chosen = AppSettings.clusterDisplay(this);
        for (Display d : list) {
            Point size = new Point();
            d.getRealSize(size);
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(getString(R.string.settings_cluster_display, d.getDisplayId(), size.x, size.y));
            rb.setTextColor(getColor(R.color.text_primary));
            int id = d.getDisplayId();
            rb.setOnCheckedChangeListener((b, on) -> {
                if (on) {
                    AppSettings.setClusterDisplay(this, id);
                }
            });
            group.addView(rb);
            if (id == chosen) {
                rb.setChecked(true);
            }
        }
    }

    @Override
    protected void onDestroy() {
        check.stop();
        super.onDestroy();
    }
}
