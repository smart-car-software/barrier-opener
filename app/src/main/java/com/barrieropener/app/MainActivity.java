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
import android.view.View;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ListView barrierListView;
    private View emptyState;
    private TextView statusText;
    private ImageView statusDot;
    private DatabaseHelper dbHelper;
    private BarrierListAdapter adapter;
    private PermissionFlow permissionFlow;
    /** Keeps the gate-api rows' state pills current while the list is on screen; idle otherwise. */
    private GateStatePoller gatePoller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = new DatabaseHelper(this);
        permissionFlow = new PermissionFlow(this);
        gatePoller = new GateStatePoller(() -> {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });

        initViews();
        loadBarriers();

        permissionFlow.start();
        startLocationServiceIfReady();
    }

    private void initViews() {
        barrierListView = findViewById(R.id.barrierListView);
        emptyState = findViewById(R.id.emptyState);
        statusText = findViewById(R.id.statusText);
        statusDot = findViewById(R.id.statusDot);
        ExtendedFloatingActionButton fabAdd = findViewById(R.id.fabAdd);
        fabAdd.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, AddEditBarrierActivity.class)));

        findViewById(R.id.btnAbout).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, AboutActivity.class)));
    }

    private void updateStatusPill() {
        int colorRes;
        int textRes;
        if (!permissionFlow.hasForegroundLocation()) {
            colorRes = R.color.status_error;
            textRes = R.string.status_no_location_permission;
        } else if (!permissionFlow.hasBackgroundLocation()) {
            colorRes = R.color.status_warning;
            textRes = R.string.status_foreground_only;
        } else if (LocationService.isStarted()) {
            colorRes = R.color.status_ok;
            textRes = R.string.status_monitoring;
        } else {
            colorRes = R.color.status_warning;
            textRes = R.string.status_idle;
        }
        statusDot.setImageTintList(
                ContextCompat.getColorStateList(this, colorRes));
        statusText.setText(textRes);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBarriers();
        startLocationServiceIfReady();
        updateStatusPill();
        gatePoller.start();
    }

    @Override
    protected void onPause() {
        // Polling is for someone looking at the list; in the background it would only wake the
        // network every two seconds for a pill nobody sees.
        gatePoller.stop();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionFlow.onRequestPermissionsResult(requestCode, grantResults);
        startLocationServiceIfReady();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        permissionFlow.onActivityResult(requestCode);
        startLocationServiceIfReady();
    }

    private void startLocationServiceIfReady() {
        if (!permissionFlow.hasForegroundLocation()) return;
        startForegroundService(new Intent(this, LocationService.class));
    }

    public void loadBarriers() {
        List<Barrier> barriers = dbHelper.getAllBarriers();
        gatePoller.setBarriers(barriers);
        if (adapter == null) {
            adapter = new BarrierListAdapter(this, this::loadBarriers, barriers, dbHelper);
            adapter.setGatePoller(gatePoller);
            barrierListView.setAdapter(adapter);
        } else {
            adapter.updateBarriers(barriers);
        }
        if (emptyState != null) {
            emptyState.setVisibility(barriers.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) {
            dbHelper.close();
        }
    }
}
