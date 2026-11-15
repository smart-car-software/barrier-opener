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

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Drives the runtime permission flow for the launcher activity:
 *   1. ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION + CALL_PHONE (foreground).
 *   2. ACCESS_BACKGROUND_LOCATION (separately, with rationale, on Android 10+).
 *   3. SYSTEM_ALERT_WINDOW (overlay) — required to show popup from background service.
 *   4. Battery optimizations whitelist (asked once, dismissible).
 *   5. Car Notification Service, when it is not installed (asked once, see {@link NotifyInstall}).
 */
public class PermissionFlow {

    public static final int REQ_FOREGROUND = 1001;
    public static final int REQ_BACKGROUND = 1002;
    public static final int REQ_OVERLAY = 1003;
    public static final int REQ_BATTERY = 1004;

    private static final String PREFS = "permission_flow";
    private static final String KEY_BATTERY_ASKED = "battery_asked";

    private final Activity activity;

    public PermissionFlow(Activity activity) {
        this.activity = activity;
    }

    /**
     * Entry point. Runs through the steps until everything is granted or the user dismisses.
     * Continued from {@link #onRequestPermissionsResult} or {@link #onActivityResult}.
     */
    public void start() {
        if (!hasForegroundLocation() || !hasCallPhone()) {
            requestForeground();
            return;
        }
        if (!hasBackgroundLocation()) {
            explainBackgroundLocation();
            return;
        }
        if (!hasOverlay()) {
            explainOverlay();
            return;
        }
        if (!askedBatteryOnce() && !isBatteryUnrestricted()) {
            askBatteryOptimizations();
            return;
        }
        if (NotifyInstall.shouldAsk(activity)) {
            NotifyInstall.ask(activity, () -> { });
        }
    }

    public boolean hasForegroundLocation() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(activity,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasCallPhone() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasOverlay() {
        return Settings.canDrawOverlays(activity);
    }

    public boolean isBatteryUnrestricted() {
        PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(activity.getPackageName());
    }

    private void requestForeground() {
        ActivityCompat.requestPermissions(activity, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CALL_PHONE
        }, REQ_FOREGROUND);
    }

    private void explainBackgroundLocation() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.permission_background_location_title)
                .setMessage(R.string.permission_background_location_message)
                .setPositiveButton(R.string.action_continue, (d, w) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ActivityCompat.requestPermissions(activity, new String[]{
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        }, REQ_BACKGROUND);
                    }
                })
                .setNegativeButton(R.string.action_later, (d, w) -> start())
                .setCancelable(false)
                .show();
    }

    private void explainOverlay() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.permission_overlay_title)
                .setMessage(R.string.permission_overlay_message)
                .setPositiveButton(R.string.permission_open_settings, (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivityForResult(intent, REQ_OVERLAY);
                })
                .setNegativeButton(R.string.action_later, (d, w) -> start())
                .setCancelable(false)
                .show();
    }

    private void askBatteryOptimizations() {
        markBatteryAsked();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.permission_battery_title)
                .setMessage(R.string.permission_battery_message)
                .setPositiveButton(R.string.permission_open_settings, (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + activity.getPackageName()));
                    try {
                        activity.startActivityForResult(intent, REQ_BATTERY);
                    } catch (Exception ignored) {
                        // Some OEMs block this intent; nothing more we can do here.
                    }
                })
                .setNegativeButton(R.string.action_later, (d, w) -> start())
                .show();
    }

    public void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode == REQ_FOREGROUND || requestCode == REQ_BACKGROUND) {
            start();
        }
    }

    public void onActivityResult(int requestCode) {
        if (requestCode == REQ_OVERLAY || requestCode == REQ_BATTERY) {
            start();
        }
    }

    private boolean askedBatteryOnce() {
        return prefs().getBoolean(KEY_BATTERY_ASKED, false);
    }

    private void markBatteryAsked() {
        prefs().edit().putBoolean(KEY_BATTERY_ASKED, true).apply();
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
