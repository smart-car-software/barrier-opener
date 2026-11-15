/*
 * Copyright © 2026 smart-car-software contributors
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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Update" block of a screen, after route-suggest's: the latest release looked at on
 * opening (every few hours, the answer kept in between) and installed by the button only — an
 * update never starts by itself, a driver may be on the way to the gate. Any of the views may
 * be null: the main screen shows only the pill, the settings screen the whole block.
 */
final class UpdateSection {
    private static final String TAG = "Updater";
    private static final String PREFS = "updater";
    private static final long CHECK_EVERY_MS = 6 * 3600_000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Updater");
        t.setDaemon(true);
        return t;
    });

    private final Activity activity;
    private final TextView status;
    private final TextView notes;
    private final Button button;
    private final View pill;
    private final TextView pillText;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Updater.Release available;
    private boolean busy;

    UpdateSection(Activity activity, TextView status, TextView notes, Button button,
                  View pill, TextView pillText) {
        this.activity = activity;
        this.status = status;
        this.notes = notes;
        this.button = button;
        this.pill = pill;
        this.pillText = pillText;
        if (button != null) button.setOnClickListener(v -> {
            if (available != null) install();
            else check(true);
        });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** {@code now}: the driver asked; otherwise the kept answer while it is fresh. */
    void check(boolean now) {
        SharedPreferences p = prefs(activity);
        if (!now && System.currentTimeMillis() - p.getLong("checked", 0) < CHECK_EVERY_MS) {
            String tag = p.getString("tag", "");
            available = Updater.newer(tag, BuildConfig.VERSION_NAME)
                    ? new Updater.Release(tag, p.getString("url", ""), p.getString("notes", ""))
                    : null;
            draw();
            return;
        }
        if (now) {
            busy = true;
            draw();
        }
        WORKER.execute(() -> {
            String err = "";
            Updater.Release found = null;
            try {
                Updater.Release r = Updater.latest();
                p.edit().putLong("checked", System.currentTimeMillis())
                        .putString("tag", r == null ? "" : r.tag)
                        .putString("url", r == null ? "" : r.apkUrl)
                        .putString("notes", r == null ? "" : r.notes).apply();
                found = r != null && Updater.newer(r.tag, BuildConfig.VERSION_NAME) ? r : null;
            } catch (IOException | RuntimeException e) {
                err = text(e);
            }
            String error = err;
            Updater.Release result = found;
            main.post(() -> {
                busy = false;
                // A failed look keeps what was known: the network may just not be up yet.
                if (error.isEmpty()) available = result;
                p.edit().putString("error", error).apply();
                draw();
                if (now && error.isEmpty() && result == null) toast(activity.getString(R.string.upd_latest));
            });
        });
    }

    private void draw() {
        Updater.Release r = available;
        if (pill != null) pill.setVisibility(r != null ? View.VISIBLE : View.GONE);
        if (pillText != null && r != null) pillText.setText(activity.getString(R.string.upd_pill, r.tag));
        if (status != null) {
            status.setText(r != null ? activity.getString(R.string.upd_available, r.tag)
                    : activity.getString(R.string.upd_current, BuildConfig.VERSION_NAME));
        }
        if (notes != null) {
            String err = prefs(activity).getString("error", "");
            String n = r != null ? r.notes.trim() : err.isEmpty() ? "" : activity.getString(R.string.upd_error, err);
            notes.setText(n);
            notes.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (button != null) {
            button.setEnabled(!busy);
            button.setText(busy ? activity.getString(R.string.upd_checking)
                    : r != null ? activity.getString(R.string.upd_install, r.tag)
                    : activity.getString(R.string.upd_check));
        }
    }

    private void install() {
        Updater.Release r = available;
        if (r == null || r.apkUrl.isEmpty()) return;
        // The installer reads our own sources setting: ask for it before downloading 15 MB.
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            toast(activity.getString(R.string.upd_allow_unknown));
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (RuntimeException ignored) {
                // A firmware without that screen: the installer will ask itself, or say no.
            }
            return;
        }
        busy = true;
        draw();
        if (button != null) button.setText(activity.getString(R.string.upd_downloading, r.tag));
        Context app = activity.getApplicationContext();
        WORKER.execute(() -> {
            String err = "";
            try {
                File apk = Updater.download(app, r);
                DebugLog.i(TAG, "downloaded " + r.tag);
                Updater.installWithPrompt(app, apk);
            } catch (IOException | RuntimeException e) {
                DebugLog.w(TAG, "update to " + r.tag + " failed", e);
                err = text(e);
            }
            String error = err;
            main.post(() -> {
                busy = false;
                draw();
                if (!error.isEmpty()) toast(activity.getString(R.string.upd_error, error));
            });
        });
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
    }

    /** An exception as a line for the screen: its message, or its kind when it has none. */
    private static String text(Throwable e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
